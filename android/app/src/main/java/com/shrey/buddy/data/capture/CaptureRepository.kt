package com.shrey.buddy.data.capture

import android.content.Context
import android.provider.Telephony
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.db.CaptureEntity
import com.shrey.buddy.finance.FinanceRepository
import com.shrey.buddy.ingest.sms.SmsParse
import com.shrey.buddy.ingest.sms.SmsParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface IngestResult {
    data class Stored(val parse: SmsParse, val transactionId: String? = null) : IngestResult
    data object Duplicate : IngestResult
    /** Not an allowlisted bank, or an informational/OTP message. Nothing is stored. */
    data object Skipped : IngestResult
}

data class ScanSummary(
    val scanned: Int,
    val stored: Int,
    val duplicates: Int,
    val skipped: Int,
    /** Previously captured rows whose classification changed under the current parser. */
    val reparsed: Int,
    /** DLT headers that look like SBI/IndusInd but aren't allowlisted, with message counts. */
    val unknownBankHeaders: Map<String, Int>,
)

class CaptureRepository(
    private val db: BuddyDatabase,
    private val context: Context,
    private val finance: FinanceRepository,
) {
    private val json = Json

    fun observeRecent(limit: Int = 20): Flow<List<CaptureEntity>> = db.captureDao().observeRecent(limit)
    fun observeCount(status: String): Flow<Int> = db.captureDao().observeCount(status)
    fun observeParsed(): Flow<List<CaptureEntity>> = db.captureDao().observeParsed()

    fun decode(capture: CaptureEntity): SmsParse = json.decodeFromString(SmsParse.serializer(), capture.payload)

    suspend fun ingestSms(sender: String, body: String, receivedAt: Long, via: String): IngestResult {
        val parse = SmsParsers.parse(sender, body)
        if (parse == null || parse is SmsParse.NotTransaction) return IngestResult.Skipped
        val capture = CaptureEntity(
            kind = "sms",
            source = sender,
            receivedAt = receivedAt,
            rawText = body,
            bodyHash = sha256("$sender\n$body"),
            status = if (parse is SmsParse.Unrecognised) STATUS_UNRECOGNISED else STATUS_PARSED,
            payload = json.encodeToString(SmsParse.serializer(), parse),
            via = via,
        )
        val id = db.captureDao().insert(capture)
        if (id == -1L) return IngestResult.Duplicate
        // Live captures become transactions immediately; scans materialise in bulk at the end.
        if (via == "live" && parse is SmsParse.Transaction) {
            finance.materialize(listOf(capture.copy(id = id) to parse))
            return IngestResult.Stored(parse, finance.transactionForCapture(id))
        }
        return IngestResult.Stored(parse)
    }

    /** Turn every parsed transaction capture into an account + transaction (idempotent). */
    suspend fun materializeAll(): Int = withContext(Dispatchers.IO) {
        val pairs = db.captureDao().allSms().mapNotNull { c ->
            if (c.status != STATUS_PARSED) return@mapNotNull null
            (runCatching { decode(c) }.getOrNull() as? SmsParse.Transaction)?.let { c to it }
        }
        finance.materialize(pairs)
    }

    /**
     * Re-run the current parser over everything already captured, so parser improvements apply
     * retroactively. Rows that are now known not to be transactions are removed.
     * Returns how many rows changed classification.
     */
    suspend fun reparseAll(): Int {
        var changed = 0
        for (row in db.captureDao().allSms()) {
            val parse = SmsParsers.parse(row.source, row.rawText)
            if (parse == null || parse is SmsParse.NotTransaction) {
                db.captureDao().delete(row.id)
                changed++
                continue
            }
            val status = if (parse is SmsParse.Unrecognised) STATUS_UNRECOGNISED else STATUS_PARSED
            val payload = json.encodeToString(SmsParse.serializer(), parse)
            if (status != row.status || payload != row.payload) {
                db.captureDao().updateParse(row.id, status, payload)
                changed++
            }
        }
        return changed
    }

    /** Backfill from the SMS inbox. Requires READ_SMS. Raw text stays in the local DB only. */
    suspend fun scanInbox(days: Long = 365): ScanSummary = withContext(Dispatchers.IO) {
        val reparsed = reparseAll()
        var scanned = 0
        var stored = 0
        var duplicates = 0
        var skipped = 0
        val unknownHeaders = mutableMapOf<String, Int>()
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val addrCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                scanned++
                val sender = cursor.getString(addrCol) ?: continue
                if (SmsParsers.bankFor(sender) == null) {
                    SmsParsers.header(sender)
                        ?.takeIf { h -> BANK_LIKE.any { h.contains(it) } }
                        ?.let { h -> unknownHeaders[h] = (unknownHeaders[h] ?: 0) + 1 }
                    continue
                }
                when (ingestSms(sender, cursor.getString(bodyCol).orEmpty(), cursor.getLong(dateCol), via = "scan")) {
                    is IngestResult.Stored -> stored++
                    IngestResult.Duplicate -> duplicates++
                    IngestResult.Skipped -> skipped++
                }
            }
        }
        materializeAll()
        ScanSummary(scanned, stored, duplicates, skipped, reparsed, unknownHeaders)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val STATUS_PARSED = "parsed"
        const val STATUS_UNRECOGNISED = "unrecognised"
        private val BANK_LIKE = listOf("SBI", "INDUS")
    }
}
