package com.shrey.buddy.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shrey.buddy.AppContainer
import com.shrey.buddy.data.items.Habit
import com.shrey.buddy.data.items.HabitLog
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Meal
import com.shrey.buddy.data.items.RunSummary
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.Polyline
import com.shrey.buddy.health.RunState
import com.shrey.buddy.health.RunTrackerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val PILL = "💊"

/** Habits, workouts, runs and meals (all stored in the shared `items` table). */
class HealthViewModel(private val c: AppContainer) : ViewModel() {
    private val items get() = c.items
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val habits: StateFlow<List<Item<Habit>>> = items.observe(Kinds.HABIT, Habit.serializer())
        .map { list -> list.filter { !it.data.archived }.sortedBy { it.entity.createdAt } }.state(emptyList())
    val habitLogs: StateFlow<List<Item<HabitLog>>> = items.observe(Kinds.HABIT_LOG, HabitLog.serializer()).state(emptyList())
    val workouts: StateFlow<List<Item<Workout>>> = items.observe(Kinds.WORKOUT, Workout.serializer()).state(emptyList())
    val meals: StateFlow<List<Item<Meal>>> = items.observe(Kinds.MEAL, Meal.serializer()).state(emptyList())

    /** Days with at least one real (non-transfer) debit — for the automatic "no-spend day" habit. */
    val spendDays: StateFlow<Set<LocalDate>> = c.finance.observeTransactions().map { txns ->
        txns.filter { it.direction == "debit" && !it.isTransfer }
            .map { Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSet()
    }.state(emptySet())

    val run: StateFlow<RunState> = RunTrackerService.state

    // ---- Body ----
    private val profileId = ItemStore.stableId("body:profile")
    val profile: StateFlow<com.shrey.buddy.data.items.BodyProfile> =
        items.observe(Kinds.BODY_PROFILE, com.shrey.buddy.data.items.BodyProfile.serializer())
            .map { it.firstOrNull()?.data ?: com.shrey.buddy.data.items.BodyProfile() }.state(com.shrey.buddy.data.items.BodyProfile())
    val weights: StateFlow<List<Item<com.shrey.buddy.data.items.WeightEntry>>> =
        items.observe(Kinds.WEIGHT, com.shrey.buddy.data.items.WeightEntry.serializer())
            .map { list -> list.sortedWith(compareBy<Item<com.shrey.buddy.data.items.WeightEntry>> { it.date }.thenBy { it.entity.createdAt }) }
            .state(emptyList())
    val measures: StateFlow<List<Item<com.shrey.buddy.data.items.BodyMeasure>>> =
        items.observe(Kinds.BODY_MEASURE, com.shrey.buddy.data.items.BodyMeasure.serializer())
            .map { list -> list.sortedBy { it.date } }.state(emptyList())

    /** Latest logged weight, for calorie maths everywhere. */
    val latestKg: StateFlow<Double?> = weights.map { it.lastOrNull()?.data?.kg }.state(null)

    fun saveProfile(p: com.shrey.buddy.data.items.BodyProfile) =
        viewModelScope.launch { items.put(Kinds.BODY_PROFILE, p, com.shrey.buddy.data.items.BodyProfile.serializer(), id = profileId) }

    /** One weigh-in per day (stable id) — logging again the same day replaces it. */
    fun saveWeight(entry: com.shrey.buddy.data.items.WeightEntry, day: LocalDate) = viewModelScope.launch {
        items.put(Kinds.WEIGHT, entry, com.shrey.buddy.data.items.WeightEntry.serializer(), id = ItemStore.stableId("weight:$day"), occurredOn = day)
    }
    fun deleteWeight(id: String) = viewModelScope.launch { items.delete(id) }

    fun saveMeasure(m: com.shrey.buddy.data.items.BodyMeasure, day: LocalDate) = viewModelScope.launch {
        items.put(Kinds.BODY_MEASURE, m, com.shrey.buddy.data.items.BodyMeasure.serializer(), id = ItemStore.stableId("measure:$day"), occurredOn = day)
    }

    private val _estimating = MutableStateFlow<String?>(null)
    /** Non-null while an AI estimate is running; holds an error message after a failure. */
    val estimating = _estimating.asStateFlow()

    fun saveHabit(id: String?, habit: Habit) = viewModelScope.launch { items.put(Kinds.HABIT, habit, Habit.serializer(), id = id) }
    fun deleteHabit(id: String) = viewModelScope.launch { items.delete(id) }

    /** One log per habit per day; deterministic ID so toggling twice (or on two devices) can't duplicate. */
    fun toggleHabit(habitId: String, day: LocalDate = LocalDate.now()) = viewModelScope.launch {
        val id = ItemStore.stableId("habitlog:$habitId:$day")
        val existing = items.get(id, HabitLog.serializer())
        if (existing != null) items.delete(id)
        else items.put(Kinds.HABIT_LOG, HabitLog(1), HabitLog.serializer(), id = id, occurredOn = day, refId = habitId)
    }

    fun saveWorkout(id: String?, workout: Workout, day: LocalDate) =
        viewModelScope.launch { items.put(Kinds.WORKOUT, workout, Workout.serializer(), id = id, occurredOn = day) }
    fun deleteWorkout(id: String) = viewModelScope.launch { items.delete(id) }

    fun saveMeal(id: String?, meal: Meal, day: LocalDate) =
        viewModelScope.launch { items.put(Kinds.MEAL, meal, Meal.serializer(), id = id, occurredOn = day) }
    fun deleteMeal(id: String) = viewModelScope.launch { items.delete(id) }

    /** AI estimate from a description; calls [onResult] with (description, kcal, protein). */
    fun estimateMeal(text: String, onResult: (String, Int, Int) -> Unit) = viewModelScope.launch {
        _estimating.value = ""
        _estimating.value = try {
            val r = c.ai.mealText(text)
            if (r.error != null) r.error else {
                onResult(r.description.ifBlank { text }, r.kcal, r.proteinG)
                null
            }
        } catch (e: Exception) {
            e.message ?: "Couldn't estimate"
        }
    }

    fun estimateMealPhoto(base64: String, onResult: (String, Int, Int) -> Unit) = viewModelScope.launch {
        _estimating.value = ""
        _estimating.value = try {
            val r = c.ai.mealPhoto(base64, "image/jpeg")
            if (r.error != null) r.error else {
                onResult(r.description, r.kcal, r.proteinG)
                null
            }
        } catch (e: Exception) {
            e.message ?: "Couldn't estimate"
        }
    }

    fun clearEstimateError() {
        _estimating.value = null
    }

    /** Saves the finished run (full track, splits, best efforts, calories) as a workout; [onSaved] gets its id. */
    fun saveRun(type: String, title: String, onSaved: (String?) -> Unit = {}) = viewModelScope.launch {
        val s = RunTrackerService.state.value
        if (s.distanceM < 10 && s.elapsedMs < 60_000) {
            RunTrackerService.reset()
            onSaved(null)
            return@launch
        }
        val summary = com.shrey.buddy.health.RunRecord.summarize(type, s, latestKg.value)
        val workout = Workout(type = type, title = title, durationMin = (s.elapsedMs / 60_000).toInt(), run = summary)
        val day = Instant.ofEpochMilli(s.startedAtWall).atZone(ZoneId.systemDefault()).toLocalDate()
        val id = items.put(Kinds.WORKOUT, workout, Workout.serializer(), occurredOn = day)
        if (workout.run != null) runCatching { c.coach.completeToday("run") }
        RunTrackerService.reset()
        onSaved(id)
    }

    // ---- Sleep, heart, water (watch via Health Connect, or phone estimates) ----
    val sleep: StateFlow<List<Item<com.shrey.buddy.data.items.SleepNight>>> =
        items.observe(Kinds.SLEEP, com.shrey.buddy.data.items.SleepNight.serializer()).map { l -> l.sortedBy { it.date } }.state(emptyList())
    val heart: StateFlow<List<Item<com.shrey.buddy.data.items.HeartDay>>> =
        items.observe(Kinds.HEART_DAY, com.shrey.buddy.data.items.HeartDay.serializer()).map { l -> l.sortedBy { it.date } }.state(emptyList())
    val water: StateFlow<Map<LocalDate, Int>> =
        items.observe(Kinds.WATER, com.shrey.buddy.data.items.WaterDay.serializer())
            .map { l -> l.mapNotNull { i -> i.date?.let { it to i.data.ml } }.toMap() }.state(emptyMap())

    private val _sources = MutableStateFlow<Set<String>>(emptySet())
    /** Watch / scale apps that have written to Health Connect recently. */
    val sources = _sources.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    fun syncWatch(days: Long = 7) = viewModelScope.launch {
        _syncing.value = true
        runCatching { c.healthSync.sync(days) }
        _sources.value = runCatching { c.healthSync.sources() }.getOrDefault(emptySet())
        _syncing.value = false
    }

    fun addWater(ml: Int) = viewModelScope.launch { com.shrey.buddy.data.health.Nudges.addWater(c.context, ml) }

    // ---- Medicines (daily repeating tasks with call-style reminders, in a "Health" plan) ----
    val medicines: StateFlow<List<com.shrey.buddy.data.db.TaskEntity>> = c.tasks.observeTasks()
        .map { l -> l.filter { it.title.startsWith(PILL) } }.state(emptyList())

    fun addMedicine(name: String, dose: String, times: List<java.time.LocalTime>) = viewModelScope.launch {
        val plan = c.db.planDao().all().firstOrNull { it.name == "Health" } ?: c.tasks.addPlan("Health", "teal")
        val zone = ZoneId.systemDefault()
        times.distinct().sorted().forEach { t ->
            var at = LocalDate.now().atTime(t)
            if (at.isBefore(java.time.LocalDateTime.now())) at = at.plusDays(1)
            val ms = at.atZone(zone).toInstant().toEpochMilli()
            c.tasks.add(
                title = "$PILL ${name.trim()}${if (dose.isNotBlank()) " · ${dose.trim()}" else ""}",
                planId = plan?.id,
                dueAt = ms, dueAllDay = false, remindAt = ms,
                repeat = com.shrey.buddy.data.tasks.RepeatRule.DAILY.encode(),
            )
        }
    }
    fun takeDose(task: com.shrey.buddy.data.db.TaskEntity) = viewModelScope.launch { c.tasks.toggleDone(task) }
    fun stopMedicine(task: com.shrey.buddy.data.db.TaskEntity) = viewModelScope.launch { c.tasks.delete(task) }

    // ---- Coach: AI plans, weekly report ----
    val plans: StateFlow<List<Item<com.shrey.buddy.data.items.TrainingPlan>>> =
        items.observe(Kinds.TRAINING_PLAN, com.shrey.buddy.data.items.TrainingPlan.serializer())
            .map { l -> l.sortedByDescending { it.entity.createdAt } }.state(emptyList())
    val reports: StateFlow<List<Item<com.shrey.buddy.data.items.HealthReport>>> =
        items.observe(Kinds.HEALTH_REPORT, com.shrey.buddy.data.items.HealthReport.serializer())
            .map { l -> l.sortedByDescending { it.date } }.state(emptyList())

    private val _coachBusy = MutableStateFlow<String?>(null)
    /** What the coach is doing right now ("Building your plan…"), or null. */
    val coachBusy = _coachBusy.asStateFlow()
    private val _coachError = MutableStateFlow<String?>(null)
    val coachError = _coachError.asStateFlow()
    private val _draft = MutableStateFlow<com.shrey.buddy.data.items.TrainingPlan?>(null)
    /** A generated plan waiting for "Start plan". */
    val draft = _draft.asStateFlow()

    fun generatePlan(r: com.shrey.buddy.data.health.Coach.PlanRequest) = viewModelScope.launch {
        _coachBusy.value = "Building your plan…"
        _coachError.value = null
        _draft.value = try { c.coach.generate(r) { step -> _coachBusy.value = step } } catch (e: Exception) { _coachError.value = e.message ?: "Couldn't build the plan"; null }
        _coachBusy.value = null
    }
    fun discardDraft() { _draft.value = null }
    /** "Start plan": only now do sessions, reminders and routines get created. */
    fun activateDraft(setup: com.shrey.buddy.data.health.Coach.Setup) = viewModelScope.launch {
        val plan = _draft.value ?: return@launch
        _coachBusy.value = "Adding sessions, reminders and routines…"
        runCatching { c.coach.activate(plan, setup) }.onFailure { _coachError.value = it.message }
        _draft.value = null
        _coachBusy.value = null
    }
    fun stopPlan(item: Item<com.shrey.buddy.data.items.TrainingPlan>) = viewModelScope.launch { c.coach.stop(item.id, item.data) }
    fun deletePlan(id: String) = viewModelScope.launch {
        plans.value.firstOrNull { it.id == id }?.let { if (it.data.active) c.coach.stop(it.id, it.data) else c.coach.removeRoutines(it.data) }
        items.delete(id)
    }

    fun weeklyReport() = viewModelScope.launch {
        _coachBusy.value = "Looking at your week…"
        _coachError.value = null
        runCatching { c.coach.weeklyReport() }.onFailure { _coachError.value = it.message ?: "Couldn't write the report" }
        _coachBusy.value = null
    }

    /** Starts a gym session from a plan's strength day, matching exercise names to the library. */
    fun startPlanSession(s: com.shrey.buddy.data.items.PlanSession) {
        session.start(s.title, strengthHistory(), com.shrey.buddy.data.health.Coach.toRoutine(c.context, s))
    }

    /** All health data in one go (badges, streaks). */
    suspend fun healthData() = com.shrey.buddy.data.health.HealthData.load(items)

    // ---- Gym sessions & routines ----
    val session = WorkoutSession()
    val routines: StateFlow<List<Item<com.shrey.buddy.data.items.Routine>>> =
        items.observe(Kinds.ROUTINE, com.shrey.buddy.data.items.Routine.serializer())
            .map { list -> list.sortedBy { it.data.name.lowercase() } }.state(emptyList())

    /** Past gym sessions, newest first (for "last time" hints and records). */
    fun strengthHistory(): List<Workout> = workouts.value
        .filter { it.data.exercises.isNotEmpty() }
        .sortedByDescending { it.data.startedAt.takeIf { s -> s > 0 } ?: (it.date?.toEpochDay()?.times(86_400_000) ?: 0) }
        .map { it.data }

    fun startWorkout(title: String, routine: Item<com.shrey.buddy.data.items.Routine>? = null) {
        session.start(title, strengthHistory(), routine?.data, routine?.id)
    }

    /** Saves the session; [onSaved] gets the new id and any personal records it set. */
    fun finishWorkout(effort: String, saveAsRoutine: Boolean, onSaved: (String?, List<com.shrey.buddy.health.StrengthMath.Record>) -> Unit) = viewModelScope.launch {
        val w = session.toWorkout(effort, latestKg.value)
        if (w == null || w.exercises.isEmpty()) {
            session.clear()
            onSaved(null, emptyList())
            return@launch
        }
        val records = com.shrey.buddy.health.StrengthMath.newRecords(w, strengthHistory())
        if (saveAsRoutine) session.asRoutine()?.let { items.put(Kinds.ROUTINE, it, com.shrey.buddy.data.items.Routine.serializer()) }
        val day = Instant.ofEpochMilli(w.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val id = items.put(Kinds.WORKOUT, w, Workout.serializer(), occurredOn = day)
        runCatching { c.coach.completeToday("strength") }
        session.clear()
        onSaved(id, records)
    }

    fun discardWorkout() = session.clear()

    fun saveRoutine(id: String?, routine: com.shrey.buddy.data.items.Routine) =
        viewModelScope.launch { items.put(Kinds.ROUTINE, routine, com.shrey.buddy.data.items.Routine.serializer(), id = id) }
    fun deleteRoutine(id: String) = viewModelScope.launch { items.delete(id) }

    fun renameWorkout(item: Item<Workout>, title: String) = viewModelScope.launch {
        items.put(Kinds.WORKOUT, item.data.copy(title = title), Workout.serializer(), id = item.id)
    }

    fun discardRun() = RunTrackerService.reset()
}
