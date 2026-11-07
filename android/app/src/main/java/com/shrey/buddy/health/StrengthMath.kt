package com.shrey.buddy.health

import com.shrey.buddy.data.items.ExerciseEntry
import com.shrey.buddy.data.items.SetEntry
import com.shrey.buddy.data.items.Workout
import kotlin.math.roundToInt

/** Volume, estimated 1-rep max, personal records and calories for gym sessions. */
object StrengthMath {
    private fun SetEntry.counts() = done && kind != "warmup" && reps > 0

    /** Epley estimate of a one-rep max. */
    fun e1rm(weightKg: Double, reps: Int): Double = if (reps <= 1) weightKg else weightKg * (1 + reps / 30.0)

    fun volume(e: ExerciseEntry): Double = e.sets.filter { it.counts() }.sumOf { it.weightKg * it.reps }
    fun volume(w: Workout): Double = w.exercises.sumOf { volume(it) }
    fun workingSets(w: Workout): Int = w.exercises.sumOf { e -> e.sets.count { it.counts() } }

    fun bestSet(e: ExerciseEntry): SetEntry? = e.sets.filter { it.counts() && it.weightKg > 0 }.maxByOrNull { e1rm(it.weightKg, it.reps) }

    data class Record(val exercise: String, val what: String, val value: String)

    /**
     * New records in [session] compared with [history] (earlier sessions): heaviest weight, best
     * estimated 1RM, and most reps at a weight — per exercise.
     */
    fun newRecords(session: Workout, history: List<Workout>): List<Record> {
        val out = mutableListOf<Record>()
        session.exercises.forEach { e ->
            val past = history.flatMap { w -> w.exercises.filter { same(it, e) } }.flatMap { it.sets.filter { s -> s.counts() } }
            val now = e.sets.filter { it.counts() && it.weightKg > 0 }
            if (now.isEmpty() || past.isEmpty()) return@forEach // first time isn't a "record"
            val heaviest = now.maxOf { it.weightKg }
            if (heaviest > past.maxOf { it.weightKg }) out += Record(e.name, "Heaviest", "%s kg".format(fmt(heaviest)))
            val best = now.maxOf { e1rm(it.weightKg, it.reps) }
            if (best > past.maxOf { e1rm(it.weightKg, it.reps) } + 0.01 && heaviest <= past.maxOf { it.weightKg }) {
                out += Record(e.name, "Best est. 1RM", "%s kg".format(fmt(best)))
            }
        }
        return out
    }

    fun same(a: ExerciseEntry, b: ExerciseEntry) =
        (a.exerciseId != null && a.exerciseId == b.exerciseId) || a.name.equals(b.name, ignoreCase = true)

    /** The last time this exercise was done, for "previous" hints while logging. */
    fun previous(name: String, exerciseId: String?, history: List<Workout>): ExerciseEntry? =
        history.firstNotNullOfOrNull { w -> w.exercises.firstOrNull { same(it, ExerciseEntry(name, exerciseId = exerciseId)) } }

    /**
     * Calories by MET (Compendium of Physical Activities): resistance training 3.5 (easy) / 5.0
     * (moderate) / 6.0 (hard); other activity types use their own METs.
     */
    fun met(type: String, effort: String): Double = when (type) {
        "strength" -> when (effort) { "easy" -> 3.5; "hard" -> 6.0; else -> 5.0 }
        "yoga" -> when (effort) { "easy" -> 2.5; "hard" -> 4.0; else -> 3.0 }
        "sport" -> when (effort) { "easy" -> 5.0; "hard" -> 8.0; else -> 6.5 }
        "walk" -> 3.5
        "run" -> 9.8
        "cycle" -> 7.5
        else -> when (effort) { "easy" -> 3.0; "hard" -> 7.0; else -> 5.0 }
    }

    fun kcal(type: String, effort: String, minutes: Int, kg: Double?): Int =
        (met(type, effort) * (kg ?: 70.0) * minutes / 60.0).roundToInt()

    fun fmt(kg: Double) = if (kg % 1.0 == 0.0) "%.0f".format(kg) else "%.1f".format(kg)
}
