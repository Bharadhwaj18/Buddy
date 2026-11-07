package com.shrey.buddy

import com.shrey.buddy.data.items.ExerciseEntry
import com.shrey.buddy.data.items.SetEntry
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.StrengthMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthMathTest {
    private fun bench(vararg sets: Pair<Double, Int>, warmup: Boolean = false) =
        ExerciseEntry("Bench Press", sets.map { (kg, r) -> SetEntry(r, kg, kind = if (warmup) "warmup" else "normal") })

    @Test fun `volume skips warm-ups and unfinished sets`() {
        val e = ExerciseEntry(
            "Squat",
            listOf(SetEntry(10, 40.0, kind = "warmup"), SetEntry(5, 100.0), SetEntry(5, 100.0, done = false)),
        )
        assertEquals(500.0, StrengthMath.volume(e), 0.01)
    }

    @Test fun `epley 1rm`() {
        assertEquals(100.0, StrengthMath.e1rm(100.0, 1), 0.01)
        assertEquals(116.7, StrengthMath.e1rm(100.0, 5), 0.1)
    }

    @Test fun `records only beat earlier sessions`() {
        val old = Workout(exercises = listOf(bench(60.0 to 8, 62.5 to 6)))
        val now = Workout(exercises = listOf(bench(65.0 to 5)))
        val recs = StrengthMath.newRecords(now, listOf(old))
        assertEquals(1, recs.size)
        assertEquals("Heaviest", recs[0].what)
        // First time doing an exercise isn't a record.
        assertTrue(StrengthMath.newRecords(now, emptyList()).isEmpty())
        // Same weight, more reps → better estimated 1RM.
        val moreReps = StrengthMath.newRecords(Workout(exercises = listOf(bench(62.5 to 9))), listOf(old))
        assertEquals("Best est. 1RM", moreReps.single().what)
    }

    @Test fun `calories from MET and weight`() {
        // 60 min moderate strength at 80 kg: 5.0 × 80 × 1 = 400
        assertEquals(400, StrengthMath.kcal("strength", "moderate", 60, 80.0))
        assertTrue(StrengthMath.kcal("strength", "hard", 60, 80.0) > 400)
    }
}
