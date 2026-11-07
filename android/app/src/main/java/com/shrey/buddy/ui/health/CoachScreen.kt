package com.shrey.buddy.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.health.Achievements
import com.shrey.buddy.data.health.Coach
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.PlanSession
import com.shrey.buddy.data.items.TrainingPlan
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val dFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachScreen(hvm: HealthViewModel, stepGoal: Int, onBack: () -> Unit, onStartWorkout: () -> Unit, onStartRun: () -> Unit) {
    val plans by hvm.plans.collectAsStateWithLifecycle()
    val reports by hvm.reports.collectAsStateWithLifecycle()
    val busy by hvm.coachBusy.collectAsStateWithLifecycle()
    val error by hvm.coachError.collectAsStateWithLifecycle()
    val draft by hvm.draft.collectAsStateWithLifecycle()
    var building by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Item<TrainingPlan>?>(null) }
    val today = LocalDate.now()
    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val data by produceState<com.shrey.buddy.data.health.HealthData?>(null, plans, reports) { value = withContext(Dispatchers.IO) { hvm.healthData() } }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Coach", style = MaterialTheme.typography.headlineMedium)
            }
        }

        busy?.let { msg ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = BuddyColors.Lime, strokeWidth = 2.dp)
                    Text(msg, modifier = Modifier.padding(start = 10.dp), color = BuddyColors.TextSecondary)
                }
            }
        }
        error?.let { item { Text(it, color = BuddyColors.Red, style = MaterialTheme.typography.bodySmall) } }

        // ---- Weekly report ----
        item {
            val report = reports.firstOrNull { it.date == thisMonday }?.data ?: reports.firstOrNull()?.data
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = BuddyColors.Lime)
                    Text("Your week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    TextButton(onClick = { hvm.weeklyReport() }, enabled = busy == null) { Text(if (report == null) "Write it" else "Refresh", color = BuddyColors.Lime) }
                }
                if (report == null) {
                    EmptyNote("Buddy reads your steps, sleep, workouts, heart and weight for the week and writes a short report — every Sunday evening too.", Modifier.padding(top = 4.dp))
                } else {
                    Text(report.headline, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    ReportList("Wins", report.highlights, BuddyColors.Lime)
                    ReportList("Keep an eye on", report.watch, BuddyColors.Fitness)
                    ReportList("Next week", report.nextWeek, BuddyColors.Tasks)
                }
            }
        }

        // ---- Plans ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Training plans", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(
                    onClick = { building = true },
                    enabled = busy == null,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
                ) { Text("New plan") }
            }
        }
        if (plans.isEmpty()) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    EmptyNote("Tell Buddy your goal — a 5K, a half marathon, building muscle or losing weight — and it builds a week-by-week plan from your real numbers, in detail. Nothing is added to Tasks, reminders or routines until you tap Start plan.")
                }
            }
        }
        items(plans, key = { it.id }) { p ->
            val start = runCatching { LocalDate.parse(p.data.startDate) }.getOrNull()
            val weekNo = start?.let { (ChronoUnit.WEEKS.between(it, today) + 1).toInt() }
            val thisWeek = p.data.weeks.firstOrNull { it.week == weekNo }
            val todays = thisWeek?.sessions?.filter { Coach.dayIndex(it.day) == today.dayOfWeek.value - 1 && it.type != "rest" }.orEmpty()
            BuddyCard(Modifier.fillMaxWidth().clickable { viewing = p }.alpha(if (p.data.active) 1f else 0.55f)) {
                Text(p.data.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        if (!p.data.active) "Stopped" else weekNo?.let { if (it in 1..p.data.weeks.size) "Week $it of ${p.data.weeks.size}" else if (it < 1) "Starts ${start.format(dFmt)}" else "Finished 🎉" },
                        thisWeek?.focus,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                )
                if (p.data.active) todays.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Today: ${Coach.sessionTitle(s)}", style = MaterialTheme.typography.bodyMedium, color = BuddyColors.Lime)
                            listOfNotNull(s.intensity.ifBlank { null }, s.warmup.ifBlank { null }?.let { "Warm-up: $it" }, s.details.ifBlank { null }).forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                            }
                        }
                        Button(
                            onClick = {
                                if (s.type == "strength" && s.exercises.isNotEmpty()) { hvm.startPlanSession(s); onStartWorkout() } else onStartRun()
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                        ) { Text("Start") }
                    }
                }
            }
        }

        // ---- Streaks ----
        data?.let { d ->
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Streaks", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Achievements.streaks(d, stepGoal).forEach { s ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(s.emoji, style = MaterialTheme.typography.titleLarge)
                                Text("${s.current}", style = MaterialTheme.typography.titleLarge.numeric())
                                Text(s.title, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary, textAlign = TextAlign.Center)
                                Text("best ${s.best} ${s.unit}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            item {
                val badges = Achievements.badges(d, stepGoal)
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Badges · ${badges.count { it.earned }} of ${badges.size}", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                        badges.forEach { b -> BadgeTile(b) }
                    }
                }
            }
        }
    }

    if (building) PlanBuilderSheet(hvm, onDismiss = { building = false })
    draft?.let { PlanPreviewDialog(it, onStart = { setup -> hvm.activateDraft(setup) }, onDiscard = { hvm.discardDraft() }) }
    viewing?.let { p -> PlanDetailDialog(p, onDismiss = { viewing = null }, onStop = { hvm.stopPlan(p); viewing = null }, onDelete = { hvm.deletePlan(p.id); viewing = null }) }
}

@Composable
private fun ReportList(title: String, lines: List<String>, color: androidx.compose.ui.graphics.Color) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(top = 10.dp))
    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
}

@Composable
private fun BadgeTile(b: com.shrey.buddy.data.health.Badge) {
    Column(
        Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(if (b.earned) BuddyColors.Lime.copy(alpha = 0.12f) else BuddyColors.SurfaceHigh).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(b.emoji, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.alpha(if (b.earned) 1f else 0.35f))
            if (!b.earned) Icon(Icons.Rounded.Lock, null, tint = BuddyColors.TextTertiary, modifier = Modifier.size(14.dp).align(Alignment.BottomEnd))
        }
        Text(b.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        if (!b.earned) {
            LinearProgressIndicator(
                progress = { b.progress }, color = BuddyColors.Fitness, trackColor = BuddyColors.Surface,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            Text(b.progressLabel, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun PlanBuilderSheet(hvm: HealthViewModel, onDismiss: () -> Unit) {
    var goal by remember { mutableStateOf("run_5k") }
    var details by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(4) }
    var experience by remember { mutableStateOf("beginner") }
    var equipment by remember { mutableStateOf("gym") }
    var weeks by remember { mutableStateOf(8) }
    FormSheet(
        title = "New training plan",
        onDismiss = onDismiss,
        onSave = {
            hvm.generatePlan(Coach.PlanRequest(goal, details.trim(), days, experience, equipment, weeks))
            onDismiss()
        },
        saveLabel = "Build my plan",
        accent = BuddyColors.Fitness,
    ) {
        Text("Goal", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary)
        Choices(Coach.goalLabels.entries.map { it.key to it.value }, goal, { goal = it }, BuddyColors.Fitness)
        Field(
            details, { details = it },
            when {
                goal.startsWith("run") -> "Target (optional) — e.g. under 30 min, race on 14 Dec"
                goal == "weight_loss" -> "Target (optional) — e.g. lose 6 kg"
                else -> "Anything to know — e.g. bad knee, focus on arms"
            },
            Modifier.padding(top = 10.dp), accent = BuddyColors.Fitness,
        )
        Text("Days a week", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 12.dp))
        Choices((2..6).map { it to "$it" }, days, { days = it }, BuddyColors.Fitness)
        Text("Experience", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 12.dp))
        Choices(listOf("beginner" to "Beginner", "intermediate" to "Intermediate", "advanced" to "Advanced"), experience, { experience = it }, BuddyColors.Fitness)
        if (!goal.startsWith("run")) {
            Text("Equipment", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 12.dp))
            Choices(listOf("gym" to "Full gym", "dumbbells" to "Dumbbells", "bodyweight" to "Bodyweight"), equipment, { equipment = it }, BuddyColors.Fitness)
        }
        Text("Length", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 12.dp))
        Choices(listOf(4 to "4 weeks", 8 to "8 weeks", 12 to "12 weeks"), weeks, { weeks = it }, BuddyColors.Fitness)
        Text(
            "Buddy uses your age, weight, recent runs and lifts so the plan fits you. You'll see it before anything is added to Tasks.",
            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private val reminderTimes: List<Pair<LocalTime?, String>> = listOf(
    null to "No", LocalTime.of(6, 0) to "6 am", LocalTime.of(7, 0) to "7 am", LocalTime.of(8, 0) to "8 am",
    LocalTime.of(17, 0) to "5 pm", LocalTime.of(18, 0) to "6 pm", LocalTime.of(19, 0) to "7 pm", LocalTime.of(20, 0) to "8 pm",
)

/**
 * The plan Buddy drafted, full screen so you can actually read it. It's only a draft:
 * sessions, reminders and routines are created when you tap "Start plan", never before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanPreviewDialog(plan: TrainingPlan, onStart: (Coach.Setup) -> Unit, onDiscard: () -> Unit) {
    val nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    val thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    var start by remember { mutableStateOf(nextMonday) }
    var weekday by remember { mutableStateOf<LocalTime?>(LocalTime.of(7, 0)) }
    var weekend by remember { mutableStateOf<LocalTime?>(LocalTime.of(8, 0)) }
    var routines by remember { mutableStateOf(true) }
    val sessions = plan.weeks.sumOf { w -> w.sessions.count { it.type != "rest" } }
    val strengthDays = plan.weeks.flatMap { it.sessions }.filter { it.type == "strength" && it.exercises.isNotEmpty() }.distinctBy { it.title.lowercase() }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = {}, // a stray tap outside mustn't throw the draft away
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        androidx.compose.material3.Surface(color = BuddyColors.Background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = 28.dp, bottom = 12.dp)) {
                Text("Draft plan · nothing is set up yet", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Fitness)
                Text(plan.title, style = MaterialTheme.typography.headlineSmall)
                Text("${plan.weeks.size} weeks · $sessions sessions", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary)

                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    item { Text(plan.summary, style = MaterialTheme.typography.bodyMedium) }
                    plan.nutrition?.takeIf { it.isNotBlank() }?.let {
                        item { Text("🍽 $it", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 8.dp)) }
                    }
                    items(plan.weeks) { w -> WeekBlock(w.week, w.focus, w.sessions) }
                    if (plan.tips.isNotEmpty()) item {
                        Text("Tips", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Lime, modifier = Modifier.padding(top = 12.dp))
                        plan.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    item {
                        BuddyCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Text("If you start this plan", style = MaterialTheme.typography.titleMedium)
                            Text("Starts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                            Choices(listOf(thisMonday to "This week", nextMonday to "Next Monday"), start, { start = it }, BuddyColors.Fitness)
                            Text("Call me on weekday session days at", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                            Choices(reminderTimes, weekday, { weekday = it }, BuddyColors.Fitness)
                            Text("…and on weekends at", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                            Choices(reminderTimes, weekend, { weekend = it }, BuddyColors.Fitness)
                            if (strengthDays.isNotEmpty()) {
                                com.shrey.buddy.ui.components.ToggleField(
                                    "Save gym days as routines", routines, { routines = it },
                                    subtitle = strengthDays.joinToString(", ") { it.title } + " — one tap to start in Workouts",
                                )
                            }
                            Text(
                                "Starting adds $sessions sessions to Tasks (plan “Training”)" +
                                    (if (weekday != null || weekend != null) ", each ringing at the time above" else ", without reminders") +
                                    (if (routines && strengthDays.isNotEmpty()) ", and ${strengthDays.size} routine${if (strengthDays.size == 1) "" else "s"}" else "") +
                                    ". Stopping the plan later removes what's left of them.",
                                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f).height(50.dp)) { Text("Discard", color = BuddyColors.TextSecondary) }
                    Button(
                        onClick = { onStart(Coach.Setup(start, weekday, weekend, routines)) },
                        modifier = Modifier.weight(2f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                    ) { Text("Start plan") }
                }
            }
        }
    }
}

/** A week of the plan. Tap a session to open its warm-up, exercises (sets × reps, load, rest) and cool-down. */
@Composable
private fun WeekBlock(week: Int, focus: String, sessions: List<PlanSession>) {
    Column(Modifier.padding(top = 14.dp)) {
        Text("Week $week · $focus", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Fitness)
        sessions.forEach { s ->
            var open by remember(week, s.day, s.title) { mutableStateOf(week == 1) }
            val rest = s.type == "rest"
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clickable(enabled = !rest) { open = !open }) {
                Text(s.day, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary, modifier = Modifier.width(38.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (rest) "Rest" else Coach.sessionTitle(s) + (s.durationMin?.let { " · $it min" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = if (rest) BuddyColors.TextTertiary else BuddyColors.TextPrimary,
                    )
                    if (!rest && !open) {
                        Text(
                            listOfNotNull(s.intensity.ifBlank { null }, if (s.exercises.isNotEmpty()) "${s.exercises.size} exercises" else s.details.lineSequence().firstOrNull()?.take(60)).joinToString(" · ") + "  ▾",
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                        )
                    }
                    if (!rest && open) {
                        if (s.intensity.isNotBlank()) Text(s.intensity, style = MaterialTheme.typography.labelMedium, color = BuddyColors.Tasks)
                        if (s.warmup.isNotBlank()) Text("Warm-up: ${s.warmup}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary)
                        if (s.details.isNotBlank()) Text(s.details, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
                        s.exercises.forEach { e ->
                            Text("• ${Coach.exerciseLine(e)}", style = MaterialTheme.typography.bodySmall)
                            if (e.notes.isNotBlank()) Text("   ${e.notes}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        }
                        if (s.cooldown.isNotBlank()) Text("Cool-down: ${s.cooldown}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanDetailDialog(p: Item<TrainingPlan>, onDismiss: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        title = { Text(p.data.title) },
        text = {
            LazyColumn(Modifier.height(480.dp)) {
                item { Text(p.data.summary, style = MaterialTheme.typography.bodyMedium) }
                p.data.nutrition?.takeIf { it.isNotBlank() }?.let {
                    item { Text("🍽 $it", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 8.dp)) }
                }
                items(p.data.weeks) { w -> WeekBlock(w.week, w.focus, w.sessions) }
                if (p.data.tips.isNotEmpty()) item {
                    Text("Tips", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Lime, modifier = Modifier.padding(top = 12.dp))
                    p.data.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (p.data.active) TextButton(onClick = onStop) { Text("Stop plan", color = BuddyColors.Red) }
            else TextButton(onClick = onDelete) { Text("Delete", color = BuddyColors.Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
