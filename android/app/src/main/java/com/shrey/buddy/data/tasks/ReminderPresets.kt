package com.shrey.buddy.data.tasks

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Quick reminder choices relative to the due date/time. */
enum class ReminderPreset(val label: String, val forTimed: Boolean) {
    AT_TIME("At due time", true),
    MIN_10("10 min before", true),
    MIN_30("30 min before", true),
    HOUR_1("1 hr before", true),
    DAY_1("1 day before", true),
    MORNING_OF("9 am that day", false),
    EVENING_BEFORE("8 pm the day before", false);

    fun at(dueDate: LocalDate, dueTime: LocalTime?): LocalDateTime? = when (this) {
        AT_TIME -> dueTime?.let { dueDate.atTime(it) }
        MIN_10 -> dueTime?.let { dueDate.atTime(it).minusMinutes(10) }
        MIN_30 -> dueTime?.let { dueDate.atTime(it).minusMinutes(30) }
        HOUR_1 -> dueTime?.let { dueDate.atTime(it).minusHours(1) }
        DAY_1 -> dueTime?.let { dueDate.atTime(it).minusDays(1) }
        MORNING_OF -> dueDate.atTime(9, 0)
        EVENING_BEFORE -> dueDate.minusDays(1).atTime(20, 0)
    }

    companion object {
        fun available(dueTime: LocalTime?) = entries.filter { it.forTimed == (dueTime != null) }

        /** Which preset (if any) produces [remindAt] for this due date/time. */
        fun match(remindAt: Long?, dueDate: LocalDate?, dueTime: LocalTime?, zone: ZoneId = ZoneId.systemDefault()): ReminderPreset? {
            if (remindAt == null || dueDate == null) return null
            return available(dueTime).firstOrNull { it.at(dueDate, dueTime)?.atZone(zone)?.toInstant()?.toEpochMilli() == remindAt }
        }
    }
}
