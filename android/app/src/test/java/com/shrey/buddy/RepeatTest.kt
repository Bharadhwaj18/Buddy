package com.shrey.buddy

import com.shrey.buddy.data.tasks.RepeatRule
import com.shrey.buddy.data.tasks.RepeatRule.Freq
import com.shrey.buddy.data.tasks.nextOccurrenceTimes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class RepeatTest {
    private val z = ZoneOffset.UTC
    private fun ms(d: LocalDateTime) = d.toInstant(z).toEpochMilli()

    @Test fun `weekdays skip the weekend`() {
        val fri = LocalDateTime.of(2026, 10, 2, 9, 0) // Friday
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 0), RepeatRule.WEEKDAYS_RULE.next(fri))
    }

    @Test fun `monthly clamps to month length`() {
        assertEquals(LocalDateTime.of(2026, 2, 28, 8, 0), RepeatRule.MONTHLY.next(LocalDateTime.of(2026, 1, 31, 8, 0)))
    }

    @Test fun `picked weekdays`() {
        val rule = RepeatRule(Freq.WEEKLY, days = setOf(MONDAY, WEDNESDAY, FRIDAY))
        val mon = LocalDateTime.of(2026, 10, 5, 6, 0)
        assertEquals(LocalDateTime.of(2026, 10, 7, 6, 0), rule.next(mon))
        assertEquals(LocalDateTime.of(2026, 10, 12, 6, 0), rule.next(LocalDateTime.of(2026, 10, 9, 6, 0)))
        assertEquals(LocalDate.of(2026, 10, 7), rule.firstOnOrAfter(LocalDate.of(2026, 10, 6))) // Tue → Wed
    }

    @Test fun `every 2 weeks on monday and friday`() {
        val rule = RepeatRule(Freq.WEEKLY, interval = 2, days = setOf(MONDAY, FRIDAY))
        val mon = LocalDateTime.of(2026, 10, 5, 7, 0)
        assertEquals(LocalDateTime.of(2026, 10, 9, 7, 0), rule.next(mon))
        assertEquals(LocalDateTime.of(2026, 10, 19, 7, 0), rule.next(LocalDateTime.of(2026, 10, 9, 7, 0))) // skips a week
    }

    @Test fun `last day of the month and every 3 days`() {
        val last = RepeatRule(Freq.MONTHLY, monthDay = -1)
        assertEquals(LocalDateTime.of(2026, 2, 28, 9, 0), last.next(LocalDateTime.of(2026, 1, 31, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 3, 31, 9, 0), last.next(LocalDateTime.of(2026, 2, 28, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 8, 9, 0), RepeatRule(Freq.DAILY, interval = 3).next(LocalDateTime.of(2026, 10, 5, 9, 0)))
    }

    @Test fun `encode and parse round trip, legacy keys still read`() {
        val rule = RepeatRule(Freq.WEEKLY, interval = 2, days = setOf(MONDAY, WEDNESDAY))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", rule.encode())
        assertEquals(rule, RepeatRule.parse(rule.encode()))
        assertEquals(RepeatRule.WEEKDAYS_RULE, RepeatRule.parse("weekdays"))
        assertEquals(RepeatRule.DAILY, RepeatRule.parse("FREQ=DAILY"))
        assertEquals(null, RepeatRule.parse("nonsense"))
    }

    @Test fun `next keeps the reminder offset and skips past dates`() {
        val due = LocalDateTime.of(2026, 10, 1, 18, 0)
        val remind = due.minusMinutes(30)
        val now = ms(LocalDateTime.of(2026, 10, 3, 12, 0)) // completed two days late
        val (nDue, nRemind) = nextOccurrenceTimes(RepeatRule.DAILY, ms(due), ms(remind), now, z)
        assertEquals(ms(LocalDateTime.of(2026, 10, 3, 18, 0)), nDue)
        assertEquals(ms(LocalDateTime.of(2026, 10, 3, 17, 30)), nRemind)
    }

    @Test fun `labels say when it repeats`() {
        val sun7pm = LocalDateTime.of(2026, 10, 4, 19, 0)
        assertEquals("Daily at 7:00 pm", RepeatRule.DAILY.describe(sun7pm, withTime = true))
        assertEquals("Every Sun at 7:00 pm", RepeatRule.WEEKLY.describe(sun7pm, withTime = true))
        assertEquals("Mon, Wed, Fri at 7:00 pm", RepeatRule(Freq.WEEKLY, days = setOf(FRIDAY, MONDAY, WEDNESDAY)).describe(sun7pm, true))
        assertEquals("Weekdays at 7:00 pm", RepeatRule.WEEKDAYS_RULE.describe(sun7pm, true))
        assertEquals("Every 2 weeks on Sun", RepeatRule(Freq.WEEKLY, interval = 2).describe(sun7pm, false))
        assertEquals("Monthly on the 4th", RepeatRule.MONTHLY.describe(sun7pm, withTime = false))
        assertEquals("Monthly on the last day", RepeatRule(Freq.MONTHLY, monthDay = -1).describe(sun7pm, false))
        assertEquals("Yearly on 4 Oct", RepeatRule.YEARLY.describe(sun7pm, withTime = false))
        assertEquals(listOf("1st", "2nd", "3rd", "11th", "12th", "13th", "21st", "22nd"), listOf(1, 2, 3, 11, 12, 13, 21, 22).map(RepeatRule::ordinal))
    }

    @Test fun `reminder-only task repeats its reminder`() {
        val r = LocalDateTime.of(2026, 10, 3, 7, 0)
        val (nDue, nRemind) = nextOccurrenceTimes(RepeatRule.WEEKLY, null, ms(r), ms(r), z)
        assertEquals(null, nDue)
        assertEquals(ms(LocalDateTime.of(2026, 10, 10, 7, 0)), nRemind)
    }
}
