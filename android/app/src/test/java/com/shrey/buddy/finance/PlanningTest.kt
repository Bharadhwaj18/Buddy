package com.shrey.buddy.finance

import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.items.Budget
import com.shrey.buddy.data.items.Recurring
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PlanningTest {
    private val zone = ZoneId.systemDefault()

    private fun txn(id: String, date: LocalDate, amount: Long, payee: String, direction: String = "debit", category: String = "other", transfer: Boolean = false) =
        TransactionEntity(
            id = id, accountId = "a", direction = direction, amountMinor = amount,
            occurredAt = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), payee = payee, counterpartyRaw = null,
            channel = "UPI", reference = null, category = category, categorySource = "auto", note = null, isTransfer = transfer,
            transferGroup = null, source = "sms", dedupeKey = id, balanceAfterMinor = null, captureId = null,
            createdAt = 0, updatedAt = 0, deletedAt = null,
        )

    @Test fun `monthly due dates clamp to short months`() {
        val r = Recurring(name = "Rent", amountMinor = 25_000_00, day = 31)
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
            RecurringLogic.dueDates(r, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
        )
    }

    @Test fun `weekly, quarterly, yearly schedules`() {
        val weekly = Recurring(frequency = "weekly", day = 1) // Mondays
        assertEquals(4, RecurringLogic.dueDates(weekly, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).size)
        val quarterly = Recurring(frequency = "quarterly", day = 15, startDate = "2026-01-15")
        assertEquals(
            listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15), LocalDate.of(2026, 7, 15), LocalDate.of(2026, 10, 15)),
            RecurringLogic.dueDates(quarterly, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 1)),
        )
        val yearly = Recurring(frequency = "yearly", day = 5, month = 3)
        assertEquals(listOf(LocalDate.of(2027, 3, 5)), RecurringLogic.dueDates(yearly, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30)))
    }

    @Test fun `SIP is marked paid by the matching bank debit, others overdue or upcoming`() {
        val sip = Recurring(name = "Flexi cap SIP", amountMinor = 4_000_00, day = 22, match = "ICCL", category = "investments")
        val today = LocalDate.of(2026, 10, 3)
        val txns = listOf(txn("t1", LocalDate.of(2026, 9, 22), 4_000_00, "ICCL - Mutual Fu"))
        val occ = RecurringLogic.occurrences(sip, txns, today, back = 45, ahead = 30)
        assertEquals(OccurrenceStatus.PAID, occ.first { it.date == LocalDate.of(2026, 9, 22) }.status)
        assertEquals("t1", occ.first { it.date == LocalDate.of(2026, 9, 22) }.matchedTxnId)
        assertEquals(OccurrenceStatus.OVERDUE, occ.first { it.date == LocalDate.of(2026, 8, 22) }.status)
        assertEquals(OccurrenceStatus.UPCOMING, occ.first { it.date == LocalDate.of(2026, 10, 22) }.status)
    }

    @Test fun `match text, amount tolerance and settled dates`() {
        val rent = Recurring(amountMinor = 20_000_00, day = 1, match = "landlord, ravi")
        val due = LocalDate.of(2026, 10, 1)
        assertEquals(true, RecurringLogic.matches(rent, txn("x", due.plusDays(2), 20_000_00, "Ravi Kumar"), due))
        assertEquals(false, RecurringLogic.matches(rent, txn("x", due, 20_000_00, "Swiggy"), due))
        assertEquals(false, RecurringLogic.matches(rent, txn("x", due.plusDays(6), 20_000_00, "Ravi"), due))
        assertEquals(false, RecurringLogic.matches(rent, txn("x", due, 25_000_00, "Ravi"), due))
        val settled = rent.copy(settled = listOf("2026-09-01"))
        assertEquals(OccurrenceStatus.PAID, RecurringLogic.occurrences(settled, emptyList(), LocalDate.of(2026, 9, 10), back = 15, ahead = 5).first().status)
    }

    @Test fun `budgets count only real spending this month`() {
        val month = java.time.YearMonth.of(2026, 10)
        val txns = listOf(
            txn("1", LocalDate.of(2026, 10, 2), 500_00, "Swiggy", category = "food"),
            txn("2", LocalDate.of(2026, 10, 3), 300_00, "Zomato", category = "food"),
            txn("3", LocalDate.of(2026, 10, 3), 10_000_00, "Me", transfer = true, category = "transfer"),
            txn("4", LocalDate.of(2026, 9, 30), 999_00, "Old", category = "food"),
            txn("5", LocalDate.of(2026, 10, 3), 5_000_00, "Salary", direction = "credit", category = "salary"),
        )
        val s = BudgetLogic.status(listOf(Budget("food", 1_000_00), Budget("total", 2_000_00)), txns, emptySet(), month)
        assertEquals("total", s.first().budget.category)
        assertEquals(800_00L, s.first { it.budget.category == "food" }.spentMinor)
        assertEquals(800_00L, s.first { it.budget.category == "total" }.spentMinor)
        assertEquals(0.8f, s.first { it.budget.category == "food" }.fraction, 0.001f)
    }
}
