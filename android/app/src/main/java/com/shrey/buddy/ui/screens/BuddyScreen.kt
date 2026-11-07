package com.shrey.buddy.ui.screens

import android.Manifest
import android.os.BatteryManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryStd
import com.shrey.buddy.ui.components.IconBadge
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.db.VoiceEvent
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.components.StatTile
import com.shrey.buddy.ui.components.UpdateCard
import com.shrey.buddy.ui.life.LifeModule
import androidx.compose.material.icons.rounded.AccountBalance
import com.shrey.buddy.ui.rememberPermissionGate
import com.shrey.buddy.ui.theme.BuddyColors
import com.shrey.buddy.voice.BuddyVoiceService
import com.shrey.buddy.voice.VoiceStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private fun VoiceStatus.isOn() = this == VoiceStatus.LOADING || this == VoiceStatus.LISTENING || this == VoiceStatus.HEARING_COMMAND

private fun VoiceStatus.label() = when (this) {
    VoiceStatus.OFF -> "Off"
    VoiceStatus.LOADING -> "Loading speech model…"
    VoiceStatus.LISTENING -> "Listening for “Hey Buddy”"
    VoiceStatus.HEARING_COMMAND -> "Listening to you…"
    VoiceStatus.ERROR -> "Stopped — see the latest event below"
}

@Composable
fun BuddyScreen(
    vm: AppViewModel,
    onOpenChat: () -> Unit,
    onOpenModule: (LifeModule) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    val context = LocalContext.current
    val status by vm.voiceStatus.collectAsStateWithLifecycle()
    val events by vm.voiceEvents.collectAsStateWithLifecycle()
    val wakes by vm.wakesSinceStart.collectAsStateWithLifecycle()
    val lastStart by vm.lastVoiceStart.collectAsStateWithLifecycle()
    val pending by vm.pendingChanges.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()

    val mic = rememberPermissionGate(
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
        required = 1,
        onResult = { granted -> if (granted) BuddyVoiceService.start(context) },
    )

    // Ticks every 30 s so uptime and battery stay fresh while the screen is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val batteryNow = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Buddy", style = MaterialTheme.typography.headlineLarge) }

        item {
            BuddyCard(Modifier.fillMaxWidth(), color = BuddyColors.Voice.copy(alpha = 0.14f), onClick = onOpenChat) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.AutoAwesome, BuddyColors.Voice, 48.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Ask Buddy", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "“How much did I spend on food this month?”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BuddyColors.TextSecondary,
                        )
                    }
                }
            }
        }

        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingMic(status)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text("Hey Buddy", style = MaterialTheme.typography.titleLarge)
                        Text(status.label(), style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextSecondary)
                    }
                    Switch(
                        checked = status.isOn(),
                        onCheckedChange = { on ->
                            when {
                                !on -> BuddyVoiceService.stop(context)
                                mic.granted -> BuddyVoiceService.start(context)
                                else -> mic.request()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BuddyColors.Background,
                            checkedTrackColor = BuddyColors.Voice,
                            uncheckedTrackColor = BuddyColors.SurfaceHigh,
                        ),
                    )
                }
                Text(
                    "Say “Hey Buddy”, wait for the double buzz, then speak: “spent 450 on lunch”, “remind me to pay rent Friday”, " +
                        "“how much did I spend this week?”",
                    style = MaterialTheme.typography.bodySmall,
                    color = BuddyColors.TextTertiary,
                    modifier = Modifier.padding(top = 14.dp),
                )
                var canOverlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
                androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    canOverlay = android.provider.Settings.canDrawOverlays(context)
                }
                if (!canOverlay) {
                    TextButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("Show a glowing screen when listening (allow “Display over other apps”)", color = BuddyColors.Voice) }
                }
            }
        }

        item { UpdateCard(vm, compact = false) }

        item { SectionHeader("Life") }
        LifeModule.entries.filter { it.inLifeGrid }.chunked(2).forEach { row ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { m ->
                        BuddyCard(Modifier.weight(1f), onClick = { onOpenModule(m) }, contentPadding = 16.dp) {
                            IconBadge(m.icon, m.color, 34.dp)
                            Text(m.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                            Text(m.blurb, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, maxLines = 1)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item { SectionHeader("Hey Buddy · spike stats") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val running = lastStart?.takeIf { status.isOn() }?.let { formatDuration(now - it.at) } ?: "—"
                val battery = lastStart?.batteryPct?.let { start -> "$start→${batteryNow ?: "?"}%" } ?: "—"
                StatTile("Wakes", wakes.toString(), Icons.Rounded.GraphicEq, BuddyColors.Voice, Modifier.weight(1f))
                StatTile("Running", running, Icons.Rounded.Timer, BuddyColors.Voice, Modifier.weight(1f))
                StatTile("Battery", battery, Icons.Rounded.BatteryStd, BuddyColors.Voice, Modifier.weight(1f))
            }
        }

        item { SectionHeader("Voice log") }
        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                if (events.isEmpty()) EmptyNote("Nothing yet. Turn on Hey Buddy and try it.", Modifier.padding(horizontal = 8.dp))
                events.forEach { VoiceEventRow(it, Modifier.padding(horizontal = 8.dp)) }
            }
        }

        item { SectionHeader("Sync") }
        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                val last = syncState?.lastSyncedAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: "never"
                ListRow(
                    icon = if (pending == 0) Icons.Rounded.CloudDone else Icons.Rounded.CloudSync,
                    tint = BuddyColors.Tasks,
                    title = if (pending == 0) "All changes synced" else "$pending change${if (pending == 1) "" else "s"} waiting",
                    subtitle = syncState?.lastError?.let { "Error: $it" } ?: "Last sync $last",
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    TextButton(onClick = { vm.syncNow() }, enabled = !syncing) { Text(if (syncing) "Syncing…" else "Sync now") }
                }
            }
        }

        item { com.shrey.buddy.capture.WidgetGalleryCard() }

        item { SectionHeader("Settings") }
        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp, onClick = onOpenAccounts) {
                ListRow(Icons.Rounded.AccountBalance, BuddyColors.Lime, "Bank accounts", "Rename, primary, totals, your UPI IDs", Modifier.padding(horizontal = 8.dp))
            }
        }
        item {
            OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign out${vm.userEmail?.let { " ($it)" }.orEmpty()}")
            }
        }
    }
}

@Composable
private fun PulsingMic(status: VoiceStatus) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val on = status.isOn()
    Box(
        Modifier
            .size(56.dp)
            .scale(if (status == VoiceStatus.LISTENING) pulse else 1f)
            .clip(CircleShape)
            .background(if (on) BuddyColors.Voice else BuddyColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            contentDescription = null,
            tint = if (on) BuddyColors.Background else BuddyColors.TextSecondary,
        )
    }
}

private val eventTime: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

@Composable
private fun VoiceEventRow(e: VoiceEvent, modifier: Modifier = Modifier) {
    val (icon, tint, title) = when (e.kind) {
        "wake" -> Triple(Icons.Rounded.GraphicEq, BuddyColors.Voice, "Heard “Hey Buddy”")
        "wake_rejected" -> Triple(Icons.Rounded.GraphicEq, BuddyColors.TextTertiary, "Ignored ${e.text.orEmpty()}")
        "command" -> Triple(Icons.Rounded.ChatBubbleOutline, BuddyColors.TextPrimary, "“${e.text.orEmpty()}”")
        "result" -> Triple(Icons.Rounded.AutoAwesome, BuddyColors.Lime, e.text.orEmpty())
        "start" -> Triple(Icons.Rounded.PlayArrow, BuddyColors.TextSecondary, "Started listening")
        "stop" -> Triple(Icons.Rounded.Stop, BuddyColors.TextSecondary, "Stopped listening")
        else -> Triple(Icons.Rounded.ErrorOutline, BuddyColors.Red, e.text ?: "Error")
    }
    val battery = e.batteryPct?.let { " · $it%" }.orEmpty()
    ListRow(icon, tint, title, "${eventTime.format(Date(e.at))}$battery", modifier)
}

/** Small status chip for the Today header. */
@Composable
fun VoiceChip(status: VoiceStatus, onClick: () -> Unit) {
    val on = status.isOn()
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) BuddyColors.Voice.copy(alpha = 0.16f) else BuddyColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (on) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            contentDescription = null,
            tint = if (on) BuddyColors.Voice else BuddyColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (on) "Listening" else "Buddy off",
            style = MaterialTheme.typography.labelMedium,
            color = if (on) BuddyColors.Voice else BuddyColors.TextTertiary,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
