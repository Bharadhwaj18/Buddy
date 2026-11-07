package com.shrey.buddy.data.health

import com.shrey.buddy.data.items.BodyProfile
import com.shrey.buddy.data.items.HeartDay
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.SleepNight
import com.shrey.buddy.data.items.StepsDay
import com.shrey.buddy.data.items.WaterDay
import com.shrey.buddy.data.items.WeightEntry
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.StrengthMath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Everything health, loaded once — for the AI coach, the weekly report and badges. */
data class HealthData(
    val profile: BodyProfile,
    val weights: List<Item<WeightEntry>>,
    val workouts: List<Item<Workout>>,
    val steps: Map<LocalDate, StepsDay>,
    val sleep: Map<LocalDate, SleepNight>,
    val heart: Map<LocalDate, HeartDay>,
    val water: Map<LocalDate, Int>,
) {
    val latestKg get() = weights.maxByOrNull { it.date ?: LocalDate.MIN }?.data?.kg

    companion object {
        suspend fun load(items: ItemStore) = HealthData(
            profile = items.all(Kinds.BODY_PROFILE, BodyProfile.serializer()).firstOrNull()?.data ?: BodyProfile(),
            weights = items.all(Kinds.WEIGHT, WeightEntry.serializer()),
            workouts = items.all(Kinds.WORKOUT, Workout.serializer()),
            steps = items.all(Kinds.STEPS_DAY, StepsDay.serializer()).mapNotNull { i -> i.date?.let { it to i.data } }.toMap(),
            sleep = items.all(Kinds.SLEEP, SleepNight.serializer()).mapNotNull { i -> i.date?.let { it to i.data } }.toMap(),
            heart = items.all(Kinds.HEART_DAY, HeartDay.serializer()).mapNotNull { i -> i.date?.let { it to i.data } }.toMap(),
            water = items.all(Kinds.WATER, WaterDay.serializer()).mapNotNull { i -> i.date?.let { it to i.data.ml } }.toMap(),
        )
    }
}

object HealthStats {
    private fun inRange(d: LocalDate?, from: LocalDate, until: LocalDate) = d != null && !d.isBefore(from) && d.isBefore(until)

    /** Current fitness numbers for the AI plan builder — aggregates only, nothing identifying. */
    fun forPlan(h: HealthData, today: LocalDate = LocalDate.now()): JsonObject {
        val p = h.profile
        val runs = h.workouts.filter { it.data.type == "run" && it.data.run != null }
        val last4w = today.minusWeeks(4)
        val recentRuns = runs.filter { (it.date ?: LocalDate.MIN) >= last4w }
        val gym = h.workouts.filter { it.data.exercises.isNotEmpty() && (it.date ?: LocalDate.MIN) >= last4w }
        fun bestLift(vararg names: String) = h.workouts.flatMap { it.data.exercises }
            .filter { e -> names.any { e.name.contains(it, ignoreCase = true) } }
            .mapNotNull { StrengthMath.bestSet(it) }.maxOfOrNull { StrengthMath.e1rm(it.weightKg, it.reps) }?.roundToInt()
        val steps30 = (0 until 30).mapNotNull { h.steps[today.minusDays(it.toLong())]?.steps }
        return buildJsonObject {
            p.birthYear?.let { put("age", today.year - it) }
            p.sex?.let { put("sex", it) }
            p.heightCm?.let { put("height_cm", it.roundToInt()) }
            h.latestKg?.let { put("weight_kg", (it * 10).roundToInt() / 10.0) }
            p.goalWeightKg?.let { put("goal_weight_kg", it) }
            put("activity_level", p.activity)
            put("runs_per_week_last_4w", (recentRuns.size / 4.0 * 10).roundToInt() / 10.0)
            put("run_km_per_week_last_4w", (recentRuns.sumOf { it.data.run!!.distanceM } / 1000 / 4 * 10).roundToInt() / 10.0)
            runs.maxOfOrNull { it.data.run!!.distanceM }?.let { put("longest_run_km", (it / 100).roundToInt() / 10.0) }
            runs.mapNotNull { it.data.run!!.best5kS }.minOrNull()?.let { put("best_5k_min", (it / 6.0).roundToInt() / 10.0) }
            put("gym_sessions_per_week_last_4w", (gym.size / 4.0 * 10).roundToInt() / 10.0)
            bestLift("bench press")?.let { put("bench_e1rm_kg", it) }
            bestLift("squat")?.let { put("squat_e1rm_kg", it) }
            bestLift("deadlift")?.let { put("deadlift_e1rm_kg", it) }
            if (steps30.isNotEmpty()) put("avg_daily_steps", steps30.average().roundToInt())
        }
    }

    /** This week (Mon–today) vs last week, for the weekly report. */
    fun forWeeklyReport(h: HealthData, today: LocalDate = LocalDate.now()): JsonObject {
        val thisMon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastMon = thisMon.minusWeeks(1)
        fun week(from: LocalDate, until: LocalDate) = buildJsonObject {
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(until) && !it.isAfter(today) }.toList()
            val steps = days.mapNotNull { h.steps[it]?.steps }
            if (steps.isNotEmpty()) put("avg_steps", steps.average().roundToInt())
            val w = h.workouts.filter { inRange(it.date, from, until) }
            put("workouts", w.size)
            put("run_km", (w.mapNotNull { it.data.run?.distanceM }.sum() / 100).roundToInt() / 10.0)
            put("gym_volume_kg", w.sumOf { StrengthMath.volume(it.data) }.roundToInt())
            put("active_kcal", w.sumOf { it.data.kcal + (it.data.run?.kcal ?: 0) })
            val sl = days.mapNotNull { h.sleep[it]?.minutes }
            if (sl.isNotEmpty()) put("avg_sleep_h", (sl.average() / 6).roundToInt() / 10.0)
            val rhr = days.mapNotNull { h.heart[it]?.restingBpm }
            if (rhr.isNotEmpty()) put("avg_resting_hr", rhr.average().roundToInt())
            val wa = days.mapNotNull { h.water[it] }
            if (wa.isNotEmpty()) put("avg_water_l", (wa.average() / 100).roundToInt() / 10.0)
            val kg = h.weights.filter { inRange(it.date, from, until) }.map { it.data.kg }
            if (kg.isNotEmpty()) put("avg_weight_kg", (kg.average() * 10).roundToInt() / 10.0)
            put("days_counted", days.size)
        }
        return buildJsonObject {
            put("this_week", week(thisMon, thisMon.plusWeeks(1)))
            put("last_week", week(lastMon, thisMon))
            h.profile.goalWeightKg?.let { put("goal_weight_kg", it) }
        }
    }
}
