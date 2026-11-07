package com.shrey.buddy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsTennis
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.health.DaySteps
import com.shrey.buddy.data.items.Habit
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Meal
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.ScreenTime
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.HealthUi
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.IconBadge
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.health.HabitSheet
import com.shrey.buddy.ui.health.HealthViewModel
import com.shrey.buddy.ui.health.MealSheet
import com.shrey.buddy.ui.health.WorkoutSheet
import com.shrey.buddy.ui.health.HourlyBars
import com.shrey.buddy.ui.health.GoalRing
import com.shrey.buddy.ui.health.WeekBars
import com.shrey.buddy.ui.health.stepStreak
import com.shrey.buddy.ui.health.formatMinutes
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

fun workoutIcon(type: String): ImageVector = when (type) {
    "run" -> Icons.AutoMirrored.Rounded.DirectionsRun
    "walk" -> Icons.AutoMirrored.Rounded.DirectionsWalk
    "cycle" -> Icons.Rounded.DirectionsBike
    "yoga" -> Icons.Rounded.SelfImprovement
    "sport" -> Icons.Rounded.SportsTennis
    else -> Icons.Rounded.FitnessCenter
}

/** Whether [habit] is done on [day]: manual logs, or computed for automatic habits. */
fun habitDone(
    habit: Item<Habit>,
    day: LocalDate,
    logs: List<Item<*>>,
    stepsByDay: Map<LocalDate, Long>,
    spendDays: Set<LocalDate>,
): Boolean = when (habit.data.auto) {
    "steps" -> (stepsByDay[day] ?: 0L) >= habit.data.target
    "no_spend" -> !day.isAfter(LocalDate.now()) && day !in spendDays
    else -> logs.any { it.refId == habit.id && it.date == day }
}

fun streak(done: (LocalDate) -> Boolean): Int {
    var d = LocalDate.now()
    if (!done(d)) d = d.minusDays(1) // today not done yet doesn't break the streak
    var n = 0
    while (done(d) && n < 365) { n++; d = d.minusDays(1) }
    return n
}

@Composable
fun HealthScreen(
    vm: AppViewModel,
    hvm: HealthViewModel,
    onStartRun: () -> Unit,
    onOpenScreenTime: () -> Unit = {},
    onOpenSteps: (LocalDate) -> Unit = {},
    onOpenBody: () -> Unit = {},
    onOpenRuns: () -> Unit = {},
    onOpenRun: (String) -> Unit = {},
    onOpenWorkouts: () -> Unit = {},
    onStartWorkout: () -> Unit = {},
    onOpenWorkout: (String) -> Unit = {},
    onOpenCoach: () -> Unit = {},
    onOpenWellbeing: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val health by vm.health.collectAsStateWithLifecycle()
    val habits by hvm.habits.collectAsStateWithLifecycle()
    val logs by hvm.habitLogs.collectAsStateWithLifecycle()
    val workouts by hvm.workouts.collectAsStateWithLifecycle()
    val meals by hvm.meals.collectAsStateWithLifecycle()
    val spendDays by hvm.spendDays.collectAsStateWithLifecycle()
    val estimating by hvm.estimating.collectAsStateWithLifecycle()
    var editHabit by remember { mutableStateOf<Item<Habit>?>(null) }
    var newHabit by remember { mutableStateOf(false) }
    var editWorkout by remember { mutableStateOf<Item<Workout>?>(null) }
    var newWorkout by remember { mutableStateOf(false) }
    var editMeal by remember { mutableStateOf<Item<Meal>?>(null) }
    var newMeal by remember { mutableStateOf(false) }
    var usageGranted by remember { mutableStateOf(ScreenTime.hasPermission(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshHealth()
        usageGranted = ScreenTime.hasPermission(context)
    }
    var resumes by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val screen by produceState<com.shrey.buddy.health.ScreenTimeDay?>(null, usageGranted, resumes) {
        value = if (usageGranted) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ScreenTime.forDay(context) } else null
    }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshHealth() }
    val stepDays by vm.stepDays.collectAsStateWithLifecycle()
    val stepGoal by vm.stepGoal.collectAsStateWithLifecycle()
    var sensorReady by remember { mutableStateOf(vm.stepSensorReady()) }
    val sensorLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        sensorReady = it
        vm.refreshHealth()
    }
    val stepsByDay = stepDays.mapValues { it.value.steps }
    val watchPermsMissing by produceState(false, health) { value = runCatching { vm.healthMissingSome() }.getOrDefault(false) }
    LaunchedEffect(Unit) { hvm.syncWatch() }
    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Health", style = MaterialTheme.typography.headlineLarge) }

        // ---- Steps ----
        item {
            val todaySteps = stepDays[today]?.steps ?: 0L
            BuddyCard(Modifier.fillMaxWidth().clickable { onOpenSteps(today) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoalRing(todaySteps, stepGoal, 72.dp)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("Steps today", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                        Text("%,d".format(todaySteps), style = MaterialTheme.typography.headlineMedium.numeric())
                        Text(
                            "%.1f km · goal %,d · 🔥 %d".format(com.shrey.buddy.data.health.StepMath.distanceKm(todaySteps), stepGoal, stepStreak(stepDays, stepGoal)),
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                        )
                    }
                }
                WeekBars(
                    weekStart = today.minusDays(6), days = stepDays, goal = stepGoal, selected = today, today = today,
                    onSelect = onOpenSteps, modifier = Modifier.padding(top = 14.dp), height = 80.dp,
                )
                if (!sensorReady && vm.hasStepSensor()) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Count steps with your phone's sensor", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { sensorLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Allow", color = BuddyColors.Fitness) }
                    }
                }
                if (health is HealthUi.NeedsPermission) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Connect Health Connect (for your watch)", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { hcLauncher.launch(vm.healthPermissions) }) { Text("Connect", color = BuddyColors.Fitness) }
                    }
                }
            }
        }

        item {
            val plans by hvm.plans.collectAsStateWithLifecycle()
            val active = plans.firstOrNull { it.data.active }
            BuddyCard(Modifier.fillMaxWidth().clickable(onClick = onOpenCoach)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.AutoAwesome, BuddyColors.Lime, 36.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Coach", style = MaterialTheme.typography.titleMedium)
                        Text(
                            active?.data?.title ?: "AI training plans, weekly report, badges & streaks",
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, maxLines = 1,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = BuddyColors.TextTertiary)
                }
            }
        }
        item { com.shrey.buddy.ui.health.BodySummaryCard(hvm, onOpen = onOpenBody) }

        // ---- Activity ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onStartRun,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null)
                    Text("Start run", modifier = Modifier.padding(start = 8.dp))
                }
                val live by hvm.session.state.collectAsStateWithLifecycle()
                OutlinedButton(
                    onClick = {
                        if (live == null) hvm.startWorkout(com.shrey.buddy.ui.health.defaultWorkoutTitle())
                        onStartWorkout()
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.FitnessCenter, null, tint = BuddyColors.Fitness)
                    Text(if (live != null) "Resume gym" else "Start gym", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        // ---- Habits ----
        item { SectionHeader("Habits", action = "New habit", onAction = { newHabit = true }) }
        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                if (habits.isEmpty()) EmptyNote("Add habits like “Read 20 min”, “10k steps” (automatic) or “No-spend day” (automatic).")
                if (habits.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 150.dp, bottom = 4.dp)) {
                    week.forEach { d ->
                        Text(
                            d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                        )
                    }
                }
                habits.forEach { habit ->
                    val done = { d: LocalDate -> habitDone(habit, d, logs, stepsByDay, spendDays) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.size(width = 150.dp, height = 40.dp).clickable { editHabit = habit }, verticalArrangement = Arrangement.Center) {
                            Text("${habit.data.emoji} ${habit.data.name}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            val s = streak(done)
                            Text(
                                if (habit.data.auto != null) "auto · 🔥 $s" else "🔥 $s day streak",
                                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                            )
                        }
                        week.forEach { d ->
                            val isDone = done(d)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape)
                                        .background(if (isDone) BuddyColors.Fitness else BuddyColors.SurfaceHigh)
                                        .then(if (habit.data.auto == null) Modifier.clickable { hvm.toggleHabit(habit.id, d) } else Modifier),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Runs ----
        item {
            val runs = com.shrey.buddy.ui.health.gpsActivities(workouts, null)
            val weekKm = runs.filter { it.date?.isAfter(today.minusDays(7)) == true }.sumOf { it.data.run!!.distanceM } / 1000
            BuddyCard(Modifier.fillMaxWidth().clickable(onClick = onOpenRuns)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.AutoMirrored.Rounded.DirectionsRun, BuddyColors.Fitness, 36.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Runs, walks & rides", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (runs.isEmpty()) "Maps, splits, personal bests" else "%.1f km in the last 7 days · %d total".format(weekKm, runs.size),
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = BuddyColors.TextTertiary)
                }
            }
        }

        // ---- Workouts ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Workouts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { newWorkout = true }) { Text("Quick log", color = BuddyColors.TextSecondary) }
                TextButton(onClick = onOpenWorkouts) { Text("All & routines ›", color = BuddyColors.Lime) }
            }
        }
        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                val weekCount = workouts.count { it.date?.isAfter(today.minusDays(7)) == true }
                Text("$weekCount this week", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                if (workouts.isEmpty()) EmptyNote("No workouts yet.", Modifier.padding(horizontal = 8.dp))
                workouts.take(8).forEach { w ->
                    val run = w.data.run
                    ListRow(
                        icon = workoutIcon(w.data.type),
                        tint = BuddyColors.Fitness,
                        title = w.data.title.ifBlank { w.data.type.replaceFirstChar(Char::uppercase) },
                        subtitle = listOfNotNull(
                            w.date?.let { friendlyDay(it) },
                            run?.let { "%.2f km".format(it.distanceM / 1000) },
                            if (w.data.durationMin > 0) "${w.data.durationMin} min" else run?.let { formatDuration(it.durationS * 1000) },
                            if (w.data.exercises.isNotEmpty()) "${w.data.exercises.size} exercises" else null,
                        ).joinToString(" · "),
                        // GPS activities open their map page; gym sessions open the editor.
                        modifier = Modifier.padding(horizontal = 8.dp).clickable { if (w.data.run != null) onOpenRun(w.id) else onOpenWorkout(w.id) },
                    )
                }
            }
        }

        // ---- Meals ----
        item {
            val todays = meals.filter { it.date == today }
            SectionHeader("Food today", action = "Log meal", onAction = { newMeal = true })
            BuddyCard(Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = 12.dp) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Text("${todays.sumOf { it.data.kcal }} kcal", style = MaterialTheme.typography.titleLarge.numeric(), modifier = Modifier.weight(1f))
                    Text("${todays.sumOf { it.data.proteinG }} g protein", style = MaterialTheme.typography.titleMedium.numeric(), color = BuddyColors.TextSecondary)
                }
                if (todays.isEmpty()) EmptyNote("Log a meal — describe it or snap a photo and Buddy estimates calories.", Modifier.padding(horizontal = 8.dp))
                todays.forEach { m ->
                    ListRow(
                        Icons.Rounded.Restaurant, BuddyColors.Fitness, m.data.description,
                        "${m.data.slot.replaceFirstChar(Char::uppercase)} · ${m.data.kcal} kcal · ${m.data.proteinG} g",
                        Modifier.padding(horizontal = 8.dp).clickable { editMeal = m },
                    )
                }
            }
        }

        // Sleep, heart, water, medicines: compact tiles; each opens its own page with the full charts.
        item { SectionHeader("Wellbeing") }
        item { com.shrey.buddy.ui.health.WellbeingTiles(hvm, onOpen = onOpenWellbeing) }

        item {
            com.shrey.buddy.ui.health.DevicesCard(
                hvm,
                needsPermission = health is HealthUi.NeedsPermission || watchPermsMissing,
                onConnect = { hcLauncher.launch(vm.healthPermissions) },
            )
        }
        item { com.shrey.buddy.ui.health.MoveNudgeRow() }

        // ---- Screen time ----
        item {
            BuddyCard(Modifier.fillMaxWidth().clickable(enabled = usageGranted, onClick = onOpenScreenTime)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.PhoneAndroid, BuddyColors.Voice, 36.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Screen time today", style = MaterialTheme.typography.titleMedium)
                        screen?.let { s ->
                            Text(
                                listOfNotNull("${s.unlocks} unlocks", s.firstPickupAt?.let { "first at ${formatTime(it)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                            )
                        }
                    }
                    screen?.let { Text(formatMinutes(it.totalMinutes), style = MaterialTheme.typography.titleMedium.numeric()) }
                }
                if (!usageGranted) {
                    Text("Allow “Usage access” for Buddy to see which apps take your time.", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { context.startActivity(ScreenTime.settingsIntent()) }) { Text("Open settings", color = BuddyColors.Voice) }
                }
                screen?.let { HourlyBars(it.hourly, Modifier.padding(top = 12.dp).height(50.dp)) }
                screen?.apps?.take(4)?.forEach { a ->
                    Row(Modifier.padding(top = 8.dp)) {
                        Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(formatMinutes(a.minutes), style = MaterialTheme.typography.bodyMedium.numeric(), color = BuddyColors.TextSecondary)
                    }
                }
                if (screen != null) Text("See all apps & past days ›", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Voice, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }

    if (newHabit || editHabit != null) {
        val h = editHabit
        HabitSheet(
            initial = h,
            onDismiss = { newHabit = false; editHabit = null },
            onSave = { hvm.saveHabit(h?.id, it); newHabit = false; editHabit = null },
            onDelete = h?.let { { hvm.deleteHabit(it.id); editHabit = null } },
        )
    }
    if (newWorkout || editWorkout != null) {
        val w = editWorkout
        WorkoutSheet(
            initial = w,
            previous = workouts,
            onDismiss = { newWorkout = false; editWorkout = null },
            onSave = { data, day -> hvm.saveWorkout(w?.id, data, day); newWorkout = false; editWorkout = null },
            onDelete = w?.let { { hvm.deleteWorkout(it.id); editWorkout = null } },
        )
    }
    if (newMeal || editMeal != null) {
        val m = editMeal
        MealSheet(
            initial = m,
            vm = hvm,
            estimating = estimating,
            onDismiss = { newMeal = false; editMeal = null },
            onSave = { data, day -> hvm.saveMeal(m?.id, data, day); newMeal = false; editMeal = null },
            onDelete = m?.let { { hvm.deleteMeal(it.id); editMeal = null } },
        )
    }
}
