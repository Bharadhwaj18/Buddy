package com.shrey.buddy.desktop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.GraphicsEnvironment

/** How long the phone waits for an answer here before it rings (LaptopFirst on the phone). */
private const val PHONE_HEAD_START_MS = 30_000L

/**
 * Due reminders: cards in the bottom-right corner, above everything, that don't take the keyboard
 * from what you're typing. They stay until you answer (here or on the phone).
 */
@Composable
fun ReminderPopups(app: BuddyState, rings: List<Ring>, theme: BuddyTheme) {
    val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val width = 340
    val state = rememberWindowState(
        position = WindowPosition((area.x + area.width - width - 16).dp, (area.y + area.height - 200).dp),
        width = width.dp, height = 180.dp,
    )
    Window(
        onCloseRequest = {},
        visible = rings.isNotEmpty(),
        state = state,
        title = "Buddy reminder",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = false,
        icon = AppIcons.window,
    ) {
        val density = LocalDensity.current.density
        Box(
            Modifier.fillMaxWidth().wrapContentHeight().onSizeChanged { s ->
                // Grow upwards from the bottom-right corner as reminders stack (pixels → screen units).
                val h = (s.height / density).toInt() + 2
                window.size = Dimension(window.width, h)
                window.setLocation(area.x + area.width - window.width - 16, area.y + area.height - h - 16)
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rings.size > 3) {
                    Text("+${rings.size - 3} more", color = theme.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
                }
                rings.takeLast(3).forEach { r -> RingCard(app, r, theme) }
            }
        }
    }
}

@Composable
private fun RingCard(app: BuddyState, r: Ring, c: BuddyTheme) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.at) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val phoneIn = (r.at + PHONE_HEAD_START_MS - now).coerceAtLeast(0)
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(if (r.missed) c.lateSoft else c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        if (r.repeats) Icons.Rounded.Repeat else Icons.Rounded.NotificationsNone, null,
                        tint = if (r.missed) c.late else c.accentText, modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (r.missed) "Missed · " else "Reminder · ") + Commands.whenText(r.at).replaceFirstChar { it.uppercase() },
                    color = if (r.missed) c.late else c.accentText, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.Close, "Dismiss", tint = c.faint,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { app.dismiss(r) }.padding(6.dp),
                )
            }
            Text(
                r.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            )
            if (!r.missed) {
                Text(
                    if (phoneIn > 0) "Phone rings in ${(phoneIn + 999) / 1000} s if you don't answer here" else "Ringing on your phone too",
                    color = c.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(Modifier.padding(top = 10.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Done", c, primary = true) { app.done(r) }
                PillButton("10 min", c) { app.snooze(r, 10) }
                PillButton("1 hr", c) { app.snooze(r, 60) }
                PillButton("Tomorrow", c) { app.tomorrow(r) }
            }
        }
        if (!r.missed && phoneIn > 0) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.divider)) {
                Box(Modifier.fillMaxWidth(phoneIn.toFloat() / PHONE_HEAD_START_MS).fillMaxHeight().background(c.accent))
            }
        }
    }
}
