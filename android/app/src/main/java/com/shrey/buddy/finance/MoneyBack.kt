package com.shrey.buddy.finance

import com.shrey.buddy.data.db.TransactionEntity

/**
 * Money back: someone paid you back for something you paid (₹900 dinner, ₹600 back → ₹300 spent).
 *
 * An incoming transaction filed under a *spending* category (Food, Shopping…) instead of an income one is
 * money back. It counts as negative spending in that category, everywhere spending is added up (totals,
 * category breakdown, budgets), and is left out of income. It can also be linked to the payment it
 * refunds ([MoneyBackLink]), which is only for showing "₹600 back · net ₹300" on that payment.
 */
object MoneyBack {
    fun isMoneyBack(t: TransactionEntity): Boolean =
        t.direction == "credit" && !t.isTransfer && Category.of(t.category).let { !it.isIncome && it != Category.TRANSFER }

    /** Debits and money back: the transactions that make up spending. */
    fun countsAsSpend(t: TransactionEntity): Boolean = !t.isTransfer && (t.direction == "debit" || isMoneyBack(t))

    /** What [t] adds to spending: + for a payment, − for money back. */
    fun spend(t: TransactionEntity): Long = if (t.direction == "debit") t.amountMinor else -t.amountMinor

    /** Real income: credits that aren't money back. */
    fun isIncome(t: TransactionEntity): Boolean = t.direction == "credit" && !t.isTransfer && !isMoneyBack(t)

    /** Net spending of [txns] (payments minus money back), never below zero. */
    fun net(txns: Iterable<TransactionEntity>): Long = txns.filter(::countsAsSpend).sumOf(::spend).coerceAtLeast(0)
}
