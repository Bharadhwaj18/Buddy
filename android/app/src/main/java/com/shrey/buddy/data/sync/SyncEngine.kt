package com.shrey.buddy.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.db.SyncState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Outbox push + per-table cursor pull (DESIGN.md §6). Push first so a pull never resurrects
 * stale server state over a local edit that hasn't been sent yet.
 */
class SyncEngine(
    private val db: BuddyDatabase,
    private val supabase: SupabaseClient,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Parents before children (plans → buckets → tasks, accounts → transactions). */
    private val tables: List<SyncTable<*, *, *>> = listOf(
        PlansTable(db), BucketsTable(db), TasksTable(db), AccountsTable(db), TransactionsTable(db), ItemsTable(db),
    )
    private val byName = tables.associateBy { it.name }

    /**
     * Tables are isolated: if one fails (e.g. its server migration hasn't been run yet), the others
     * still sync, and the failing table's changes stay queued for the next attempt.
     */
    suspend fun sync(): Result<Unit> = mutex.withLock {
        runCatching {
            supabase.auth.sessionStatus.first { it !is SessionStatus.Initializing }
            if (supabase.auth.currentSessionOrNull() == null) return@runCatching
            val failures = mutableMapOf<String, Throwable>()
            push(failures)
            for (table in tables) {
                if (table.name in failures) continue
                runCatching { pull(table) }.onFailure { failures[table.name] = it }
            }
            for ((name, e) in failures) {
                Log.w(TAG, "sync failed for $name", e)
                val prev = db.syncStateDao().get(name)
                db.syncStateDao().put(SyncState(name, prev?.cursor, prev?.lastSyncedAt, "$name: ${e.message ?: e.javaClass.simpleName}"))
            }
            if (failures.isNotEmpty()) error("Sync failed for ${failures.keys.joinToString()}")
        }.onFailure { e -> Log.w(TAG, "sync failed", e) }
    }

    private suspend fun push(failures: MutableMap<String, Throwable>) {
        val outbox = db.outboxDao()
        while (true) {
            val batch = outbox.peekExcluding(failures.keys.toList(), PAGE)
            if (batch.isEmpty()) return
            val grouped = batch.groupBy { it.tableName }
            val sent = mutableListOf<Long>()
            for (table in tables) {
                val entries = grouped[table.name] ?: continue
                runCatching { pushRows(table, entries.map { it.rowId }.distinct()) }
                    .onSuccess { sent += entries.map { it.seq } }
                    .onFailure { failures[table.name] = it }
            }
            (grouped.keys - byName.keys).forEach { name ->
                Log.w(TAG, "dropping outbox entries for unknown table $name")
                sent += grouped.getValue(name).map { it.seq }
            }
            // Only remove what we sent; edits made during the push keep their own (newer) entries.
            outbox.remove(sent)
        }
    }

    private suspend fun <E, P, R> pushRows(table: SyncTable<E, P, R>, ids: List<String>) {
        val rows = table.loadLocal(ids)
        if (rows.isEmpty()) return
        val payload: List<JsonObject> = rows.map { json.encodeToJsonElement(table.pushSerializer, table.toPush(it)).jsonObject }
        supabase.from(table.name).upsert(payload) { onConflict = "id" }
    }

    private suspend fun <E, P, R> pull(table: SyncTable<E, P, R>) {
        val stateDao = db.syncStateDao()
        var cursor = stateDao.get(table.name)?.cursor
        while (true) {
            val since = cursor
            val page = supabase.from(table.name).select {
                if (since != null) filter { gte("server_updated_at", since) }
                order("server_updated_at", Order.ASCENDING)
                limit(PAGE.toLong())
            }.decodeList<JsonObject>().map { json.decodeFromJsonElement(table.rowSerializer, it) }
            if (page.isEmpty()) break

            db.withTransaction {
                for (row in page) {
                    val id = table.rowId(row)
                    val local = table.localUpdatedAt(id)
                    val localIsNewerAndUnsent = local != null &&
                        local >= table.rowUpdatedAt(row) &&
                        db.outboxDao().hasPending(table.name, id)
                    if (!localIsNewerAndUnsent) table.applyRow(row)
                }
            }

            val newCursor = table.rowServerUpdatedAt(page.last())
            // gte re-reads the boundary row each time (harmless, upserts are idempotent);
            // stop when the page is short or the cursor can't advance.
            val done = page.size < PAGE || newCursor == cursor
            cursor = newCursor
            if (done) break
        }
        stateDao.put(SyncState(table.name, cursor, System.currentTimeMillis(), lastError = null))
    }

    private companion object {
        const val TAG = "BuddySync"
        const val PAGE = 200
    }
}
