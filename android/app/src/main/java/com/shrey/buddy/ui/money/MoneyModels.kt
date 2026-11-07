package com.shrey.buddy.ui.money

import com.shrey.buddy.data.db.AccountEntity
import com.shrey.buddy.data.db.CaptureEntity
import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.finance.Category
import com.shrey.buddy.finance.prettyParty
import com.shrey.buddy.ingest.sms.Bank
import com.shrey.buddy.ingest.sms.SmsParse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

data class AccountItem(
    val id: String,
    val name: String,
    val detail: String,
    val kind: String,
    /** Last balance reported by the bank (from the newest SMS that carried one). */
    val balanceMinor: Long?,
    val balanceAt: Long?,
    val isPrimary: Boolean,
    val includeInTotals: Boolean,
    val archived: Boolean,
    val entity: AccountEntity,
)

data class TxnItem(
    val id: String,
    val title: String,
    val accountName: String?,
    val channel: String?,
    val category: Category,
    /** Your sub-group under the category ("Coffee"), if any. */
    val sub: String? = null,
    val amountMinor: Long,
    val isCredit: Boolean,
    val isTransfer: Boolean,
    val note: String?,
    val date: LocalDate,
    val at: Long,
)

data class UpcomingItem(val payee: String, val amountMinor: Long, val dueDate: LocalDate?, val bank: String)

data class CategoryTotal(val category: Category, val amountMinor: Long, val subs: List<Pair<String, Long>> = emptyList())

data class MoneyData(
    val accounts: List<AccountItem> = emptyList(),
    val selectedAccountId: String? = null,
    val transactions: List<TxnItem> = emptyList(),
    val upcoming: List<UpcomingItem> = emptyList(),
    val spentThisMonth: Long = 0,
    val receivedThisMonth: Long = 0,
    val spentLastMonth: Long = 0,
    val topCategories: List<CategoryTotal> = emptyList(),
)

private val zone: ZoneId = ZoneId.systemDefault()

fun Bank.label() = when (this) {
    Bank.INDUSIND -> "IndusInd"
    Bank.SBI -> "SBI"
}

private val kindLabels = mapOf(
    "savings" to "Savings", "current" to "Current", "credit_card" to "Credit card",
    "wallet" to "Wallet", "cash" to "Cash", "loan" to "Loan", "other" to "Other",
)

fun kindLabel(kind: String) = kindLabels[kind] ?: kind

/**
 * Current balance = the newest anchor (a balance you typed in, or the last balance a bank SMS reported)
 * plus every transaction in the account after it. Null if there's no anchor yet.
 */
fun currentBalance(account: AccountEntity, accountTxns: List<TransactionEntity>): Pair<Long?, Long?> {
    val sms = accountTxns.filter { it.balanceAfterMinor != null }.maxByOrNull { it.occurredAt }
    val manualAt = account.manualBalanceAt
    val (base, baseAt) = when {
        account.manualBalanceMinor != null && manualAt != null && (sms == null || manualAt >= sms.occurredAt) ->
            account.manualBalanceMinor to manualAt
        sms != null -> sms.balanceAfterMinor!! to sms.occurredAt
        else -> return null to null
    }
    val after = accountTxns.filter { it.occurredAt > baseAt }
        .sumOf { if (it.direction == "credit") it.amountMinor else -it.amountMinor }
    return (base + after) to baseAt
}

fun buildMoneyData(
    txns: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    captures: List<CaptureEntity>,
    decode: (CaptureEntity) -> SmsParse,
    selectedAccountId: String?,
    today: LocalDate = LocalDate.now(),
): MoneyData {
    val accountById = accounts.associateBy { it.id }

    val accountItems = accounts.filter { !it.archived }.map { a ->
        val (balance, balanceAt) = currentBalance(a, txns.filter { it.accountId == a.id })
        AccountItem(
            id = a.id,
            name = a.name,
            detail = listOfNotNull(kindLabel(a.kind), a.last4?.let { "••$it" }).joinToString(" · "),
            kind = a.kind,
            balanceMinor = balance,
            balanceAt = balanceAt,
            isPrimary = a.isPrimary,
            includeInTotals = a.includeInTotals,
            archived = a.archived,
            entity = a,
        )
    }

    val visible = if (selectedAccountId == null) txns else txns.filter { it.accountId == selectedAccountId }
    val items = visible.map { t ->
        TxnItem(
            id = t.id,
            title = t.payee ?: prettyParty(t.counterpartyRaw, t.channel),
            accountName = t.accountId?.let { accountById[it]?.name },
            channel = t.channel,
            category = Category.of(t.category),
            sub = Category.subOf(t.category),
            amountMinor = t.amountMinor,
            isCredit = t.direction == "credit",
            isTransfer = t.isTransfer,
            note = t.note,
            date = Instant.ofEpochMilli(t.occurredAt).atZone(zone).toLocalDate(),
            at = t.occurredAt,
        )
    }.sortedWith(compareByDescending<TxnItem> { it.date }.thenByDescending { it.at })

    // Totals: real spending only — no transfers, no accounts excluded from totals
    // (unless the user is looking at that one account).
    val counted = visible.filter { t ->
        !t.isTransfer && (selectedAccountId != null || t.accountId == null || accountById[t.accountId]?.includeInTotals != false)
    }
    fun monthOf(t: TransactionEntity) = YearMonth.from(Instant.ofEpochMilli(t.occurredAt).atZone(zone))
    val thisMonth = YearMonth.from(today)
    val lastMonth = thisMonth.minusMonths(1)
    val debitsThisMonth = counted.filter { it.direction == "debit" && monthOf(it) == thisMonth }

    val upcoming = linkedMapOf<Triple<String, Long, LocalDate?>, UpcomingItem>()
    for (c in captures) {
        val p = runCatching { decode(c) }.getOrNull() as? SmsParse.UpcomingDebit ?: continue
        val due = p.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (due != null && due.isBefore(today)) continue
        val payee = prettyParty(p.payee, null)
        // Banks often send the same notice more than once; keep one per payee/amount/date.
        upcoming.putIfAbsent(Triple(payee, p.amountMinor, due), UpcomingItem(payee, p.amountMinor, due, p.bank.label()))
    }

    return MoneyData(
        accounts = accountItems,
        selectedAccountId = selectedAccountId,
        transactions = items,
        upcoming = upcoming.values.sortedBy { it.dueDate ?: LocalDate.MAX },
        spentThisMonth = debitsThisMonth.sumOf { it.amountMinor },
        receivedThisMonth = counted.filter { it.direction == "credit" && monthOf(it) == thisMonth }.sumOf { it.amountMinor },
        spentLastMonth = counted.filter { it.direction == "debit" && monthOf(it) == lastMonth }.sumOf { it.amountMinor },
        topCategories = debitsThisMonth.groupBy { Category.of(it.category) }
            .map { (cat, list) ->
                val subs = list.groupBy { Category.subOf(it.category) }.filterKeys { it != null }
                    .map { (s, l) -> s!! to l.sumOf { it.amountMinor } }.sortedByDescending { it.second }
                CategoryTotal(cat, list.sumOf { it.amountMinor }, subs)
            }
            .sortedByDescending { it.amountMinor },
    )
}
