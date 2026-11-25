package com.shrey.buddy.finance

import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.items.Recurring
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The bill & subscription radar: finds payments that repeat (Netflix, rent, SIPs, phone bill) in the bank
 * history, so they can become Recurring entries without typing them in; spots price rises on ones already
 * tracked, and ones that seem to have stopped. Pure; unit-tested.
 */
object BillRadar {
    enum class Kind { NEW, PRICE_CHANGE }

    data class Found(
        /** Stable id for "not a bill" dismissals. */
        val key: String,
        val kind: Kind,
        val name: String,
        /** Text to recognise it in future payees (goes into Recurring.match). */
        val match: String,
        val amountMinor: Long,
        val frequency: String,
        val day: Int,
        val month: Int,
        val category: String,
        val accountId: String?,
        val lastDate: LocalDate,
        val count: Int,
        /** PRICE_CHANGE only: the tracked entry and its old amount. */
        val recurringId: String? = null,
        val oldAmountMinor: Long? = null,
    ) {
        fun toRecurring() = Recurring(
            name = name, amountMinor = amountMinor, direction = "debit", category = category, accountId = accountId,
            frequency = frequency, day = day, month = month, startDate = lastDate.toString(), match = match,
        )
    }

    private data class Pattern(val frequency: String, val gap: IntRange, val minCount: Int)

    private val PATTERNS = listOf(
        Pattern("weekly", 6..8, 4),
        Pattern("monthly", 26..35, 3),
        Pattern("quarterly", 85..97, 3),
        Pattern("yearly", 350..380, 2),
    )

    private fun day(t: TransactionEntity, zone: ZoneId) = Instant.ofEpochMilli(t.occurredAt).atZone(zone).toLocalDate()

    /** "NETFLIX.COM", "netflix*subscription", "Netflix India Pvt Ltd" → "netflix". */
    fun key(t: TransactionEntity): String? {
        val raw = (t.payee?.takeIf { it.isNotBlank() } ?: t.counterpartyRaw ?: return null).lowercase()
        val words = raw.substringBefore('@')
            .replace(Regex("""[^a-z ]"""), " ")
            .split(' ')
            .filter { it.length > 1 && it !in NOISE }
        return words.take(2).joinToString(" ").takeIf { it.length >= 3 }
    }

    private val NOISE = setOf(
        "upi", "neft", "imps", "rtgs", "ach", "nach", "pos", "ecom", "pvt", "ltd", "private", "limited", "india", "the", "payment", "payments",
        "com", "www", "in", "co", "llp", "inc", "technologies", "tech", "services", "online", "subscription", "autopay", "mandate", "si", "to", "by",
    )

    fun detect(
        txns: List<TransactionEntity>,
        tracked: List<Pair<String, Recurring>>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Found> {
        val debits = txns.filter { it.deletedAt == null && it.direction == "debit" && !it.isTransfer && it.amountMinor > 0 }
            .filter { !day(it, zone).isBefore(today.minusDays(400)) }
        val out = mutableListOf<Found>()
        for ((k, group) in debits.groupBy { key(it) }) {
            if (k == null) continue
            val sorted = group.sortedBy { it.occurredAt }
            // A shop you visit often (several times a week) isn't a bill.
            val found = PATTERNS.firstNotNullOfOrNull { p -> fit(k, sorted, p, today, zone) } ?: continue
            val last = sorted.last()
            val trackedHit = tracked.firstOrNull { (_, r) -> r.active && r.direction == "debit" && covers(r, last, k) }
            if (trackedHit == null) {
                out += found
            } else {
                val (id, r) = trackedHit
                val recent = sorted.takeLast(2).map { it.amountMinor }
                val now = recent.last()
                val steady = recent.size < 2 || abs(recent[0] - recent[1]) <= maxOf(recent[1] / 50, 100)
                if (steady && abs(now - r.amountMinor) > maxOf(r.amountMinor / 20, 100)) {
                    out += found.copy(key = "price:$id:$now", kind = Kind.PRICE_CHANGE, name = r.name, recurringId = id, oldAmountMinor = r.amountMinor, amountMinor = now)
                }
            }
        }
        return out.sortedByDescending { it.lastDate }
    }

    private fun covers(r: Recurring, t: TransactionEntity, key: String): Boolean {
        val hay = "${t.payee.orEmpty()} ${t.counterpartyRaw.orEmpty()} ${t.note.orEmpty()}".lowercase()
        val words = r.match.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.any { it in hay }) return true
        return r.name.lowercase().let { it.isNotBlank() && (key in it || it in key) }
    }

    private fun fit(k: String, sorted: List<TransactionEntity>, p: Pattern, today: LocalDate, zone: ZoneId): Found? {
        // Look at the latest run of payments that fit this rhythm.
        val days = sorted.map { day(it, zone) }
        var run = mutableListOf(sorted.last())
        var head = sorted.lastIndex
        var j = head - 1
        while (j >= 0) {
            val gap = ChronoUnit.DAYS.between(days[j], days[head]).toInt()
            if (gap in p.gap) { run.add(0, sorted[j]); head = j }
            else if (gap > p.gap.last) break
            // else: an extra payment in between; skip it
            j--
        }
        if (run.size < p.minCount) return null
        // Lots of other payments to the same place in that time (a café you go to daily): not a bill.
        val others = sorted.count { it.occurredAt >= run.first().occurredAt } - run.size
        if (others > run.size / 2) return null
        run = run.takeLast(6).toMutableList()
        // Amounts must agree (within 10%), allowing one price change: an old price, then a new one.
        fun near(a: Long, b: Long) = abs(a - b) <= maxOf(b / 10, 100)
        val now = run.last().amountMinor
        val change = run.indexOfLast { !near(it.amountMinor, now) }
        if (change >= 0) {
            val before = run[change].amountMinor
            if (run.subList(0, change + 1).any { !near(it.amountMinor, before) }) return null
            if (run.size - change - 1 < 1) return null
        }
        // Still going: the last one is no more than ~1.5 periods ago.
        val lastDay = day(run.last(), zone)
        if (ChronoUnit.DAYS.between(lastDay, today) > p.gap.last * 3 / 2) return null
        val last = run.last()
        val name = prettyParty(last.payee ?: last.counterpartyRaw, last.channel).ifBlank { k.replaceFirstChar { it.uppercase() } }
        return Found(
            key = "new:$k:${p.frequency}",
            kind = Kind.NEW,
            name = name,
            match = k,
            amountMinor = last.amountMinor,
            frequency = p.frequency,
            day = if (p.frequency == "weekly") lastDay.dayOfWeek.value else run.map { day(it, zone).dayOfMonth }.sorted()[run.size / 2],
            month = lastDay.monthValue,
            category = last.category,
            accountId = last.accountId,
            lastDate = lastDay,
            count = run.size,
        )
    }

    /** Tracked debits whose last two due dates both went by with no payment: probably cancelled. */
    fun stopped(tracked: List<Pair<String, Recurring>>, txns: List<TransactionEntity>, today: LocalDate = LocalDate.now()): List<Pair<String, Recurring>> =
        tracked.filter { (_, r) ->
            if (!r.active || r.direction != "debit" || r.autoLog || r.frequency == "yearly") return@filter false
            val back = when (r.frequency) { "weekly" -> 20L; "quarterly" -> 200L; else -> 70L }
            val past = RecurringLogic.occurrences(r, txns, today, back = back, ahead = 0).filter { it.date.isBefore(today.minusDays(4)) }
            past.size >= 2 && past.takeLast(2).all { it.status == OccurrenceStatus.OVERDUE }
        }

    /** Debits due exactly [days] from today and not already paid (for the "in 2 days" heads-up). */
    fun dueIn(tracked: List<Pair<String, Recurring>>, txns: List<TransactionEntity>, days: Long = 2, today: LocalDate = LocalDate.now()) =
        tracked.mapNotNull { (id, r) ->
            if (!r.active || r.direction != "debit") return@mapNotNull null
            val target = today.plusDays(days)
            RecurringLogic.occurrences(r, txns, today, back = 0, ahead = days)
                .firstOrNull { it.date == target && it.status == OccurrenceStatus.UPCOMING }
                ?.let { Triple(id, r, it.date) }
        }
}
