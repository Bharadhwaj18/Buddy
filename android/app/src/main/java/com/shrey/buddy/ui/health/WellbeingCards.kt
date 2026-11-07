package com.shrey.buddy.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.health.Nudges
import com.shrey.buddy.data.health.sourceName
import com.shrey.buddy.data.items.SleepNight
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.tasks.TimePickDialog
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val SleepDeep = Color(0xFF5B5FE0)
private val SleepLight = Color(0xFF8E9BFF)
private val SleepRem = Color(0xFF30D5C8)
private val SleepAwake = Color(0xFFFF8A65)

fun hm(minutes: Int) = "${minutes / 60}h ${minutes % 60}m"

@Composable
fun SleepCard(hvm: HealthViewModel) {
    val nights by hvm.sleep.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val last = nights.lastOrNull { it.date != null && !it.date!!.isBefore(today.minusDays(1)) }
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val byDay = nights.associateBy { it.date }
    BuddyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, null, tint = SleepLight)
            Text("Sleep", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
            last?.let { Text(hm(it.data.minutes), style = MaterialTheme.typography.titleLarge.numeric()) }
        }
        if (last == null) {
            EmptyNote("Allow Usage access (Screen time) and Buddy estimates sleep from when your phone is idle — your watch will add sleep stages.", Modifier.padding(top = 8.dp))
            return@BuddyCard
        }
        val zone = ZoneId.systemDefault()
        val s = last.data
        Text(
            "${formatTime(s.startMs)} → ${formatTime(s.endMs)} · " + if (s.source == "phone") "estimated from your phone" else sourceName(s.source),
            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
        )
        if (s.deepMin + s.lightMin + s.remMin > 0) {
            StageBar(s, Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(SleepDeep, "Deep ${hm(s.deepMin)}"); Dot(SleepLight, "Light ${hm(s.lightMin)}"); Dot(SleepRem, "REM ${hm(s.remMin)}")
            }
        }
        // 7 nights.
        val max = (week.maxOfOrNull { byDay[it]?.data?.minutes ?: 0 } ?: 0).coerceAtLeast(480)
        Canvas(Modifier.fillMaxWidth().height(60.dp).padding(top = 12.dp)) {
            val gap = 10.dp.toPx(); val w = (size.width - gap * 6) / 7
            val target = size.height - size.height * 480 / max
            drawLine(BuddyColors.Lime.copy(alpha = 0.4f), Offset(0f, target), Offset(size.width, target), 1.5f)
            week.forEachIndexed { i, d ->
                val m = byDay[d]?.data?.minutes ?: 0
                val h = (size.height * m / max).coerceAtLeast(3.dp.toPx())
                drawRoundRect(if (m >= 420) SleepLight else SleepLight.copy(alpha = 0.45f), Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            week.forEach { d ->
                Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        val avg = week.mapNotNull { byDay[it]?.data?.minutes }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        avg?.let {
            Text("7-night average ${hm(it)} · aim for 7–9 h", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun StageBar(s: SleepNight, modifier: Modifier) {
    val total = (s.deepMin + s.lightMin + s.remMin + s.awakeMin).coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        listOf(s.deepMin to SleepDeep, s.lightMin to SleepLight, s.remMin to SleepRem, s.awakeMin to SleepAwake).forEach { (m, c) ->
            if (m > 0) Box(Modifier.weight(m.toFloat() / total).height(12.dp).background(c))
        }
    }
}

@Composable
private fun Dot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary)
    }
}

@Composable
fun HeartCard(hvm: HealthViewModel) {
    val days by hvm.heart.collectAsStateWithLifecycle()
    if (days.isEmpty()) return
    val today = LocalDate.now()
    val latest = days.last()
    val resting = days.takeLast(30).mapNotNull { d -> d.data.restingBpm?.let { (d.date ?: today) to it.toDouble() } }
    BuddyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, null, tint = BuddyColors.Red)
            Text("Heart", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
            latest.data.restingBpm?.let { Text("$it bpm resting", style = MaterialTheme.typography.titleSmall.numeric()) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            listOfNotNull(
                latest.data.minBpm?.let { "Lowest" to "$it" },
                latest.data.avgBpm?.let { "Average" to "$it" },
                latest.data.maxBpm?.let { "Highest" to "$it" },
                latest.data.spo2Pct?.let { "SpO₂" to "%.0f%%".format(it) },
                latest.data.hrvMs?.let { "HRV" to "%.0f ms".format(it) },
            ).forEach { (l, v) ->
                Column(Modifier.weight(1f)) {
                    Text(v, style = MaterialTheme.typography.titleSmall.numeric())
                    Text(l, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }
            }
        }
        if (resting.size >= 3) {
            Text("Resting heart rate, last 30 days", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 12.dp))
            LineChart(resting.map { it.first.toEpochDay().toDouble() to it.second }, invert = false, color = BuddyColors.Red, modifier = Modifier.padding(top = 6.dp))
            Text("A falling resting heart rate usually means fitness is improving.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
        }
    }
}

@Composable
fun WaterCard(hvm: HealthViewModel) {
    val context = LocalContext.current
    val water by hvm.water.collectAsStateWithLifecycle()
    val kg by hvm.latestKg.collectAsStateWithLifecycle()
    val goalVersion by Nudges.goalVersion.collectAsStateWithLifecycle()
    var nudges by remember { mutableStateOf(Nudges.waterOn(context)) }
    val goal = remember(kg, goalVersion) { Nudges.waterGoalMl(context, kg) }
    val had = water[LocalDate.now()] ?: 0
    val glasses = goal / 250
    BuddyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WaterDrop, null, tint = BuddyColors.Tasks)
            Text("Water", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Text("%.2f / %.1f L".format(had / 1000.0, goal / 1000.0), style = MaterialTheme.typography.titleSmall.numeric())
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(glasses) { i ->
                Box(
                    Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp))
                        .background(if ((i + 1) * 250 <= had) BuddyColors.Tasks else BuddyColors.SurfaceHigh),
                )
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { hvm.addWater(250) }) { Text("+ Glass") }
            OutlinedButton(onClick = { hvm.addWater(500) }) { Text("+ Bottle") }
            if (had > 0) TextButton(onClick = { hvm.addWater(-250) }) { Text("Undo", color = BuddyColors.TextTertiary) }
            Box(Modifier.weight(1f))
            Text("Nudges", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
            Switch(
                checked = nudges,
                onCheckedChange = { nudges = it; Nudges.setWater(context, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
            )
        }
        Text(
            if (kg != null) "Goal ≈ 35 ml per kg of body weight." else "Goal 2.5 L — log your weight for a personal goal.",
            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
        )
    }
}

@Composable
fun MedicinesCard(hvm: HealthViewModel) {
    val meds by hvm.medicines.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    // Today's doses: open ones due today or earlier, plus ones ticked today.
    val todays = meds.filter { t ->
        val due = t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val doneToday = t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == today
        (!t.isDone && due != null && !due.isAfter(today)) || (t.isDone && doneToday)
    }.sortedBy { it.dueAt }
    val upcoming = meds.filter { !it.isDone && it.repeat != null }.distinctBy { it.title }
    BuddyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Medication, null, tint = BuddyColors.Voice)
            Text("Medicines & supplements", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
            IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add medicine", tint = BuddyColors.Voice) }
        }
        if (upcoming.isEmpty() && todays.isEmpty()) {
            EmptyNote("Add a medicine or supplement — Buddy calls you at dose time, every day.", Modifier.padding(top = 4.dp))
        }
        todays.forEach { t -> DoseRow(t, onTake = { hvm.takeDose(t) }, onStop = { hvm.stopMedicine(t) }) }
        upcoming.filter { u -> todays.none { it.id == u.id } }.forEach { t -> DoseRow(t, onTake = null, onStop = { hvm.stopMedicine(t) }) }
    }
    if (adding) MedicineSheet(onDismiss = { adding = false }) { name, dose, times -> hvm.addMedicine(name, dose, times); adding = false }
}

@Composable
private fun DoseRow(t: TaskEntity, onTake: (() -> Unit)?, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onTake != null) {
            IconButton(onClick = onTake) {
                Icon(if (t.isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = "Taken", tint = if (t.isDone) BuddyColors.Lime else BuddyColors.TextTertiary)
            }
        } else Box(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title.removePrefix(PILL).trim(), style = MaterialTheme.typography.bodyMedium, color = if (t.isDone) BuddyColors.TextTertiary else BuddyColors.TextPrimary)
            t.dueAt?.let { Text((if (onTake == null) "next " else "") + formatTime(it), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary) }
        }
        IconButton(onClick = onStop) { Icon(Icons.Rounded.Close, contentDescription = "Stop this medicine", tint = BuddyColors.TextTertiary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicineSheet(onDismiss: () -> Unit, onSave: (String, String, List<LocalTime>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var times by remember { mutableStateOf(listOf(LocalTime.of(9, 0))) }
    var picking by remember { mutableStateOf<Int?>(null) }
    FormSheet(title = "Add medicine", onDismiss = onDismiss, onSave = { onSave(name, dose, times) }, saveEnabled = name.isNotBlank() && times.isNotEmpty(), accent = BuddyColors.Voice) {
        Field(name, { name = it }, "Name (e.g. Vitamin D3)", accent = BuddyColors.Voice)
        Field(dose, { dose = it }, "Dose (optional, e.g. 1 tablet after food)", Modifier.padding(top = 10.dp), accent = BuddyColors.Voice)
        Text("Times each day", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            times.forEachIndexed { i, t ->
                FilterChip(
                    selected = true,
                    onClick = { picking = i },
                    label = { Text(formatTime(LocalDate.now().atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())) },
                    trailingIcon = if (times.size > 1) { { Icon(Icons.Rounded.Close, contentDescription = "Remove time", modifier = Modifier.size(16.dp).clickable { times = times.filterIndexed { j, _ -> j != i } }) } } else null,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Voice.copy(alpha = 0.25f)),
                )
            }
            FilterChip(selected = false, onClick = { times = times + LocalTime.of(21, 0); picking = times.size - 1 }, label = { Text("+ time") })
        }
        Text("Buddy rings like a call at each time; tick it off and tomorrow's is set.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 10.dp))
    }
    picking?.let { i ->
        TimePickDialog(times.getOrElse(i) { LocalTime.of(9, 0) }, onDismiss = { picking = null }) { t ->
            times = times.toMutableList().also { if (i < it.size) it[i] = t else it += t }
            picking = null
        }
    }
}

/** Shows which watch / scale is feeding data, and how to connect one. */
@Composable
fun DevicesCard(hvm: HealthViewModel, needsPermission: Boolean, onConnect: () -> Unit) {
    val sources by hvm.sources.collectAsStateWithLifecycle()
    val syncing by hvm.syncing.collectAsStateWithLifecycle()
    BuddyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = BuddyColors.Lime)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Watch & devices", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        sources.isNotEmpty() -> "Connected: " + sources.map(::sourceName).distinct().joinToString(", ")
                        needsPermission -> "Not connected yet"
                        else -> "Ready — no watch data yet"
                    },
                    style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                )
            }
            IconButton(onClick = { hvm.syncWatch(30) }, enabled = !syncing) { Icon(Icons.Rounded.Sync, contentDescription = "Sync now", tint = BuddyColors.TextSecondary) }
        }
        if (needsPermission) {
            OutlinedButton(onClick = onConnect, modifier = Modifier.padding(top = 8.dp)) { Text("Allow Health Connect data") }
        }
        if (sources.isEmpty()) {
            Text(
                "When your Galaxy Fit 3 arrives: pair it in Galaxy Wearable → in Samsung Health turn on Settings › Health Connect → allow Buddy here. Sleep, heart rate, SpO₂, workouts and steps then flow in automatically. Any other watch that syncs to Health Connect works the same way.",
                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---- Compact tiles on the Health tab (like the Life grid on the Buddy tab) ----

@Composable
fun WellbeingTiles(hvm: HealthViewModel, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val nights by hvm.sleep.collectAsStateWithLifecycle()
    val heart by hvm.heart.collectAsStateWithLifecycle()
    val water by hvm.water.collectAsStateWithLifecycle()
    val meds by hvm.medicines.collectAsStateWithLifecycle()
    val kg by hvm.latestKg.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val lastNight = nights.lastOrNull { it.date != null && !it.date!!.isBefore(today.minusDays(1)) }?.data
    val rhr = heart.lastOrNull()?.data?.restingBpm
    val had = water[today] ?: 0
    val goal = Nudges.waterGoalMl(context, kg)
    val dosesToday = meds.filter { t -> t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.let { !it.isAfter(today) } == true || t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == today }
    val taken = dosesToday.count { it.isDone }
    val tiles = listOf(
        Tile("sleep", Icons.Rounded.Bedtime, SleepLight, "Sleep", lastNight?.let { "${hm(it.minutes)} last night" } ?: "No data yet"),
        Tile("heart", Icons.Rounded.Favorite, BuddyColors.Red, "Heart", rhr?.let { "$it bpm resting" } ?: "Needs a watch"),
        Tile("water", Icons.Rounded.WaterDrop, BuddyColors.Tasks, "Water", "%.1f / %.1f L".format(had / 1000.0, goal / 1000.0)),
        Tile("medicines", Icons.Rounded.Medication, BuddyColors.Voice, "Medicines", if (dosesToday.isEmpty()) "None today" else "$taken of ${dosesToday.size} taken"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    BuddyCard(Modifier.weight(1f), onClick = { onOpen(t.key) }, contentPadding = 16.dp) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(t.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(t.icon, null, tint = t.color, modifier = Modifier.size(20.dp))
                        }
                        Text(t.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                        Text(t.summary, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, maxLines = 1)
                    }
                }
            }
        }
    }
}

private data class Tile(val key: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color, val label: String, val summary: String)

/** The full page for one tile: the detailed card plus more history. */
@Composable
fun WellbeingScreen(hvm: HealthViewModel, kind: String, onBack: () -> Unit) {
    val title = when (kind) { "sleep" -> "Sleep"; "heart" -> "Heart"; "water" -> "Water"; else -> "Medicines" }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
        }
        when (kind) {
            "sleep" -> {
                item { SleepCard(hvm) }
                item { SleepHistory(hvm) }
            }
            "heart" -> {
                item {
                    val days by hvm.heart.collectAsStateWithLifecycle()
                    if (days.isEmpty()) BuddyCard(Modifier.fillMaxWidth()) {
                        EmptyNote("Heart rate, resting heart rate, SpO₂ and HRV appear here once a watch syncs to Health Connect (Health → Watch & devices).")
                    } else HeartCard(hvm)
                }
                item { HeartHistory(hvm) }
            }
            "water" -> {
                item { WaterCard(hvm) }
                item { WaterHistory(hvm) }
            }
            else -> item { MedicinesCard(hvm) }
        }
    }
}

@Composable
private fun SleepHistory(hvm: HealthViewModel) {
    val nights by hvm.sleep.collectAsStateWithLifecycle()
    if (nights.isEmpty()) return
    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Last 14 nights", style = MaterialTheme.typography.titleMedium)
        nights.takeLast(14).reversed().forEach { n ->
            val s = n.data
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(n.date?.let { com.shrey.buddy.ui.friendlyDay(it) }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    Text("${formatTime(s.startMs)} → ${formatTime(s.endMs)}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }
                Text(hm(s.minutes), style = MaterialTheme.typography.titleSmall.numeric(), color = if (s.minutes >= 420) SleepLight else BuddyColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun HeartHistory(hvm: HealthViewModel) {
    val days by hvm.heart.collectAsStateWithLifecycle()
    if (days.isEmpty()) return
    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Last 14 days", style = MaterialTheme.typography.titleMedium)
        days.takeLast(14).reversed().forEach { d ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(d.date?.let { com.shrey.buddy.ui.friendlyDay(it) }.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(d.data.restingBpm?.let { "rest $it" }, d.data.avgBpm?.let { "avg $it" }, d.data.maxBpm?.let { "max $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.numeric(), color = BuddyColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun WaterHistory(hvm: HealthViewModel) {
    val context = LocalContext.current
    val water by hvm.water.collectAsStateWithLifecycle()
    val kg by hvm.latestKg.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf(Nudges.waterGoalMl(context, kg)) }
    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
        val max = (week.maxOfOrNull { water[it] ?: 0 } ?: 0).coerceAtLeast(goal)
        Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 12.dp)) {
            val gap = 10.dp.toPx(); val w = (size.width - gap * 6) / 7
            val goalY = size.height - size.height * goal / max
            drawLine(BuddyColors.Lime.copy(alpha = 0.45f), Offset(0f, goalY), Offset(size.width, goalY), 1.5f)
            week.forEachIndexed { i, d ->
                val ml = water[d] ?: 0
                val h = (size.height * ml / max).coerceAtLeast(3.dp.toPx())
                drawRoundRect(if (ml >= goal) BuddyColors.Tasks else BuddyColors.Tasks.copy(alpha = 0.45f), Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            week.forEach { d ->
                Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Text("Daily goal", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 14.dp))
        FlowRowGoals(goal) { goal = it; Nudges.setWaterGoal(context, it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowGoals(goal: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        listOf(2000, 2500, 3000, 3500, 4000).forEach { ml ->
            FilterChip(
                selected = goal == ml, onClick = { onPick(ml) }, label = { Text("%.1f L".format(ml / 1000.0)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Tasks.copy(alpha = 0.25f)),
            )
        }
    }
}

/** Toggle for the "time to move" nudge. */
@Composable
fun MoveNudgeRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Nudges.moveOn(context)) }
    Surface(shape = RoundedCornerShape(16.dp), color = BuddyColors.Surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Move nudges", style = MaterialTheme.typography.bodyMedium)
                Text("A reminder after an hour without steps (9 am – 9 pm)", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
            }
            Switch(on, { on = it; Nudges.setMove(context, it) }, colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime))
        }
    }
}
