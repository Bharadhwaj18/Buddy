package com.shrey.buddy.data.health

import com.shrey.buddy.health.StrengthMath
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class Badge(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    /** 0..1 */
    val progress: Float,
    /** e.g. "3 / 5 km" */
    val progressLabel: String,
) {
    val earned get() = progress >= 1f
}

data class Streak(val emoji: String, val title: String, val current: Int, val best: Int, val unit: String)

/** Badges and streaks, computed from the data (nothing extra stored). */
object Achievements {
    private fun frac(v: Double, target: Double) = (v / target).coerceIn(0.0, 1.0).toFloat()

    fun badges(h: HealthData, stepGoal: Int): List<Badge> {
        val runs = h.workouts.filter { it.data.type == "run" && it.data.run != null }
        val longest = runs.maxOfOrNull { it.data.run!!.distanceM } ?: 0.0
        val totalKm = h.workouts.mapNotNull { it.data.run?.distanceM }.sum() / 1000
        val gym = h.workouts.count { it.data.exercises.isNotEmpty() }
        val allWorkouts = h.workouts.size
        val bestSteps = h.steps.values.maxOfOrNull { it.steps } ?: 0
        val stepBest = streaks(h, stepGoal).first { it.title == "Step goal" }.best
        val sleep7 = bestRun(h.sleep.filterValues { it.minutes >= 420 }.keys)
        val volume = h.workouts.sumOf { StrengthMath.volume(it.data) }
        val weightGoal = h.profile.goalWeightKg
        val firstKg = h.weights.minByOrNull { it.date ?: LocalDate.MAX }?.data?.kg
        val reached = weightGoal != null && h.latestKg != null && firstKg != null &&
            ((firstKg > weightGoal && h.latestKg!! <= weightGoal) || (firstKg < weightGoal && h.latestKg!! >= weightGoal))
        fun km(v: Double) = "%.1f".format(v)
        return listOf(
            Badge("first_run", "👟", "First run", "Record your first run", frac(runs.size.toDouble(), 1.0), "${runs.size.coerceAtMost(1)} / 1"),
            Badge("5k", "🥉", "5K finisher", "Run 5 km in one go", frac(longest, 5000.0), "${km(longest / 1000)} / 5 km"),
            Badge("10k", "🥈", "10K finisher", "Run 10 km in one go", frac(longest, 10_000.0), "${km(longest / 1000)} / 10 km"),
            Badge("half", "🥇", "Half marathon", "Run 21.1 km in one go", frac(longest, 21_097.0), "${km(longest / 1000)} / 21.1 km"),
            Badge("100km", "🛣️", "100 km club", "Cover 100 km running, walking or riding", frac(totalKm, 100.0), "${km(totalKm)} / 100 km"),
            Badge("first_gym", "🏋️", "First lift", "Log your first gym session", frac(gym.toDouble(), 1.0), "${gym.coerceAtMost(1)} / 1"),
            Badge("gym25", "💪", "25 gym sessions", "Show up 25 times", frac(gym.toDouble(), 25.0), "$gym / 25"),
            Badge("workouts100", "🏆", "Century", "100 workouts of any kind", frac(allWorkouts.toDouble(), 100.0), "$allWorkouts / 100"),
            Badge("tonne", "🧱", "Lifted 100 tonnes", "Total gym volume of 100,000 kg", frac(volume, 100_000.0), "%,.0f / 100,000 kg".format(volume)),
            Badge("steps10k", "🚶", "10k day", "Walk 10,000 steps in a day", frac(bestSteps.toDouble(), 10_000.0), "%,d / 10,000".format(bestSteps)),
            Badge("steps20k", "🔥", "20k day", "Walk 20,000 steps in a day", frac(bestSteps.toDouble(), 20_000.0), "%,d / 20,000".format(bestSteps)),
            Badge("stepweek", "📅", "Goal week", "Hit your step goal 7 days in a row", frac(stepBest.toDouble(), 7.0), "$stepBest / 7 days"),
            Badge("stepmonth", "🗓️", "Goal month", "Hit your step goal 30 days in a row", frac(stepBest.toDouble(), 30.0), "$stepBest / 30 days"),
            Badge("sleep7", "😴", "Well rested", "7+ hours of sleep, 7 nights in a row", frac(sleep7.toDouble(), 7.0), "$sleep7 / 7 nights"),
            Badge("weightgoal", "🎯", "Goal weight", "Reach your goal weight", if (reached) 1f else 0f, if (weightGoal == null) "Set a goal in Body" else if (reached) "Reached" else "Keep going"),
        )
    }

    fun streaks(h: HealthData, stepGoal: Int, today: LocalDate = LocalDate.now()): List<Streak> {
        val stepDays = h.steps.filterValues { it.steps >= stepGoal }.keys
        val waterDays = h.water.filterValues { it >= 2000 }.keys
        val sleepDays = h.sleep.filterValues { it.minutes >= 420 }.keys
        // Weeks with at least 2 workouts, counted back from this week.
        val weeksWithTwo = h.workouts.mapNotNull { it.date }.groupBy { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .filterValues { it.size >= 2 }.keys
        return listOf(
            Streak("🚶", "Step goal", current(stepDays, today), bestRun(stepDays), "days"),
            Streak("🏋️", "Active weeks", currentWeeks(weeksWithTwo, today), bestWeeks(weeksWithTwo), "weeks"),
            Streak("😴", "7h+ sleep", current(sleepDays, today), bestRun(sleepDays), "nights"),
            Streak("💧", "2 L water", current(waterDays, today), bestRun(waterDays), "days"),
        )
    }

    /** Consecutive days ending today (or yesterday if today isn't done yet). */
    fun current(days: Set<LocalDate>, today: LocalDate): Int {
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    fun bestRun(days: Set<LocalDate>): Int {
        var best = 0; var run = 0; var prev: LocalDate? = null
        days.sorted().forEach { d ->
            run = if (prev != null && d == prev!!.plusDays(1)) run + 1 else 1
            best = maxOf(best, run); prev = d
        }
        return best
    }

    private fun currentWeeks(weeks: Set<LocalDate>, today: LocalDate): Int {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var w = if (thisWeek in weeks) thisWeek else thisWeek.minusWeeks(1)
        var n = 0
        while (w in weeks) { n++; w = w.minusWeeks(1) }
        return n
    }

    private fun bestWeeks(weeks: Set<LocalDate>): Int {
        var best = 0; var run = 0; var prev: LocalDate? = null
        weeks.sorted().forEach { w ->
            run = if (prev != null && w == prev!!.plusWeeks(1)) run + 1 else 1
            best = maxOf(best, run); prev = w
        }
        return best
    }
}
