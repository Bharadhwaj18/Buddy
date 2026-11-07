package com.shrey.buddy.ui.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.ExerciseEntry
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Routine
import com.shrey.buddy.data.items.RoutineExercise
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.StrengthMath
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.screens.workoutIcon
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate

private val typeLabel = mapOf("strength" to "Gym", "run" to "Run", "walk" to "Walk", "cycle" to "Ride", "yoga" to "Yoga", "sport" to "Sport", "other" to "Other")

/** Calories for any workout: stored value, else estimated from type, effort, minutes and weight. */
fun workoutKcal(w: Workout, kg: Double?): Int = when {
    w.kcal > 0 -> w.kcal
    w.run?.kcal?.let { it > 0 } == true -> w.run.kcal
    w.durationMin > 0 -> StrengthMath.kcal(w.type, w.effort, w.durationMin, kg)
    else -> 0
}

@Composable
fun WorkoutsScreen(hvm: HealthViewModel, onBack: () -> Unit, onStartLogger: () -> Unit, onOpen: (String) -> Unit) {
    val workouts by hvm.workouts.collectAsStateWithLifecycle()
    val routines by hvm.routines.collectAsStateWithLifecycle()
    val session by hvm.session.state.collectAsStateWithLifecycle()
    val kg by hvm.latestKg.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Routine>?>(null) }
    var newRoutine by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val history = workouts.filter { it.data.run == null }.sortedByDescending { it.date }
    val week = workouts.filter { it.date?.isAfter(today.minusDays(7)) == true }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Workouts", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            Button(
                onClick = {
                    if (session == null) hvm.startWorkout(defaultWorkoutTitle())
                    onStartLogger()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
            ) {
                Icon(if (session != null) Icons.Rounded.PlayArrow else Icons.Rounded.Add, null)
                Text(if (session != null) "Resume “${session!!.title}”" else "Start empty workout", modifier = Modifier.padding(start = 8.dp))
            }
        }

        // ---- This week ----
        item {
            val gym = week.filter { it.data.exercises.isNotEmpty() }
            val sets = gym.flatMap { it.data.exercises }.groupBy { it.muscles.firstOrNull() ?: "other" }
                .mapValues { (_, list) -> list.sumOf { e -> e.sets.count { it.done && it.kind != "warmup" } } }
                .filterValues { it > 0 }.entries.sortedByDescending { it.value }
            BuddyCard(Modifier.fillMaxWidth()) {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Stat("Sessions", "${week.size}", Modifier.weight(1f))
                    Stat("Gym volume", "%,.0f kg".format(gym.sumOf { StrengthMath.volume(it.data) }), Modifier.weight(1f))
                    Stat("Burned", "≈ %,d kcal".format(week.sumOf { workoutKcal(it.data, kg) }), Modifier.weight(1f))
                }
                if (sets.isNotEmpty()) {
                    Text("Working sets by muscle", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 14.dp))
                    val max = sets.first().value.coerceAtLeast(1)
                    sets.take(8).forEach { (m, n) ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                            LinearProgressIndicator(
                                progress = { n.toFloat() / max }, color = BuddyColors.Fitness, trackColor = BuddyColors.SurfaceHigh,
                                modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            Text("  $n", style = MaterialTheme.typography.bodySmall.numeric())
                        }
                    }
                    Text("10–20 hard sets per muscle a week is a good range for growth.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        // ---- Average calories per type ----
        item {
            val byType = workouts.groupBy { it.data.type }.mapValues { (_, l) -> l.map { workoutKcal(it.data, kg) }.filter { it > 0 } }.filterValues { it.isNotEmpty() }
            if (byType.isNotEmpty()) {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Average calories per session", style = MaterialTheme.typography.titleMedium)
                    byType.entries.sortedByDescending { it.value.size }.forEach { (type, list) ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(workoutIcon(type), null, tint = BuddyColors.Fitness)
                            Text(typeLabel[type] ?: type, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Text("≈ ${list.average().toInt()} kcal", style = MaterialTheme.typography.bodyMedium.numeric())
                            Text("  · ${list.size}×", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                        }
                    }
                    Text(
                        if (kg == null) "Log your weight in Body for more accurate numbers." else "Estimated from activity, effort, duration and your weight.",
                        style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // ---- Routines ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Routines", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { newRoutine = true }) { Text("New", color = BuddyColors.Fitness) }
            }
        }
        if (routines.isEmpty()) item { BuddyCard(Modifier.fillMaxWidth()) { EmptyNote("Save a workout as a routine (Push day, Leg day…) to start it in one tap.") } }
        items(routines, key = { it.id }) { r ->
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.data.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            r.data.exercises.joinToString(", ") { it.name }.ifBlank { "No exercises" },
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, maxLines = 2,
                        )
                    }
                    IconButton(onClick = { editing = r }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit routine", tint = BuddyColors.TextTertiary) }
                    Button(
                        onClick = { hvm.startWorkout(r.data.name, r); onStartLogger() },
                        enabled = session == null,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
                    ) { Text("Start") }
                }
            }
        }

        // ---- History ----
        item { Text("History", style = MaterialTheme.typography.titleMedium) }
        if (history.isEmpty()) item { BuddyCard(Modifier.fillMaxWidth()) { EmptyNote("No workouts yet.") } }
        items(history.take(60), key = { it.id }) { w ->
            BuddyCard(Modifier.fillMaxWidth().clickable { onOpen(w.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(workoutIcon(w.data.type), null, tint = BuddyColors.Fitness)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(w.data.title.ifBlank { typeLabel[w.data.type] ?: "Workout" }, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(
                                w.date?.let { friendlyDay(it, today) },
                                if (w.data.durationMin > 0) "${w.data.durationMin} min" else null,
                                if (w.data.exercises.isNotEmpty()) "%,.0f kg".format(StrengthMath.volume(w.data)) else null,
                                workoutKcal(w.data, kg).takeIf { it > 0 }?.let { "≈ $it kcal" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }

    if (newRoutine || editing != null) {
        val r = editing
        RoutineEditorSheet(
            initial = r?.data,
            onDismiss = { newRoutine = false; editing = null },
            onSave = { hvm.saveRoutine(r?.id, it); newRoutine = false; editing = null },
            onDelete = r?.let { { hvm.deleteRoutine(it.id); editing = null } },
        )
    }
}

fun defaultWorkoutTitle(hour: Int = java.time.LocalTime.now().hour) = when (hour) {
    in 4..11 -> "Morning workout"
    in 12..16 -> "Afternoon workout"
    in 17..20 -> "Evening workout"
    else -> "Night workout"
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleSmall.numeric())
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

/** A routine: name + exercises with sets × reps and rest. */
@Composable
fun RoutineEditorSheet(initial: Routine?, onDismiss: () -> Unit, onSave: (Routine) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var exercises by remember { mutableStateOf(initial?.exercises.orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    FormSheet(
        title = if (initial == null) "New routine" else "Edit routine",
        onDismiss = onDismiss,
        onSave = { onSave(Routine(name.trim(), exercises)) },
        saveEnabled = name.isNotBlank() && exercises.isNotEmpty(),
        accent = BuddyColors.Fitness,
        onDelete = onDelete,
    ) {
        Field(name, { name = it }, "Name (e.g. Push day)", accent = BuddyColors.Fitness)
        exercises.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Field("${e.sets}", { v -> v.toIntOrNull()?.let { n -> exercises = exercises.toMutableList().also { it[i] = e.copy(sets = n.coerceIn(1, 20)) } } }, "Sets", Modifier.width(64.dp), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
                Field("${e.reps}", { v -> v.toIntOrNull()?.let { n -> exercises = exercises.toMutableList().also { it[i] = e.copy(reps = n.coerceIn(1, 100)) } } }, "Reps", Modifier.width(64.dp).padding(start = 6.dp), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
                IconButton(onClick = { exercises = exercises.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = BuddyColors.TextTertiary) }
            }
        }
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Rounded.FitnessCenter, null, tint = BuddyColors.Fitness)
            Text("Add exercises", modifier = Modifier.padding(start = 6.dp))
        }
    }
    if (picking) {
        ExercisePickerSheet(
            onDismiss = { picking = false },
            onPick = { list -> exercises = exercises + list.map { RoutineExercise(it.name, it.id, it.primaryMuscles) }; picking = false },
            onCustom = { n -> exercises = exercises + RoutineExercise(n); picking = false },
        )
    }
}

/** One finished gym session: stats, every exercise and set, records-style best sets, delete. */
@Composable
fun WorkoutDetailScreen(hvm: HealthViewModel, workoutId: String, onBack: () -> Unit) {
    val workouts by hvm.workouts.collectAsStateWithLifecycle()
    val kg by hvm.latestKg.collectAsStateWithLifecycle()
    val item = workouts.firstOrNull { it.id == workoutId }
    var progressOf by remember { mutableStateOf<ExerciseEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(item?.data?.title?.ifBlank { null } ?: "Workout", style = MaterialTheme.typography.headlineSmall)
                    item?.date?.let { Text(friendlyDay(it), style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary) }
                }
                if (item?.data?.exercises.isNullOrEmpty()) IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = BuddyColors.Red) }
            }
        }
        val w = item?.data
        if (w == null) {
            item { Text("This workout isn't available.", color = BuddyColors.TextSecondary) }
            return@LazyColumn
        }
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Stat("Duration", if (w.durationMin > 0) formatDuration(w.durationMin * 60_000L) else "–", Modifier.weight(1f))
                    Stat("Calories", "≈ ${workoutKcal(w, kg)} kcal", Modifier.weight(1f))
                    if (w.exercises.isNotEmpty()) Stat("Volume", "%,.0f kg".format(StrengthMath.volume(w)), Modifier.weight(1f))
                    else Stat("Type", typeLabel[w.type] ?: w.type, Modifier.weight(1f))
                }
                if (w.notes.isNotBlank()) Text(w.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            }
        }
        items(w.exercises) { e ->
            BuddyCard(Modifier.fillMaxWidth().clickable { progressOf = e }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, style = MaterialTheme.typography.titleSmall, color = BuddyColors.Fitness, modifier = Modifier.weight(1f))
                    StrengthMath.bestSet(e)?.let {
                        Text("best ≈ %s kg 1RM".format(StrengthMath.fmt(StrengthMath.e1rm(it.weightKg, it.reps))), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                }
                if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
                var n = 0
                e.sets.forEach { s ->
                    val label = when (s.kind) { "warmup" -> "W"; "drop" -> "D"; "failure" -> "F"; else -> "${++n}" }
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextTertiary, modifier = Modifier.width(28.dp))
                        Text(
                            if ((s.seconds ?: 0) > 0) "${s.seconds} s" else if (s.weightKg > 0) "${StrengthMath.fmt(s.weightKg)} kg × ${s.reps}" else "${s.reps} reps",
                            style = MaterialTheme.typography.bodyMedium.numeric(),
                        )
                    }
                }
                Text("Tap for progress", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    progressOf?.let { e -> ExerciseProgressDialog(e, hvm.strengthHistory(), onDismiss = { progressOf = null }) }
    if (editing && item != null) {
        WorkoutSheet(
            initial = item, previous = workouts,
            onDismiss = { editing = false },
            onSave = { data, day -> hvm.saveWorkout(item.id, data, day); editing = false },
            onDelete = null,
        )
    }
    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Delete this workout?") },
            confirmButton = { TextButton(onClick = { hvm.deleteWorkout(item.id); confirmDelete = false; onBack() }) { Text("Delete", color = BuddyColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Best estimated 1RM per session for one exercise, over time. */
@Composable
private fun ExerciseProgressDialog(e: ExerciseEntry, history: List<Workout>, onDismiss: () -> Unit) {
    val points = history.reversed().mapIndexedNotNull { i, w ->
        w.exercises.firstOrNull { StrengthMath.same(it, e) }?.let(StrengthMath::bestSet)?.let { i.toDouble() to StrengthMath.e1rm(it.weightKg, it.reps) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        title = { Text(e.name) },
        text = {
            Column {
                if (points.size < 2) Text("Do this exercise a couple more times to see your progress.", color = BuddyColors.TextSecondary)
                else {
                    Text("Estimated 1-rep max per session", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary)
                    LineChart(points, invert = false, color = BuddyColors.Fitness, fill = true, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "%s kg → %s kg".format(StrengthMath.fmt(points.first().second), StrengthMath.fmt(points.last().second)),
                        style = MaterialTheme.typography.titleMedium.numeric(), modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
