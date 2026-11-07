package com.shrey.buddy.voice

import com.shrey.buddy.data.tasks.QuickParse
import com.shrey.buddy.data.tasks.RepeatRule
import com.shrey.buddy.finance.Categorizer
import com.shrey.buddy.finance.Category
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/**
 * Offline, keyword-first understanding of what you said to Buddy — no AI call when the words make it clear:
 *
 *  - **expense**  "spent 450 on lunch yesterday", "paid 1200 to Ramesh from SBI", "uber 230"
 *  - **income**   "got 5000 from dad", "received salary 85000", "refund of 499"
 *  - **reminder** "remind me to call mom at 7", "set an alarm for 6 am", "wake me up at 6"
 *  - **task**     "add task pay rent on the 5th", "I need to book tickets tomorrow", "buy milk"
 *  - **note**     "note: wifi password is on the router", "make a note that…"
 *  - **question** "how much did I spend today?", "what's due tomorrow"
 *
 * Returns null when it isn't sure — the caller then asks Gemini.
 */
object VoiceIntent {
    enum class Kind { EXPENSE, INCOME, REMINDER, TASK, NOTE, QUESTION }

    data class Result(
        val kind: Kind,
        /** A trigger word was said ("remind me", "add task", "spent 450"); chat only acts on these. */
        val explicit: Boolean,
        val amountMinor: Long? = null,
        val payee: String? = null,
        val category: Category? = null,
        val account: String? = null,
        val date: LocalDate? = null,
        val time: LocalTime? = null,
        val title: String = "",
        val body: String = "",
        val repeat: RepeatRule? = null,
        val remind: Boolean = false,
    )

    fun parse(input: String, accounts: List<String> = emptyList(), now: LocalDateTime = LocalDateTime.now()): Result? {
        val text = normalise(input)
        if (text.isBlank()) return null
        val cased = normalise(input, keepCase = true)

        val result = when {
            note(text) != null -> note(text)!!
            // "did I set a reminder…" is a question; "remind me…" (even "can you remind me…") is not.
            QUESTION_START.containsMatchIn(text) -> Result(Kind.QUESTION, explicit = true, title = input.trim())
            REMINDER.containsMatchIn(text) -> reminder(text, now)
            input.trim().endsWith("?") -> Result(Kind.QUESTION, explicit = true, title = input.trim())
            else -> money(text, accounts, now) ?: task(text, now) ?: return null
        }
        return when (result.kind) {
            Kind.TASK, Kind.REMINDER, Kind.NOTE -> result.copy(title = restoreCase(result.title, cased), body = restoreCase(result.body, cased))
            else -> result
        }
    }

    // ---- notes ----

    private val NOTE = Regex("^(?:please )?(?:make a note|take a note|add a note|save a note|new note|note down|note that|note|write down|jot down|jot)(?: that| of| saying)?:?\\s+(.+)$")

    private fun note(text: String): Result? {
        val body = NOTE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pretty = body.replaceFirstChar { it.uppercase() }
        val words = pretty.split(' ')
        val title = if (words.size <= 6) pretty else words.take(6).joinToString(" ") + "…"
        return Result(Kind.NOTE, explicit = true, title = title, body = pretty)
    }

    // ---- reminders & tasks (dates/times via the same parser as quick add) ----

    private val REMINDER = Regex("\\b(remind me|reminder|alarm|wake me|ping me|don'?t let me forget|alert me)\\b")

    private fun reminder(text: String, now: LocalDateTime): Result {
        val phrased = text
            .replace(Regex("^(?:set|add|create|make|put) (?:a |an |me a )?(?:reminder|alarm)(?: for me)?(?: to| for| that)?\\b"), "remind me to")
            .replace(Regex("\\b(?:wake me up|wake me)\\b"), "remind me to wake up")
            .replace(Regex("\\b(?:ping me|alert me|don'?t let me forget)(?: to| about)?\\b"), "remind me to")
            .replace(Regex("\\bremind me (?:about|of)\\b"), "remind me to")
        val p = QuickParse.parse(phrased.let { if ("remind me" in it) it else "remind me $it" }, now)
        val title = cleanTitle(p.title).ifEmpty { "Reminder" }
        return Result(Kind.REMINDER, explicit = true, title = title, date = p.date, time = p.time, repeat = p.repeat, remind = true)
    }

    private val TASK_TRIGGER = Regex(
        "^(?:add (?:a )?(?:new )?(?:task|to ?do)(?: to| called| for|:)?|new (?:task|to ?do):?|(?:task|to ?do):|(?:i )?(?:need|have|got|gotta|must) (?:to )?|i should|don'?t forget (?:to )?|remember to|add)\\b\\s*",
    )
    private val TASK_SUFFIX = Regex("\\s+(?:to|on|in) (?:my )?(?:to ?do(?: list)?|task list|tasks|list)$")
    private val TASK_VERB = Regex(
        "^(call|buy|pay|book|email|mail|text|message|send|pick up|collect|get|clean|finish|submit|renew|schedule|fix|check|order|return|cancel|visit|meet|apply|file|print|wash|cook|study|read|write|update|reply|go|take|bring|drop|charge|recharge|transfer|plan|prepare|review|sign|post|clear)\\b",
    )

    private fun task(text: String, now: LocalDateTime): Result? {
        val trigger = TASK_TRIGGER.find(text)
        var body = trigger?.let { text.removeRange(it.range) } ?: text
        body = body.replace(TASK_SUFFIX, "").trim()
        if (body.isEmpty()) return null
        val p = QuickParse.parse(body, now)
        val title = cleanTitle(p.title)
        if (title.isEmpty()) return null
        // "I need to…" in chat might just be talk; "add task…" / "…to my list" never is.
        val explicit = (trigger != null && !Regex("^(?:i |need|have|got|gotta|must)").containsMatchIn(trigger.value)) || TASK_SUFFIX.containsMatchIn(text)
        // Without a trigger, only treat it as a task when it reads like one (a to-do verb or a date/time).
        if (!explicit && !TASK_VERB.containsMatchIn(body) && p.time == null && p.repeat == null) return null
        return Result(Kind.TASK, explicit = explicit, title = title, date = p.date, time = p.time, repeat = p.repeat, remind = p.remind && p.time != null)
    }

    private fun cleanTitle(t: String) = t.trim().trim(',', '.', ':').replace(Regex("^(?:to|that|about) ", RegexOption.IGNORE_CASE), "")
        .replaceFirstChar { it.uppercase() }

    // ---- money ----

    private val PAST_SPEND = Regex("\\b(spent|spend|paid|bought|purchased|ordered|gave|tipped|donated|recharged|debited|expense|cost|costed|shelled out|lost|blew)\\b")
    private val FUTURE_PAY = Regex("^(?:pay|buy|order|recharge|transfer|send)\\b")
    private val INCOME_WORD = Regex("\\b(received|receive|got paid|got|earned|credited|salary|refund|refunded|cashback|income|sold|paid me|sent me|gave me|returned|paid back|reimbursed|bonus|won)\\b")
    private val FUTURE_WORDS = Regex("\\b(tomorrow|tmrw|next|in \\d+ (?:days?|weeks?|months?)|every|tonight|later)\\b")

    private fun money(text: String, accounts: List<String>, now: LocalDateTime): Result? {
        val (date, time, withQty) = pastWhen(text, now)
        // "5 litres" / "at 12450 km" are fuel details, not the amount.
        val rest = withQty.replace(LITRES, " ").replace(ODOMETER, " ").replace(Regex("\\s+"), " ").trim()
        val amount = amountIn(rest) ?: return null
        val incomeHit = INCOME_WORD.find(rest)
        val spendHit = PAST_SPEND.find(rest)
        // "pay 5000 rent tomorrow" is a to-do, not a payment that happened.
        if (spendHit == null && incomeHit == null && FUTURE_PAY.containsMatchIn(rest) && FUTURE_WORDS.containsMatchIn(text)) return null
        if (spendHit == null && incomeHit == null && TASK_TRIGGER.containsMatchIn(text)) return null
        val income = incomeHit != null && (spendHit == null || incomeHit.value in setOf("paid me", "sent me", "gave me", "paid back", "refund", "refunded", "cashback"))
        val explicit = incomeHit != null || spendHit != null || CURRENCY.containsMatchIn(rest)

        val account = accountHint(rest, accounts)
        val payee = payeeIn(rest.replace(amount.second, " "), income)
        val category = if (income) {
            when {
                Regex("\\bsalary|payroll|bonus\\b").containsMatchIn(rest) -> Category.SALARY
                Regex("\\brefund|cashback|reimburs|returned\\b").containsMatchIn(rest) -> Category.REFUNDS
                else -> Category.INCOME
            }
        } else {
            spokenCategory(rest) ?: Categorizer.categorize("debit", rest, null, amount.first).takeIf { it != Category.OTHER } ?: Category.OTHER
        }
        return Result(
            if (income) Kind.INCOME else Kind.EXPENSE, explicit = explicit,
            amountMinor = amount.first, payee = payee, category = category, account = account,
            date = date, time = time, title = payee ?: category.label,
        )
    }

    private val LITRES = Regex("\\b(\\d+(?:\\.\\d+)?) ?(?:ltr|ltrs|litres?|liters?)\\b")
    private val ODOMETER = Regex("\\b(?:at |odometer |odo |reading )?(\\d{3,6}) ?(?:km|kms|kilometers?|kilometres?)\\b")

    /** Litres and odometer reading said alongside a fuel payment ("5 litres at 12450 km"). */
    fun fuelDetails(input: String): Pair<Double, Int> {
        val t = normalise(input)
        return (LITRES.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0) to (ODOMETER.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
    }

    private val CURRENCY = Regex("(₹|\\brs\\.?|\\binr\\b|\\brupees?\\b|\\bbucks\\b)")
    private val AMOUNT = Regex("(?:₹\\s*|\\brs\\.?\\s*|\\binr\\s*)?(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(k|thousand|lakhs?|lacs?|l)?\\b(?:\\s*(?:rupees?|bucks|rs))?")

    /** The amount in paise and the matched text. Prefers a number with ₹/rs/rupees, else the first number. */
    internal fun amountIn(text: String): Pair<Long, String>? {
        val matches = AMOUNT.findAll(text).toList().filter { it.groupValues[1].replace(",", "").toDoubleOrNull() != null }
        val m = matches.firstOrNull { CURRENCY.containsMatchIn(it.value) } ?: matches.firstOrNull() ?: return null
        var rupees = m.groupValues[1].replace(",", "").toDouble()
        rupees *= when (m.groupValues[2]) {
            "k", "thousand" -> 1_000.0
            "lakh", "lakhs", "lac", "lacs", "l" -> 1_00_000.0
            else -> 1.0
        }
        if (rupees <= 0) return null
        return (rupees * 100).roundToLong() to m.value
    }

    private fun accountHint(text: String, accounts: List<String>): String? {
        if (Regex("\\b(?:in |by |with |using |from |paid )?cash\\b").containsMatchIn(text)) return "cash"
        for (name in accounts) {
            val key = name.lowercase().split(' ', '•', '(').firstOrNull { it.length >= 3 } ?: continue
            if (Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(text)) return key
        }
        return Regex("\\b(?:from|via|using|through|with|on) (?:my )?(sbi|hdfc|icici|axis|indusind|kotak|yes bank|idfc|paytm|card|credit card|debit card|upi|gpay|google pay|phonepe)\\b")
            .find(text)?.groupValues?.get(1)
    }

    private val STOP = "(?:on|for|from|via|using|through|with|in|at|to|by|yesterday|today|this|last|and|of|rs|rupees|₹|\\d)"

    private fun payeeIn(text: String, income: Boolean): String? {
        val who = if (income) "from|by" else "at|to|from"
        val direct = Regex("\\b(?:$who) (?!my\\b|cash\\b|the bank\\b)([a-z][a-z'&.]*(?: (?!$STOP\\b)[a-z][a-z'&.]*){0,3})").find(text)?.groupValues?.get(1)
        val item = Regex("\\b(?:on|for) (?:a |an |some |the |my )?([a-z][a-z'&]*(?: (?!$STOP\\b)[a-z][a-z'&]*){0,2})").find(text)?.groupValues?.get(1)
        // "uber 230", "450 lunch": the leftover word is the payee.
        val bare = text.replace(Regex("\\b(?:spent|spend|paid|bought|got|received|$STOP|for|on|my|a|an|the|i|was|is|me|it)\\b"), " ")
            .replace(Regex("[^a-z ]"), " ").trim().split(Regex("\\s+")).filter { it.length >= 3 }.take(2).joinToString(" ")
        return (direct ?: item ?: bare.ifEmpty { null })
            ?.split(' ')?.joinToString(" ") { w -> if (w.length <= 3 && w in setOf("sbi", "kfc", "ola", "atm")) w.uppercase() else w.replaceFirstChar { it.uppercase() } }
            ?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("cash", "money", "upi", "card") }
    }

    private val SPOKEN = listOf(
        "breakfast|lunch|dinner|food|coffee|tea|chai|snacks?|meal|restaurant|cafe|eating out|biryani|pizza|burger|juice|swiggy|zomato|drinks|beer|bar" to Category.FOOD,
        "groceries|grocery|vegetables|veggies|fruits?|milk|eggs|bread|blinkit|zepto|bigbasket|instamart|kirana|supermarket" to Category.GROCERIES,
        "petrol|diesel|fuel|tank|cng" to Category.FUEL,
        "uber|ola|auto|rickshaw|cab|taxi|bus|metro|train|parking|toll|rapido|bike service|car service" to Category.TRANSPORT,
        "flight|hotel|trip|holiday|vacation|airbnb|booking" to Category.TRAVEL,
        "rent|maintenance|plumber|electrician|furniture" to Category.RENT,
        "electricity|power bill|water bill|gas|wifi|internet|broadband|phone bill|mobile bill|recharge|bill" to Category.BILLS,
        "netflix|spotify|prime|hotstar|youtube premium|subscription|icloud|chatgpt" to Category.SUBSCRIPTIONS,
        "medicine|medicines|doctor|pharmacy|hospital|gym|protein|tablets|physio|dentist" to Category.HEALTH,
        "movie|movies|cinema|concert|game|games|party|netflix" to Category.ENTERTAINMENT,
        "clothes|shoes|shirt|jeans|amazon|flipkart|myntra|gadget|headphones|phone case|shopping|gift" to Category.SHOPPING,
        "course|books?|tuition|fees|exam|class" to Category.EDUCATION,
        "mutual fund|sip|stocks?|shares|invest|investment|gold|fd" to Category.INVESTMENTS,
    ).map { (words, c) -> Regex("\\b(?:$words)\\b") to c }

    private fun spokenCategory(text: String) = SPOKEN.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.second

    // ---- when a payment happened (dates point back in time) ----

    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val MONTHS = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL, "may" to Month.MAY, "jun" to Month.JUNE,
        "jul" to Month.JULY, "aug" to Month.AUGUST, "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )
    private const val DAY = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)"
    private const val MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"

    /** Date and time of something that already happened; returns what's left of the text. */
    internal fun pastWhen(input: String, now: LocalDateTime): Triple<LocalDate?, LocalTime?, String> {
        var text = " $input "
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        fun take(pattern: String, onMatch: (MatchResult) -> Unit) {
            val m = Regex("(?<=\\s)$pattern(?=[\\s,.!?])").find(text) ?: return
            onMatch(m)
            text = text.removeRange(m.range).replace(Regex("\\s+"), " ").let { " ${it.trim()} " }
        }
        take("day before yesterday") { date = today.minusDays(2) }
        take("last night") { date = today.minusDays(1); time = LocalTime.of(21, 0) }
        take("yesterday(?: (morning|afternoon|evening|night))?") { m -> date = today.minusDays(1); time = partOfDay(m.groupValues[1]) }
        take("(?:this|today) (morning|afternoon|evening)") { m -> date = today; time = partOfDay(m.groupValues[1]) }
        take("(?:just now|today|tonight)") { if (date == null) date = today }
        take("(\\d{1,2}|a|one|two|three) (days?|weeks?) ago") { m ->
            val n = when (val g = m.groupValues[1]) { "a", "one" -> 1L; "two" -> 2L; "three" -> 3L; else -> g.toLong() }
            date = if (m.groupValues[2].startsWith("week")) today.minusWeeks(n) else today.minusDays(n)
        }
        take("last week") { date = today.minusWeeks(1) }
        take("last $DAY") { m -> date = today.with(TemporalAdjusters.previous(DAYS.getValue(m.groupValues[1]))) }
        take("(?:on |this (?:past )?)$DAY") { m -> date = today.with(TemporalAdjusters.previousOrSame(DAYS.getValue(m.groupValues[1]))) }
        take("(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH") { m -> date = pastDate(today, MONTHS.getValue(m.groupValues[2].take(3)), m.groupValues[1].toInt()) }
        take("(?:on )?$MONTH (\\d{1,2})(?:st|nd|rd|th)?") { m -> date = pastDate(today, MONTHS.getValue(m.groupValues[1].take(3)), m.groupValues[2].toInt()) }
        take("on (?:the )?(\\d{1,2})(?:st|nd|rd|th)") { m ->
            val d = m.groupValues[1].toInt()
            val thisMonth = today.withDayOfMonth(d.coerceIn(1, today.lengthOfMonth()))
            date = if (thisMonth.isAfter(today)) today.minusMonths(1).let { it.withDayOfMonth(d.coerceIn(1, it.lengthOfMonth())) } else thisMonth
        }
        take("(?:at |around )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)") { m ->
            var h = m.groupValues[1].toInt() % 12
            if (m.groupValues[3].startsWith("p")) h += 12
            time = LocalTime.of(h, m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59))
        }
        if (time == null) take("(?:at |around )?([01]?\\d|2[0-3]):([0-5]\\d)") { m -> time = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        if (time == null) take("in the (morning|afternoon|evening)") { m -> time = partOfDay(m.groupValues[1]) }
        // A time later than now with no date means yesterday ("paid 200 at 11 pm" said at 9 am).
        if (date == null && time != null) date = if (time!!.isAfter(now.toLocalTime())) today.minusDays(1) else today
        return Triple(date, time, text.trim())
    }

    private fun partOfDay(word: String): LocalTime? = when (word) {
        "morning" -> LocalTime.of(9, 0)
        "afternoon" -> LocalTime.of(14, 0)
        "evening" -> LocalTime.of(19, 0)
        "night" -> LocalTime.of(21, 0)
        else -> null
    }

    private fun pastDate(today: LocalDate, month: Month, day: Int): LocalDate {
        val d = LocalDate.of(today.year, month, day.coerceIn(1, month.length(java.time.Year.isLeap(today.year.toLong()))))
        return if (d.isAfter(today)) d.minusYears(1) else d
    }

    // ---- cleanup ----

    private val QUESTION_START = Regex(
        "^(what|what's|whats|how|when|where|why|who|which|did|do (?:i|you|we)|does|is|are|am i|was|were|have i|has|should i|tell me|show me|give me|any)\\b",
    )

    /** Lower-case (unless [keepCase]), drop "hey buddy"/"please"/"can you", spoken numbers → digits. */
    internal fun normalise(input: String, keepCase: Boolean = false): String {
        var t = (if (keepCase) input else input.lowercase()).replace('’', '\'').replace(Regex("[\"“”]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(?:(?:hey|hi|ok|okay) )?buddy[,.!]?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(?:(?:can|could|would|will) you (?:please )?|please |pls |kindly |just )+", RegexOption.IGNORE_CASE), "")
        t = wordsToDigits(t)
        return t.trim().trimEnd('.', '!')
    }

    /** Puts back the capitals you said ("call Ramesh") — matching is done on lower-case text. */
    private fun restoreCase(s: String, cased: String): String {
        val pool = cased.split(' ').toMutableList()
        return s.split(' ').joinToString(" ") { w ->
            val i = pool.indexOfFirst { it.equals(w, ignoreCase = true) }
            if (i >= 0) pool.removeAt(i) else w
        }.replaceFirstChar { it.uppercase() }
    }

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val SCALES = mapOf("hundred" to 100, "thousand" to 1_000, "lakh" to 1_00_000, "lakhs" to 1_00_000, "lac" to 1_00_000)

    /** "four hundred and fifty" → 450, "two thousand five hundred" → 2500, "one fifty" → 150. */
    internal fun wordsToDigits(text: String): String {
        val original = text.split(' ')
        val words = original.map { it.lowercase() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val isNum = w in UNITS || w in TENS || (w == "a" && words.getOrNull(i + 1) in SCALES)
            if (!isNum) { out += original[i]; i++; continue }
            var total = 0L
            var current = 0L
            var j = i
            var lastWasUnitOnly = false
            while (j < words.size) {
                val x = words[j]
                when {
                    x == "a" && words.getOrNull(j + 1) in SCALES -> { current = 1; lastWasUnitOnly = false }
                    x in UNITS -> {
                        current += UNITS.getValue(x)
                        lastWasUnitOnly = current < 10
                    }
                    // "one fifty": a lone unit then a tens word means hundreds.
                    x in TENS -> {
                        val v = TENS.getValue(x).toLong()
                        current = if (lastWasUnitOnly && current in 1..9) current * 100 + v else current + v
                        lastWasUnitOnly = false
                    }
                    x in SCALES -> {
                        val s = SCALES.getValue(x).toLong()
                        if (s == 100L) current = (if (current == 0L) 1 else current) * 100
                        else { total += (if (current == 0L) 1 else current) * s; current = 0 }
                        lastWasUnitOnly = false
                    }
                    x == "and" && j + 1 < words.size && (words[j + 1] in UNITS || words[j + 1] in TENS) && (current > 0 || total > 0) -> {}
                    else -> break
                }
                j++
            }
            out += (total + current).toString()
            i = j
        }
        return out.joinToString(" ")
    }
}
