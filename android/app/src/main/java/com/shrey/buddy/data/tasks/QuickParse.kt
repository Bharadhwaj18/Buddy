package com.shrey.buddy.data.tasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Plain-English quick add, fully offline: "Pay rent 5th every month 10am" →
 * title "Pay rent", due the 5th at 10:00, monthly, reminder at 10:00.
 *
 * Recognised bits are cut out of the title; anything unrecognised stays in it.
 */
object QuickParse {
    data class Result(
        val title: String,
        val date: LocalDate? = null,
        val time: LocalTime? = null,
        val repeat: RepeatRule? = null,
        /** Ring at the due time — said "remind me", or gave a time. */
        val remind: Boolean = false,
    ) {
        val hasDetails get() = date != null || time != null || repeat != null || remind
        fun dueAt(): LocalDateTime? = date?.let { d -> time?.let { d.atTime(it) } }
    }

    private val days = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "tuesday" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val months = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL, "may" to Month.MAY,
        "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )
    private const val DAY = "(mon|monday|tue|tues|tuesday|wed|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday|sun|sunday)"
    private const val MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*"
    private const val ORD = "(\\d{1,2})(?:st|nd|rd|th)"

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Result {
        var text = " ${input.trim()} "
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var repeat: RepeatRule? = null
        var remind = false
        var partOfDay: LocalTime? = null

        /** Finds [pattern] (case-insensitive, whole words), removes it from the text, and hands over the match. */
        fun take(pattern: String, onMatch: (MatchResult) -> Unit): Boolean {
            val m = Regex("(?i)(?<=\\s)$pattern(?=[\\s,.!?])").find(text) ?: return false
            onMatch(m)
            text = text.removeRange(m.range).replace(Regex("\\s+"), " ").let { " ${it.trim()} " }
            return true
        }

        take("(remind me to|remind me|reminder:?|remember to)") { remind = true }

        // Repeats (some also fix the date).
        take("every (\\d{1,3}|other) (days|day|weeks|week|months|month|years|year)") { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 2
            val unit = m.groupValues[2].lowercase()
            repeat = RepeatRule(
                when {
                    unit.startsWith("day") -> RepeatRule.Freq.DAILY
                    unit.startsWith("week") -> RepeatRule.Freq.WEEKLY
                    unit.startsWith("month") -> RepeatRule.Freq.MONTHLY
                    else -> RepeatRule.Freq.YEARLY
                },
                interval = n,
            )
        }
        take("(every ?day|daily|every morning|every evening|every night)") { m ->
            repeat = RepeatRule.DAILY
            partOfDay = partOfDayTime(m.value.lowercase())
        }
        take("(every weekday|on weekdays|weekdays)") { repeat = RepeatRule.WEEKDAYS_RULE }
        // "every mon", "every mon, wed and fri"
        take("every $DAY((?:,? ?(?:and |& )?$DAY)*)") { m ->
            val picked = Regex("(?i)$DAY").findAll(m.value).map { days.getValue(it.groupValues[1].lowercase()) }.toSet()
            repeat = RepeatRule(RepeatRule.Freq.WEEKLY, days = if (picked.size > 1) picked else emptySet())
            date = repeat!!.firstOnOrAfter(today).let { first ->
                if (picked.size == 1) today.with(TemporalAdjusters.nextOrSame(picked.first())) else first
            }
        }
        take("(every week|weekly)") { if (repeat == null) repeat = RepeatRule.WEEKLY }
        take("every $ORD(?: of (?:the|every) month)?") { m ->
            repeat = RepeatRule.MONTHLY
            date = nextDayOfMonth(today, m.groupValues[1].toInt())
        }
        take("(every month|monthly)") { if (repeat == null) repeat = RepeatRule.MONTHLY }
        take("(every year|yearly|annually)") { if (repeat == null) repeat = RepeatRule.YEARLY }

        // Relative: "in 20 minutes", "in another 10 mins", "after 2 hours", "in half an hour",
        // "in an hour and a half", "10 minutes from now", "in a couple of days".
        fun relative(amount: String, unit: String, andHalf: Boolean) {
            val a = amount.lowercase().trim()
            val n: Double = when {
                a.startsWith("half") -> 0.5
                a == "a" || a == "an" || a == "one" -> 1.0
                a.startsWith("a couple") || a == "couple of" || a == "two" -> 2.0
                a == "a few" || a == "few" || a == "three" -> 3.0
                a == "five" -> 5.0
                a == "ten" -> 10.0
                a == "fifteen" -> 15.0
                a == "twenty" -> 20.0
                a == "thirty" -> 30.0
                else -> a.toDoubleOrNull() ?: return
            } + if (andHalf) 0.5 else 0.0
            fun at(t: LocalDateTime) { date = t.toLocalDate(); time = t.toLocalTime().withSecond(0).withNano(0) }
            when (unit.lowercase().first()) {
                's' -> at(now.plusMinutes(1))
                'm' -> at(now.plusSeconds((n * 60).toLong()))
                'h' -> at(now.plusMinutes((n * 60).toLong()))
                'd' -> date = today.plusDays(n.toLong().coerceAtLeast(1))
                'w' -> date = today.plusWeeks(n.toLong().coerceAtLeast(1))
            }
        }
        val amount = "(\\d{1,3}(?:\\.\\d)?|half an?|an?|one|two|three|five|ten|fifteen|twenty|thirty|a couple of|a few)"
        val unit = "(seconds|second|secs|sec|minutes|minute|mins|min|m|hours|hour|hrs|hr|h|days|day|d|weeks|week|w)"
        val matchedRelative = take("(?:in|after|within)(?: another| about| around| the next| just)? $amount ?$unit( and a half)?(?: from now| time)?") { m ->
            relative(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty())
        }
        if (!matchedRelative) take("$amount ?(seconds|second|secs|sec|minutes|minute|mins|min|hours|hour|hrs|hr|days|day|weeks|week)( and a half)? (?:from now|later)") { m ->
            relative(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty())
        }

        // Dates.
        take("day after tomorrow") { date = today.plusDays(2) }
        take("(tomorrow|tmrw|tmr|tomo)") { date = today.plusDays(1) }
        take("tonight") { date = today; partOfDay = LocalTime.of(21, 0) }
        take("today") { date = today }
        take("next week") { date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) }
        take("next $DAY") { m -> date = today.with(TemporalAdjusters.next(days.getValue(m.groupValues[1].lowercase()))) }
        take("(?:on |this )?$DAY") { m -> date = today.with(TemporalAdjusters.nextOrSame(days.getValue(m.groupValues[1].lowercase()))) }
        val dayMonth = take("(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH") { m ->
            date = futureDate(today, months.getValue(m.groupValues[2].lowercase()), m.groupValues[1].toInt())
        }
        if (!dayMonth) take("(?:on )?$MONTH (\\d{1,2})(?:st|nd|rd|th)?") { m ->
            date = futureDate(today, months.getValue(m.groupValues[1].lowercase()), m.groupValues[2].toInt())
        }
        if (date == null) take("(?:on )?(?:the )?$ORD") { m -> date = nextDayOfMonth(today, m.groupValues[1].toInt()) }

        // Times.
        take("(?:at |by )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)") { m ->
            var h = m.groupValues[1].toInt() % 12
            if (m.groupValues[3].lowercase().startsWith("p")) h += 12
            time = LocalTime.of(h.coerceIn(0, 23), m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59))
        }
        if (time == null) take("(?:at |by )?([01]?\\d|2[0-3]):([0-5]\\d)") { m ->
            time = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        if (time == null) take("(?:at |by )(\\d{1,2})") { m ->
            // "at 7": the next 7 o'clock — 7 am if it's still ahead today, otherwise 7 pm. 1–6 means pm.
            val h = m.groupValues[1].toInt()
            time = when {
                h > 12 && h < 24 -> LocalTime.of(h, 0)
                h in 1..6 -> LocalTime.of(h + 12, 0)
                date == null || date == today -> if (LocalTime.of(h % 12, 0).isAfter(now.toLocalTime())) LocalTime.of(h % 12, 0) else LocalTime.of((h % 12) + 12, 0)
                else -> LocalTime.of(h % 12, 0)
            }
        }
        if (time == null) take("noon") { time = LocalTime.NOON }
        if (time == null) take("midnight") { time = LocalTime.of(23, 59) }
        take("(?:in the |this )?(morning|afternoon|evening|night)") { m -> partOfDay = partOfDayTime(m.groupValues[1].lowercase()) }
        if (time == null) time = partOfDay

        // A time with no date means the next time it comes round.
        if (time != null && date == null) date = if (time!!.isAfter(now.toLocalTime())) today else today.plusDays(1)
        // "Remind me…" with no time: 9 am on the day, or an hour from now if that's already gone.
        if (remind && time == null) {
            val d = date ?: today
            time = if (d == today && !LocalTime.of(9, 0).isAfter(now.toLocalTime())) now.plusHours(1).toLocalTime().withMinute(0).withSecond(0).withNano(0) else LocalTime.of(9, 0)
            if (d == today && time!!.isBefore(now.toLocalTime())) date = today.plusDays(1) else date = d
        }
        // A repeat with no date starts from the next occurrence.
        if (repeat != null && date == null) {
            date = if (time == null || time!!.isAfter(now.toLocalTime())) today else today.plusDays(1)
        }
        // A repeat whose first time today has already gone starts at the following occurrence
        // ("rent 5th every month 10am" typed at 11 am on the 5th → next month).
        if (repeat != null && date == today && time != null && !time!!.isAfter(now.toLocalTime())) {
            date = repeat!!.next(today.atTime(time)).toLocalDate()
        }

        val title = text.trim().replace(Regex("(?i)\\s+(at|on|by|in|every|the)$"), "").trim()
            // "remind me in 10 min to call mom" leaves "to call mom".
            .let { if (remind) it.replace(Regex("(?i)^(to|that|about)\\s+"), "") else it }
            .replaceFirstChar { it.uppercase() }
        if (title.isEmpty()) return Result(input.trim())
        return Result(title, date, time, repeat, remind = remind || time != null)
    }

    private fun partOfDayTime(word: String): LocalTime? = when {
        "morning" in word -> LocalTime.of(9, 0)
        "afternoon" in word -> LocalTime.of(14, 0)
        "evening" in word -> LocalTime.of(18, 0)
        "night" in word -> LocalTime.of(21, 0)
        else -> null
    }

    private fun nextDayOfMonth(today: LocalDate, day: Int): LocalDate {
        val thisMonth = today.withDayOfMonth(day.coerceIn(1, today.lengthOfMonth()))
        if (!thisMonth.isBefore(today)) return thisMonth
        val next = today.plusMonths(1)
        return next.withDayOfMonth(day.coerceIn(1, next.lengthOfMonth()))
    }

    private fun futureDate(today: LocalDate, month: Month, day: Int): LocalDate {
        val year = today.year
        val d = LocalDate.of(year, month, day.coerceIn(1, month.length(java.time.Year.isLeap(year.toLong()))))
        return if (d.isBefore(today)) d.plusYears(1) else d
    }
}
