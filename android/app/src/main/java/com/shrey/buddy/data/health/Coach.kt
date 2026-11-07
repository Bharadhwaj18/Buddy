package com.shrey.buddy.data.health

import android.content.Context
import com.shrey.buddy.AppContainer
import com.shrey.buddy.data.items.HealthReport
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.PlanSession
import com.shrey.buddy.data.items.PlanWeek
import com.shrey.buddy.data.items.Routine
import com.shrey.buddy.data.items.RoutineExercise
import com.shrey.buddy.data.items.TrainingPlan
import com.shrey.buddy.health.ExerciseLibrary
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** AI training plans (→ Tasks, routines, reminders once you accept them) and the weekly health report. */
class Coach(private val c: AppContainer) {
    data class PlanRequest(
        val goal: String,
        val details: String,
        val days: Int,
        val experience: String,
        val equipment: String,
        val weeks: Int,
    )

    /** When and how an accepted plan goes into your day. */
    data class Setup(
        val start: LocalDate,
        /** Reminder time on Mon–Fri session days (null = no reminder). */
        val weekday: LocalTime? = null,
        val weekend: LocalTime? = null,
        /** Save each strength day ("Upper A") as a routine in Workouts. */
        val routines: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds the plan a few weeks at a time, so every session can be detailed and no single AI call has to
     * write the whole thing. [onProgress] gets "Writing weeks 4–6 of 8…". Nothing is saved: the result is a
     * draft until [activate].
     */
    suspend fun generate(r: PlanRequest, onProgress: (String) -> Unit = {}): TrainingPlan {
        val stats = HealthStats.forPlan(HealthData.load(c.items))
        var plan = TrainingPlan()
        var from = 1
        while (from <= r.weeks) {
            val to = minOf(from + BLOCK - 1, r.weeks)
            onProgress(if (from == 1) "Designing your plan: weeks 1–$to of ${r.weeks}…" else "Writing weeks $from–$to of ${r.weeks}…")
            val soFar = if (from == 1) null else buildJsonObject {
                put("title", JsonPrimitive(plan.title))
                put("outline", JsonArray(plan.outline.map { JsonPrimitive(it) }))
                plan.weeks.lastOrNull()?.let { put("last_week", json.encodeToJsonElement(PlanWeek.serializer(), it)) }
            }
            // The free AI tier is sometimes busy: give each block three goes before giving up.
            var part: TrainingPlan? = null
            var failure: Exception? = null
            for (attempt in 1..3) {
                try {
                    val p = c.ai.trainingPlan(
                        goal = goalLabels[r.goal] ?: r.goal, details = r.details, days = r.days, experience = r.experience,
                        equipment = r.equipment, weeks = r.weeks, stats = stats, fromWeek = from, toWeek = to, soFar = soFar,
                    )
                    if (p.error != null) throw IllegalStateException(p.error)
                    if (p.weeks.isEmpty()) throw IllegalStateException("The plan came back empty")
                    part = p
                    break
                } catch (e: Exception) {
                    failure = e
                    if (attempt < 3) {
                        onProgress("Buddy AI is busy, trying again ($attempt/3)…")
                        delay(4_000L * attempt)
                    }
                }
            }
            if (part == null) {
                // Keep what has been written rather than throwing away minutes of work.
                if (plan.weeks.isEmpty()) throw failure ?: IllegalStateException("Couldn't build the plan")
                val done = plan.weeks.size
                return plan.copy(goal = r.goal, tips = plan.tips + "Only weeks 1–$done could be written this time. Build a new plan for the rest when you get there.")
            }
            val weeks = part.weeks.sortedBy { it.week }.take(to - from + 1).mapIndexed { i, w -> w.copy(week = from + i) }
            plan = if (from == 1) part.copy(weeks = weeks) else plan.copy(weeks = plan.weeks + weeks)
            from = to + 1
        }
        return plan.copy(goal = r.goal)
    }

    /**
     * You accepted the plan: saves it, puts every session in Tasks (a "Training" plan) on the right dates from
     * the Monday of [Setup.start], rings at the chosen time on session days, and saves each strength day as a routine.
     */
    suspend fun activate(plan: TrainingPlan, setup: Setup): String {
        val monday = setup.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val taskPlan = c.db.planDao().all().firstOrNull { it.name == "Training" } ?: c.tasks.addPlan("Training", "orange")
        val zone = ZoneId.systemDefault()
        plan.weeks.forEach { w ->
            w.sessions.filter { it.type != "rest" }.forEach { s ->
                val day = monday.plusWeeks((w.week - 1).toLong()).plusDays(dayIndex(s.day).toLong())
                if (day.isBefore(LocalDate.now())) return@forEach
                val remindAt = if (day.dayOfWeek.value >= 6) setup.weekend else setup.weekday
                val due = (remindAt?.let { day.atTime(it) } ?: day.atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
                val task = c.tasks.add(
                    title = sessionTitle(s),
                    planId = taskPlan?.id,
                    dueAt = due,
                    dueAllDay = remindAt == null,
                    remindAt = if (remindAt != null) due else null,
                )
                task?.let { c.tasks.save(it.copy(notes = sessionNotes(w.focus, s))) }
            }
        }
        // Each distinct strength day becomes a routine: one tap in Workouts, with sets, reps, rest and starting loads.
        val planKey = UUID.randomUUID().toString()
        val routineIds = if (!setup.routines) emptyList() else plan.weeks.flatMap { it.sessions }
            .filter { it.type == "strength" && it.exercises.isNotEmpty() }
            .distinctBy { it.title.lowercase() }
            .map { s ->
                c.items.put(
                    Kinds.ROUTINE, toRoutine(c.context, s).copy(notes = "From plan: ${plan.title}"), Routine.serializer(),
                    id = ItemStore.stableId("planroutine:$planKey:${s.title.lowercase()}"),
                )
            }
        val saved = plan.copy(startDate = monday.toString(), active = true, taskPlanId = taskPlan?.id, routineIds = routineIds)
        return c.items.put(Kinds.TRAINING_PLAN, saved, TrainingPlan.serializer(), occurredOn = monday)
    }

    /** Stops a plan: removes its future sessions from Tasks (and their reminders) and the routines it made. */
    suspend fun stop(id: String, plan: TrainingPlan) {
        c.items.put(Kinds.TRAINING_PLAN, plan.copy(active = false, routineIds = emptyList()), TrainingPlan.serializer(), id = id)
        removeRoutines(plan)
        val titles = plan.weeks.flatMap { it.sessions }.map(::sessionTitle).toSet()
        val now = System.currentTimeMillis()
        c.db.taskDao().all()
            .filter { it.planId == plan.taskPlanId && it.status != "done" && it.title in titles && (it.dueAt ?: 0) >= now - 86_400_000 }
            .forEach { c.tasks.delete(it) }
    }

    suspend fun removeRoutines(plan: TrainingPlan) = plan.routineIds.forEach { id -> runCatching { c.items.delete(id) } }

    /** You just finished a workout ("strength") or a run ("run"): tick today's matching plan session off in Tasks. */
    suspend fun completeToday(type: String) {
        val emoji = if (type == "run") "🏃" else "🏋️"
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val planIds = c.items.all(Kinds.TRAINING_PLAN, TrainingPlan.serializer()).filter { it.data.active }.mapNotNull { it.data.taskPlanId }.toSet()
        if (planIds.isEmpty()) return
        c.db.taskDao().all()
            .filter { it.planId in planIds && it.status != "done" && it.deletedAt == null && it.title.startsWith(emoji) }
            .filter { t -> t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == today }
            .forEach { c.tasks.save(it.copy(status = "done")) }
    }

    suspend fun weeklyReport(today: LocalDate = LocalDate.now()): HealthReport {
        val data = HealthData.load(c.items)
        val report = c.ai.healthReport(HealthStats.forWeeklyReport(data, today))
        report.error?.let { throw IllegalStateException(it) }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        c.items.put(Kinds.HEALTH_REPORT, report, HealthReport.serializer(), id = ItemStore.stableId("health_report:$monday"), occurredOn = monday)
        return report
    }

    companion object {
        /** Weeks written per AI call. */
        private const val BLOCK = 3

        val goalLabels = linkedMapOf(
            "run_5k" to "Run a 5K", "run_10k" to "Run a 10K", "run_half" to "Half marathon",
            "muscle" to "Build muscle", "strength" to "Get stronger", "weight_loss" to "Lose weight", "fitness" to "General fitness",
        )
        private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        fun dayIndex(d: String) = days.indexOfFirst { d.startsWith(it, ignoreCase = true) }.coerceAtLeast(0)

        fun sessionTitle(s: PlanSession): String {
            val emoji = when (s.type) { "run" -> "🏃"; "strength" -> "🏋️"; "mobility" -> "🧘"; else -> "⚡" }
            val dist = s.distanceKm?.takeIf { it > 0 }?.let { " · %s km".format(if (it % 1.0 == 0.0) "%.0f".format(it) else "%.1f".format(it)) }.orEmpty()
            return "$emoji ${s.title}$dist"
        }

        fun exerciseLine(e: com.shrey.buddy.data.items.PlanExercise): String {
            val extra = listOfNotNull(e.load.ifBlank { null }, e.restS?.takeIf { it > 0 }?.let { "rest $it s" }).joinToString(", ")
            return "${e.name}: ${e.sets} × ${e.reps}" + if (extra.isEmpty()) "" else " ($extra)"
        }

        fun sessionNotes(focus: String, s: PlanSession): String = buildString {
            if (focus.isNotBlank()) appendLine("Week focus: $focus")
            listOfNotNull(s.durationMin?.let { "≈ $it min" }, s.intensity.ifBlank { null }).joinToString(" · ").ifBlank { null }?.let { appendLine(it) }
            if (s.warmup.isNotBlank()) appendLine("Warm-up: ${s.warmup}")
            if (s.details.isNotBlank()) appendLine(s.details)
            s.exercises.forEach { appendLine("• ${exerciseLine(it)}") }
            if (s.cooldown.isNotBlank()) appendLine("Cool-down: ${s.cooldown}")
        }.trim()

        /** A plan's strength day as a routine, with exercise names matched to the built-in library. */
        fun toRoutine(context: Context, s: PlanSession): Routine {
            val lib = ExerciseLibrary.all(context)
            return Routine(
                name = s.title,
                exercises = s.exercises.map { pe ->
                    val match = lib.firstOrNull { it.name.equals(pe.name, true) }
                        ?: lib.firstOrNull { it.name.contains(pe.name, true) || pe.name.contains(it.name, true) }
                    RoutineExercise(
                        name = match?.name ?: pe.name, exerciseId = match?.id, muscles = match?.primaryMuscles.orEmpty(),
                        sets = pe.sets.coerceIn(1, 10),
                        reps = Regex("\\d+").find(pe.reps)?.value?.toIntOrNull()?.coerceIn(1, 100) ?: 10,
                        restS = pe.restS?.coerceIn(0, 600) ?: 90,
                        notes = listOfNotNull(
                            pe.load.ifBlank { null }?.let { "Start: $it" },
                            pe.reps.takeIf { it.any { ch -> !ch.isDigit() } }?.let { "$it reps" },
                            pe.notes.ifBlank { null },
                        ).joinToString(" · "),
                    )
                },
            )
        }
    }
}
