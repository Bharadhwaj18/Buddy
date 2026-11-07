package com.shrey.buddy

import com.shrey.buddy.data.tasks.QuickParse
import com.shrey.buddy.data.tasks.RepeatRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class QuickParseTest {
    // Monday 5 Oct 2026, 2:30 pm.
    private val now = LocalDateTime.of(2026, 10, 5, 14, 30)
    private fun p(s: String) = QuickParse.parse(s, now)

    private fun rel(s: String, title: String, expected: LocalDateTime) {
        val r = p(s)
        assertEquals(s, title, r.title)
        assertEquals(s, expected, r.dueAt())
        assertTrue(s, r.remind)
    }

    @Test fun `relative times in the ways people say them`() {
        rel("remind me to call mom in another 10 mins", "Call mom", now.plusMinutes(10))
        rel("remind me to call mom in 10 minutes", "Call mom", now.plusMinutes(10))
        rel("remind me in 10 min to call mom", "Call mom", now.plusMinutes(10))
        rel("remind me to check the oven after 20 minutes", "Check the oven", now.plusMinutes(20))
        rel("remind me to stretch 15 minutes from now", "Stretch", now.plusMinutes(15))
        rel("remind me to leave in half an hour", "Leave", now.plusMinutes(30))
        rel("remind me to take the pill in an hour", "Take the pill", now.plusHours(1))
        rel("remind me to call back in an hour and a half", "Call back", now.plusMinutes(90))
        rel("remind me to move the car in about 5 minutes", "Move the car", now.plusMinutes(5))
        rel("remind me to drink water in 2 hours", "Drink water", now.plusHours(2))
        rel("remind me to check mail in a couple of hours", "Check mail", now.plusHours(2))
    }

    @Test fun `in a meeting is not a time`() = assertEquals("Call sam in a meeting room", p("call sam in a meeting room").title)

    @Test fun `plain title is untouched`() {
        val r = p("buy milk")
        assertEquals("Buy milk", r.title)
        assertFalse(r.hasDetails)
    }

    @Test fun `monthly rent on the 5th at 10am`() {
        val r = p("Pay rent 5th every month 10am")
        assertEquals("Pay rent", r.title)
        assertEquals(RepeatRule.MONTHLY, r.repeat)
        assertEquals(LocalDate.of(2026, 11, 5), r.date) // 10 am on the 5th has passed (it's 2:30 pm)
        assertEquals(LocalTime.of(10, 0), r.time)
        assertTrue(r.remind)
    }

    @Test fun `repeat still ahead today starts today`() {
        val early = QuickParse.parse("Pay rent 5th every month 10am", now.withHour(8))
        assertEquals(LocalDate.of(2026, 10, 5), early.date)
    }

    @Test fun `remind me tomorrow at 7pm`() {
        val r = p("remind me to call mom tomorrow at 7pm")
        assertEquals("Call mom", r.title)
        assertEquals(LocalDate.of(2026, 10, 6), r.date)
        assertEquals(LocalTime.of(19, 0), r.time)
        assertTrue(r.remind)
    }

    @Test fun `every sunday evening`() {
        val r = p("Call grandma every sunday evening")
        assertEquals("Call grandma", r.title)
        assertEquals(RepeatRule.WEEKLY, r.repeat)
        assertEquals(LocalDate.of(2026, 10, 11), r.date)
        assertEquals(LocalTime.of(18, 0), r.time)
    }

    @Test fun `in 20 minutes`() {
        val r = p("take pizza out in 20 min")
        assertEquals("Take pizza out", r.title)
        assertEquals(LocalDate.of(2026, 10, 5), r.date)
        assertEquals(LocalTime.of(14, 50), r.time)
    }

    @Test fun `bare hour picks the next one`() {
        assertEquals(LocalTime.of(19, 0), p("gym at 7").time) // 7 am already gone today
        assertEquals(LocalTime.of(15, 0), p("call at 3").time) // small numbers mean pm
    }

    @Test fun `24h time and time already passed rolls to tomorrow`() {
        val r = p("standup 09:45")
        assertEquals(LocalTime.of(9, 45), r.time)
        assertEquals(LocalDate.of(2026, 10, 6), r.date)
    }

    @Test fun `dates by name`() {
        assertEquals(LocalDate.of(2026, 10, 9), p("submit report friday").date)
        assertEquals(LocalDate.of(2026, 10, 12), p("dentist next monday").date)
        assertEquals(LocalDate.of(2026, 12, 25), p("gifts 25 dec").date)
        assertEquals(LocalDate.of(2027, 3, 1), p("renew insurance mar 1").date)
    }

    @Test fun `daily and weekdays`() {
        val r = p("take vitamins daily 9am")
        assertEquals(RepeatRule.DAILY, r.repeat)
        assertEquals(LocalDate.of(2026, 10, 6), r.date) // 9 am today already passed
        assertEquals(RepeatRule.WEEKDAYS_RULE, p("standup every weekday 10:00").repeat)
    }

    @Test fun `custom repeats`() {
        val gym = p("gym every mon, wed and fri 6am")
        assertEquals("Gym", gym.title)
        assertEquals(RepeatRule(RepeatRule.Freq.WEEKLY, days = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)), gym.repeat)
        assertEquals(LocalDate.of(2026, 10, 7), gym.date) // 6 am Monday has gone → Wednesday
        assertEquals(RepeatRule(RepeatRule.Freq.WEEKLY, interval = 2), p("clean fridge every 2 weeks").repeat)
        assertEquals(RepeatRule(RepeatRule.Freq.DAILY, interval = 2), p("water plants every other day").repeat)
    }

    @Test fun `remind me with no time defaults sensibly`() {
        val r = p("remind me to water plants")
        assertEquals("Water plants", r.title)
        assertEquals(LocalTime.of(15, 0), r.time) // next full hour, since 9 am has gone
        assertEquals(LocalDate.of(2026, 10, 5), r.date)
    }

    @Test fun `numbers inside the title survive`() {
        val r = p("pay 500 to ravi")
        assertEquals("Pay 500 to ravi", r.title)
        assertNull(r.date)
    }
}
