package com.shrey.buddy.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.health.ExerciseLibrary
import com.shrey.buddy.health.RestTimer
import com.shrey.buddy.health.StrengthMath
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.delay

@Composable
fun WorkoutLoggerScreen(hvm: HealthViewModel, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val s by hvm.session.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<StrengthMath.Record>?>(null) }
    var savedId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val session = s
    if (session == null) {
        // Saved with records to show? Show them; otherwise nothing to log.
        records?.let { recs ->
            AlertDialog(
                onDismissRequest = { savedId?.let(onSaved) ?: onBack() },
                containerColor = BuddyColors.Surface,
                title = { Text(if (recs.isEmpty()) "Workout saved 💪" else "New personal records 🏆") },
                text = {
                    Column {
                        recs.forEach { r -> Text("${r.exercise}: ${r.what} ${r.value}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                    }
                },
                confirmButton = { TextButton(onClick = { savedId?.let(onSaved) ?: onBack() }) { Text("See summary") } },
            )
        }
        if (records == null) LaunchedEffect(Unit) { onBack() }
        return
    }

    // Rest finished while the screen is open → buzz now (the alarm covers the screen-off case).
    val restLeft = session.restEndsAt?.let { ((it - now) / 1000).toInt() }
    LaunchedEffect(session.restEndsAt, restLeft != null && restLeft <= 0) {
        if (session.restEndsAt != null && restLeft != null && restLeft <= 0) {
            RestTimer.cancel(context)
            RestTimer.buzz(context)
            hvm.session.skipRest()
        }
    }

    val resting = session.restEndsAt != null && restLeft != null && restLeft > 0
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (resting) 120.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back (keeps the workout running)") }
                Column(Modifier.weight(1f).clickable { renaming = true }) {
                    Text(session.title, style = MaterialTheme.typography.titleLarge)
                    Text("Tap to rename", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }
                Button(
                    onClick = { focus.clearFocus(); finishing = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                ) { Text("Finish") }
            }
        }

        item {
            // Duration · Volume · Sets, always in view at the top (like Hevy).
            Row(Modifier.fillMaxWidth()) {
                TopStat("Duration", formatDuration(now - session.startedAt), Modifier.weight(1f), BuddyColors.Fitness)
                TopStat("Volume", "%,.0f kg".format(StrengthMath.volume(hvm.session.toWorkout("moderate", null) ?: com.shrey.buddy.data.items.Workout())), Modifier.weight(1f))
                TopStat("Sets", "${session.doneSets} / ${session.totalSets}", Modifier.weight(1f))
            }
        }

        if (session.exercises.isEmpty()) {
            item {
                Text(
                    "Add your first exercise — Buddy fills in what you lifted last time.",
                    style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextSecondary,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        }

        items(session.exercises, key = { it.key }) { e -> ExerciseCard(hvm, e, onReplace = { replacing = e.key }) { rest ->
            val next = e.name
            if (rest > 0) RestTimer.start(context, rest, next) else RestTimer.cancel(context)
        } }

        item {
            Button(
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.SurfaceHigh, contentColor = BuddyColors.Fitness),
            ) {
                Icon(Icons.Rounded.Add, null)
                Text("Add exercise", modifier = Modifier.padding(start = 6.dp))
            }
        }
        item {
            TextButton(onClick = { hvm.discardWorkout(); RestTimer.cancel(context); onBack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Discard workout", color = BuddyColors.Red)
            }
        }
    }

    // Rest timer stays pinned to the bottom while you scroll.
    if (resting) {
        Surface(
            shape = RoundedCornerShape(20.dp), color = BuddyColors.SurfaceHighest, shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                LinearProgressIndicator(
                    progress = { restLeft!!.toFloat() / session.restTotalS.coerceAtLeast(1) },
                    color = BuddyColors.Fitness, trackColor = BuddyColors.SurfaceHigh,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                )
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { hvm.session.adjustRest(-15); hvm.session.state.value?.restEndsAt?.let { RestTimer.reschedule(context, it, "next set") } }) { Text("−15") }
                    Text(formatDuration(restLeft!! * 1000L), style = MaterialTheme.typography.headlineSmall.numeric(), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { hvm.session.adjustRest(15); hvm.session.state.value?.restEndsAt?.let { RestTimer.reschedule(context, it, "next set") } }) { Text("+15") }
                    Button(
                        onClick = { hvm.session.skipRest(); RestTimer.cancel(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.OnLime),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Skip") }
                }
            }
        }
    }
    }

    replacing?.let { key ->
        ExercisePickerSheet(
            onDismiss = { replacing = null },
            onPick = { it.firstOrNull()?.let { ex -> hvm.session.replaceExercise(key, ex, hvm.strengthHistory()) }; replacing = null },
            onCustom = { replacing = null },
        )
    }

    if (picking) {
        ExercisePickerSheet(
            onDismiss = { picking = false },
            onPick = { hvm.session.addExercises(it, hvm.strengthHistory()); picking = false },
            onCustom = { hvm.session.addCustomExercise(it); picking = false },
        )
    }
    if (renaming) {
        var text by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Workout name") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { hvm.session.rename(text.trim().ifEmpty { session.title }); renaming = false }) { Text("Save") } },
        )
    }
    if (finishing) {
        var effort by remember { mutableStateOf("moderate") }
        var asRoutine by remember { mutableStateOf(session.routineId == null && session.exercises.size >= 2) }
        val forgotten = hvm.session.filledUnticked()
        val w = hvm.session.toWorkout(effort, hvm.latestKg.value)
        AlertDialog(
            onDismissRequest = { finishing = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Finish workout?") },
            text = {
                Column {
                    if (w != null) {
                        Text(
                            "${formatDuration(now - session.startedAt)} · ${StrengthMath.workingSets(w)} set${if (StrengthMath.workingSets(w) == 1) "" else "s"} · %,.0f kg · ≈ ${w.kcal} kcal".format(StrengthMath.volume(w)),
                            style = MaterialTheme.typography.bodyMedium.numeric(),
                        )
                    }
                    Text("How did it feel?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("easy" to "Easy", "moderate" to "Solid", "hard" to "Brutal").forEach { (k, l) ->
                            FilterChip(
                                selected = effort == k, onClick = { effort = k }, label = { Text(l) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Fitness.copy(alpha = 0.25f)),
                            )
                        }
                    }
                    if (session.routineId == null) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Save as a routine", modifier = Modifier.weight(1f))
                            Switch(asRoutine, { asRoutine = it }, colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime))
                        }
                    }
                    if (forgotten > 0) {
                        OutlinedButton(onClick = { hvm.session.tickFilled() }, modifier = Modifier.padding(top = 10.dp)) {
                            Text("Tick the $forgotten set${if (forgotten == 1) "" else "s"} I filled in but didn't tick")
                        }
                    }
                    Text("Only ticked sets are saved.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    finishing = false
                    RestTimer.cancel(context)
                    hvm.finishWorkout(effort, asRoutine) { id, recs ->
                        savedId = id
                        records = recs
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { finishing = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun TopStat(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = BuddyColors.TextPrimary) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
        Text(value, style = MaterialTheme.typography.titleMedium.numeric(), color = color)
    }
}

private val restChoices = listOf(0 to "Off", 30 to "30 s", 60 to "1 min", 90 to "1 min 30", 120 to "2 min", 180 to "3 min", 240 to "4 min")
private fun restLabel(s: Int) = restChoices.firstOrNull { it.first == s }?.second ?: "$s s"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseCard(hvm: HealthViewModel, e: LiveExercise, onReplace: () -> Unit, onRest: (Int) -> Unit) {
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    var restMenu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val timed = e.mode == "timed"
    Surface(shape = RoundedCornerShape(20.dp), color = BuddyColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = e.exerciseId != null) { info = true }) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, color = BuddyColors.Fitness)
                    e.muscles.firstOrNull()?.let {
                        Text(it.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Exercise options") }
                    DropdownMenu(menu, { menu = false }, containerColor = BuddyColors.SurfaceHigh) {
                        DropdownMenuItem(text = { Text("Replace exercise") }, onClick = { menu = false; onReplace() })
                        DropdownMenuItem(
                            text = { Text(if (timed) "Track weight & reps" else "Track time (seconds)") },
                            onClick = { hvm.session.setMode(e.key, if (timed) "weight" else "timed"); menu = false },
                        )
                        DropdownMenuItem(text = { Text("Move up") }, onClick = { hvm.session.moveExercise(e.key, -1); menu = false })
                        DropdownMenuItem(text = { Text("Move down") }, onClick = { hvm.session.moveExercise(e.key, 1); menu = false })
                        DropdownMenuItem(text = { Text("Remove", color = BuddyColors.Red) }, onClick = { hvm.session.removeExercise(e.key); menu = false })
                    }
                }
            }

            // Notes for this exercise ("seat 4", "slow negatives").
            Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
                if (e.notes.isEmpty()) Text("Add notes…", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                BasicTextField(
                    value = e.notes,
                    onValueChange = { hvm.session.setNotes(e.key, it) },
                    textStyle = MaterialTheme.typography.bodySmall.merge(TextStyle(color = BuddyColors.TextSecondary)),
                    cursorBrush = SolidColor(BuddyColors.Fitness),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Rest timer: tap to change.
            Box(Modifier.padding(start = 6.dp)) {
                TextButton(onClick = { restMenu = true }) {
                    Text("⏱ Rest: ${restLabel(e.restS)}", color = BuddyColors.Tasks, style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(restMenu, { restMenu = false }, containerColor = BuddyColors.SurfaceHigh) {
                    restChoices.forEach { (secs, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { hvm.session.setRest(e.key, secs); restMenu = false })
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Header("Set", Modifier.width(40.dp)); Header("Previous", Modifier.weight(1f))
                if (timed) Header("Seconds", Modifier.width(136.dp))
                else { Header(if (e.mode == "bodyweight") "+kg" else "kg", Modifier.width(72.dp)); Header("Reps", Modifier.width(64.dp)) }
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = BuddyColors.TextTertiary, modifier = Modifier.size(14.dp)) }
            }

            e.sets.forEachIndexed { i, set ->
                androidx.compose.runtime.key(set.id) {
                    val prev = e.previous.getOrNull(i)
                    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState()
                    // Swipe a set left to delete it.
                    androidx.compose.material3.SwipeToDismissBox(
                        state = swipe,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(BuddyColors.Red.copy(alpha = 0.85f)).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                Text("Delete", color = BuddyColors.TextPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        onDismiss = { hvm.session.removeSet(e.key, set.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (set.done) androidx.compose.ui.graphics.lerp(BuddyColors.Surface, BuddyColors.Lime, 0.14f) else BuddyColors.Surface)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tap the set number to mark warm-up / drop / failure.
                            Box(
                                Modifier.width(40.dp).height(44.dp).clip(RoundedCornerShape(10.dp)).clickable { hvm.session.cycleKind(e.key, set.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    when (set.kind) { "warmup" -> "W"; "drop" -> "D"; "failure" -> "F"; else -> "${e.sets.take(i + 1).count { it.kind == "normal" }}" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = when (set.kind) { "warmup" -> BuddyColors.Tasks; "drop", "failure" -> BuddyColors.Red; else -> BuddyColors.TextPrimary },
                                )
                            }
                            // Tap last time's numbers to copy them in.
                            Box(
                                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = prev != null && !set.done) { prev?.let { hvm.session.copyPrevious(e.key, set.id, it) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    when {
                                        e.isRecord(set) -> "🏆 Record"
                                        prev == null -> "–"
                                        (prev.seconds ?: 0) > 0 -> "${prev.seconds} s"
                                        prev.weightKg > 0 -> "${StrengthMath.fmt(prev.weightKg)} kg × ${prev.reps}"
                                        else -> "× ${prev.reps}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.numeric(),
                                    color = if (e.isRecord(set)) BuddyColors.Lime else BuddyColors.TextTertiary,
                                )
                            }
                            if (timed) {
                                NumberCell(set.secs, prev?.seconds?.toString().orEmpty(), KeyboardType.Number, Modifier.width(136.dp)) { v -> hvm.session.type(e.key, set.id, secs = v) }
                            } else {
                                NumberCell(set.kg, prev?.weightKg?.takeIf { it > 0 }?.let(StrengthMath::fmt).orEmpty(), KeyboardType.Decimal, Modifier.width(72.dp)) { v ->
                                    hvm.session.type(e.key, set.id, kg = v)
                                }
                                NumberCell(set.reps, prev?.reps?.takeIf { it > 0 }?.toString().orEmpty(), KeyboardType.Number, Modifier.width(64.dp)) { v ->
                                    hvm.session.type(e.key, set.id, reps = v)
                                }
                            }
                            Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (set.done) BuddyColors.Lime else BuddyColors.SurfaceHigh)
                                        .clickable {
                                            focus.clearFocus() // put the keyboard away between sets
                                            // Ticking with empty fields takes last time's numbers.
                                            if (!set.done && !e.filled(set) && prev != null) hvm.session.copyPrevious(e.key, set.id, prev)
                                            onRest(hvm.session.toggleDone(e.key, set.id))
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = if (set.done) BuddyColors.OnLime else BuddyColors.TextTertiary, modifier = Modifier.size(24.dp)) }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { hvm.session.addSet(e.key) },
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.SurfaceHigh, contentColor = BuddyColors.TextPrimary),
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Text("Add set", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    if (info) ExerciseLibrary.byId(context, e.exerciseId)?.let { ExerciseInfoSheet(it, onDismiss = { info = false }) }
}

@Composable
private fun Header(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, textAlign = TextAlign.Center, modifier = modifier)
}

/**
 * A big number box. Tapping it selects what's there, so you just type the new number — no backspacing.
 */
@Composable
private fun NumberCell(value: String, hint: String, keyboard: KeyboardType, modifier: Modifier, onChange: (String) -> Unit) {
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    // Follow changes made elsewhere (copy previous, the set above being edited).
    if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    var selectAll by remember { mutableStateOf(false) }
    LaunchedEffect(selectAll) {
        if (selectAll) {
            field = field.copy(selection = androidx.compose.ui.text.TextRange(0, field.text.length))
            selectAll = false
        }
    }
    Box(
        modifier.padding(horizontal = 3.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).background(BuddyColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.titleMedium.numeric(), color = BuddyColors.TextTertiary.copy(alpha = 0.6f))
        BasicTextField(
            value = field,
            onValueChange = { v ->
                val clean = v.text.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
                field = v.copy(text = clean)
                if (clean != value) onChange(clean)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = MaterialTheme.typography.titleMedium.numeric().merge(TextStyle(color = BuddyColors.TextPrimary, textAlign = TextAlign.Center)),
            cursorBrush = SolidColor(BuddyColors.Fitness),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) selectAll = true },
        )
    }
}
