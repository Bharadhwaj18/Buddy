package com.shrey.buddy.finance

import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.items.Budget
import com.shrey.buddy.data.items.Recurring
import com.shrey.buddy.finance.Category as Cat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

enum class OccurrenceStatus { PAID, DUE, OVERDUE, UPCOMING }

data class Occurrence(val date: LocalDate, val status: OccurrenceStatus, val matchedTxnId: String? = null)

/** Schedules and matching for recurring payments (rent, SIPs, EMIs, subscriptions). Pure, unit-tested. */
object RecurringLogic {
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun clampDay(ym: YearMonth, day: Int) = ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))

    /** All due dates in [from, to], never before the start date. */
    fun dueDates(r: Recurring, from: LocalDate, to: LocalDate): List<LocalDate> {
        val start = r.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.MIN
        val out = mutableListOf<LocalDate>()
        when (r.frequency) {
            "weekly" -> {
                var d = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(r.day.coerceIn(1, 7))))
                while (!d.isAfter(to)) { out += d; d = d.plusWeeks(1) }
            }
            "yearly" -> {
                for (y in from.year..to.year) out += clampDay(YearMonth.of(y, r.month.coerceIn(1, 12)), r.day)
            }
            "quarterly" -> {
                // Every 3 months, anchored on the start month (or January).
                val anchor = if (start != LocalDate.MIN) start.monthValue else r.month.coerceIn(1, 12)
                var ym = YearMonth.from(from).minusMonths(1)
                while (!ym.atDay(1).isAfter(to)) {
                    if ((ym.monthValue - anchor).mod(3) == 0) out += clampDay(ym, r.day)
                    ym = ym.plusMonths(1)
                }
            }
            else -> {
                var ym = YearMonth.from(from).minusMonths(1)
                while (!ym.atDay(1).isAfter(to)) { out += clampDay(ym, r.day); ym = ym.plusMonths(1) }
            }
        }
        return out.filter { !it.isBefore(from) && !it.isAfter(to) && !it.isBefore(start) }.distinct().sorted()
    }

    /** Does [t] look like the bank's record of this payment due on [due]? */
    fun matches(r: Recurring, t: TransactionEntity, due: LocalDate): Boolean {
        if (t.direction != r.direction) return false
        val date = Instant.ofEpochMilli(t.occurredAt).atZone(zone).toLocalDate()
        if (abs(ChronoUnit.DAYS.between(due, date)) > 4) return false
        val tolerance = maxOf(r.amountMinor / 20, 100) // 5% (min ₹1): SIP NAV rounding, small fee changes
        if (abs(t.amountMinor - r.amountMinor) > tolerance) return false
        if (r.accountId != null && t.accountId != null && t.accountId != r.accountId) return false
        if (r.match.isBlank()) return true
        val hay = "${t.payee.orEmpty()} ${t.counterpartyRaw.orEmpty()} ${t.note.orEmpty()}".lowercase()
        return r.match.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }.any { it in hay }
    }

    fun occurrences(
        r: Recurring,
        txns: List<TransactionEntity>,
        today: LocalDate = LocalDate.now(),
        back: Long = 35,
        ahead: Long = 40,
    ): List<Occurrence> {
        val used = mutableSetOf<String>()
        return dueDates(r, today.minusDays(back), today.plusDays(ahead)).map { due ->
            val match = txns.firstOrNull { it.id !in used && it.deletedAt == null && matches(r, it, due) }
            when {
                match != null -> { used += match.id; Occurrence(due, OccurrenceStatus.PAID, match.id) }
                due.toString() in r.settled -> Occurrence(due, OccurrenceStatus.PAID)
                due.isBefore(today) -> Occurrence(due, OccurrenceStatus.OVERDUE)
                due == today -> Occurrence(due, OccurrenceStatus.DUE)
                else -> Occurrence(due, OccurrenceStatus.UPCOMING)
            }
        }
    }

    /** Approximate monthly cost, for "committed per month". */
    fun monthlyMinor(r: Recurring): Long = when (r.frequency) {
        "weekly" -> r.amountMinor * 52 / 12
        "quarterly" -> r.amountMinor / 3
        "yearly" -> r.amountMinor / 12
        else -> r.amountMinor
    }
}

data class BudgetStatus(val budget: Budget, val spentMinor: Long) {
    val fraction: Float get() = if (budget.limitMinor <= 0) 0f else spentMinor.toFloat() / budget.limitMinor
}

object BudgetLogic {
    /** This month's spend vs each budget. Excludes transfers and accounts left out of totals. */
    fun status(
        budgets: List<Budget>,
        txns: List<TransactionEntity>,
        excludedAccounts: Set<String>,
        month: YearMonth = YearMonth.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BudgetStatus> {
        val spend = txns.filter {
            it.deletedAt == null && it.direction == "debit" && !it.isTransfer && it.accountId !in excludedAccounts &&
                YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == month
        }
        return budgets.map { b ->
            BudgetStatus(b, if (b.category == "total") spend.sumOf { it.amountMinor } else spend.filter { Cat.matches(it.category, b.category) }.sumOf { it.amountMinor })
        }.sortedWith(compareByDescending<BudgetStatus> { it.budget.category == "total" }.thenByDescending { it.fraction })
    }
}
