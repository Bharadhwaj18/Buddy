package com.shrey.buddy.ui.health

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shrey.buddy.data.items.ExerciseEntry
import com.shrey.buddy.data.items.Habit
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Meal
import com.shrey.buddy.data.items.SetEntry
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.ui.ImageInput
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.DateField
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.launch
import java.time.LocalDate

val workoutTypes = listOf(
    "strength" to "Strength", "run" to "Run", "walk" to "Walk", "cycle" to "Cycle",
    "yoga" to "Yoga", "sport" to "Sport", "other" to "Other",
)

@Composable
fun HabitSheet(initial: Item<Habit>?, onDismiss: () -> Unit, onSave: (Habit) -> Unit, onDelete: (() -> Unit)?) {
    val h = initial?.data ?: Habit()
    var name by remember { mutableStateOf(h.name) }
    var emoji by remember { mutableStateOf(h.emoji) }
    var auto by remember { mutableStateOf(h.auto ?: "manual") }
    var target by remember { mutableStateOf(if (h.auto == "steps") h.target.toString() else "8000") }

    FormSheet(
        title = if (initial == null) "New habit" else "Edit habit",
        onDismiss = onDismiss,
        accent = BuddyColors.Fitness,
        saveEnabled = name.isNotBlank(),
        onDelete = onDelete,
        onSave = {
            onSave(
                h.copy(
                    name = name.trim(),
                    emoji = emoji.trim().ifEmpty { "✅" },
                    auto = auto.takeIf { it != "manual" },
                    target = if (auto == "steps") target.toIntOrNull() ?: 8000 else 1,
                ),
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(emoji, { emoji = it.take(2) }, "Icon", Modifier.width(90.dp), accent = BuddyColors.Fitness)
            Field(name, { name = it }, "Habit, e.g. Read 20 min", Modifier.weight(1f), accent = BuddyColors.Fitness)
        }
        Choices(
            listOf("manual" to "I'll tick it", "steps" to "Auto: steps", "no_spend" to "Auto: no-spend day"),
            auto, { auto = it }, BuddyColors.Fitness, label = "How it's tracked",
        )
        if (auto == "steps") Field(target, { target = it.filter(Char::isDigit) }, "Daily step goal", keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
    }
}

@Composable
fun WorkoutSheet(
    initial: Item<Workout>?,
    previous: List<Item<Workout>>,
    onDismiss: () -> Unit,
    onSave: (Workout, LocalDate) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val w = initial?.data ?: Workout()
    var type by remember { mutableStateOf(w.type) }
    var title by remember { mutableStateOf(w.title) }
    var minutes by remember { mutableStateOf(if (w.durationMin > 0) w.durationMin.toString() else "") }
    var notes by remember { mutableStateOf(w.notes) }
    var day by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }
    var exercises by remember { mutableStateOf(w.exercises) }
    var effort by remember { mutableStateOf(w.effort) }
    val lastSame = previous.firstOrNull { it.id != initial?.id && it.data.type == "strength" && it.data.exercises.isNotEmpty() && (title.isBlank() || it.data.title.equals(title, true)) }

    FormSheet(
        title = if (initial == null) "Log workout" else "Edit workout",
        onDismiss = onDismiss,
        accent = BuddyColors.Fitness,
        onDelete = onDelete,
        onSave = {
            onSave(
                w.copy(
                    type = type,
                    title = title.trim().ifEmpty { workoutTypes.first { it.first == type }.second },
                    durationMin = minutes.toIntOrNull() ?: 0,
                    notes = notes.trim(),
                    exercises = exercises.filter { it.name.isNotBlank() },
                    effort = effort,
                    // Re-estimated from the (possibly edited) type / effort / minutes; weight comes later in the list view.
                    kcal = 0,
                ),
                day,
            )
        },
    ) {
        Choices(workoutTypes, type, { type = it }, BuddyColors.Fitness)
        Field(title, { title = it }, "Title, e.g. Push day", accent = BuddyColors.Fitness)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(minutes, { minutes = it.filter(Char::isDigit) }, "Minutes", Modifier.width(120.dp), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
            DateField("Date", day, { day = it ?: LocalDate.now() }, allowClear = false)
        }
        Text("Effort", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary)
        Choices(listOf("easy" to "Easy", "moderate" to "Moderate", "hard" to "Hard"), effort, { effort = it }, BuddyColors.Fitness)
        (minutes.toIntOrNull())?.takeIf { it > 0 }?.let { m ->
            Text(
                "≈ ${com.shrey.buddy.health.StrengthMath.kcal(type, effort, m, null)} kcal (at 70 kg — log your weight for your own number)",
                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
            )
        }

        if (type == "strength") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exercises", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                if (lastSame != null && exercises.isEmpty()) {
                    TextButton(onClick = {
                        exercises = lastSame.data.exercises
                        if (title.isBlank()) title = lastSame.data.title
                    }) { Text("Repeat “${lastSame.data.title}”", color = BuddyColors.Fitness) }
                }
            }
            exercises.forEachIndexed { ei, ex ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Field(ex.name, { v -> exercises = exercises.mapIndexed { i, e -> if (i == ei) e.copy(name = v) else e } }, "Exercise", Modifier.weight(1f), accent = BuddyColors.Fitness)
                        IconButton(onClick = { exercises = exercises.filterIndexed { i, _ -> i != ei } }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove exercise", tint = BuddyColors.TextTertiary)
                        }
                    }
                    ex.sets.forEachIndexed { si, set ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Set ${si + 1}", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary, modifier = Modifier.width(44.dp))
                            Field(
                                if (set.reps > 0) set.reps.toString() else "",
                                { v -> exercises = updateSet(exercises, ei, si) { it.copy(reps = v.filter(Char::isDigit).toIntOrNull() ?: 0) } },
                                "Reps", Modifier.weight(1f), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness,
                            )
                            Field(
                                if (set.weightKg > 0) set.weightKg.toString().removeSuffix(".0") else "",
                                { v -> exercises = updateSet(exercises, ei, si) { it.copy(weightKg = v.toDoubleOrNull() ?: 0.0) } },
                                "kg", Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = BuddyColors.Fitness,
                            )
                        }
                    }
                    TextButton(onClick = {
                        exercises = exercises.mapIndexed { i, e -> if (i == ei) e.copy(sets = e.sets + (e.sets.lastOrNull() ?: SetEntry())) else e }
                    }) { Text("+ Set", color = BuddyColors.Fitness) }
                }
            }
            OutlinedButton(onClick = { exercises = exercises + ExerciseEntry(sets = listOf(SetEntry())) }) {
                Icon(Icons.Rounded.Add, null, tint = BuddyColors.Fitness)
                Text("Add exercise", modifier = Modifier.padding(start = 6.dp))
            }
        }
        Field(notes, { notes = it }, "Notes", singleLine = false, minLines = 2, accent = BuddyColors.Fitness)
    }
}

private fun updateSet(list: List<ExerciseEntry>, ei: Int, si: Int, f: (SetEntry) -> SetEntry) =
    list.mapIndexed { i, e -> if (i == ei) e.copy(sets = e.sets.mapIndexed { j, s -> if (j == si) f(s) else s }) else e }

@Composable
fun MealSheet(
    initial: Item<Meal>?,
    vm: HealthViewModel,
    estimating: String?,
    onDismiss: () -> Unit,
    onSave: (Meal, LocalDate) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val m = initial?.data ?: Meal()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf(m.description) }
    var kcal by remember { mutableStateOf(if (m.kcal > 0) m.kcal.toString() else "") }
    var protein by remember { mutableStateOf(if (m.proteinG > 0) m.proteinG.toString() else "") }
    var slot by remember { mutableStateOf(m.slot) }
    var day by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val apply: (String, Int, Int) -> Unit = { d, k, p ->
        if (description.isBlank()) description = d
        kcal = k.toString()
        protein = p.toString()
    }
    fun estimateFrom(uri: Uri) = scope.launch {
        vm.estimateMealPhoto(ImageInput.toBase64Jpeg(context, uri), apply)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { estimateFrom(it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let { estimateFrom(it) } }

    FormSheet(
        title = if (initial == null) "Log meal" else "Edit meal",
        onDismiss = { vm.clearEstimateError(); onDismiss() },
        accent = BuddyColors.Fitness,
        saveEnabled = description.isNotBlank(),
        onDelete = onDelete,
        onSave = {
            onSave(m.copy(description = description.trim(), kcal = kcal.toIntOrNull() ?: 0, proteinG = protein.toIntOrNull() ?: 0, slot = slot), day)
        },
    ) {
        Choices(listOf("breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snack", "meal" to "Other"), slot, { slot = it }, BuddyColors.Fitness)
        Field(description, { description = it }, "What did you eat?", singleLine = false, accent = BuddyColors.Fitness)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.estimateMeal(description, apply) }, enabled = description.isNotBlank() && estimating != "") {
                Icon(Icons.Rounded.AutoAwesome, null, tint = BuddyColors.Voice, modifier = Modifier.size(18.dp))
                Text("Estimate", modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Estimate from photo", tint = BuddyColors.Voice)
            }
            IconButton(onClick = { ImageInput.newCaptureUri(context).also { captureUri = it; camera.launch(it) } }) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = "Take photo", tint = BuddyColors.Voice)
            }
            if (estimating == "") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (!estimating.isNullOrEmpty()) Text(estimating, color = BuddyColors.Red, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(kcal, { kcal = it.filter(Char::isDigit) }, "kcal", Modifier.weight(1f), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
            Field(protein, { protein = it.filter(Char::isDigit) }, "Protein g", Modifier.weight(1f), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
        }
        DateField("Date", day, { day = it ?: LocalDate.now() }, allowClear = false)
    }
}
