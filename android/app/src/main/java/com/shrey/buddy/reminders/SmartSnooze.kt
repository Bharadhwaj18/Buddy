package com.shrey.buddy.reminders

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Snooze choices. Positive values are minutes; the negative codes are named moments, resolved when
 * tapped (so a notification posted at 6 pm still means "tonight" when tapped at 7).
 */
object SmartSnooze {
    const val TONIGHT = -1
    const val TOMORROW_MORNING = -2
    const val NEXT_WEEK = -3

    data class Choice(val code: Int, val label: String)

    /** The named options that make sense right now. */
    fun named(now: LocalDateTime = LocalDateTime.now()): List<Choice> = buildList {
        if (now.toLocalTime().isBefore(LocalTime.of(19, 30))) add(Choice(TONIGHT, "Tonight 8 pm"))
        add(Choice(TOMORROW_MORNING, "Tomorrow 9 am"))
        add(Choice(NEXT_WEEK, "Next Mon 9 am"))
    }

    fun until(code: Int, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val at = when (code) {
            TONIGHT -> now.toLocalDate().atTime(20, 0).let { if (it.isAfter(now)) it else now.plusHours(1) }
            TOMORROW_MORNING -> now.toLocalDate().plusDays(1).atTime(9, 0)
            NEXT_WEEK -> now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(9, 0)
            else -> now.plusMinutes(code.coerceAtLeast(1).toLong())
        }
        return at.atZone(zone).toInstant().toEpochMilli()
    }
}
