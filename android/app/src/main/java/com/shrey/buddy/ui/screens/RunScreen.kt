package com.shrey.buddy.ui.screens

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.health.RunState
import com.shrey.buddy.health.RunTrackerService
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.health.formatPace
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.health.HealthViewModel
import com.shrey.buddy.ui.rememberPermissionGate
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.delay
import kotlin.math.cos

@Composable
fun RunScreen(hvm: HealthViewModel, onDone: () -> Unit, onSaved: (String) -> Unit = {}) {
    val context = LocalContext.current
    val s by hvm.run.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf("run") }
    var voice by remember { mutableStateOf(RunTrackerService.voiceOn(context)) }
    val gate = rememberPermissionGate(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS),
        required = 1,
        onResult = { if (it) RunTrackerService.start(context) },
    )
    // Smooth clock between GPS fixes.
    val shownElapsed by produceState(s.elapsedMs, s) {
        val base = s.elapsedMs
        val t0 = System.currentTimeMillis()
        while (s.status == RunState.Status.RUNNING) {
            value = base + (System.currentTimeMillis() - t0)
            delay(500)
        }
        value = s.elapsedMs
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(
                when (s.status) {
                    RunState.Status.IDLE -> "New activity"
                    RunState.Status.RUNNING -> "Recording"
                    RunState.Status.PAUSED -> "Paused"
                    RunState.Status.FINISHED -> "Finished"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        if (s.status == RunState.Status.IDLE) {
            Choices(listOf("run" to "Run", "walk" to "Walk", "cycle" to "Cycle"), type, { type = it }, BuddyColors.Fitness)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Spoken km updates", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = voice,
                    onCheckedChange = { voice = it; RunTrackerService.setVoice(context, it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
                )
            }
        }

        // Real map; follows you while recording.
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)),
        ) {
            val route = s.points.map { it.lat to it.lng }
            com.shrey.buddy.ui.health.RouteMap(
                segments = listOf(com.shrey.buddy.ui.health.RouteSegment(route)),
                follow = if (s.status == RunState.Status.RUNNING || s.status == RunState.Status.PAUSED) route.lastOrNull() else null,
                modifier = Modifier.fillMaxSize(),
            )
            if (route.isEmpty()) {
                Text(
                    if (s.status == RunState.Status.IDLE) "Press Start — the map follows you." else "Waiting for GPS…",
                    color = BuddyColors.TextSecondary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        BuddyCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.2f".format(s.distanceM / 1000), style = MaterialTheme.typography.displayMedium.numeric())
                Text(" km", style = MaterialTheme.typography.titleMedium, color = BuddyColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Stat("Time", formatDuration(shownElapsed), Modifier.weight(1f))
                Stat("Avg pace", formatPace(s.paceSecPerKm), Modifier.weight(1f))
                Stat("Climb", "%.0f m".format(s.elevationGainM), Modifier.weight(1f))
            }
        }

        when (s.status) {
            RunState.Status.IDLE -> BigButton("Start") { if (gate.granted) RunTrackerService.start(context) else gate.request() }
            RunState.Status.RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { RunTrackerService.pause(context) }, modifier = Modifier.weight(1f).height(60.dp)) { Text("Pause") }
                BigButton("Finish", Modifier.weight(1f)) { RunTrackerService.finish(context) }
            }
            RunState.Status.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { RunTrackerService.resume(context) }, modifier = Modifier.weight(1f).height(60.dp)) { Text("Resume") }
                BigButton("Finish", Modifier.weight(1f)) { RunTrackerService.finish(context) }
            }
            RunState.Status.FINISHED -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { hvm.discardRun(); onDone() }, modifier = Modifier.weight(1f).height(60.dp)) { Text("Discard") }
                BigButton("Save", Modifier.weight(1f)) {
                    hvm.saveRun(type, defaultRunTitle(type)) { id -> if (id != null) onSaved(id) else onDone() }
                }
            }
        }
    }
}

/** "Morning Run", "Evening Walk" — like Strava. */
fun defaultRunTitle(type: String, hour: Int = java.time.LocalTime.now().hour): String {
    val part = when (hour) {
        in 4..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
    return "$part ${when (type) { "walk" -> "Walk"; "cycle" -> "Ride"; else -> "Run" }}"
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge.numeric())
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Route drawn to fit the box (equirectangular projection is fine at run scale). No map tiles needed. */
@Composable
fun RouteSketch(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val latMid = points.map { it.first }.average()
        val k = cos(Math.toRadians(latMid))
        val xs = points.map { it.second * k }
        val ys = points.map { it.first }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1e-6
        val pad = 16.dp.toPx()
        val scale = (minOf(size.width, size.height) - 2 * pad) / span
        val offX = (size.width - (maxX - minX) * scale) / 2
        val offY = (size.height - (maxY - minY) * scale) / 2
        fun pt(i: Int) = Offset((offX + (xs[i] - minX) * scale).toFloat(), (offY + (maxY - ys[i]) * scale).toFloat())
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until points.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(path, BuddyColors.Fitness, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(BuddyColors.Lime, 7.dp.toPx(), pt(0))
        drawCircle(BuddyColors.TextPrimary, 7.dp.toPx(), pt(points.lastIndex))
    }
}
