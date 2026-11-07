package com.shrey.buddy.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.shrey.buddy.data.db.BucketEntity
import com.shrey.buddy.data.db.PlanEntity
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.sync.startMillis
import com.shrey.buddy.data.tasks.ChecklistItem
import com.shrey.buddy.data.tasks.Checklists
import com.shrey.buddy.data.tasks.checklist
import com.shrey.buddy.data.tasks.inMyDay
import com.shrey.buddy.data.tasks.ReminderPreset
import com.shrey.buddy.data.tasks.RepeatRule
import androidx.compose.material.icons.rounded.Repeat
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.reminders.ReminderSettings
import androidx.compose.ui.platform.LocalContext
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
internal fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = BuddyColors.SurfaceHigh,
    unfocusedContainerColor = BuddyColors.SurfaceHigh,
    focusedBorderColor = BuddyColors.Tasks,
    unfocusedBorderColor = BuddyColors.SurfaceHigh,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailSheet(
    task: TaskEntity,
    plans: List<PlanEntity>,
    buckets: List<BucketEntity>,
    onDismiss: () -> Unit,
    onSave: (TaskEntity) -> Unit,
    onDelete: (TaskEntity) -> Unit,
) {
    var title by remember { mutableStateOf(task.title) }
    var notes by remember { mutableStateOf(task.notes.orEmpty()) }
    var status by remember { mutableStateOf(task.status) }
    var priority by remember { mutableStateOf(task.priority) }
    var due by remember { mutableStateOf(task.dueDate()) }
    var time by remember { mutableStateOf(task.dueTime()) }
    var preset by remember { mutableStateOf(ReminderPreset.match(task.remindAt, task.dueDate(), task.dueTime())) }
    var customRemind by remember { mutableStateOf(if (preset == null) task.remindAt else null) }
    var repeat by remember { mutableStateOf(task.repeat) }
    val context = LocalContext.current
    var nag by remember { mutableStateOf(ReminderSettings.isNag(context, task.id)) }
    var myDay by remember { mutableStateOf(task.inMyDay()) }
    var planId by remember { mutableStateOf(task.planId) }
    var bucketId by remember { mutableStateOf(task.bucketId) }
    var items by remember { mutableStateOf(task.checklist) }
    var newItem by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var editingRepeat by remember { mutableStateOf(false) }
    var pickingRemindDate by remember { mutableStateOf(false) }
    var customRemindDate by remember { mutableStateOf<LocalDate?>(null) }
    val planBuckets = buckets.filter { it.planId == planId }

    // Switching between all-day and timed changes which presets make sense; keep a sensible one.
    fun setTime(t: LocalTime?) {
        time = t
        if (preset != null && preset !in ReminderPreset.available(t)) preset = if (t != null) ReminderPreset.AT_TIME else ReminderPreset.MORNING_OF
    }
    fun remindAt(): Long? = preset?.let { p -> due?.let { d -> p.at(d, time)?.millis() } } ?: customRemind?.takeIf { preset == null }

    fun build() = task.copy(
        title = title.trim().ifEmpty { task.title },
        notes = notes.trim().ifEmpty { null },
        status = status,
        priority = priority,
        dueAt = due?.let { d -> time?.let { d.atTime(it).millis() } ?: d.startMillis() },
        dueAllDay = time == null || due == null,
        remindAt = remindAt(),
        repeat = repeat,
        myDay = if (myDay) (if (task.inMyDay()) task.myDay else LocalDate.now().toString()) else null,
        planId = planId,
        bucketId = bucketId,
        checklistJson = Checklists.encode(items),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Scrolling the form must never drag the sheet closed; Back / tapping outside / Save still close it.
        sheetGesturesEnabled = false,
        containerColor = BuddyColors.Surface,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = MaterialTheme.typography.titleLarge,
                shape = RoundedCornerShape(16.dp),
                colors = sheetFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Progress")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                statuses.forEach { (key, label) ->
                    FilterChip(selected = status == key, onClick = { status = key }, label = { Text(label) }, colors = chipColors())
                }
            }

            Label("Priority")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                priorities.forEach { (key, label) ->
                    FilterChip(selected = priority == key, onClick = { priority = key }, label = { Text(label) }, colors = chipColors())
                }
            }

            Label("Due")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerButton(Icons.Rounded.Event, due?.let { friendlyDay(it) } ?: "Add date", active = due != null) { pickingDate = true }
                if (due != null) {
                    PickerButton(Icons.Rounded.Schedule, time?.let { formatTime(due!!.atTime(it).millis()) } ?: "Add time", active = time != null) { pickingTime = true }
                    IconButton(onClick = {
                        due = null
                        setTime(null)
                        if (preset != null) preset = null
                    }) { Icon(Icons.Rounded.Close, contentDescription = "Clear due date", tint = BuddyColors.TextTertiary) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WbSunny, null, tint = if (myDay) BuddyColors.Lime else BuddyColors.TextTertiary, modifier = Modifier.size(18.dp))
                Text("Add to My Day", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Switch(
                    checked = myDay,
                    onCheckedChange = { myDay = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
                )
            }

            Label("Remind me (rings like a call)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = preset == null && customRemind == null,
                    onClick = { preset = null; customRemind = null },
                    label = { Text("None") }, colors = chipColors(),
                )
                if (due != null) ReminderPreset.available(time).forEach { p ->
                    FilterChip(selected = preset == p, onClick = { preset = p; customRemind = null }, label = { Text(p.label) }, colors = chipColors())
                }
                FilterChip(
                    selected = preset == null && customRemind != null,
                    onClick = { pickingRemindDate = true },
                    label = { Text(if (preset == null && customRemind != null) "Custom ✎" else "Pick date & time") },
                    colors = chipColors(),
                )
            }
            remindAt()?.let { at ->
                val past = at < System.currentTimeMillis()
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Alarm, null, tint = if (past) BuddyColors.Red else BuddyColors.Lime, modifier = Modifier.size(16.dp))
                    Text(
                        (if (past) "Was due " else "Rings ") + describeMoment(at),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (past) BuddyColors.Red else BuddyColors.TextSecondary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (remindAt() != null) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep calling until I answer", style = MaterialTheme.typography.bodyMedium)
                        Text("Rings again every 10 min if missed (up to 6 times)", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                    Switch(
                        checked = nag,
                        onCheckedChange = { nag = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
                    )
                }
            }

            Label("Repeat")
            val rule = RepeatRule.parse(repeat)
            val ruleAnchor = due?.let { d -> time?.let { d.atTime(it) } ?: d.atStartOfDay() }
                ?: remindAt()?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
            PickerButton(
                Icons.Rounded.Repeat,
                rule?.describe(ruleAnchor, withTime = time != null || (due == null && remindAt() != null)) ?: "Doesn't repeat",
                active = rule != null,
            ) { editingRepeat = true }
            if (repeat != null) {
                Text(
                    "Ticking it off creates the next one.",
                    style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (plans.size > 1) {
                Label("Plan")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    plans.forEach { p ->
                        FilterChip(
                            selected = planId == p.id,
                            onClick = {
                                if (planId != p.id) {
                                    planId = p.id
                                    bucketId = buckets.firstOrNull { it.planId == p.id }?.id
                                }
                            },
                            label = { Text(p.name) },
                            colors = chipColors(),
                        )
                    }
                }
            }
            if (planBuckets.isNotEmpty()) {
                Label("Bucket")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    planBuckets.forEach { b ->
                        FilterChip(selected = bucketId == b.id, onClick = { bucketId = b.id }, label = { Text(b.name) }, colors = chipColors())
                    }
                }
            }

            Label("Checklist")
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { items = items.map { if (it.id == item.id) it.copy(done = !it.done) else it } }) {
                        Icon(
                            if (item.done) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (item.done) BuddyColors.Tasks else BuddyColors.TextTertiary,
                        )
                    }
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        color = if (item.done) BuddyColors.TextTertiary else BuddyColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { items = items.filterNot { it.id == item.id } }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove item", tint = BuddyColors.TextTertiary)
                    }
                }
            }
            val addItem = {
                if (newItem.isNotBlank()) {
                    items = items + ChecklistItem(UUID.randomUUID().toString(), newItem.trim())
                    newItem = ""
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newItem,
                    onValueChange = { newItem = it },
                    placeholder = { Text("Add an item") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = sheetFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addItem() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = addItem) { Icon(Icons.Rounded.Add, contentDescription = "Add item", tint = BuddyColors.Tasks) }
            }

            Label("Notes")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = { Text("Add notes") },
                minLines = 2,
                shape = RoundedCornerShape(14.dp),
                colors = sheetFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onDelete(task) }, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete task", tint = BuddyColors.Red)
                }
                Button(
                    onClick = {
                        ReminderSettings.setNag(context, task.id, nag && remindAt() != null)
                        onSave(build())
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Tasks, contentColor = BuddyColors.Background),
                ) { Text("Save") }
            }
        }
    }

    if (editingRepeat) {
        RepeatEditorSheet(
            initialRule = RepeatRule.parse(repeat),
            initialStart = due ?: remindAt()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
            initialTime = time ?: remindAt()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() },
            initialRing = remindAt() != null || repeat == null,
            onDismiss = { editingRepeat = false },
            onApply = { result ->
                editingRepeat = false
                if (result == null) {
                    repeat = null
                } else {
                    repeat = result.rule.encode()
                    due = result.start
                    setTime(result.time)
                    // The repeat's time is when it rings (if wanted).
                    if (result.ring) {
                        preset = if (result.time != null) ReminderPreset.AT_TIME else ReminderPreset.MORNING_OF
                        customRemind = null
                    } else {
                        preset = null
                        customRemind = null
                    }
                }
            },
        )
    }
    if (pickingDate) {
        DatePickDialog(due ?: LocalDate.now(), onDismiss = { pickingDate = false }) {
            due = it
            pickingDate = false
        }
    }
    if (pickingTime) {
        TimePickDialog(time ?: LocalTime.of(9, 0), onDismiss = { pickingTime = false }) {
            setTime(it)
            pickingTime = false
        }
    }
    // Custom reminder: date first, then time.
    if (pickingRemindDate) {
        val current = customRemind?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        DatePickDialog(current ?: due ?: LocalDate.now(), onDismiss = { pickingRemindDate = false }) {
            customRemindDate = it
            pickingRemindDate = false
        }
    }
    customRemindDate?.let { d ->
        val current = customRemind?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
        TimePickDialog(current ?: time ?: LocalTime.now().plusHours(1).withMinute(0), onDismiss = { customRemindDate = null }) {
            customRemind = d.atTime(it).millis()
            preset = null
            customRemindDate = null
        }
    }
}

private fun LocalDateTime.millis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** "today, 6:30 pm" / "Sat 4 Oct, 9:00 am". */
fun describeMoment(millis: Long, today: LocalDate = LocalDate.now()): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }
    return "$day, ${formatTime(millis)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // DatePicker works in UTC midnights; convert to/from the local date.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun PickerButton(icon: ImageVector, text: String, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = BuddyColors.SurfaceHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (active) BuddyColors.Tasks else BuddyColors.TextTertiary, modifier = Modifier.size(18.dp))
            Text(
                text, style = MaterialTheme.typography.labelLarge,
                color = if (active) BuddyColors.TextPrimary else BuddyColors.TextSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = BuddyColors.Tasks.copy(alpha = 0.2f),
    selectedLabelColor = BuddyColors.TextPrimary,
)
