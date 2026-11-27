package com.shrey.buddy.data.tasks

import java.time.LocalDate
import java.time.LocalTime

/**
 * Finds a clock time in typed or dictated text, in every form people use:
 *   2pm · 2 pm · 2PM · 2 p.m. · 2:30pm · 2.30 pm · 2 30 pm · 230pm · 2:30 · 14:30 · 1430 hrs · 09:00
 *   at 2 · by 4 · @4pm · around 7 · till 6 · at 2.30 · half past 2 · quarter past 3 · quarter to 5
 *   10 past 4 · 20 to 6 · 2 o'clock · two thirty pm · five pm · noon · 12 noon · midday · midnight
 *   2 in the afternoon · 8 in the morning · 9 at night · 7 tonight · 6 this evening
 * Without am/pm the hour comes from the words around it (morning / evening / tonight), else:
 * 12 → noon, 1–6 → pm, 7–11 → the next one to come today (am on a later day).
 * The laptop's TypeScript copy (laptop/src/lib/when.ts) follows the same rules; both are checked
 * against shared/time-cases.json.
 */
object Clock {
    data class Found(
        val range: IntRange,
        val hour: Int,
        val minute: Int,
        /** 'a' or 'p' when said; null otherwise. */
        val meridiem: Char?,
        /** 24-hour time as written ("14:30", "09:00", "1430 hrs"): used as is. */
        val exact: Boolean,
        /** morning / afternoon / evening / night said right next to it. */
        val part: String?,
    )

    private val WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )
    private val MINUTE_WORDS = mapOf(
        "oh five" to 5, "o five" to 5, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "twenty five" to 25,
        "thirty" to 30, "thirty five" to 35, "forty" to 40, "forty five" to 45, "fifty" to 50, "fifty five" to 55,
    )

    private const val CUE_WORDS = "(?:at|by|@|around|about|till|until|before|from)"
    private const val CUE = "(?:$CUE_WORDS\\s?)?"
    private const val CUE_REQ = "$CUE_WORDS\\s?"
    private const val HOUR = "(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    /**
     * Two groups: "am" / "p.m." (spaced or not). The second is always empty, kept so group numbers stay put
     * (a bare "2a" / "2p" is not read as a time: "flat 2a").
     */
    private const val MER = "(?:\\s?(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)())"
    /** Two groups: a day part, or "tonight". */
    private const val PART = "(?:\\s(?:in the |at |this )?(morning|afternoon|evening|night)|\\s(tonight))?"
    private const val MINW = "(oh five|o five|twenty[- ]five|thirty[- ]five|forty[- ]five|fifty[- ]five|ten|fifteen|twenty|thirty|forty|fifty)"

    /** Not a time when one of these follows: "about 5 minutes", "by 2 kg", "at 3 people". */
    private const val UNITS = "mins?|minutes?|hours?|hrs?|days?|weeks?|months?|years?|secs?|seconds?|kgs?|kms?|g|gms?|grams?|litres?|liters?|l|ml|rs|rupees|bucks|people|persons|times|pages?|pcs|pieces|items"

    private class Form(pattern: String, val read: (MatchResult) -> Found?) {
        val regex = Regex("(?i)(?<=\\s)$pattern(?=[\\s,!?;)]|\\.(?:\\s|$)|$)(?!\\s(?:$UNITS)\\b|%)")
    }

    private fun hourOf(s: String): Int? = s.toIntOrNull() ?: WORDS[s.lowercase()]
    private fun minuteWord(s: String): Int? = MINUTE_WORDS[s.lowercase().replace('-', ' ')]
    private fun mer(m: MatchResult, i: Int): Char? =
        (m.groupValues[i].ifEmpty { m.groupValues[i + 1] }).firstOrNull()?.lowercaseChar()
    private fun part(m: MatchResult, i: Int): String? =
        m.groupValues[i].ifEmpty { null }?.lowercase() ?: m.groupValues[i + 1].ifEmpty { null }?.let { "night" }
    private fun leadingZero(raw: String) = raw.length == 2 && raw.startsWith("0")

    private val forms = listOf(
        // half past 2, quarter past 3 pm
        Form("$CUE(half|quarter) past $HOUR$MER?$PART") { m ->
            val h = hourOf(m.groupValues[2]) ?: return@Form null
            Found(m.range, h, if (m.groupValues[1].equals("half", true)) 30 else 15, mer(m, 3), false, part(m, 5))
        },
        // quarter to 5
        Form("${CUE}quarter to $HOUR$MER?$PART") { m ->
            val h = hourOf(m.groupValues[1]) ?: return@Form null
            Found(m.range, if (h == 1) 12 else h - 1, 45, mer(m, 2), false, part(m, 4))
        },
        // 10 past 4, 20 to 6, twenty five past 3
        Form("$CUE(\\d{1,2}|five|ten|twenty|twenty[- ]five) (?:minutes? |mins? )?(past|after|to) $HOUR$MER?$PART") { m ->
            val n = m.groupValues[1].toIntOrNull() ?: minuteWord(m.groupValues[1]) ?: return@Form null
            val h = hourOf(m.groupValues[3]) ?: return@Form null
            if (n !in 1..59) return@Form null
            if (m.groupValues[2].equals("to", true)) Found(m.range, if (h == 1) 12 else h - 1, 60 - n, mer(m, 4), false, part(m, 6))
            else Found(m.range, h, n, mer(m, 4), false, part(m, 6))
        },
        // two thirty pm, at 7 forty five (before "five pm" below can take the end of it)
        Form("$CUE$HOUR $MINW$MER?$PART") { m ->
            val h = hourOf(m.groupValues[1]) ?: return@Form null
            val min = minuteWord(m.groupValues[2]) ?: return@Form null
            // A digit before a number word ("buy 2 ten rupee stamps") needs a cue or am / pm to count.
            val cued = Regex("(?i)^$CUE_REQ").containsMatchIn(m.value)
            if (m.groupValues[1].toIntOrNull() != null && !cued && mer(m, 3) == null) return@Form null
            Found(m.range, h, min, mer(m, 3), false, part(m, 5))
        },
        // 2pm, 2:30 pm, 2.30pm, 2 30 pm, 2 o'clock pm, two pm
        Form("$CUE$HOUR(?:[:.](\\d{2})|\\s(\\d{2})(?=\\s?[ap]))?(?:\\s?o'?\\s?clock)?$MER$PART") { m ->
            val h = hourOf(m.groupValues[1]) ?: return@Form null
            val min = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { "0" }.toInt()
            Found(m.range, h, min, mer(m, 4), false, part(m, 6))
        },
        // 230pm, 1030am
        Form("$CUE(\\d{1,2})(\\d{2})$MER$PART") { m ->
            Found(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), mer(m, 3), false, part(m, 5))
        },
        // 1430 hrs, 0900 hours
        Form("$CUE([01]\\d|2[0-3])([0-5]\\d)\\s?(?:hrs|hours|h)") { m ->
            Found(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, true, null)
        },
        // 14:30, 2:30, 09:00, 14:30 hrs
        Form("$CUE(\\d{1,2}):(\\d{2})(\\s?(?:hrs|hours|h))?$PART") { m ->
            val raw = m.groupValues[1]
            Found(m.range, raw.toInt(), m.groupValues[2].toInt(), null, leadingZero(raw) || raw.toInt() > 12 || m.groupValues[3].isNotEmpty(), part(m, 4))
        },
        // 14.30 hrs
        Form("$CUE(\\d{1,2})\\.(\\d{2})\\s?(?:hrs|hours)") { m ->
            Found(m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, true, null)
        },
        // at 2.30 (a dot needs a cue word, so "2.50 kg" isn't a time)
        Form("$CUE_REQ(\\d{1,2})\\.(\\d{2})$PART") { m ->
            val raw = m.groupValues[1]
            Found(m.range, raw.toInt(), m.groupValues[2].toInt(), null, leadingZero(raw) || raw.toInt() > 12, part(m, 3))
        },
        // 2 o'clock
        Form("$CUE$HOUR\\s?o'?\\s?clock$PART") { m ->
            val h = hourOf(m.groupValues[1]) ?: return@Form null
            Found(m.range, h, 0, null, false, part(m, 2))
        },
        // noon, 12 noon, midday, midnight
        Form("$CUE(?:12\\s?)?(noon|midday|midnight)") { m ->
            if (m.groupValues[1].equals("midnight", true)) Found(m.range, 23, 59, null, true, null)
            else Found(m.range, 12, 0, null, true, null)
        },
        // at 7, by 4, around 6 tonight
        Form("$CUE_REQ$HOUR$PART") { m ->
            val raw = m.groupValues[1]
            val h = hourOf(raw) ?: return@Form null
            Found(m.range, h, 0, null, leadingZero(raw) || h > 12, part(m, 2))
        },
        // 2 in the afternoon, 7 tonight, 9 at night
        Form("$HOUR(?:\\s(?:in the |at |this )(morning|afternoon|evening|night)|\\s(tonight))") { m ->
            val h = hourOf(m.groupValues[1]) ?: return@Form null
            Found(m.range, h, 0, null, false, part(m, 2))
        },
    )

    /** The first clock time in [text] (which starts and ends with a space), or null. */
    fun find(text: String): Found? {
        for (f in forms) {
            for (m in f.regex.findAll(text)) {
                val found = f.read(m) ?: continue
                if (found.hour !in 0..23 || found.minute !in 0..59) continue
                if (found.meridiem != null && (found.hour == 0 || found.hour > 12)) continue
                return found
            }
        }
        return null
    }

    /** The time of day [f] means, given a day part said elsewhere and the day it's for. */
    fun resolve(f: Found, dayPart: String?, date: LocalDate?, today: LocalDate, now: LocalTime): LocalTime {
        var h = f.hour
        val m = f.minute
        val part = f.part ?: dayPart
        when {
            f.meridiem == 'a' -> if (h == 12) h = 0
            f.meridiem == 'p' -> if (h < 12) h += 12
            f.exact || h == 0 || h > 12 -> Unit
            part == "morning" -> if (h == 12) h = 0
            part == "afternoon" || part == "evening" -> if (h < 12) h += 12
            part == "night" -> when {
                h == 12 -> return LocalTime.of(23, 59)
                h in 1..4 -> Unit
                else -> h += 12
            }
            h == 12 -> Unit
            h in 1..6 -> h += 12
            // 7–11: the next one to come today; on a later day, the morning one.
            (date == null || date == today) && !LocalTime.of(h, m).isAfter(now) -> h += 12
        }
        return LocalTime.of(h, m)
    }
}
