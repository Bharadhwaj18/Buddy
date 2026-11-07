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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.RunSummary
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.RunMath
import com.shrey.buddy.health.RunRecord
import com.shrey.buddy.health.TrackPoint
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.health.formatPace
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** Splits the route into ~200 m pieces coloured by pace vs. the run's average. */
fun paceSegments(track: List<TrackPoint>, hasTimes: Boolean): List<RouteSegment> {
    if (track.size < 2) return emptyList()
    if (!hasTimes) return listOf(RouteSegment(track.map { it.lat to it.lng }))
    val cum = RunMath.cumulative(track)
    val total = cum.last()
    val avg = (track.last().t - track.first().t) / (total / 1000).coerceAtLeast(0.01)
    val out = mutableListOf<RouteSegment>()
    var start = 0
    for (i in 1 until track.size) {
        if (cum[i] - cum[start] >= 200 || i == track.lastIndex) {
            val pace = (track[i].t - track[start].t) / ((cum[i] - cum[start]) / 1000).coerceAtLeast(0.001)
            val color = when {
                pace <= avg * 0.95 -> BuddyColors.Lime
                pace <= avg * 1.07 -> BuddyColors.Fitness
                else -> BuddyColors.Red
            }
            out += RouteSegment(track.subList(start, i + 1).map { it.lat to it.lng }, color)
            start = i
        }
    }
    return out
}

@Composable
fun RunDetailScreen(hvm: HealthViewModel, workoutId: String, onBack: () -> Unit) {
    val workouts by hvm.workouts.collectAsStateWithLifecycle()
    val item = workouts.firstOrNull { it.id == workoutId }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val run = item?.data?.run

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f).clickable { renaming = true }) {
                    Text(item?.data?.title?.ifBlank { null } ?: "Run", style = MaterialTheme.typography.headlineSmall)
                    val whenText = listOfNotNull(
                        item?.date?.let { friendlyDay(it) },
                        run?.startedAt?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFmt).lowercase() },
                    ).joinToString(" · ")
                    Text(whenText, style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary)
                }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = BuddyColors.Red) }
            }
        }
        if (item == null || run == null) {
            item { Text("This activity isn't available.", color = BuddyColors.TextSecondary) }
            return@LazyColumn
        }
        val track = RunRecord.track(run)
        val detail = RunRecord.hasDetail(run)

        item {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(22.dp))) {
                RouteMap(paceSegments(track, detail), Modifier.fillMaxSize())
            }
            if (detail) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(BuddyColors.Lime, "Faster"); Legend(BuddyColors.Fitness, "Average"); Legend(BuddyColors.Red, "Slower")
                }
            }
        }

        item { StatsGrid(item.data, run) }

        if (run.splits.isNotEmpty()) item { SplitsCard(run) }

        if (detail) {
            item {
                val pace = RunMath.paceSeries(track)
                if (pace.size >= 3) {
                    BuddyCard(Modifier.fillMaxWidth()) {
                        Text("Pace", style = MaterialTheme.typography.titleMedium)
                        LineChart(pace.map { it.first to it.second.toDouble() }, invert = true, color = BuddyColors.Fitness, modifier = Modifier.padding(top = 10.dp))
                        AxisLabels("0 km", "%.1f km".format(run.distanceM / 1000))
                    }
                }
            }
            item {
                val elev = RunMath.elevationSeries(track)
                if (elev.size >= 3 && (elev.maxOf { it.second } - elev.minOf { it.second }) > 2) {
                    BuddyCard(Modifier.fillMaxWidth()) {
                        Text("Elevation", style = MaterialTheme.typography.titleMedium)
                        LineChart(elev, invert = false, color = BuddyColors.Tasks, fill = true, modifier = Modifier.padding(top = 10.dp))
                        AxisLabels("%.0f m low".format(elev.minOf { it.second }), "%.0f m high".format(elev.maxOf { it.second }))
                    }
                }
            }
        }
    }

    if (renaming && item != null) {
        var text by remember { mutableStateOf(item.data.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Name this activity") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { hvm.renameWorkout(item, text.trim()); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Delete this activity?") },
            confirmButton = { TextButton(onClick = { hvm.deleteWorkout(item.id); confirmDelete = false; onBack() }) { Text("Delete", color = BuddyColors.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatsGrid(w: Workout, r: RunSummary) {
    val moving = if (r.movingS > 0) r.movingS.toLong() else r.durationS
    val cells = buildList {
        add("Distance" to "%.2f km".format(r.distanceM / 1000))
        if (w.type == "cycle") add("Avg speed" to "%.1f km/h".format(r.distanceM / 1000 / (moving / 3600.0).coerceAtLeast(1e-6)))
        else add("Avg pace" to formatPace(RunRecord.avgPace(r)))
        add("Moving time" to formatDuration(moving * 1000))
        if (moving != r.durationS) add("Elapsed" to formatDuration(r.durationS * 1000))
        add("Elevation" to "%.0f m".format(r.elevationGainM))
        if (r.kcal > 0) add("Calories" to "${r.kcal} kcal")
        r.best1kS?.let { add("Best 1 km" to formatPace(it.toLong()).removeSuffix(" /km")) }
        r.best5kS?.let { add("Best 5 km" to formatDuration(it * 1000L)) }
        r.best10kS?.let { add("Best 10 km" to formatDuration(it * 1000L)) }
    }
    BuddyCard(Modifier.fillMaxWidth()) {
        cells.chunked(3).forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 14.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(value, style = MaterialTheme.typography.titleMedium.numeric())
                        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SplitsCard(r: RunSummary) {
    val paces = r.splits.map { (it.s / (it.m / 1000)) }
    val fastest = paces.minOrNull() ?: 1.0
    val slowest = paces.maxOrNull() ?: 1.0
    BuddyCard(Modifier.fillMaxWidth()) {
        Text("Splits", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Km", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.width(36.dp))
            Text("Pace", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f))
            Text("Climb", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
        }
        r.splits.forEachIndexed { i, s ->
            val pace = paces[i]
            // Longer bar = faster (like Strava).
            val frac = if (slowest == fastest) 1f else (0.35f + 0.65f * ((slowest - pace) / (slowest - fastest)).toFloat())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.m < 990) "%.1f".format(s.km - 1 + s.m / 1000) else "${s.km}", style = MaterialTheme.typography.bodyMedium.numeric(), modifier = Modifier.width(36.dp))
                Text(formatPace(pace.toLong()).removeSuffix(" /km"), style = MaterialTheme.typography.bodyMedium.numeric(), modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(10.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            if (pace == fastest) BuddyColors.Lime else BuddyColors.Fitness,
                            size = Size(size.width * frac, size.height),
                            cornerRadius = CornerRadius(5.dp.toPx()),
                        )
                    }
                }
                Text("  %+.0f m".format(s.climb), style = MaterialTheme.typography.bodySmall.numeric(), color = BuddyColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).height(4.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx())) }
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

@Composable
private fun AxisLabels(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
        Text(right, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

/** Simple line chart over x; [invert] puts lower values on top (pace: faster = higher). */
@Composable
fun LineChart(points: List<Pair<Double, Double>>, invert: Boolean, color: Color, modifier: Modifier = Modifier, fill: Boolean = false) {
    if (points.size < 2) return
    val xs = points.map { it.first }
    val ys = points.map { it.second }
    val x0 = xs.min(); val x1 = xs.max().coerceAtLeast(x0 + 1e-6)
    // Clip outliers (GPS jitter) to the 5th–95th percentile so the chart stays readable.
    val sorted = ys.sorted()
    val lo = sorted[(sorted.size * 0.05).toInt()]
    val hi = sorted[((sorted.size - 1) * 0.95).toInt()].coerceAtLeast(lo + 1e-6)
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        fun x(v: Double) = ((v - x0) / (x1 - x0) * size.width).toFloat()
        fun y(v: Double): Float {
            val t = ((v.coerceIn(lo, hi) - lo) / (hi - lo)).toFloat()
            return if (invert) t * size.height else (1 - t) * size.height
        }
        val path = Path()
        points.forEachIndexed { i, (px, py) -> if (i == 0) path.moveTo(x(px), y(py)) else path.lineTo(x(px), y(py)) }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(x(points.last().first), size.height)
                lineTo(x(points.first().first), size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawLine(BuddyColors.SurfaceHigh, Offset(0f, size.height), Offset(size.width, size.height), 1f)
    }
}
