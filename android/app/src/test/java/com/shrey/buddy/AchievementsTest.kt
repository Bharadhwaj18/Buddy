package com.shrey.buddy

import com.shrey.buddy.data.health.Achievements
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AchievementsTest {
    private val today = LocalDate.of(2026, 10, 5)
    private fun days(vararg back: Long) = back.map { today.minusDays(it) }.toSet()

    @Test fun `current streak tolerates today not done yet`() {
        assertEquals(3, Achievements.current(days(1, 2, 3, 5), today)) // yesterday, 2 and 3 days ago
        assertEquals(4, Achievements.current(days(0, 1, 2, 3, 5), today))
        assertEquals(0, Achievements.current(days(2, 3), today))
    }

    @Test fun `best run anywhere in history`() {
        assertEquals(4, Achievements.bestRun(days(1, 2, 10, 11, 12, 13, 20)))
    }
}
