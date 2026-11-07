package com.shrey.buddy.data.tasks

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * How a task repeats. Stored as a small iCalendar RRULE subset, e.g.
 * `FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE,FR` or `FREQ=MONTHLY;BYMONTHDAY=-1` (last day).
 * The older plain keys (daily, weekdays, weekly, monthly, yearly) are still read.
 *
 * The time of day isn't part of the rule — it's the task's due/reminder time.
 */
data class RepeatRule(
    val freq: Freq,
    val interval: Int = 1,
    /** WEEKLY only; empty = the same weekday as the task's date. */
    val days: Set<DayOfWeek> = emptySet(),
    /** MONTHLY only; null = same day as the task's date, -1 = last day of the month. */
    val monthDay: Int? = null,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    fun encode(): String = buildList {
        add("FREQ=${freq.name}")
        if (interval > 1) add("INTERVAL=$interval")
        if (freq == Freq.WEEKLY && days.isNotEmpty()) add("BYDAY=" + days.sorted().joinToString(",") { it.code() })
        if (freq == Freq.MONTHLY && monthDay != null) add("BYMONTHDAY=$monthDay")
    }.joinToString(";")

    /** The next occurrence strictly after [from] (which is itself an occurrence). Time of day is kept. */
    fun next(from: LocalDateTime): LocalDateTime = when (freq) {
        Freq.DAILY -> from.plusDays(interval.toLong())
        Freq.WEEKLY -> if (days.isEmpty()) from.plusWeeks(interval.toLong()) else nextWeekday(from)
        Freq.MONTHLY -> {
            val month = from.toLocalDate().plusMonths(interval.toLong())
            val day = when (val d = monthDay) {
                null -> from.dayOfMonth
                -1 -> month.lengthOfMonth()
                else -> d
            }
            month.withDayOfMonth(day.coerceIn(1, month.lengthOfMonth())).atTime(from.toLocalTime())
        }
        Freq.YEARLY -> from.plusYears(interval.toLong())
    }

    private fun nextWeekday(from: LocalDateTime): LocalDateTime {
        val anchorWeek = from.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var d = from.toLocalDate().plusDays(1)
        repeat(7 * interval + 7) {
            val week = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeksApart = ChronoUnit.WEEKS.between(anchorWeek, week)
            if (d.dayOfWeek in days && weeksApart % interval == 0L) return d.atTime(from.toLocalTime())
            d = d.plusDays(1)
        }
        return from.plusWeeks(interval.toLong())
    }

    /** First occurrence on or after [date] — e.g. picking "Mon, Wed" on a Tuesday starts Wednesday. */
    fun firstOnOrAfter(date: LocalDate): LocalDate = when {
        freq == Freq.WEEKLY && days.isNotEmpty() -> generateSequence(date) { it.plusDays(1) }.first { it.dayOfWeek in days }
        freq == Freq.MONTHLY && monthDay != null -> {
            fun inMonth(m: LocalDate) = if (monthDay == -1) m.withDayOfMonth(m.lengthOfMonth()) else m.withDayOfMonth(monthDay.coerceIn(1, m.lengthOfMonth()))
            inMonth(date).let { if (it.isBefore(date)) inMonth(date.plusMonths(1)) else it }
        }
        else -> date
    }

    /** "Daily at 7:30 am", "Mon, Wed, Fri at 6:00 am", "Every 2 weeks on Sun", "Monthly on the last day". */
    fun describe(anchor: LocalDateTime?, withTime: Boolean): String {
        val at = if (withTime && anchor != null) " at " + anchor.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase(Locale.ENGLISH) else ""
        val every = if (interval > 1) "Every $interval " else ""
        return when (freq) {
            Freq.DAILY -> if (interval > 1) "Every $interval days$at" else "Daily$at"
            Freq.WEEKLY -> {
                val dayNames = when {
                    days == WEEKDAYS -> "weekdays"
                    days.size == 7 -> "every day"
                    days.isNotEmpty() -> days.sorted().joinToString(", ") { it.short() }
                    anchor != null -> anchor.dayOfWeek.short()
                    else -> ""
                }
                when {
                    interval > 1 -> "${every}weeks on $dayNames$at"
                    days == WEEKDAYS -> "Weekdays$at"
                    days.isNotEmpty() && days.size > 1 -> "$dayNames$at"
                    else -> "Every $dayNames$at"
                }
            }
            Freq.MONTHLY -> {
                val day = when (val d = monthDay ?: anchor?.dayOfMonth) {
                    null -> ""
                    -1 -> " on the last day"
                    else -> " on the ${ordinal(d)}"
                }
                (if (interval > 1) "Every $interval months" else "Monthly") + day + at
            }
            Freq.YEARLY -> (if (interval > 1) "Every $interval years" else "Yearly") +
                (anchor?.let { " on " + it.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) } ?: "") + at
        }
    }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        val DAILY = RepeatRule(Freq.DAILY)
        val WEEKDAYS_RULE = RepeatRule(Freq.WEEKLY, days = WEEKDAYS)
        val WEEKLY = RepeatRule(Freq.WEEKLY)
        val MONTHLY = RepeatRule(Freq.MONTHLY)
        val YEARLY = RepeatRule(Freq.YEARLY)

        fun parse(raw: String?): RepeatRule? {
            if (raw.isNullOrBlank()) return null
            when (raw.trim().lowercase()) {
                "daily" -> return DAILY
                "weekdays" -> return WEEKDAYS_RULE
                "weekly" -> return WEEKLY
                "monthly" -> return MONTHLY
                "yearly" -> return YEARLY
            }
            val parts = raw.removePrefix("RRULE:").split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
            val freq = parts["FREQ"]?.let { f -> runCatching { Freq.valueOf(f.uppercase()) }.getOrNull() } ?: return null
            return RepeatRule(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull()?.coerceIn(1, 365) ?: 1,
                days = parts["BYDAY"]?.split(',')?.mapNotNull { dayFromCode(it.trim()) }?.toSet().orEmpty(),
                monthDay = parts["BYMONTHDAY"]?.toIntOrNull()?.takeIf { it == -1 || it in 1..31 },
            )
        }

        private val codes = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        private fun DayOfWeek.code() = codes.getValue(this)
        private fun dayFromCode(c: String) = codes.entries.firstOrNull { it.value == c.uppercase() }?.key

        fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

        fun ordinal(n: Int): String = n.toString() + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
    }
}

/**
 * Next instance of a repeating task: due date and reminder both move forward by the rule, keeping the
 * gap between them. If the task was completed late, it keeps advancing until the date is in the future,
 * so you never get a stack of already-overdue copies.
 */
fun nextOccurrenceTimes(
    rule: RepeatRule,
    dueAt: Long?,
    remindAt: Long?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<Long?, Long?> {
    val anchor = dueAt ?: remindAt ?: return null to null
    val offset = if (dueAt != null && remindAt != null) remindAt - dueAt else 0L
    var next = rule.next(LocalDateTime.ofInstant(Instant.ofEpochMilli(anchor), zone))
    while (next.atZone(zone).toInstant().toEpochMilli() + offset <= now) next = rule.next(next)
    val nextAnchor = next.atZone(zone).toInstant().toEpochMilli()
    return if (dueAt != null) nextAnchor to remindAt?.let { nextAnchor + offset } else null to nextAnchor
}
