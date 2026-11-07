package com.shrey.buddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.inMyDay
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Pill
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.tasks.TaskDetailSheet
import com.shrey.buddy.ui.tasks.TaskRow
import com.shrey.buddy.ui.tasks.dueDate
import com.shrey.buddy.ui.tasks.planColor
import com.shrey.buddy.ui.tasks.planColors
import com.shrey.buddy.ui.theme.BuddyColors
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.shrey.buddy.reminders.ReminderSetup
import com.shrey.buddy.reminders.ReminderSettings
import com.shrey.buddy.reminders.RingSettings
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.data.tasks.QuickParse
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.tasks.TaskMenu
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class TaskView(val label: String, val icon: ImageVector) {
    MY_DAY("My Day", Icons.Rounded.WbSunny),
    UPCOMING("Upcoming", Icons.Rounded.Event),
    REMINDERS("Reminders", Icons.Rounded.Alarm),
    ALL("All", Icons.AutoMirrored.Rounded.List),
    DONE("Completed", Icons.Rounded.CheckCircle),
}

/** My Day = picked for today, plus anything due today or overdue (like Microsoft To Do's suggestions). */
fun filterTasks(tasks: List<TaskEntity>, view: TaskView, today: LocalDate = LocalDate.now()): List<TaskEntity> = when (view) {
    TaskView.MY_DAY -> tasks.filter { t ->
        val due = t.dueDate()
        !t.isDone && (t.inMyDay(today) || (due != null && !due.isAfter(today)))
    }.sortedWith(compareBy<TaskEntity> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.position })
    TaskView.UPCOMING -> tasks.filter { !it.isDone && it.dueAt != null }.sortedBy { it.dueAt }
    TaskView.REMINDERS -> tasks.filter { !it.isDone && it.remindAt != null }.sortedBy { it.remindAt }
    TaskView.ALL -> tasks.filter { !it.isDone }.sortedWith(compareBy<TaskEntity> { it.planId }.thenBy { it.position })
    TaskView.DONE -> tasks.filter { it.isDone }.sortedByDescending { it.completedAt ?: it.updatedAt }.take(100)
}

/** The "Completed" section under each list: what was finished from that list, recently. */
fun completedFor(tasks: List<TaskEntity>, view: TaskView, today: LocalDate = LocalDate.now()): List<TaskEntity> {
    fun daysAgo(t: TaskEntity) = t.completedAt?.let {
        java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate(), today)
    } ?: Long.MAX_VALUE
    val done = tasks.filter { it.isDone }
    return when (view) {
        TaskView.MY_DAY -> done.filter { daysAgo(it) == 0L }
        TaskView.UPCOMING -> done.filter { it.dueAt != null && daysAgo(it) <= 7 }
        TaskView.REMINDERS -> done.filter { it.remindAt != null && daysAgo(it) <= 30 }
        TaskView.ALL -> done.filter { daysAgo(it) <= 7 }
        TaskView.DONE -> emptyList()
    }.sortedByDescending { it.completedAt }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(vm: AppViewModel, onOpenPlan: (String) -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val buckets by vm.buckets.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(TaskView.MY_DAY) }
    var text by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var newPlan by remember { mutableStateOf(false) }
    val planById = plans.associateBy { it.id }
    val visible = remember(tasks, view) { filterTasks(tasks, view) }
    val completed = remember(tasks, view) { completedFor(tasks, view) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var ringSettings by remember { mutableStateOf(false) }
    val parsed = remember(text) { QuickParse.parse(text) }
    val submit = {
        when {
            // Plain English with a date/time/repeat ("call mom tomorrow 7pm") — use what was understood.
            parsed.hasDetails -> vm.addParsed(parsed.let { if (view == TaskView.REMINDERS) it.copy(remind = true) else it }, myDay = view == TaskView.MY_DAY)
            view == TaskView.REMINDERS -> {
                // New reminder with no time: rings at the next full hour; open it straight away to pick the time.
                val at = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                vm.addTask(text, remindAt = at) { editing = it }
            }
            else -> vm.addTask(text, myDay = view == TaskView.MY_DAY)
        }
        text = ""
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                val open = tasks.count { !it.isDone }
                if (open > 0) Pill("$open open", BuddyColors.Tasks)
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TaskView.entries) { v ->
                    val count = filterTasks(tasks, v).count { !it.isDone }
                    FilterChip(
                        selected = view == v,
                        onClick = { view = v },
                        label = { Text(if (v != TaskView.DONE && count > 0) "${v.label} · $count" else v.label) },
                        leadingIcon = { Icon(v.icon, null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BuddyColors.Tasks.copy(alpha = 0.2f),
                            selectedLabelColor = BuddyColors.TextPrimary,
                            selectedLeadingIconColor = BuddyColors.Tasks,
                        ),
                    )
                }
            }
        }

        if (view != TaskView.DONE) {
            item { Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = {
                            Text(
                                when (view) {
                                    TaskView.MY_DAY -> "Add to My Day"
                                    TaskView.REMINDERS -> "Remind me to…"
                                    else -> "Add a task"
                                },
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) submit() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BuddyColors.Surface,
                            unfocusedContainerColor = BuddyColors.Surface,
                            focusedBorderColor = BuddyColors.Tasks,
                            unfocusedBorderColor = BuddyColors.Surface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = submit,
                        enabled = text.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = BuddyColors.Tasks, contentColor = BuddyColors.Background),
                    ) { Icon(Icons.Rounded.Add, contentDescription = "Add task") }
                }
                AnimatedVisibility(parsed.hasDetails && text.isNotBlank()) {
                    Box(Modifier.padding(top = 8.dp)) { ParsePreview(parsed) }
                }
            } }
        }

        if (view == TaskView.REMINDERS) item { ReminderSetupCard(onOpenSettings = { ringSettings = true }) }

        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
                if (visible.isEmpty()) {
                    EmptyNote(
                        when (view) {
                            TaskView.MY_DAY -> "Nothing planned for today. Tap ☀ on any task to add it to My Day."
                            TaskView.UPCOMING -> "No tasks with due dates."
                            TaskView.REMINDERS -> "No reminders. Type one above — Buddy will ring you like a call."
                            TaskView.ALL -> "All clear."
                            TaskView.DONE -> "Nothing completed yet."
                        },
                        Modifier.padding(12.dp),
                    )
                }
                visible.forEach { t ->
                    key(t.id) {
                        val plan = t.planId?.let { planById[it] }
                        TaskRow(
                            task = t,
                            onToggle = { vm.toggle(t) },
                            onMyDay = { vm.toggleMyDay(t) },
                            onClick = { editing = t },
                            planName = plan?.name?.takeIf { plans.size > 1 },
                            planColorKey = plan?.color,
                            menu = taskMenu(vm, t),
                        )
                    }
                }
            }
        }

        if (completed.isNotEmpty()) {
            item {
                Column {
                    Surface(
                        onClick = { showCompleted = !showCompleted },
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                    ) {
                        Row(Modifier.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val turn by animateFloatAsState(if (showCompleted) 90f else 0f, label = "chevron")
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = BuddyColors.TextSecondary, modifier = Modifier.rotate(turn))
                            Text("Completed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp))
                            Text(
                                "  ${completed.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = BuddyColors.TextTertiary,
                            )
                        }
                    }
                    AnimatedVisibility(showCompleted, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        BuddyCard(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = 8.dp) {
                            completed.forEach { t ->
                                key(t.id) {
                                    TaskRow(task = t, onToggle = { vm.toggle(t) }, onMyDay = { vm.toggleMyDay(t) }, onClick = { editing = t })
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Plans", action = "New plan", onAction = { newPlan = true }) }
        items(plans, key = { it.id }) { p ->
            val planTasks = tasks.filter { it.planId == p.id }
            val done = planTasks.count { it.isDone }
            Surface(
                onClick = { onOpenPlan(p.id) },
                shape = RoundedCornerShape(20.dp),
                color = BuddyColors.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(planColor(p.color)))
                        Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Text(
                            "${buckets.count { it.planId == p.id }} buckets",
                            style = MaterialTheme.typography.labelSmall,
                            color = BuddyColors.TextTertiary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (planTasks.isEmpty()) 0f else done.toFloat() / planTasks.size },
                        color = planColor(p.color),
                        trackColor = BuddyColors.SurfaceHigh,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Text(
                        "$done of ${planTasks.size} done",
                        style = MaterialTheme.typography.labelSmall,
                        color = BuddyColors.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    // Undo bar after ticking something off.
    com.shrey.buddy.ui.components.UndoBar(vm, Modifier.align(Alignment.BottomCenter))
    }

    editing?.let { t ->
        TaskDetailSheet(
            task = t,
            plans = plans,
            buckets = buckets,
            onDismiss = { editing = null },
            onSave = {
                vm.saveTask(it)
                editing = null
            },
            onDelete = {
                vm.delete(it)
                editing = null
            },
        )
    }
    if (newPlan) {
        PlanSheet(
            title = "New plan",
            initialName = "",
            initialColor = "sky",
            onDismiss = { newPlan = false },
            onSave = { name, color ->
                vm.addPlan(name, color)
                newPlan = false
            },
        )
    }
    if (ringSettings) RingSettingsSheet(onDismiss = { ringSettings = false })
}

fun taskMenu(vm: AppViewModel, t: TaskEntity) = TaskMenu(
    onToday = { vm.moveTask(t, LocalDate.now()) },
    onTomorrow = { vm.moveTask(t, LocalDate.now().plusDays(1)) },
    onPriority = { vm.setPriority(t, it) },
    onDuplicate = { vm.duplicate(t) },
    onDelete = { vm.delete(t) },
)

/** What the quick-add box understood, shown live under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParsePreview(p: QuickParse.Result) {
    val today = LocalDate.now()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 6.dp)) {
        p.date?.let { PreviewChip(Icons.Rounded.Event, friendlyDay(it, today)) }
        p.time?.let { PreviewChip(Icons.Rounded.Schedule, formatTime(p.date!!.atTime(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())) }
        p.repeat?.let { r -> PreviewChip(Icons.Rounded.Repeat, r.describe(p.dueAt() ?: p.date?.atStartOfDay(), p.time != null)) }
        if (p.remind && p.time != null) PreviewChip(Icons.Rounded.Alarm, "Rings")
    }
}

@Composable
private fun PreviewChip(icon: ImageVector, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(BuddyColors.Tasks.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = BuddyColors.Tasks, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextPrimary, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Ring settings at a glance (tap to change), plus anything Android still needs for call-style reminders. */
@Composable
private fun ReminderSetupCard(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(ReminderSetup.checks(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks = ReminderSetup.checks(context) }
    val missing = checks.filterNot { it.ok }
    val settings by ReminderSettings.observe(context).collectAsStateWithLifecycle()

    Surface(onClick = onOpenSettings, shape = RoundedCornerShape(20.dp), color = BuddyColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = BuddyColors.Voice)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(remember(settings) { ReminderSettings.ringtoneName(context) }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        settings?.let { ringSummary(it) }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = BuddyColors.TextTertiary,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = BuddyColors.TextTertiary)
            }
            missing.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = BuddyColors.Fitness, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(c.label, style = MaterialTheme.typography.bodyMedium)
                        Text(c.why, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                    TextButton(onClick = { runCatching { context.startActivity(c.fix) } }) { Text("Allow", color = BuddyColors.Tasks) }
                }
            }
        }
    }
}

private fun ringSummary(s: RingSettings) = listOfNotNull(
    if (s.gradual) "Gets louder" else "Full volume",
    if (s.vibrate) "Vibrates" else null,
    "Rings ${if (s.ringSeconds >= 60) "${s.ringSeconds / 60} min" else "${s.ringSeconds} s"}",
).joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RingSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by ReminderSettings.observe(context).collectAsStateWithLifecycle()
    val s = settings ?: return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        // "Default ringtone" comes back as the settings URI — store that as "follow the phone".
        val isDefault = picked == null || picked == Settings.System.DEFAULT_RINGTONE_URI
        ReminderSettings.update(context) { it.copy(ringtone = if (isDefault) null else picked) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("How reminders ring", style = MaterialTheme.typography.titleLarge)

            Surface(
                onClick = {
                    picker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Reminder ringtone")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_RINGTONE_URI)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.ringtone ?: Settings.System.DEFAULT_RINGTONE_URI),
                    )
                },
                shape = RoundedCornerShape(16.dp),
                color = BuddyColors.SurfaceHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, null, tint = BuddyColors.Voice)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Ringtone", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary)
                        Text(remember(s) { ReminderSettings.ringtoneName(context) }, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Change", color = BuddyColors.Tasks, style = MaterialTheme.typography.labelLarge)
                }
            }

            SettingSwitch("Start quiet, get louder", "Volume rises over 30 seconds", s.gradual) { on ->
                ReminderSettings.update(context) { it.copy(gradual = on) }
            }
            SettingSwitch("Vibrate", "Call-style ring-ring vibration", s.vibrate) { on ->
                ReminderSettings.update(context) { it.copy(vibrate = on) }
            }
            SettingSwitch("Read it out loud", "“Hey Shrey. Reminder: call mom.”", s.speak) { on ->
                ReminderSettings.update(context) { it.copy(speak = on) }
            }

            Text("Ring for", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 120 to "2 min").forEach { (secs, label) ->
                    FilterChip(
                        selected = s.ringSeconds == secs,
                        onClick = { ReminderSettings.update(context) { it.copy(ringSeconds = secs) } },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BuddyColors.Tasks.copy(alpha = 0.2f),
                            selectedLabelColor = BuddyColors.TextPrimary,
                        ),
                    )
                }
            }
            Text(
                "After that it becomes a “Missed reminder” notification.",
                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 4.dp),
            )

            Text("Daily check-ins", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 22.dp))
            SettingSwitch("Morning plan · 8:00 am", "Today's tasks and reminders at a glance", s.morningPlan) { on ->
                ReminderSettings.update(context) { it.copy(morningPlan = on) }
            }
            SettingSwitch("Evening wrap-up · 9:00 pm", "What's left, with “Move all to tomorrow”", s.eveningWrap) { on ->
                ReminderSettings.update(context) { it.copy(eveningWrap = on) }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanSheet(
    title: String,
    initialName: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Scrolling the form must never drag the sheet closed; Back / tapping outside / Save still close it.
        sheetGesturesEnabled = false,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Plan name, e.g. Home, Learning, Trip") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BuddyColors.SurfaceHigh,
                    unfocusedContainerColor = BuddyColors.SurfaceHigh,
                    focusedBorderColor = BuddyColors.Tasks,
                    unfocusedBorderColor = BuddyColors.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Text("Colour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                planColors.forEach { (key, c) ->
                    Surface(
                        onClick = { color = key },
                        shape = CircleShape,
                        color = c,
                        border = if (color == key) androidx.compose.foundation.BorderStroke(3.dp, BuddyColors.TextPrimary) else null,
                        modifier = Modifier.size(36.dp),
                    ) {}
                }
            }
            Button(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, color) },
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Tasks, contentColor = BuddyColors.Background),
            ) { Text("Save") }
        }
    }
}
