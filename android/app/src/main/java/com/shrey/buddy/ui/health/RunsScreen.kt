package com.shrey.buddy.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.Polyline
import com.shrey.buddy.health.RunRecord
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.health.formatPace
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.screens.workoutIcon
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val dFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Runs, walks and rides with a GPS route. */
fun gpsActivities(workouts: List<Item<Workout>>, type: String?) =
    workouts.filter { it.data.run != null && (type == null || it.data.type == type) }
        .sortedByDescending { it.data.run?.startedAt?.takeIf { s -> s > 0 } ?: it.date?.toEpochDay()?.times(86_400_000) ?: 0 }

@Composable
fun RunsScreen(hvm: HealthViewModel, onBack: () -> Unit, onStart: () -> Unit, onOpen: (String) -> Unit) {
    val workouts by hvm.workouts.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf<String?>("run") }
    val list = gpsActivities(workouts, type)
    val today = LocalDate.now()
    val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Activities", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null)
                    Text("Start", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("run" to "Runs", "walk" to "Walks", "cycle" to "Rides", null to "All").forEach { (k, label) ->
                    FilterChip(
                        selected = type == k,
                        onClick = { type = k },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Fitness.copy(alpha = 0.25f), selectedLabelColor = BuddyColors.TextPrimary),
                    )
                }
            }
        }

        if (list.isEmpty()) {
            item { BuddyCard(Modifier.fillMaxWidth()) { EmptyNote("Nothing here yet — press Start and Buddy maps it.") } }
            return@LazyColumn
        }

        // Totals.
        item {
            fun sum(from: LocalDate) = list.filter { (it.date ?: LocalDate.MIN) >= from }
            val week = sum(thisWeek)
            val month = sum(today.withDayOfMonth(1))
            val year = sum(today.withDayOfYear(1))
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("This week" to week, "This month" to month, "This year" to year).forEach { (label, l) ->
                        Column(Modifier.weight(1f)) {
                            Text("%.1f km".format(l.sumOf { it.data.run!!.distanceM } / 1000), style = MaterialTheme.typography.titleLarge.numeric())
                            Text("$label · ${l.size}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        }
                    }
                }
                // 12 weeks of distance.
                val weeks = (11 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
                val km = weeks.map { w -> list.filter { val d = it.date; d != null && !d.isBefore(w) && d.isBefore(w.plusWeeks(1)) }.sumOf { it.data.run!!.distanceM } / 1000 }
                val max = (km.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
                Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 16.dp)) {
                    val gap = 6.dp.toPx()
                    val w = (size.width - gap * 11) / 12
                    km.forEachIndexed { i, k ->
                        val h = (size.height * k / max).toFloat().coerceAtLeast(3.dp.toPx())
                        drawRoundRect(
                            if (i == 11) BuddyColors.Fitness else BuddyColors.Fitness.copy(alpha = 0.45f),
                            Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(4.dp.toPx()),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(weeks.first().format(dFmt), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    Text("km per week", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    Text("This week", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }
            }
        }

        // Personal bests.
        item {
            fun best(sel: (com.shrey.buddy.data.items.RunSummary) -> Int?) =
                list.mapNotNull { w -> sel(w.data.run!!)?.let { it to w } }.minByOrNull { it.first }
            val pbs = listOfNotNull(
                best { it.best1kS }?.let { (s, w) -> Triple("Fastest 1 km", formatDuration(s * 1000L), w) },
                best { it.best5kS }?.let { (s, w) -> Triple("Fastest 5 km", formatDuration(s * 1000L), w) },
                best { it.best10kS }?.let { (s, w) -> Triple("Fastest 10 km", formatDuration(s * 1000L), w) },
                list.maxByOrNull { it.data.run!!.distanceM }?.let { Triple("Longest", "%.2f km".format(it.data.run!!.distanceM / 1000), it) },
                list.maxByOrNull { it.data.run!!.elevationGainM }?.takeIf { it.data.run!!.elevationGainM >= 10 }
                    ?.let { Triple("Most climbing", "%.0f m".format(it.data.run!!.elevationGainM), it) },
            )
            if (pbs.isNotEmpty()) {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Personal bests 🏆", style = MaterialTheme.typography.titleMedium)
                    pbs.forEach { (label, value, w) ->
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { onOpen(w.id) }) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(value, style = MaterialTheme.typography.bodyMedium.numeric())
                            Text("  ${w.date?.format(dFmt).orEmpty()}", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                        }
                    }
                }
            }
        }

        // Every route on one map.
        item {
            val routes = list.take(60).map { w ->
                val r = w.data.run!!
                RouteSegment(Polyline.decode(r.polyline.ifEmpty { r.track }), BuddyColors.Fitness)
            }.filter { it.points.size >= 2 }
            if (routes.isNotEmpty()) {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Your routes", style = MaterialTheme.typography.titleMedium)
                    Text("Brighter = more often", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    Box(Modifier.fillMaxWidth().height(260.dp).padding(top = 10.dp).clip(RoundedCornerShape(18.dp))) {
                        RouteMap(routes, Modifier.fillMaxSize(), showEnds = false, faint = true)
                    }
                }
            }
        }

        items(list, key = { it.id }) { w ->
            val r = w.data.run!!
            BuddyCard(Modifier.fillMaxWidth().clickable { onOpen(w.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(workoutIcon(w.data.type), null, tint = BuddyColors.Fitness)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(w.data.title.ifBlank { "Activity" }, style = MaterialTheme.typography.titleSmall)
                        Text(w.date?.let { friendlyDay(it, today) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    listOf(
                        "Distance" to "%.2f km".format(r.distanceM / 1000),
                        (if (w.data.type == "cycle") "Speed" else "Pace") to (
                            if (w.data.type == "cycle") "%.1f km/h".format(r.distanceM / 1000 / ((if (r.movingS > 0) r.movingS.toLong() else r.durationS) / 3600.0).coerceAtLeast(1e-6))
                            else formatPace(RunRecord.avgPace(r))
                        ),
                        "Time" to formatDuration((if (r.movingS > 0) r.movingS.toLong() else r.durationS) * 1000),
                    ).forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(value, style = MaterialTheme.typography.titleSmall.numeric())
                            Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        }
                    }
                }
            }
        }
    }
}
