package com.shrey.buddy.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.checklist
import com.shrey.buddy.data.tasks.inMyDay
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.reminders.ReminderScheduler
import com.shrey.buddy.reminders.ReminderSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Repeat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val planColors: Map<String, Color> = linkedMapOf(
    "lime" to BuddyColors.Lime,
    "sky" to BuddyColors.Tasks,
    "orange" to BuddyColors.Fitness,
    "violet" to BuddyColors.Voice,
    "pink" to Color(0xFFFF6FAE),
    "teal" to Color(0xFF30D5C8),
)

fun planColor(key: String?): Color = planColors[key] ?: BuddyColors.Tasks

val statuses = listOf("todo" to "Not started", "in_progress" to "In progress", "done" to "Done")
val priorities = listOf("low" to "Low", "normal" to "Normal", "high" to "High", "urgent" to "Urgent")

fun TaskEntity.dueDate(): LocalDate? = dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
fun TaskEntity.dueTime(): LocalTime? = if (dueAllDay) null else dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }

/** Overdue = past the due time (timed tasks) or before today (all-day tasks). */
fun TaskEntity.isOverdue(now: Long = System.currentTimeMillis(), today: LocalDate = LocalDate.now()): Boolean {
    if (isDone || dueAt == null) return false
    return if (dueAllDay) dueDate()!!.isBefore(today) else dueAt < now
}

private fun statusIcon(status: String): Pair<ImageVector, Color> = when (status) {
    "done" -> Icons.Rounded.CheckCircle to BuddyColors.Tasks
    "in_progress" -> Icons.Rounded.Timelapse to BuddyColors.Fitness
    else -> Icons.Rounded.RadioButtonUnchecked to BuddyColors.TextTertiary
}

@Composable
private fun MetaChip(icon: ImageVector?, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = if (icon != null) 3.dp else 0.dp))
    }
}

/** "6:00 pm" today, otherwise "Tomorrow 9:00 am" / "Sat 4 Oct 9:00 am". */
private fun shortMoment(millis: Long, today: LocalDate): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (date == today) formatTime(millis) else "${friendlyDay(date, today)} ${formatTime(millis)}"
}

/** Due date, priority, checklist progress, notes — the small line under a task title. */
@Composable
fun TaskMeta(task: TaskEntity, planName: String? = null, planColorKey: String? = null) {
    val today = LocalDate.now()
    val due = task.dueDate()
    val items = task.checklist
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (planName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(planColor(planColorKey)))
                Text(planName, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (due != null) {
            val color = when {
                task.isDone -> BuddyColors.TextTertiary
                task.isOverdue(today = today) -> BuddyColors.Red
                due == today -> BuddyColors.Lime
                else -> BuddyColors.TextSecondary
            }
            val time = if (task.dueAllDay) "" else ", " + formatTime(task.dueAt!!)
            MetaChip(null, friendlyDay(due, today) + time, color)
        }
        if (task.remindAt != null && !task.isDone) {
            val context = LocalContext.current
            val snoozeVersion by ReminderSettings.snoozeVersion.collectAsState()
            val now = System.currentTimeMillis()
            val snoozed = remember(task.id, task.remindAt, snoozeVersion) {
                ReminderScheduler.snoozedUntil(context, task)?.takeIf { it > now }
            }
            if (snoozed != null) {
                MetaChip(Icons.Rounded.Snooze, "Snoozed · rings ${shortMoment(snoozed, today)}", BuddyColors.Fitness)
            } else {
                MetaChip(
                    Icons.Rounded.Alarm,
                    if (task.remindAt == task.dueAt) "" else shortMoment(task.remindAt, today),
                    if (task.remindAt > now) BuddyColors.Voice else BuddyColors.TextTertiary,
                )
            }
        }
        if (task.repeat != null) Icon(Icons.Rounded.Repeat, contentDescription = "Repeats", tint = BuddyColors.TextTertiary, modifier = Modifier.size(13.dp))
        when (task.priority) {
            "urgent" -> MetaChip(Icons.Rounded.PriorityHigh, "Urgent", BuddyColors.Red)
            "high" -> MetaChip(Icons.Rounded.Flag, "High", BuddyColors.Fitness)
        }
        if (items.isNotEmpty()) MetaChip(Icons.Rounded.Checklist, "${items.count { it.done }}/${items.size}", BuddyColors.TextSecondary)
        if (!task.notes.isNullOrBlank()) Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = BuddyColors.TextTertiary, modifier = Modifier.size(13.dp))
    }
}

/**
 * List row used in My Day / Upcoming / Reminders / All.
 *
 * Ticking an open task plays a short "done" moment — the check pops in, the title strikes through —
 * then the row folds away and [onToggle] runs. Un-ticking a done task is immediate.
 */
@Composable
fun TaskRow(
    task: TaskEntity,
    onToggle: () -> Unit,
    onMyDay: () -> Unit,
    onClick: () -> Unit,
    planName: String? = null,
    planColorKey: String? = null,
    /** Long-press menu + swipe-left-to-tomorrow; null for read-only places. */
    menu: TaskMenu? = null,
) {
    // Keyed on status too: if the row is still shown after the change lands, it reappears in its new state.
    var completing by remember(task.id, task.status) { mutableStateOf(false) }
    val visible = remember(task.id, task.status) { MutableTransitionState(true) }
    LaunchedEffect(completing) {
        if (completing) {
            delay(650)
            visible.targetState = false
        }
    }
    // Commit once the fold-away animation has finished.
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (completing && visible.isIdle && !visible.currentState) onToggle()
    }

    val done = task.isDone || completing
    val (icon, tint) = if (completing) Icons.Rounded.CheckCircle to BuddyColors.Lime else statusIcon(task.status)
    val pop by animateFloatAsState(
        if (completing) 1.25f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pop",
    )
    val glow by animateColorAsState(if (completing) BuddyColors.Lime.copy(alpha = 0.10f) else Color.Transparent, tween(250), label = "glow")

    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var lastSwipeAt by remember(task.id) { mutableStateOf(0L) }
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    AnimatedVisibility(visibleState = visible, exit = shrinkVertically(tween(320)) + fadeOut(tween(260))) {
        // Swipe right = done, swipe left = tomorrow. The row springs back; the action does the rest.
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromStartToEnd = !task.isDone && !completing,
            enableDismissFromEndToStart = !task.isDone && !completing && menu != null,
            backgroundContent = { SwipeBackground(swipe.dismissDirection) },
            onDismiss = { direction ->
                // One swipe = one action, even if the box reports the settled state more than once.
                val now = System.currentTimeMillis()
                if (now - lastSwipeAt < 1_500) return@SwipeToDismissBox
                lastSwipeAt = now
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> completing = true
                    SwipeToDismissBoxValue.EndToStart -> menu?.onTomorrow?.invoke()
                    else -> Unit
                }
                scope.launch { swipe.reset() }
            },
        ) {
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BuddyColors.Surface).background(glow)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = if (menu == null) null else {
                                {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuOpen = true
                                }
                            },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (task.isDone) onToggle() else if (!completing) completing = true }) {
                        Icon(icon, contentDescription = if (task.isDone) "Mark not done" else "Mark done", tint = tint, modifier = Modifier.scale(pop))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (done) BuddyColors.TextTertiary else BuddyColors.TextPrimary,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TaskMeta(task, planName, planColorKey)
                    }
                    IconButton(onClick = onMyDay) {
                        Icon(
                            Icons.Rounded.WbSunny,
                            contentDescription = if (task.inMyDay()) "Remove from My Day" else "Add to My Day",
                            tint = if (task.inMyDay()) BuddyColors.Lime else BuddyColors.SurfaceHighest,
                        )
                    }
                }
                if (menu != null) TaskMenuDropdown(menuOpen, task, menu, onClose = { menuOpen = false })
            }
        }
    }
}

/** Long-press actions on a task row. */
class TaskMenu(
    val onToday: () -> Unit,
    val onTomorrow: () -> Unit,
    val onPriority: (String) -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun TaskMenuDropdown(open: Boolean, task: TaskEntity, menu: TaskMenu, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose, containerColor = BuddyColors.SurfaceHigh, offset = DpOffset(48.dp, 0.dp)) {
        @Composable
        fun item(label: String, icon: ImageVector, tint: Color = BuddyColors.TextSecondary, action: () -> Unit) = DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, null, tint = tint) },
            onClick = {
                onClose()
                action()
            },
        )
        if (!task.inMyDay()) item("Do today", Icons.Rounded.WbSunny, BuddyColors.Lime, menu.onToday)
        item("Move to tomorrow", Icons.Rounded.Event, BuddyColors.Fitness, menu.onTomorrow)
        HorizontalDivider(color = BuddyColors.Surface)
        priorities.reversed().filter { it.first != task.priority && it.first != "low" }.forEach { (key, label) ->
            item(
                "Mark $label".let { if (key == "normal") "Normal priority" else it },
                if (key == "urgent") Icons.Rounded.PriorityHigh else Icons.Rounded.Flag,
                when (key) { "urgent" -> BuddyColors.Red; "high" -> BuddyColors.Fitness; else -> BuddyColors.TextSecondary },
            ) { menu.onPriority(key) }
        }
        HorizontalDivider(color = BuddyColors.Surface)
        item("Duplicate", Icons.Rounded.ContentCopy, action = menu.onDuplicate)
        item("Delete", Icons.Rounded.DeleteOutline, BuddyColors.Red, menu.onDelete)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, icon, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Quad(BuddyColors.Lime, Icons.Rounded.CheckCircle, "Done", Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> Quad(BuddyColors.Fitness, Icons.Rounded.Event, "Tomorrow", Alignment.CenterEnd)
        else -> Quad(Color.Transparent, null, "", Alignment.Center)
    }
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.85f)).padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = BuddyColors.OnLime)
                Text(label, style = MaterialTheme.typography.labelLarge, color = BuddyColors.OnLime, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private data class Quad(val color: Color, val icon: ImageVector?, val label: String, val alignment: Alignment)

/** Card on a plan's board. */
@Composable
fun TaskCard(task: TaskEntity, onToggle: () -> Unit, onClick: () -> Unit) {
    val (icon, tint) = statusIcon(task.status)
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = BuddyColors.SurfaceHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) { Icon(icon, contentDescription = "Toggle done", tint = tint) }
            Column(Modifier.weight(1f).padding(top = 9.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (task.isDone) BuddyColors.TextTertiary else BuddyColors.TextPrimary,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                )
                Box(Modifier.padding(top = 4.dp)) { TaskMeta(task) }
            }
            if (task.inMyDay()) Icon(Icons.Rounded.WbSunny, null, tint = BuddyColors.Lime, modifier = Modifier.padding(top = 10.dp).size(16.dp))
        }
    }
}
