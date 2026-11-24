package com.shrey.buddy.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

enum class Phase { Edit, Thinking, Saving, Saved, Answer }

data class BarUi(
    val visible: Boolean = false,
    /** Wispr is listening (hotkey held). */
    val listening: Boolean = false,
    /** Hotkey released: Wispr's text is on its way. */
    val awaitingVoice: Boolean = false,
    val phase: Phase = Phase.Edit,
    val steps: List<Step> = emptyList(),
    val saved: List<String> = emptyList(),
    /** Bumps each time the bar opens fresh, to clear and re-focus. */
    val openCount: Int = 0,
)

/** The small card at the bottom of the screen: "Saving…" with Cancel, then "Reminder added". */
data class ToastUi(val saving: Boolean, val heading: String, val body: String)

/**
 * The quick bar. Enter closes the bar at once and works out what you meant in the background.
 * A small card then shows "Saving…" with Cancel for [Bar.SAVE_DELAY_MS]; Cancel puts your text back
 * in the bar. Otherwise it saves and confirms with a short "Reminder added" card.
 */
class Bar(private val app: BuddyState) {
    val ui = MutableStateFlow(BarUi())
    val toast = MutableStateFlow<ToastUi?>(null)
    var text by mutableStateOf(TextFieldValue(""))
    private var job: Job? = null
    private var pendingInput: String = ""

    fun open() = ui.update { s ->
        if (s.visible && s.phase == Phase.Edit) s
        else {
            text = TextFieldValue("")
            BarUi(visible = true, openCount = s.openCount + 1)
        }
    }

    fun talkStart() = ui.update { s ->
        if (!s.visible || s.phase != Phase.Edit) {
            text = TextFieldValue("")
            BarUi(visible = true, listening = true, openCount = s.openCount + 1)
        } else s.copy(listening = true, awaitingVoice = false)
    }

    fun talkEnd() = ui.update { it.copy(listening = false, awaitingVoice = true) }

    /** Closes the bar. A save that's already counting down still finishes. */
    fun hide() = ui.update { it.copy(visible = false, listening = false, awaitingVoice = false) }

    /** Esc closes the bar (a pending save has its own Cancel). */
    fun escape() = hide()

    /** Cancel on the saving card: nothing is stored, and the text comes back to the bar. */
    fun cancel() {
        job?.cancel()
        toast.value = null
        text = TextFieldValue(pendingInput)
        ui.update { BarUi(visible = true, openCount = it.openCount + 1) }
    }

    fun submit() {
        val input = text.text
        if (input.isBlank()) return
        pendingInput = input
        text = TextFieldValue("")
        ui.update { it.copy(visible = false, listening = false, awaitingVoice = false, phase = Phase.Edit) }
        job?.cancel()
        job = app.scope.launch {
            val steps = runCatching { Commands.plan(app, input) }.getOrElse { listOf(Step.Info("Something went wrong. Try again?")) }
            val saves = steps.filterIsInstance<Step.Save>()
            if (saves.isEmpty()) {
                // Nothing to save: show the answer (or "didn't understand") in the bar.
                text = TextFieldValue(input)
                ui.update { BarUi(visible = true, phase = Phase.Answer, steps = steps, openCount = it.openCount + 1) }
                return@launch
            }
            val kind = if (saves.size > 1) "${saves.size} items" else saves.first().kind
            val title = saves.joinToString(" · ") { it.title }
            toast.value = ToastUi(saving = true, heading = "Saving ${kind.lowercase()}…", body = title)
            delay(SAVE_DELAY_MS)
            val messages = saves.map { s ->
                runCatching { s.commit() }.getOrElse { e -> Log.w("save failed", e); "Couldn't save \"${s.title}\"" }
            } + steps.filterIsInstance<Step.Info>().map { it.text }
            toast.value = ToastUi(saving = false, heading = "$kind added", body = messages.joinToString(" · "))
            delay(CONFIRM_MS)
            toast.value = null
        }
    }

    companion object {
        const val SAVE_DELAY_MS = 2_000L
        const val CONFIRM_MS = 2_200L
    }
}

@Composable
fun QuickBarWindow(bar: Bar, ui: BarUi, holdLabel: String, theme: BuddyTheme) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val state = rememberWindowState(
        position = WindowPosition(((screen.width - 600) / 2).dp, (screen.height / 5).dp),
        width = 600.dp, height = 80.dp,
    )
    Window(
        onCloseRequest = bar::hide,
        visible = ui.visible,
        state = state,
        title = "Buddy",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = true,
        icon = AppIcons.window,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { bar.escape(); true } else false
        },
    ) {
        LaunchedEffect(ui.openCount, ui.visible) {
            if (ui.visible) Win.bringToFront(window)
        }
        LaunchedEffect(window) {
            window.addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent?) {
                    val s = bar.ui.value
                    // Clicking away closes it, except while you're talking or a save is counting down.
                    if (!s.listening && !s.awaitingVoice && s.phase != Phase.Saving && s.phase != Phase.Thinking) bar.hide()
                }
            })
        }
        val density = LocalDensity.current.density
        Box(
            Modifier.fillMaxWidth().wrapContentHeight().onSizeChanged { size ->
                window.size = Dimension(window.width, (size.height / density).toInt() + 2)
            },
        ) {
            BarCard(bar, ui, holdLabel, theme)
        }
    }
}

@Composable
private fun BarCard(bar: Bar, ui: BarUi, holdLabel: String, c: BuddyTheme) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(ui.openCount, ui.phase) {
        if (ui.phase == Phase.Edit || ui.phase == Phase.Answer) {
            delay(60)
            runCatching { focus.requestFocus() }
            bar.text = bar.text.copy(selection = TextRange(bar.text.text.length))
        }
    }
    // Wispr's text lands a moment after the key is released; then you check it and press Enter.
    LaunchedEffect(ui.awaitingVoice, bar.text.text) {
        if (!ui.awaitingVoice) return@LaunchedEffect
        delay(if (bar.text.text.isBlank()) 10_000 else 900)
        bar.ui.update { it.copy(awaitingVoice = false) }
        runCatching { focus.requestFocus() }
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface)
            .border(1.dp, if (ui.listening) c.accent else c.line, shape),
    ) {
        when (ui.phase) {
            Phase.Edit, Phase.Thinking -> EditRow(bar, ui, holdLabel, c, focus)
            Phase.Saving, Phase.Saved -> {}
            Phase.Answer -> {
                EditRow(bar, ui, holdLabel, c, focus)
                AnswerBlock(ui, c)
            }
        }
    }
}

@Composable
private fun EditRow(bar: Bar, ui: BarUi, holdLabel: String, c: BuddyTheme, focus: FocusRequester) {
    val text = bar.text
    Row(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                ui.listening -> Waveform(c)
                ui.phase == Phase.Thinking -> CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                else -> Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (text.text.isEmpty()) {
                Text(
                    when {
                        ui.listening -> "Listening… let go when you're done"
                        ui.awaitingVoice -> "Writing it down…"
                        else -> "Add a reminder, a task or a note, or ask something"
                    },
                    color = c.dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { bar.text = it },
                singleLine = true,
                readOnly = ui.phase == Phase.Thinking,
                textStyle = TextStyle(color = c.text, fontSize = 16.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.Enter || e.key == Key.NumPadEnter -> { bar.submit(); true }
                        e.key == Key.Escape -> { bar.escape(); true }
                        else -> false
                    }
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            ui.phase == Phase.Thinking -> Text("Working it out…", color = c.dim, fontSize = 12.sp)
            ui.listening -> Text("Esc to cancel", color = c.dim, fontSize = 12.sp)
            text.text.isEmpty() -> KeyHint("Hold $holdLabel", "to talk", c)
        }
        if (ui.phase == Phase.Edit && !ui.listening) {
            Spacer(Modifier.width(10.dp))
            PillButton("Cancel", c) { bar.hide() }
        }
    }
    // Quick local preview of what Enter will save (Buddy's AI may refine it).
    val preview = remember(text.text) { text.text.takeIf { it.isNotBlank() }?.let { Commands.timesFromText(it) } }
    if (text.text.isNotBlank() && !ui.listening && ui.phase == Phase.Edit) {
        Row(
            Modifier.fillMaxWidth().background(c.surfaceAlt).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (preview != null) {
                val isReminder = preview.remindAt != null
                Chip(if (isReminder) "Reminder" else "Task", if (isReminder) Icons.Rounded.NotificationsNone else Icons.Rounded.TaskAlt, c)
                Text(
                    preview.title, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                (preview.remindAt ?: preview.dueAt)?.let {
                    Text(
                        Commands.whenText(it, withTime = preview.remindAt != null || !preview.dueAllDay).replaceFirstChar { ch -> ch.uppercase() },
                        color = c.dim, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            KeyHint("Enter", "save", c)
            KeyHint("Esc", "cancel", c)
        }
    }
}

@Composable
private fun AnswerBlock(ui: BarUi, c: BuddyTheme) {
    Column(
        Modifier.fillMaxWidth().background(c.surfaceAlt).heightIn(max = 340.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ui.steps.forEach { s ->
            when (s) {
                is Step.Answer -> Text(s.text, color = c.text, fontSize = 14.sp, lineHeight = 21.sp)
                is Step.Info -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = c.dim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.text, color = c.dim, fontSize = 13.sp)
                }
                is Step.Save -> {}
            }
        }
        Text("Esc to close", color = c.faint, fontSize = 11.sp)
    }
}

@Composable
private fun Waveform(c: BuddyTheme) {
    val t = rememberInfiniteTransition()
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    Canvas(Modifier.size(22.dp)) {
        val bars = 5
        val gap = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val v = 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((phase * 2 * Math.PI + i * 0.9).toFloat()))
            val h = size.height * v
            val x = i * gap * 2 + gap / 2
            drawLine(c.accent, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = gap, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun Chip(label: String, icon: ImageVector?, c: BuddyTheme) {
    Row(
        Modifier.background(c.accentSoft, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = c.accentText, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(label, color = c.accentText, fontSize = 12.sp)
    }
}

@Composable
fun KeyHint(key: String, action: String, c: BuddyTheme) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key, color = c.dim, fontSize = 11.sp,
            modifier = Modifier.border(1.dp, c.line, RoundedCornerShape(5.dp)).background(c.surface, RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(action, color = c.dim, fontSize = 11.sp)
    }
}

/** Soft pill button; [primary] fills it with the accent. */
@Composable
fun PillButton(label: String, c: BuddyTheme, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) c.onAccent else c.text,
        fontSize = 13.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) c.accent else c.chip)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun ToastWindow(bar: Bar, c: BuddyTheme) {
    val t by bar.toast.collectAsState()
    val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    Window(
        onCloseRequest = {},
        visible = t != null,
        state = rememberWindowState(width = 360.dp, height = 80.dp),
        title = "Buddy",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = false,
        icon = AppIcons.window,
    ) {
        val density = LocalDensity.current.density
        Box(
            Modifier.wrapContentSize().onSizeChanged { s ->
                // Bottom centre, clear of the reminder cards (bottom right).
                val w = (s.width / density).toInt() + 2
                val h = (s.height / density).toInt() + 2
                window.setBounds(area.x + (area.width - w) / 2, area.y + area.height - h - 24, w, h)
            },
        ) {
            t?.let { ToastCard(bar, it, c) }
        }
    }
}

@Composable
private fun ToastCard(bar: Bar, t: ToastUi, c: BuddyTheme) {
    val shape = RoundedCornerShape(16.dp)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(t.saving) {
        if (t.saving) progress.animateTo(1f, tween(Bar.SAVE_DELAY_MS.toInt(), easing = LinearEasing))
        else progress.snapTo(1f)
    }
    Column(Modifier.width(340.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.saving) {
                Canvas(Modifier.size(22.dp)) {
                    val stroke = 3.dp.toPx()
                    drawArc(c.divider, 0f, 360f, false, style = Stroke(stroke))
                    drawArc(c.accent, -90f, 360f * progress.value, false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            } else {
                Box(Modifier.size(22.dp).background(c.okSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = c.ok, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.heading, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(t.body, color = c.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (t.saving) {
                Spacer(Modifier.width(10.dp))
                PillButton("Cancel", c, onClick = bar::cancel)
            }
        }
        if (t.saving) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.divider)) {
                Box(Modifier.fillMaxWidth(progress.value).fillMaxHeight().background(c.accent))
            }
        }
    }
}
