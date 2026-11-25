package com.shrey.buddy.ui.life

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Hum
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.music.Hums
import com.shrey.buddy.music.MicCapture
import com.shrey.buddy.music.NoteEvent
import com.shrey.buddy.music.NoteNames
import com.shrey.buddy.music.PitchReading
import com.shrey.buddy.music.Player
import com.shrey.buddy.music.Synth
import com.shrey.buddy.music.Tempo
import com.shrey.buddy.music.Theory
import com.shrey.buddy.music.Transcriber
import com.shrey.buddy.music.Wav
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.rememberPermissionGate
import com.shrey.buddy.ui.theme.BuddyColors
import com.shrey.buddy.voice.BuddyVoiceService
import com.shrey.buddy.voice.VoiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private val Mint = Color(0xFF5EE6D0)
private val Amber = Color(0xFFFFC94D)

private enum class MusicMode(val label: String) { HUM("Hum to MIDI"), TUNER("Tuner"), TRAINER("Ear trainer") }

/**
 * Owns the mic for the Music screen. Pauses "Hey Buddy" (which also listens to the mic) the first time it is needed
 * and turns it back on in [release].
 */
class MicSession(private val context: Context) {
    var listening by mutableStateOf(false); private set
    var reading by mutableStateOf<PitchReading?>(null); private set
    var trace by mutableStateOf<List<Float?>>(emptyList()); private set
    var elapsedMs by mutableIntStateOf(0); private set
    private var current: MicCapture? = null
    private var pausedVoice = false

    /** Listens until [stop], [maxMs], or [until] returns true. Throws if the mic can't be opened. */
    suspend fun listen(keepAudio: Boolean, maxMs: Int = 120_000, until: (PitchReading) -> Boolean = { false }): MicCapture {
        if (!pausedVoice && BuddyVoiceService.pauseForMic(context)) {
            pausedVoice = true
            withTimeoutOrNull(2_500) { BuddyVoiceService.status.first { it == VoiceStatus.OFF || it == VoiceStatus.ERROR } }
            delay(200) // let the wake-word recogniser let go of the mic
        }
        val cap = MicCapture(keepAudio, maxMs)
        current = cap
        listening = true; elapsedMs = 0; trace = emptyList(); reading = null
        var lastUi = 0L
        try {
            cap.run { r ->
                if (until(r)) cap.stop()
                val now = SystemClock.uptimeMillis()
                if (now - lastUi >= 33) {
                    lastUi = now
                    reading = r
                    elapsedMs = (cap.frames.size * cap.hopMs).toInt()
                    trace = cap.frames.takeLast(260)
                }
            }
        } finally {
            listening = false; reading = null; current = null
        }
        return cap
    }

    fun stop() { current?.stop() }

    fun release() {
        current?.stop()
        if (pausedVoice) {
            pausedVoice = false
            BuddyVoiceService.restoreIfWanted(context)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val hums by lvm.hums.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(MusicMode.HUM) }
    val session = remember { MicSession(context.applicationContext) }
    val mic = rememberPermissionGate(arrayOf(Manifest.permission.RECORD_AUDIO))
    DisposableEffect(Unit) { onDispose { session.release(); Player.stop() } }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    ModuleScaffold(LifeModule.MUSIC, lvm, onBack) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { if (!session.listening) mode = m else lvm.say("Stop listening first.") },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Color.Black),
                    )
                }
            }
        }
        if (!mic.granted) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Buddy needs the mic", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Everything is worked out on this phone. Nothing you hum is sent anywhere.",
                        style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = mic.request,
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Allow the mic") }
                }
            }
            return@ModuleScaffold
        }
        when (mode) {
            MusicMode.HUM -> {
                item { RecordCard(lvm, session) { open = it } }
                val sorted = hums.sortedByDescending { it.data.createdAt }
                val shown = if (query.isBlank()) sorted else sorted.filter {
                    val q = query.trim().lowercase()
                    it.data.title.lowercase().contains(q) || it.data.key.lowercase().contains(q) ||
                        it.data.chords.any { c -> c.lowercase() == q }
                }
                item { SectionHeader("Idea vault · ${hums.size}") }
                if (hums.size > 4) {
                    item {
                        OutlinedTextField(
                            query, { query = it }, singleLine = true, label = { Text("Search by name, key or chord") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (hums.isEmpty()) item { EmptyNote("Every tune you hum is kept here with its notes, key, tempo and recording.") }
                items(shown, key = { it.id }) { h ->
                    HumCard(lvm, h, expanded = open == h.id, onToggle = { open = if (open == h.id) null else h.id })
                }
            }
            MusicMode.TUNER -> item { TunerCard(lvm, session) }
            MusicMode.TRAINER -> item { TrainerCard(lvm, session) }
        }
    }
}

// ---------------- Hum to MIDI ----------------

private val titleTime = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
private fun RecordCard(lvm: LifeViewModel, session: MicSession, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val recording = session.listening && job?.isActive == true

    fun start() {
        Player.stop()
        job = scope.launch {
            val cap = try {
                session.listen(keepAudio = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lvm.say(e.message ?: "Couldn't open the mic.")
                session.release()
                return@launch
            }
            session.release()
            working = true
            try {
                val file = UUID.randomUUID().toString() + ".wav"
                val title = "Hum · " + titleTime.format(java.time.LocalDateTime.now()).replace("AM", "am").replace("PM", "pm")
                val hum = withContext(Dispatchers.Default) { Hums.analyse(cap.frames, cap.hopMs, title, file) }
                if (hum == null) {
                    lvm.say("Didn't catch a tune. Hum a bit louder, for a few seconds, with clear notes.")
                    return@launch
                }
                withContext(Dispatchers.IO) { Wav.write(lvm.humFile(file), cap.audio(), cap.rate) }
                onSaved(lvm.saveHum(hum))
            } finally {
                working = false
            }
        }
    }

    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Hum a tune", style = MaterialTheme.typography.titleMedium)
        Text(
            "Hum, sing or whistle one line. Buddy writes down the notes, finds the key, tempo and chords, and makes a MIDI file.",
            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val level = session.reading?.level ?: 0f
            val ring = if (recording) (6 + (level * 160).coerceAtMost(22f)).dp else 0.dp
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(Mint.copy(alpha = if (recording) 0.22f else 0f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(96.dp - 22.dp + ring).clip(CircleShape)
                        .background(if (recording) BuddyColors.Red else Mint)
                        .clickable(enabled = !working) { if (recording) session.stop() else start() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (recording) "Stop" else "Record",
                        tint = Color.Black, modifier = Modifier.size(34.dp),
                    )
                }
            }
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                when {
                    working -> Text("Working out the notes…", style = MaterialTheme.typography.titleSmall)
                    recording -> {
                        val m = session.reading?.midi
                        Text(
                            m?.let { NoteNames.name(it.roundToInt()) } ?: "…",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Mint,
                        )
                        Text(
                            "${clock(session.elapsedMs)} · tap to stop",
                            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                        )
                    }
                    else -> {
                        Text("Tap to start", style = MaterialTheme.typography.titleSmall)
                        Text("Up to 2 minutes. A quiet room works best.", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                    }
                }
            }
        }
        if (recording) {
            LiveTrace(session.trace, Modifier.fillMaxWidth().height(90.dp).padding(top = 14.dp))
        }
    }
}

private fun clock(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** The last few seconds of pitch as a moving line of dots, with the note lanes behind. */
@Composable
private fun LiveTrace(frames: List<Float?>, modifier: Modifier) {
    val voiced = frames.filterNotNull()
    val mid = if (voiced.isEmpty()) 57f else Transcriber.medianOf(voiced.takeLast(80))
    val lo = mid - 9; val hi = mid + 9
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(BuddyColors.SurfaceHigh)) {
        val rowH = size.height / (hi - lo)
        for (n in lo.toInt()..hi.toInt()) {
            if (NoteNames.isBlackKey(n)) {
                drawRect(Color.White.copy(alpha = 0.03f), Offset(0f, size.height - (n - lo + 0.5f) * rowH), Size(size.width, rowH))
            }
        }
        val step = size.width / 260f
        frames.forEachIndexed { i, m ->
            if (m != null && m in lo..hi) {
                val x = size.width - (frames.size - i) * step
                val y = size.height - (m - lo) * rowH
                drawCircle(Mint, radius = 2.6f.dp.toPx() / 1.3f, center = Offset(x, y))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HumCard(lvm: LifeViewModel, item: Item<Hum>, expanded: Boolean, onToggle: () -> Unit) {
    val h = item.data
    val context = LocalContext.current
    val playing by Player.playing.collectAsStateWithLifecycle()
    var snap by rememberSaveable(item.id) { mutableStateOf(false) }
    var withChords by rememberSaveable(item.id) { mutableStateOf(true) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val audio = h.audio?.let { lvm.humFile(it) }?.takeIf { it.exists() }
    val synthTag = "synth:${item.id}"; val recTag = "rec:${item.id}"

    if (renaming) {
        var name by remember { mutableStateOf(h.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Name this idea") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { lvm.updateHum(item.id, h.copy(title = name.trim())); renaming = false }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Delete \"${h.title}\"?") },
            text = { Text("The notes and the recording on this phone are deleted.") },
            confirmButton = { TextButton(onClick = { Player.stop(); lvm.deleteHum(item); deleting = false }) { Text("Delete", color = BuddyColors.Red) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }

    BuddyCard(Modifier.fillMaxWidth(), onClick = onToggle, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Mint.copy(alpha = 0.14f))
                    .clickable { if (playing?.tag == synthTag) Player.stop() else Player.play(synthTag, Hums.render(h, snap, withChords)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing?.tag == synthTag) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Mint)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(h.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    listOfNotNull(h.key.ifBlank { null }, "${h.bpm} bpm", "${h.notes.size} notes", clock(h.lengthMs)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, maxLines = 1,
                )
            }
        }
        if (!expanded) return@BuddyCard

        val notes = Hums.events(h).let { if (snap) Tempo.snap(it, h.bpm) else it }
        val playhead = playheadMs(playing, synthTag, recTag, h.audioOffsetMs)
        PianoRoll(notes, h.bpm, if (withChords) h.chords else emptyList(), playhead, Modifier.fillMaxWidth().height(170.dp).padding(top = 14.dp))

        if (h.chords.isNotEmpty()) {
            Text("Chords, one per bar", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                h.chords.forEach { c ->
                    Text(
                        c, style = MaterialTheme.typography.labelLarge, color = Mint,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Mint.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Hums.key(h)?.let { k ->
                Text(
                    "Also in ${k.name}: " + Theory.diatonic(k).filter { it.symbol(k.flats) !in h.chords }.joinToString("  ") { it.symbol(k.flats) },
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Text("Range ${Hums.range(h)}", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            FilterChip(withChords, { withChords = !withChords }, label = { Text("Play chords") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint.copy(alpha = 0.2f)))
            FilterChip(snap, { snap = !snap }, label = { Text("Snap to beat") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint.copy(alpha = 0.2f)))
        }

        Stepper("Tempo", "${h.bpm} bpm", Modifier.padding(top = 6.dp),
            onMinus = { lvm.updateHum(item.id, Hums.withTempo(h, h.bpm - 2)) },
            onPlus = { lvm.updateHum(item.id, Hums.withTempo(h, h.bpm + 2)) },
            extra = {
                TextButton(onClick = { lvm.updateHum(item.id, Hums.withTempo(h, h.bpm / 2)) }, enabled = h.bpm / 2 >= 40) { Text("½×") }
                TextButton(onClick = { lvm.updateHum(item.id, Hums.withTempo(h, h.bpm * 2)) }, enabled = h.bpm * 2 <= 240) { Text("2×") }
            },
        )
        Stepper("Key", h.key.ifBlank { "?" }, Modifier,
            onMinus = { lvm.updateHum(item.id, Hums.transpose(h, -1)) },
            onPlus = { lvm.updateHum(item.id, Hums.transpose(h, 1)) },
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (audio != null) {
                OutlinedButton(onClick = {
                    if (playing?.tag == recTag) Player.stop()
                    else Wav.read(audio)?.let { (pcm, rate) -> Player.play(recTag, pcm, rate) }
                }) {
                    Icon(if (playing?.tag == recTag) Icons.Rounded.Stop else Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Your hum", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Button(
                onClick = { shareMidi(context, h, snap, withChords) },
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.Black),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("MIDI file", modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = BuddyColors.TextSecondary) }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = BuddyColors.TextSecondary) }
        }
        if (audio == null && h.audio != null) {
            Text("The recording is on the phone that made it.", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
        }
        Text(
            "Saved " + DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").format(Instant.ofEpochMilli(h.createdAt).atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Stepper(
    label: String, value: String, modifier: Modifier,
    onMinus: () -> Unit, onPlus: () -> Unit, extra: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextSecondary, modifier = Modifier.width(58.dp))
        IconButton(onClick = onMinus) { Icon(Icons.Rounded.Remove, contentDescription = "Lower $label") }
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
        IconButton(onClick = onPlus) { Icon(Icons.Rounded.Add, contentDescription = "Raise $label") }
        extra()
    }
}

/** Where the playhead is (ms from the first note) while this hum is playing, else null. Ticks every frame. */
@Composable
private fun playheadMs(playing: Player.Playing?, synthTag: String, recTag: String, audioOffset: Int): Float? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = playing != null && (playing.tag == synthTag || playing.tag == recTag)
    LaunchedEffect(active, playing) {
        while (active && isActive) withFrameMillis { now = System.currentTimeMillis() }
    }
    if (!active || playing == null) return null
    val t = (now - playing.startedAt).toFloat()
    return if (playing.tag == recTag) t - audioOffset else t
}

@Composable
private fun PianoRoll(notes: List<NoteEvent>, bpm: Int, chords: List<String>, playhead: Float?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    if (notes.isEmpty()) return
    var lo = notes.minOf { it.midi } - 1
    var hi = notes.maxOf { it.midi } + 1
    while (hi - lo < 12) { lo--; if (hi - lo < 12) hi++ }
    val totalMs = notes.maxOf { it.end } + 150f
    val bar = Theory.barMs(bpm).toFloat()
    val labelStyle = TextStyle(color = BuddyColors.TextTertiary, fontSize = 9.sp)
    val chordStyle = TextStyle(color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(BuddyColors.SurfaceHigh)) {
        val top = if (chords.isEmpty()) 0f else 18.dp.toPx()
        val left = 24.dp.toPx()
        val rows = hi - lo + 1
        val rowH = (size.height - top) / rows
        val pxPerMs = (size.width - left) / totalMs
        fun y(m: Int) = top + (hi - m) * rowH
        for (m in lo..hi) {
            if (NoteNames.isBlackKey(m)) drawRect(Color.White.copy(alpha = 0.035f), Offset(left, y(m)), Size(size.width - left, rowH))
            if (NoteNames.pc(m) == 0 || m == lo) {
                drawText(measurer, NoteNames.name(m), Offset(3.dp.toPx(), y(m) + rowH / 2 - 6.sp.toPx()), labelStyle)
                drawLine(Color.White.copy(alpha = 0.08f), Offset(left, y(m) + rowH), Offset(size.width, y(m) + rowH))
            }
        }
        // Beats and bars.
        val beat = bar / 4
        var b = 0
        while (b * beat < totalMs) {
            val x = left + b * beat * pxPerMs
            drawLine(Color.White.copy(alpha = if (b % 4 == 0) 0.16f else 0.05f), Offset(x, 0f), Offset(x, size.height), strokeWidth = if (b % 4 == 0) 1.5f else 1f)
            if (b % 4 == 0) chords.getOrNull(b / 4)?.let { drawText(measurer, it, Offset(x + 4.dp.toPx(), 2.dp.toPx()), chordStyle) }
            b++
        }
        for (n in notes) {
            val x = left + n.at * pxPerMs
            val past = playhead != null && playhead >= n.at
            drawRoundRect(
                if (past) Color.White else Mint,
                Offset(x, y(n.midi) + 1f), Size((n.len * pxPerMs - 2f).coerceAtLeast(3f), (rowH - 2f).coerceAtLeast(2f)),
                CornerRadius(3.dp.toPx()),
            )
        }
        if (playhead != null && playhead >= 0) {
            val x = left + playhead * pxPerMs
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
    }
}

private fun shareMidi(context: Context, h: Hum, snap: Boolean, withChords: Boolean) {
    val safe = h.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Buddy hum" }
    val dir = File(context.cacheDir, "music").apply { mkdirs() }
    val file = File(dir, "$safe.mid")
    file.writeBytes(Hums.midi(h, snap, withChords))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("audio/midi").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, h.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Send the MIDI file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- Tuner ----------------

@Composable
private fun TunerCard(lvm: LifeViewModel, session: MicSession) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val on = job?.isActive == true
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    var smooth by remember { mutableStateOf<Float?>(null) }
    val r = session.reading
    LaunchedEffect(r) {
        val m = r?.midi ?: return@LaunchedEffect
        val s = smooth
        smooth = if (s == null || abs(m - s) > 0.6f) m else s * 0.75f + m * 0.25f
    }

    fun start() {
        Player.stop()
        job = scope.launch {
            try {
                session.listen(keepAudio = false, maxMs = 15 * 60_000)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) lvm.say(e.message ?: "Couldn't open the mic.")
            } finally {
                session.release(); smooth = null
            }
        }
    }

    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Tuner", style = MaterialTheme.typography.titleMedium)
        Text("Sing or play one note. A4 = 440 Hz.", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
        val m = smooth
        val cents = m?.let { NoteNames.cents(it) }
        val tint = when {
            cents == null -> BuddyColors.TextTertiary
            abs(cents) <= 5 -> Mint
            abs(cents) <= 20 -> Amber
            else -> BuddyColors.Red
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                m?.let { NoteNames.name(it.roundToInt()) } ?: "–",
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold), color = tint,
            )
            Text(
                when {
                    cents == null -> if (on) "Listening…" else " "
                    abs(cents) <= 5 -> "In tune"
                    cents < 0 -> "$cents cents · go up"
                    else -> "+$cents cents · go down"
                },
                style = MaterialTheme.typography.bodyMedium, color = tint,
            )
            CentsMeter(cents, tint, Modifier.fillMaxWidth().height(36.dp).padding(top = 12.dp))
            Text(
                m?.let { "%.1f Hz".format(NoteNames.midiToHz(it)) } ?: " ",
                style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Button(
            onClick = { if (on) job?.cancel() else start() },
            colors = ButtonDefaults.buttonColors(containerColor = if (on) BuddyColors.SurfaceHighest else Mint, contentColor = if (on) BuddyColors.TextPrimary else Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (on) "Stop" else "Start tuner") }
    }
}

/** A needle on a -50..+50 cents scale. */
@Composable
private fun CentsMeter(cents: Int?, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val mid = size.width / 2
        drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(0f, size.height / 2 - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        drawRoundRect(Mint.copy(alpha = 0.25f), Offset(mid - size.width * 0.05f, size.height / 2 - 3.dp.toPx()), Size(size.width * 0.1f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        for (c in listOf(-50, -25, 0, 25, 50)) {
            val x = mid + c / 50f * (mid - 4.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, 8.dp.toPx()))
        }
        if (cents != null) {
            val x = mid + cents.coerceIn(-50, 50) / 50f * (mid - 4.dp.toPx())
            drawLine(tint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
        }
    }
}

// ---------------- Ear trainer ----------------

private val RANGES = listOf("Low" to (48..60), "Middle" to (55..67), "High" to (60..72))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainerCard(lvm: LifeViewModel, session: MicSession) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("music", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableIntStateOf(prefs.getInt("trainer_range", 1)) }
    var job by remember { mutableStateOf<Job?>(null) }
    val on = job?.isActive == true
    var target by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }
    var good by remember { mutableStateOf<Boolean?>(null) }
    var score by remember { mutableIntStateOf(0) }
    var rounds by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best_streak", 0)) }
    DisposableEffect(Unit) { onDispose { job?.cancel(); Player.stop() } }

    suspend fun round() {
        val notes = RANGES[range].second
        var t: Int
        do t = notes.random() while (t == target && notes.first != notes.last)
        target = t; good = null
        status = "Listen…"
        Player.playAndWait("tone", Synth.tone(t))
        delay(150)
        status = "Your turn: sing it back"
        var held = 0
        var octave = false
        val heard = mutableListOf<Float>()
        session.listen(keepAudio = false, maxMs = 7_000) { r ->
            val m = r.midi
            if (m != null) {
                heard += m
                val d = m - t
                val folded = d - 12 * (d / 12).roundToInt()
                if (abs(folded) <= 0.4f) { held++; if (abs(d) > 6) octave = true } else held = 0
            }
            held >= 40 // ~half a second on the note
        }
        rounds++
        if (held >= 40) {
            score++; streak++
            if (streak > best) { best = streak; prefs.edit().putInt("best_streak", best).apply() }
            good = true
            status = if (octave) "Right note, other octave" else "Spot on!"
            delay(1_000)
        } else {
            streak = 0; good = false
            status = if (heard.size < 10) "Didn't hear you. Sing a bit louder."
            else "It was ${NoteNames.name(t)}. You sang ${NoteNames.name(Transcriber.medianOf(heard).roundToInt())}."
            delay(2_000)
        }
    }

    fun start() {
        job = scope.launch {
            score = 0; rounds = 0; streak = 0
            try {
                while (isActive) round()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) lvm.say(e.message ?: "Couldn't open the mic.")
            } finally {
                session.release(); Player.stop(); target = null; status = ""; good = null
            }
        }
    }

    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Pitch trainer", style = MaterialTheme.typography.titleMedium)
        Text(
            "Buddy plays a note, you sing it back. Hold it for half a second to score.",
            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            RANGES.forEachIndexed { i, (label, notes) ->
                FilterChip(
                    selected = range == i,
                    onClick = { if (!on) { range = i; prefs.edit().putInt("trainer_range", i).apply() } else lvm.say("Stop first to change the range.") },
                    label = { Text("$label ${NoteNames.name(notes.first)}–${NoteNames.name(notes.last)}") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Color.Black),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val t = target
            Text(
                t?.let { NoteNames.name(it) } ?: "?",
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = when (good) { true -> Mint; false -> BuddyColors.Red; null -> BuddyColors.TextPrimary },
            )
            Text(status.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextSecondary, textAlign = TextAlign.Center)
            val m = session.reading?.midi
            val cents = if (t != null && m != null && session.listening) {
                val d = m - t
                ((d - 12 * (d / 12).roundToInt()) * 100).roundToInt()
            } else null
            CentsMeter(cents, if (cents != null && abs(cents) <= 40) Mint else Amber, Modifier.fillMaxWidth().height(36.dp).padding(top = 12.dp))
            Text(
                if (session.listening && m != null) "You: ${NoteNames.name(m.roundToInt())}" else " ",
                style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Score $score / $rounds", style = MaterialTheme.typography.labelLarge)
            Text("Streak $streak", style = MaterialTheme.typography.labelLarge)
            Text("Best $best", style = MaterialTheme.typography.labelLarge, color = Mint)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (on) job?.cancel() else start() },
            colors = ButtonDefaults.buttonColors(containerColor = if (on) BuddyColors.SurfaceHighest else Mint, contentColor = if (on) BuddyColors.TextPrimary else Color.Black),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (on) "Stop" else "Start") }
    }
}
