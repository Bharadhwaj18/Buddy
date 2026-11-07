package com.shrey.buddy.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.health.StepMath
import com.shrey.buddy.data.items.StepsDay
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Consecutive days (ending today, or yesterday if today isn't done yet) at or over [goal]. */
fun stepStreak(days: Map<LocalDate, StepsDay>, goal: Int, today: LocalDate = LocalDate.now()): Int {
    var d = today
    if ((days[d]?.steps ?: 0) < goal) d = d.minusDays(1)
    var n = 0
    while ((days[d]?.steps ?: 0) >= goal && n < 3650) { n++; d = d.minusDays(1) }
    return n
}

fun longestStepStreak(days: Map<LocalDate, StepsDay>, goal: Int): Int {
    var best = 0
    var run = 0
    var prev: LocalDate? = null
    days.filterValues { it.steps >= goal }.keys.sorted().forEach { d ->
        run = if (prev != null && d == prev!!.plusDays(1)) run + 1 else 1
        best = maxOf(best, run)
        prev = d
    }
    return best
}

@Composable
fun StepsScreen(vm: AppViewModel, startDay: LocalDate?, onBack: () -> Unit, weightKg: Double? = null, heightCm: Double? = null) {
    val days by vm.stepDays.collectAsStateWithLifecycle()
    val goal by vm.stepGoal.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    var selectedEpoch by rememberSaveable { mutableStateOf((startDay ?: today).toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val weekStart = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    // Always offer at least 12 weeks back, more if older history exists.
    val oldest = (days.keys.minOrNull() ?: today).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val earliestWeek = minOf(oldest, thisWeek.minusWeeks(11))

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Steps", style = MaterialTheme.typography.headlineMedium)
            }
        }

        // Week navigator + bars.
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { selectedEpoch = selected.minusWeeks(1).toEpochDay() },
                        enabled = weekStart.isAfter(earliestWeek),
                    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous week") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (weekStart == thisWeek) "This week" else if (weekStart == thisWeek.minusWeeks(1)) "Last week"
                            else "${weekStart.format(dayFmt)} – ${weekStart.plusDays(6).format(dayFmt)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val week = (0..6).map { weekStart.plusDays(it.toLong()) }.filter { !it.isAfter(today) }
                        val avg = if (week.isEmpty()) 0 else week.sumOf { days[it]?.steps ?: 0 } / week.size
                        Text("avg %,d / day".format(avg), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                    IconButton(
                        onClick = { selectedEpoch = minOf(selected.plusWeeks(1), today).toEpochDay() },
                        enabled = weekStart.isBefore(thisWeek),
                    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next week") }
                }
                WeekBars(
                    weekStart = weekStart, days = days, goal = goal, selected = selected, today = today,
                    onSelect = { selectedEpoch = it.toEpochDay() },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        // The selected day.
        item {
            val d = days[selected]
            val steps = d?.steps ?: 0
            BuddyCard(Modifier.fillMaxWidth()) {
                Text(friendlyDay(selected, today) + if (selected != today && selected != today.minusDays(1)) "" else ", ${selected.format(dayFmt)}",
                    style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    GoalRing(steps, goal, 86.dp)
                    Column(Modifier.padding(start = 18.dp)) {
                        Text("%,d".format(steps), style = MaterialTheme.typography.displaySmall.numeric())
                        Text(
                            if (steps >= goal) "Goal reached 🎉" else "%,d to go".format(goal - steps),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (steps >= goal) BuddyColors.Lime else BuddyColors.TextTertiary,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("Distance", "%.2f km".format(d?.distanceM?.div(1000) ?: StepMath.distanceKm(steps, heightCm)))
                    MiniStat("Calories", "≈ %d kcal".format(StepMath.kcal(steps, weightKg, heightCm).toInt()))
                    MiniStat("Source", when (d?.source) {
                        null -> "–"
                        "phone" -> "Phone"
                        "health_connect" -> "Health Connect"
                        else -> if ("samsung" in d.source) "Samsung Health" else "Watch"
                    })
                }
                if (d != null && d.hourly.any { it > 0 }) {
                    Text("By hour", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 16.dp))
                    HourlySteps(d.hourly, Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("12a", "6a", "12p", "6p", "12a").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary) }
                    }
                }
            }
        }

        // 12-week overview.
        item {
            val weeks = (11 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
            BuddyCard(Modifier.fillMaxWidth()) {
                Text("Last 12 weeks", style = MaterialTheme.typography.titleMedium)
                Text("Daily average per week — tap a week to open it", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                val avgs = weeks.map { w ->
                    val ds = (0..6).map { w.plusDays(it.toLong()) }.filter { !it.isAfter(today) }
                    if (ds.isEmpty()) 0L else ds.sumOf { days[it]?.steps ?: 0 } / ds.size
                }
                val max = (avgs.maxOrNull() ?: 0L).coerceAtLeast(goal.toLong())
                Canvas(
                    Modifier.fillMaxWidth().height(110.dp).padding(top = 12.dp).pointerInput(weeks) {
                        detectTapGestures { pos ->
                            val i = (pos.x / (size.width / weeks.size)).toInt().coerceIn(0, weeks.lastIndex)
                            selectedEpoch = minOf(weeks[i].plusDays(6), today).toEpochDay()
                        }
                    },
                ) {
                    val gap = 6.dp.toPx()
                    val w = (size.width - gap * (weeks.size - 1)) / weeks.size
                    val goalY = size.height - size.height * goal / max
                    drawLine(BuddyColors.Lime.copy(alpha = 0.5f), Offset(0f, goalY), Offset(size.width, goalY), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    avgs.forEachIndexed { i, a ->
                        val h = (size.height * a / max).coerceAtLeast(4.dp.toPx())
                        drawRoundRect(
                            color = when {
                                weeks[i] == weekStart -> BuddyColors.Fitness
                                a >= goal -> BuddyColors.Fitness.copy(alpha = 0.6f)
                                else -> BuddyColors.SurfaceHighest
                            },
                            topLeft = Offset(i * (w + gap), size.height - h),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(5.dp.toPx()),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(weeks.first().format(dayFmt), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    Text("This week", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }
                val last84 = (0 until 84).map { today.minusDays(it.toLong()) }
                val withData = last84.mapNotNull { days[it]?.steps }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("Daily avg", "%,d".format(if (withData.isEmpty()) 0 else withData.sum() / withData.size))
                    MiniStat("Best day", "%,d".format(withData.maxOrNull() ?: 0))
                    MiniStat("Streak", "🔥 ${stepStreak(days, goal)}")
                    MiniStat("Longest", "${longestStepStreak(days, goal)} days")
                }
            }
        }

        // Goal.
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Text("Daily goal", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6_000, 8_000, 10_000, 12_000, 15_000).forEach { g ->
                        FilterChip(
                            selected = goal == g,
                            onClick = { vm.setStepGoal(g) },
                            label = { Text("${g / 1000}k") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Fitness.copy(alpha = 0.25f), selectedLabelColor = BuddyColors.TextPrimary),
                        )
                    }
                }
            }
        }
    }
}

/** Mon–Sun bars for one week; tap a bar to select that day. The dashed line is the goal. */
@Composable
fun WeekBars(
    weekStart: LocalDate,
    days: Map<LocalDate, StepsDay>,
    goal: Int,
    selected: LocalDate?,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    val week = (0..6).map { weekStart.plusDays(it.toLong()) }
    val max = (week.maxOfOrNull { days[it]?.steps ?: 0 } ?: 0L).coerceAtLeast(goal.toLong())
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(weekStart) {
                detectTapGestures { pos ->
                    val i = (pos.x / (size.width / 7)).toInt().coerceIn(0, 6)
                    if (!week[i].isAfter(today)) onSelect(week[i])
                }
            },
        ) {
            val gap = 10.dp.toPx()
            val w = (size.width - gap * 6) / 7
            val goalY = size.height - size.height * goal / max
            drawLine(BuddyColors.Lime.copy(alpha = 0.5f), Offset(0f, goalY), Offset(size.width, goalY), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            week.forEachIndexed { i, d ->
                val steps = days[d]?.steps ?: 0
                val h = (size.height * steps / max).coerceAtLeast(5.dp.toPx())
                drawRoundRect(
                    color = when {
                        d == selected -> BuddyColors.Fitness
                        d.isAfter(today) -> BuddyColors.SurfaceHigh
                        steps >= goal -> BuddyColors.Fitness.copy(alpha = 0.55f)
                        else -> BuddyColors.SurfaceHighest
                    },
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            week.forEach { d ->
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (d == selected) BuddyColors.Fitness else BuddyColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun GoalRing(steps: Long, goal: Int, size: Dp) {
    val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 9.dp.toPx()
            drawArc(BuddyColors.SurfaceHigh, 0f, 360f, false, style = Stroke(stroke))
            drawArc(BuddyColors.Fitness, -90f, 360f * progress, false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge.numeric())
    }
}

@Composable
private fun HourlySteps(hourly: List<Int>, modifier: Modifier) {
    val max = (hourly.maxOrNull() ?: 0).coerceAtLeast(100)
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * 23) / 24
        hourly.forEachIndexed { i, s ->
            val h = (size.height * s / max).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (s > 0) BuddyColors.Fitness else BuddyColors.SurfaceHigh,
                Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(3.dp.toPx()),
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall.numeric())
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}
