package com.shrey.buddy.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shrey.buddy.health.ScreenTime
import com.shrey.buddy.health.ScreenTimeDay
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

fun formatMinutes(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

@Composable
fun ScreenTimeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var offset by rememberSaveable { mutableStateOf(0) }
    val day = today.minusDays(offset.toLong())
    val data by produceState<ScreenTimeDay?>(null, offset) {
        value = null
        value = withContext(Dispatchers.Default) { ScreenTime.forDay(context, day) }
    }
    // Same weekday last week isn't available (Android keeps ~7 days), so compare with the day before.
    val previous by produceState<ScreenTimeDay?>(null, offset) {
        value = withContext(Dispatchers.Default) { ScreenTime.forDay(context, day.minusDays(1)) }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Screen time", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until ScreenTime.DAYS_AVAILABLE).toList()) { i ->
                    FilterChip(
                        selected = offset == i,
                        onClick = { offset = i },
                        label = { Text(friendlyDay(today.minusDays(i.toLong()), today)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Voice.copy(alpha = 0.25f), selectedLabelColor = BuddyColors.TextPrimary),
                    )
                }
            }
        }

        if (!ScreenTime.hasPermission(context)) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Allow “Usage access” for Buddy to see your screen time.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { context.startActivity(ScreenTime.settingsIntent()) }) { Text("Open settings", color = BuddyColors.Voice) }
                }
            }
            return@LazyColumn
        }

        val d = data
        if (d == null) {
            item { Text("Counting…", color = BuddyColors.TextSecondary) }
            return@LazyColumn
        }

        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Text(formatMinutes(d.totalMinutes), style = MaterialTheme.typography.displaySmall.numeric())
                previous?.let { p ->
                    val diff = d.totalMinutes - p.totalMinutes
                    if (p.totalMinutes > 0 && offset != 0) {
                        Text(
                            (if (diff >= 0) "▲ " else "▼ ") + formatMinutes(kotlin.math.abs(diff)) + " vs the day before",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (diff > 0) BuddyColors.Red else BuddyColors.Lime,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Unlocks", "${d.unlocks}")
                    Stat("First pickup", d.firstPickupAt?.let { formatTime(it) } ?: "–")
                    Stat("Apps used", "${d.apps.size}")
                }
                HourlyBars(d.hourly, Modifier.padding(top = 18.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("12a", "6a", "12p", "6p", "12a").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary) }
                }
            }
        }

        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Text("Apps", style = MaterialTheme.typography.titleMedium)
                if (d.apps.isEmpty()) EmptyNote("Nothing used this day.", Modifier.padding(top = 8.dp))
                val top = (d.apps.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)
                d.apps.forEach { a ->
                    Column(Modifier.padding(top = 12.dp)) {
                        Row {
                            Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${formatMinutes(a.minutes)} · ${a.opens} open${if (a.opens == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall.numeric(), color = BuddyColors.TextSecondary,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { a.minutes.toFloat() / top },
                            color = BuddyColors.Voice,
                            trackColor = BuddyColors.SurfaceHigh,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge.numeric())
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

@Composable
fun HourlyBars(hourly: List<Int>, modifier: Modifier = Modifier) {
    val max = (hourly.maxOrNull() ?: 0).coerceAtLeast(10)
    Canvas(modifier.fillMaxWidth().height(80.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 23) / 24
        hourly.forEachIndexed { i, m ->
            val h = (size.height * m / max).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = if (m > 0) BuddyColors.Voice else BuddyColors.SurfaceHigh,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}
