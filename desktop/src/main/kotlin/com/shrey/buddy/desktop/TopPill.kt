package com.shrey.buddy.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Toolkit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The always-on pill at the top of the screen ("4 left · 1 late · Next …"). Hover to drop down
 * today's list; tick things off right there. Click opens the quick bar; drag to move it.
 */
/** Keeps a pending position save so a drag writes once, not on every pixel. */
private class SaveHolder { var job: Job? = null }

/**
 * The always-on pill at the top of the screen ("4 left · 1 late · Next …"). Hover to drop down
 * today's list; tick things off right there. Click opens the quick bar; press and drag anywhere on
 * the pill to move it (the window itself moves, natively, so it's smooth).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopPillWindow(app: BuddyState, agenda: Agenda, theme: BuddyTheme, onAdd: () -> Unit) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var hoverJob by remember { mutableStateOf<Job?>(null) }
    val save = remember { SaveHolder() }

    Window(
        onCloseRequest = {},
        state = rememberWindowState(width = 240.dp, height = 36.dp),
        title = "Buddy today",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = false,
        icon = AppIcons.window,
    ) {
        val density = LocalDensity.current.density

        // Remember where the user dropped it (top-centre point), once they stop dragging.
        LaunchedEffect(window) {
            window.addComponentListener(object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) {
                    save.job?.cancel()
                    save.job = scope.launch {
                        delay(250)
                        app.updateSettings { it.copy(pillX = window.x + window.width / 2, pillY = window.y) }
                    }
                }
            })
        }

        Column(
            Modifier.wrapContentSize()
                .onSizeChanged { sz ->
                    // Keep the top-centre point where the user left it when the size changes.
                    val st = app.settings.value
                    val w = (sz.width / density).toInt() + 2
                    val h = (sz.height / density).toInt() + 2
                    window.setBounds((st.pillX ?: (screen.width / 2)) - w / 2, st.pillY ?: 6, w, h)
                }
                .onPointerEvent(PointerEventType.Enter) {
                    hoverJob?.cancel()
                    expanded = true
                }
                .onPointerEvent(PointerEventType.Exit) {
                    hoverJob?.cancel()
                    hoverJob = scope.launch { delay(120); expanded = false }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WindowDraggableArea {
                Pill(
                    agenda, theme,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { expanded = false; onAdd() })
                    },
                )
            }
            // Fade only: the window is resized once when it opens, not on every animation frame.
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(90)), exit = fadeOut(tween(60))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(6.dp))
                    TodayPanel(app, agenda, theme, onAdd = { expanded = false; onAdd() })
                }
            }
        }
    }
}

@Composable
private fun Pill(a: Agenda, c: BuddyTheme, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(50))
            .padding(start = 5.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).background(if (a.late > 0) c.lateSoft else c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(6.dp).background(if (a.late > 0) c.late else c.accent, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                a.total == 0 -> "Nothing today"
                a.left == 0 -> "All done"
                else -> "${a.left} today"
            },
            color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
        if (a.late > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "${a.late} late", color = c.late, fontSize = 11.sp,
                modifier = Modifier.background(c.lateSoft, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
            )
        }
        a.next?.let { n ->
            Spacer(Modifier.width(6.dp))
            Text(
                "Next · ${n.title.take(18)}${if (n.title.length > 18) "…" else ""} ${time(n.remindAt!!)}",
                color = c.dim, fontSize = 11.sp, maxLines = 1,
            )
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private fun time(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(timeFmt).lowercase()

@Composable
private fun TodayPanel(app: BuddyState, a: Agenda, c: BuddyTheme, onAdd: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(340.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Today", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), color = c.dim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (a.total > 0) Text("${a.done.size} of ${a.total} done", color = c.accentText, fontSize = 12.sp)
        }
        if (a.total > 0) {
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(c.divider)) {
                Box(Modifier.fillMaxWidth(a.done.size.toFloat() / a.total).fillMaxHeight().clip(RoundedCornerShape(50)).background(c.accent))
            }
        }
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (a.left == 0) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TaskAlt, null, tint = c.ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (a.total == 0) "Nothing planned for today." else "All done for today. Nice.", color = c.dim, fontSize = 13.sp)
                }
            }
            Section("Overdue", a.overdue, c, c.late) { t -> TaskRow(app, t, c, late = true) }
            Section("Reminders", a.reminders, c) { t -> TaskRow(app, t, c) }
            Section("Tasks", a.tasks, c) { t -> TaskRow(app, t, c) }
            Section("Done", a.done.take(4), c) { t -> TaskRow(app, t, c) }
        }
        Row(
            Modifier.fillMaxWidth().background(c.surfaceAlt).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tomorrow ${a.tomorrow}  ·  This week ${a.thisWeek}", color = c.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(c.accent).clickable(onClick = onAdd).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add", color = c.onAccent, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, items: List<Task>, c: BuddyTheme, color: Color = c.dim, row: @Composable (Task) -> Unit) {
    if (items.isEmpty()) return
    Text(title, color = color, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
    items.forEach { row(it) }
}

@Composable
fun TaskRow(app: BuddyState, t: Task, c: BuddyTheme, late: Boolean = false, showDay: Boolean = false) {
    val now = System.currentTimeMillis()
    val passed = !t.isDone && (t.remindAt?.let { it <= now } ?: false)
    val bg = when {
        late -> c.lateSoft
        t.remindAt != null && !t.isDone && !passed && t.id == app.agenda.value.next?.id -> c.surfaceAlt
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (t.isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            if (t.isDone) "Mark not done" else "Mark done",
            tint = if (t.isDone) c.accent else if (late) c.late.copy(alpha = 0.6f) else c.faint,
            modifier = Modifier.size(19.dp).clip(CircleShape).clickable { app.toggle(t) },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            t.title, color = if (t.isDone) c.faint else c.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textDecoration = if (t.isDone) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TaskMeta(t, c, late, passed, showDay)
    }
}

@Composable
private fun TaskMeta(t: Task, c: BuddyTheme, late: Boolean, passed: Boolean, showDay: Boolean) {
    if (t.isDone) return
    when {
        late -> Text(Commands.whenText(t.remindAt ?: t.dueAt!!, withTime = false).replaceFirstChar { it.uppercase() }, color = c.late, fontSize = 11.sp)
        t.remindAt != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (t.repeat != null) Icons.Rounded.Repeat else Icons.Rounded.NotificationsNone, null,
                tint = if (passed) c.late else c.accentText, modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                if (showDay) Commands.whenText(t.remindAt) else time(t.remindAt),
                color = if (passed) c.late else c.accentText, fontSize = 11.sp,
            )
        }
        t.priority == "high" || t.priority == "urgent" -> Text(
            t.priority.replaceFirstChar { it.uppercase() }, color = c.warn, fontSize = 10.sp,
            modifier = Modifier.background(c.warnSoft, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
        )
        showDay && t.dueAt != null -> Text(Commands.whenText(t.dueAt, withTime = !t.dueAllDay), color = c.dim, fontSize = 11.sp)
    }
}
