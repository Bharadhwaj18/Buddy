package com.shrey.buddy.reminders

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.ui.theme.BuddyColors
import com.shrey.buddy.ui.theme.BuddyTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Full-screen "Buddy is calling" screen for reminders — over the lock screen, turning the screen on. */
class ReminderCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            BuddyTheme {
                val info by ReminderRinging.current.collectAsStateWithLifecycle()
                LaunchedEffect(info) { if (info == null) finish() }
                info?.let { CallScreen(it) { action, minutes -> ReminderActions.handle(this, action, it.taskId, minutes); finish() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReminderRinging.current.value?.let { ReminderNotifications.quiet(this, it) }
    }

    // Back shouldn't silently dismiss a ringing reminder; use the buttons.
    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() = Unit
}

@Composable
private fun CallScreen(info: RingInfo, act: (String, Int) -> Unit) {
    val rings = rememberInfiniteTransition(label = "rings")
    val wave by rings.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave")
    val bob by rings.animateFloat(1f, 1.06f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "bob")
    val now = remember { LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2210), BuddyColors.Background, Color.Black))),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Buddy is calling…", style = MaterialTheme.typography.titleMedium, color = BuddyColors.Lime, modifier = Modifier.padding(top = 32.dp))
            Text(
                if (info.repeating) "Repeating reminder · $now" else "Reminder · $now",
                style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 4.dp),
            )

            Box(Modifier.padding(top = 48.dp).size(220.dp), contentAlignment = Alignment.Center) {
                // Two expanding rings, half a cycle apart.
                listOf(wave, (wave + 0.5f) % 1f).forEach { w ->
                    Box(
                        Modifier.size(220.dp).scale(0.5f + w * 0.5f).alpha(1f - w)
                            .clip(CircleShape).background(BuddyColors.Lime.copy(alpha = 0.25f)),
                    )
                }
                Box(
                    Modifier.size(112.dp).scale(bob).clip(CircleShape).background(BuddyColors.Lime),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.NotificationsActive, null, tint = BuddyColors.OnLime, modifier = Modifier.size(52.dp)) }
            }

            Text(
                info.title,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = BuddyColors.TextPrimary, textAlign = TextAlign.Center, maxLines = 3,
                modifier = Modifier.padding(top = 36.dp),
            )
            Text(info.subtitle, style = MaterialTheme.typography.titleMedium, color = BuddyColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))

            Spacer(Modifier.weight(1f))

            Text("Snooze", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextTertiary)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(5, 10, 30, 60).forEach { m ->
                    SnoozeChip(if (m == 60) "1 hr" else "$m min", Modifier.weight(1f)) { act(ReminderReceiver.ACTION_SNOOZE, m) }
                }
            }
            val named = remember { SmartSnooze.named() }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                named.forEach { c -> SnoozeChip(c.label, Modifier.weight(1f)) { act(ReminderReceiver.ACTION_SNOOZE, c.code) } }
            }

            Row(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallButton(
                    icon = if (info.repeating) Icons.Rounded.SkipNext else Icons.Rounded.Close,
                    label = if (info.repeating) "Skip" else "Dismiss",
                    color = BuddyColors.Red,
                ) { act(ReminderReceiver.ACTION_DISMISS, 0) }
                CallButton(Icons.Rounded.Check, "Done", Color(0xFF2ECC71)) { act(ReminderReceiver.ACTION_DONE, 0) }
            }
        }
    }
}

@Composable
private fun SnoozeChip(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = BuddyColors.SurfaceHigh, modifier = modifier.height(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextPrimary, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = color, modifier = Modifier.size(76.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
    }
}
