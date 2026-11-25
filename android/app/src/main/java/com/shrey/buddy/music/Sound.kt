package com.shrey.buddy.music

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** A soft electric-piano-ish voice, made from a few sine harmonics. */
object Synth {
    const val RATE = 22050

    fun render(notes: List<NoteEvent>, chords: List<Chord> = emptyList(), bpm: Int = 100, rate: Int = RATE): ShortArray {
        if (notes.isEmpty()) return ShortArray(0)
        val origin = notes.first().at
        val end = notes.maxOf { it.end } - origin
        val bar = Theory.barMs(bpm)
        val chordEnd = if (chords.isEmpty()) 0.0 else chords.size * bar
        val total = ((maxOf(end.toDouble(), chordEnd) + 400) * rate / 1000).toInt()
        val mix = FloatArray(total)
        for (n in notes) voice(mix, rate, n.at - origin.toDouble(), n.len.toDouble(), n.midi, 0.5f)
        chords.forEachIndexed { i, c ->
            val root = 48 + c.root
            for (m in listOf(root, root + if (c.minor) 3 else 4, root + 7)) voice(mix, rate, i * bar, bar - 30, m, 0.14f)
        }
        return toPcm(mix)
    }

    /** One note on its own, for the pitch trainer. */
    fun tone(midi: Int, ms: Int = 900, rate: Int = RATE): ShortArray {
        val mix = FloatArray((ms + 300) * rate / 1000)
        voice(mix, rate, 0.0, ms.toDouble(), midi, 0.6f)
        return toPcm(mix)
    }

    private fun voice(mix: FloatArray, rate: Int, startMs: Double, lenMs: Double, midi: Int, gain: Float) {
        val hz = NoteNames.midiToHz(midi.toFloat()).toDouble()
        val s0 = (startMs * rate / 1000).toInt()
        val held = (lenMs * rate / 1000).toInt().coerceAtLeast(1)
        val release = rate / 12 // ~80 ms fade
        val attack = rate / 200 // 5 ms
        for (i in 0 until held + release) {
            val idx = s0 + i
            if (idx !in mix.indices) break
            val t = i.toDouble() / rate
            val env = when {
                i < attack -> i.toDouble() / attack
                else -> 0.35 + 0.65 * exp(-t * 3.0)
            } * if (i >= held) 1.0 - (i - held).toDouble() / release else 1.0
            val w = 2 * PI * hz * t
            val v = sin(w) + 0.45 * sin(2 * w) * exp(-t * 4) + 0.2 * sin(3 * w) * exp(-t * 6)
            mix[idx] += (gain * env * v).toFloat()
        }
    }

    private fun toPcm(mix: FloatArray): ShortArray {
        val peak = mix.maxOfOrNull { abs(it) }?.coerceAtLeast(1f) ?: 1f
        return ShortArray(mix.size) { (mix[it] / peak * 0.8f * Short.MAX_VALUE).toInt().toShort() }
    }
}

/** Plays one sound at a time (a new one stops the old). [playing] names what's playing, for play/stop buttons. */
object Player {
    data class Playing(val tag: String, val startedAt: Long, val lengthMs: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var track: AudioTrack? = null
    private var job: Job? = null
    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing.asStateFlow()

    fun play(tag: String, pcm: ShortArray, rate: Int = Synth.RATE) {
        stop()
        if (pcm.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
        val ms = (pcm.size * 1000L / rate).toInt()
        _playing.value = Playing(tag, System.currentTimeMillis(), ms)
        job = scope.launch { delay(ms + 100L); stop() }
    }

    /** Suspends until the sound has finished. */
    suspend fun playAndWait(tag: String, pcm: ShortArray, rate: Int = Synth.RATE) {
        withContext(Dispatchers.Main) { play(tag, pcm, rate) }
        delay(pcm.size * 1000L / rate)
    }

    fun stop() {
        job?.cancel(); job = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        _playing.value = null
    }
}

/** 16-bit mono WAV files for the recordings in the idea vault. */
object Wav {
    fun write(file: File, pcm: ShortArray, rate: Int) {
        file.parentFile?.mkdirs()
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size * 2)
        file.outputStream().use { it.write(h.array()); it.write(data.array()) }
    }

    /** Reads back a file written by [write]: (samples, sample rate). */
    fun read(file: File): Pair<ShortArray, Int>? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(44); f.readFully(head)
            val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val rate = h.getInt(24)
            val bytes = ByteArray((f.length() - 44).toInt()); f.readFully(bytes)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(bytes.size / 2) { b.getShort() } to rate
        }
    }.getOrNull()
}

/**
 * Listens to the mic and reports a pitch reading every ~12 ms. Records at 44.1 kHz (every phone supports it)
 * and halves that to 22.05 kHz, plenty for a voice. With [keepAudio] it also keeps the sound for the vault.
 */
class MicCapture(private val keepAudio: Boolean, private val maxMs: Int = 120_000) {
    val rate = Synth.RATE
    val hopMs = HOP * 1000f / rate
    /** One fractional MIDI pitch (or null) per hop. */
    val frames = ArrayList<Float?>()
    private var pcm = ShortArray(if (keepAudio) rate * 10 else 0)
    private var pcmLen = 0

    @Volatile private var stopped = false

    fun audio(): ShortArray = pcm.copyOf(pcmLen)

    /** Ends [run] normally (keeping what was heard), unlike cancelling. */
    fun stop() { stopped = true }

    /** Runs until cancelled, the time limit, or the mic fails (throws). */
    @SuppressLint("MissingPermission") // the screen asks for the mic before starting
    suspend fun run(onReading: (PitchReading) -> Unit) = withContext(Dispatchers.Default) {
        val inRate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "The mic is busy (another app may be using it)." }
        val chunk = ShortArray(2048)
        val window = FloatArray(WINDOW)
        val pending = FloatArray(WINDOW * 4)
        var pendingLen = 0
        val maxSamples = maxMs.toLong() * rate / 1000
        var total = 0L
        try {
            rec.startRecording()
            while (isActive && !stopped && total < maxSamples) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                // 44.1 → 22.05 kHz: average each pair (a gentle low-pass at the same time).
                var i = 0
                while (i + 1 < n) {
                    val s = (chunk[i] + chunk[i + 1]) / 2
                    if (keepAudio) append(s.toShort())
                    if (pendingLen == pending.size) { // shouldn't happen, but never overflow
                        System.arraycopy(pending, HOP, pending, 0, pendingLen - HOP); pendingLen -= HOP
                    }
                    pending[pendingLen++] = s / 32768f
                    total++
                    i += 2
                }
                while (pendingLen >= WINDOW) {
                    System.arraycopy(pending, 0, window, 0, WINDOW)
                    val r = read(window)
                    frames += r.midi
                    onReading(r)
                    if (stopped) break
                    System.arraycopy(pending, HOP, pending, 0, pendingLen - HOP)
                    pendingLen -= HOP
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun read(w: FloatArray): PitchReading {
        val level = Yin.rms(w)
        if (level < GATE) return PitchReading(null, level)
        val p = Yin.detect(w, rate) ?: return PitchReading(null, level)
        return PitchReading(NoteNames.hzToMidi(p.first), level, p.second)
    }

    private fun append(s: Short) {
        if (pcmLen == pcm.size) pcm = pcm.copyOf(min(pcm.size * 2, rate * (maxMs / 1000 + 2)).coerceAtLeast(pcm.size + 1))
        pcm[pcmLen++] = s
    }

    companion object {
        const val WINDOW = 1024
        const val HOP = 256
        /** Quieter than this (about -40 dBFS) counts as silence. */
        const val GATE = 0.01f
    }
}

