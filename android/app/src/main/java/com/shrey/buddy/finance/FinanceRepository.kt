package com.shrey.buddy.finance

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.room.withTransaction
import com.shrey.buddy.data.db.AccountEntity
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.db.CaptureEntity
import com.shrey.buddy.data.db.OutboxEntry
import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.ingest.sms.Bank
import com.shrey.buddy.ingest.sms.Direction
import com.shrey.buddy.ingest.sms.SmsParse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

/**
 * Accounts + transactions (v1 finance core). Turns parsed SMS into synced transactions,
 * categorises them, and detects transfers between the owner's own accounts.
 */
class FinanceRepository(
    private val db: BuddyDatabase,
    private val prefs: SharedPreferences,
    private val onLocalChange: () -> Unit,
) {
    private val zone = ZoneId.systemDefault()
    private val mutex = Mutex()

    /** Called with (manual id, bank id) when a voice/manual entry is merged into the bank's SMS transaction. */
    var onMerged: (suspend (String, String) -> Unit)? = null

    fun observeAccounts(): Flow<List<AccountEntity>> = db.accountDao().observeAll()
    fun observeTransactions(): Flow<List<TransactionEntity>> = db.transactionDao().observeAll()

    // ---- "You": UPI IDs / names that identify the owner, for transfer detection (local setting) ----

    private val _ownIdentifiers = MutableStateFlow(loadOwnIdentifiers())
    val ownIdentifiers: StateFlow<List<String>> = _ownIdentifiers.asStateFlow()

    private fun loadOwnIdentifiers(): List<String> =
        prefs.getString(KEY_OWN, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun setOwnIdentifiers(values: List<String>) {
        val clean = values.map { it.trim() }.filter { it.length >= 3 }.distinct()
        prefs.edit { putString(KEY_OWN, clean.joinToString(",")) }
        _ownIdentifiers.value = clean
        matchTransfers()
    }

    // ---- SMS -> transactions ----

    /** Idempotent: deterministic IDs mean re-running over the same captures never duplicates. */
    suspend fun materialize(captures: List<Pair<CaptureEntity, SmsParse.Transaction>>): Int = mutex.withLock {
        if (captures.isEmpty()) return@withLock 0
        val existingTxns = db.transactionDao().allIds().toHashSet()
        val existingAccounts = db.accountDao().all().associateBy { it.id }.toMutableMap()
        // Voice/manual entries not yet matched to a bank message (candidates for merging).
        val unmatchedManual = db.transactionDao().all()
            .filter { it.source in MANUAL_SOURCES && it.dedupeKey.startsWith("manual:") }
            .toMutableList()
        var inserted = 0
        val merged = mutableListOf<Pair<String, String>>()
        db.withTransaction {
            for ((capture, p) in captures) {
                val account = accountFor(p.bank, p.accountLast4)
                if (account.id !in existingAccounts) {
                    write(account)
                    existingAccounts[account.id] = account
                }
                var txn = transactionFor(capture, p, account.id)
                if (txn.id !in existingTxns) {
                    // "Hey Buddy, spent 450 on dinner" followed by the bank's SMS for the same payment:
                    // keep the bank's record, carry over what the user said, and drop the manual copy.
                    val manual = unmatchedManual.firstOrNull {
                        it.direction == txn.direction && it.amountMinor == txn.amountMinor &&
                            abs(it.occurredAt - txn.occurredAt) <= MERGE_WINDOW
                    }
                    if (manual != null) {
                        unmatchedManual.remove(manual)
                        txn = txn.copy(
                            category = manual.category,
                            categorySource = manual.categorySource,
                            note = manual.note ?: manual.payee?.takeIf { !it.equals(txn.payee, ignoreCase = true) },
                        )
                        val now = System.currentTimeMillis()
                        write(manual.copy(deletedAt = now, updatedAt = maxOf(now, manual.updatedAt + 1)))
                        merged += manual.id to txn.id
                    }
                    write(txn)
                    existingTxns += txn.id
                    inserted++
                }
            }
        }
        merged.forEach { (old, new) -> runCatching { onMerged?.invoke(old, new) } }
        if (inserted > 0) onLocalChange()
        inserted
    }.also { if (it > 0) matchTransfers() }

    private fun accountFor(bank: Bank, last4: String?): AccountEntity {
        val now = System.currentTimeMillis()
        val label = when (bank) {
            Bank.INDUSIND -> "IndusInd"
            Bank.SBI -> "SBI"
        }
        return AccountEntity(
            id = uuid("acct:${bank.name}:${last4 ?: "unknown"}"),
            name = if (last4 != null) "$label ••$last4" else "$label (other)",
            bank = label,
            kind = "savings",
            last4 = last4,
            // A newly seen IndusInd account starts as primary; change it any time under Bank accounts.
            isPrimary = bank == Bank.INDUSIND && last4 != null,
            includeInTotals = true,
            archived = false,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
    }

    private fun transactionFor(capture: CaptureEntity, p: SmsParse.Transaction, accountId: String): TransactionEntity {
        val now = System.currentTimeMillis()
        val direction = if (p.direction == Direction.CREDIT) "credit" else "debit"
        val dedupeKey = p.reference?.let { "sms:${p.bank.name}:$direction:$it" } ?: "capture:${capture.bodyHash}"
        val received = Instant.ofEpochMilli(capture.receivedAt).atZone(zone)
        // Use the SMS's own date when it differs from the arrival date (delayed messages, backfills).
        val occurredAt = p.txnDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it != received.toLocalDate() }
            ?.atTime(12, 0)?.atZone(zone)?.toInstant()?.toEpochMilli()
            ?: capture.receivedAt
        val category = Categorizer.categorize(direction, p.counterparty, p.channel, p.amountMinor)
        return TransactionEntity(
            id = uuid("txn:$dedupeKey"),
            accountId = accountId,
            direction = direction,
            amountMinor = p.amountMinor,
            occurredAt = occurredAt,
            payee = prettyParty(p.counterparty, p.channel),
            counterpartyRaw = p.counterparty,
            channel = p.channel,
            reference = p.reference,
            category = category.slug,
            categorySource = "auto",
            note = null,
            isTransfer = false,
            transferGroup = null,
            source = "sms",
            dedupeKey = dedupeKey,
            balanceAfterMinor = p.balanceMinor,
            captureId = capture.id,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
    }

    // ---- transfer detection ----

    /**
     * Marks transfers between the owner's own money:
     *  1. counterparty matches one of the owner's UPI IDs / names (covers accounts Buddy doesn't track),
     *  2. a debit and a credit of the same amount in two different tracked accounts within 3 days.
     * Never touches transactions the user has categorised by hand.
     */
    suspend fun matchTransfers() = mutex.withLock {
        val own = _ownIdentifiers.value.map { it.lowercase() }
        val txns = db.transactionDao().all()
        val changed = mutableMapOf<String, TransactionEntity>()
        fun current(t: TransactionEntity) = changed[t.id] ?: t
        fun mark(t: TransactionEntity, group: String?) {
            val c = current(t)
            if (c.categorySource == "user") return
            if (c.isTransfer && c.transferGroup == (group ?: c.transferGroup)) return
            changed[t.id] = c.copy(isTransfer = true, category = Category.TRANSFER.slug, transferGroup = group ?: c.transferGroup)
        }

        if (own.isNotEmpty()) {
            for (t in txns) {
                val hay = "${t.counterpartyRaw.orEmpty()} ${t.payee.orEmpty()}".lowercase()
                if (own.any { it in hay }) mark(t, null)
            }
        }

        val credits = txns.filter { it.direction == "credit" && it.accountId != null && it.transferGroup == null }.toMutableList()
        for (d in txns.filter { it.direction == "debit" && it.accountId != null && it.transferGroup == null }.sortedBy { it.occurredAt }) {
            val match = credits
                .filter { it.amountMinor == d.amountMinor && it.accountId != d.accountId && abs(it.occurredAt - d.occurredAt) <= THREE_DAYS }
                .minByOrNull { abs(it.occurredAt - d.occurredAt) }
                ?: continue
            credits.remove(match)
            val group = uuid("xfer:" + listOf(d.id, match.id).sorted().joinToString(":"))
            mark(d, group)
            mark(match, group)
        }

        if (changed.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        db.withTransaction {
            changed.values.forEach { write(it.copy(updatedAt = maxOf(now, it.updatedAt + 1))) }
        }
        onLocalChange()
    }

    // ---- manual / voice entries ----

    /**
     * Logs a transaction the user told Buddy about. Goes to the account they named (fuzzy match),
     * else Cash. If the bank's SMS for the same payment arrives later, the two are merged.
     * Returns the account name used.
     */
    suspend fun addManualTransaction(
        direction: String,
        amountMinor: Long,
        payee: String?,
        category: Category,
        accountHint: String?,
        note: String?,
        source: String,
        accountId: String? = null,
        occurredAt: Long = System.currentTimeMillis(),
        /** Sub-category under [category], e.g. "Coffee". */
        sub: String? = null,
        /** Pass an id when something else (a fuel log) needs to link to this transaction. */
        id: String = UUID.randomUUID().toString(),
    ): String {
        val account = accountId?.let { db.accountDao().get(it) } ?: resolveAccount(accountHint)
        val now = System.currentTimeMillis()
        db.withTransaction {
            if (db.accountDao().get(account.id) == null) write(account)
            write(
                TransactionEntity(
                    id = id, accountId = account.id, direction = direction, amountMinor = amountMinor,
                    occurredAt = occurredAt, payee = payee?.trim()?.ifEmpty { null }, counterpartyRaw = null,
                    channel = if (account.kind == "cash") "Cash" else null, reference = null,
                    category = Category.key(category, sub), categorySource = "user", note = note?.trim()?.ifEmpty { null },
                    isTransfer = false, transferGroup = null, source = source, dedupeKey = "manual:$id",
                    balanceAfterMinor = null, captureId = null, createdAt = now, updatedAt = now, deletedAt = null,
                ),
            )
        }
        onLocalChange()
        return account.name
    }

    /** The account you named, else your primary account, else Cash ("paid in cash" always means Cash). */
    private suspend fun resolveAccount(hint: String?): AccountEntity {
        val accounts = db.accountDao().all().filter { !it.archived }
        val h = hint?.trim()?.lowercase().orEmpty()
        if (h.isNotEmpty() && h != "cash") {
            accounts.firstOrNull { a ->
                a.name.lowercase().contains(h) || a.bank?.lowercase()?.let { it.contains(h) || h.contains(it) } == true
            }?.let { return it }
        }
        if (h != "cash") accounts.firstOrNull { it.isPrimary }?.let { return it }
        accounts.firstOrNull { it.kind == "cash" }?.let { return it }
        val now = System.currentTimeMillis()
        return AccountEntity(
            id = uuid("acct:CASH"), name = "Cash", bank = null, kind = "cash", last4 = null,
            isPrimary = false, includeInTotals = true, archived = false,
            createdAt = now, updatedAt = now, deletedAt = null,
        )
    }

    // ---- user edits ----

    suspend fun updateTransaction(id: String, category: String, isTransfer: Boolean, note: String?) {
        val t = db.transactionDao().get(id) ?: return
        val updated = t.copy(
            category = if (isTransfer) Category.TRANSFER.slug else category,
            isTransfer = isTransfer,
            transferGroup = if (isTransfer) t.transferGroup else null,
            note = note?.trim()?.ifEmpty { null },
            categorySource = "user",
            updatedAt = maxOf(System.currentTimeMillis(), t.updatedAt + 1),
        )
        db.withTransaction { write(updated) }
        onLocalChange()
    }

    /** Soft delete: syncs as deleted, and a re-scan of the same SMS never brings it back (its id is kept). */
    suspend fun deleteTransaction(id: String) {
        val t = db.transactionDao().get(id) ?: return
        val now = System.currentTimeMillis()
        db.withTransaction { write(t.copy(deletedAt = now, updatedAt = maxOf(now, t.updatedAt + 1))) }
        onLocalChange()
    }

    suspend fun restoreTransaction(id: String) {
        val t = db.transactionDao().get(id) ?: return
        db.withTransaction { write(t.copy(deletedAt = null, updatedAt = maxOf(System.currentTimeMillis(), t.updatedAt + 1))) }
        onLocalChange()
    }

    /** The transaction made from a captured SMS (so tapping its notification can open it). */
    suspend fun transactionForCapture(captureId: Long): String? = db.transactionDao().byCapture(captureId)

    suspend fun saveAccount(account: AccountEntity) {
        val prev = db.accountDao().get(account.id)
        val now = System.currentTimeMillis()
        db.withTransaction {
            // Only one primary account at a time.
            if (account.isPrimary) {
                db.accountDao().all().filter { it.isPrimary && it.id != account.id }.forEach {
                    write(it.copy(isPrimary = false, updatedAt = maxOf(now, it.updatedAt + 1)))
                }
            }
            write(account.copy(updatedAt = maxOf(now, (prev?.updatedAt ?: 0) + 1), createdAt = prev?.createdAt ?: now))
        }
        onLocalChange()
    }

    suspend fun addAccount(name: String, kind: String) {
        val now = System.currentTimeMillis()
        saveAccount(
            AccountEntity(
                id = UUID.randomUUID().toString(), name = name.trim(), bank = null, kind = kind, last4 = null,
                isPrimary = false, includeInTotals = true, archived = false,
                createdAt = now, updatedAt = now, deletedAt = null,
            ),
        )
    }

    // ---- writes always go through the outbox ----

    private suspend fun write(a: AccountEntity) {
        db.accountDao().upsert(a)
        db.outboxDao().enqueue(OutboxEntry(tableName = "accounts", rowId = a.id, enqueuedAt = System.currentTimeMillis()))
    }

    private suspend fun write(t: TransactionEntity) {
        db.transactionDao().upsert(t)
        db.outboxDao().enqueue(OutboxEntry(tableName = "transactions", rowId = t.id, enqueuedAt = System.currentTimeMillis()))
    }

    private fun uuid(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

    private companion object {
        const val KEY_OWN = "own_identifiers"
        const val THREE_DAYS = 3L * 24 * 60 * 60 * 1000
        const val MERGE_WINDOW = 36L * 60 * 60 * 1000
        val MANUAL_SOURCES = setOf("voice", "manual")
    }
}
