package com.shrey.buddy

import com.shrey.buddy.ai.Facts
import com.shrey.buddy.ai.LocalAnswers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAnswersTest {
    private val f = Facts(
        spentToday = 45_000, spentYesterday = 120_000, spentWeek = 600_000, spentMonth = 2_500_000, spentLastMonth = 4_000_000,
        spentLastMonthSamePoint = 2_000_000, incomeMonth = 9_000_000, topCategoriesMonth = listOf("Food" to 900_000),
        accounts = listOf("IndusInd" to 12_345_600), budgets = emptyList(), tasksToday = listOf("Pay rent"), tasksOverdue = emptyList(),
        remindersToday = emptyList(), openTasks = 3, stepsToday = 6400, stepsYesterday = 11000, steps7dAvg = 8000, stepGoal = 10_000,
        sleepLastNightMin = 410, workoutsThisWeek = 2, runKmThisWeek = 5.2, weightKg = 72.4, waterTodayMl = 1250, restingHr = 62,
        habitsToday = emptyList(),
    )

    @Test fun `simple questions are answered on the phone`() {
        assertTrue(LocalAnswers.answer("How much did I spend today?", f)!!.contains("450"))
        assertTrue(LocalAnswers.answer("how much have i spent this month so far", f)!!.contains("25,000"))
        assertNotNull(LocalAnswers.answer("What are my account balances?", f))
        assertTrue(LocalAnswers.answer("steps today?", f)!!.contains("6,400"))
        assertNotNull(LocalAnswers.answer("what's due today", f))
    }

    @Test fun `narrower questions go to the AI`() {
        assertNull(LocalAnswers.answer("How much did I spend on food this month?", f))
        assertNull(LocalAnswers.answer("How much did I spend at Swiggy this week?", f))
        assertNull(LocalAnswers.answer("How much did I spend last month?", f))
        assertNull(LocalAnswers.answer("Where did most of my money go last month?", f))
    }
}
