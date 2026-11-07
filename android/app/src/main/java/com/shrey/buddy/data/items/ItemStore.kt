package com.shrey.buddy.data.items

import androidx.room.withTransaction
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.db.ItemEntity
import com.shrey.buddy.data.db.OutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

/** A decoded item: the row plus its typed payload. */
data class Item<T>(val entity: ItemEntity, val data: T) {
    val id: String get() = entity.id
    val date: LocalDate? get() = entity.occurredOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val refId: String? get() = entity.refId
}

/**
 * Typed access to the shared `items` table. Every write goes through the outbox, so all
 * modules sync without per-module plumbing.
 */
class ItemStore(
    private val db: BuddyDatabase,
    private val onLocalChange: () -> Unit,
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun <T> observe(kind: String, serializer: KSerializer<T>): Flow<List<Item<T>>> =
        db.itemDao().observeKind(kind).map { rows -> rows.mapNotNull { decode(it, serializer) } }

    suspend fun <T> all(kind: String, serializer: KSerializer<T>): List<Item<T>> =
        db.itemDao().allOfKind(kind).mapNotNull { decode(it, serializer) }

    suspend fun <T> get(id: String, serializer: KSerializer<T>): Item<T>? =
        db.itemDao().get(id)?.takeIf { it.deletedAt == null }?.let { decode(it, serializer) }

    fun <T> decode(row: ItemEntity, serializer: KSerializer<T>): Item<T>? =
        runCatching { Item(row, json.decodeFromString(serializer, row.dataJson)) }.getOrNull()

    /** Insert or update. Pass [id] to update (or for deterministic IDs like one-journal-per-day). */
    suspend fun <T> put(
        kind: String,
        data: T,
        serializer: KSerializer<T>,
        id: String? = null,
        occurredOn: LocalDate? = null,
        refId: String? = null,
        notify: Boolean = true,
    ): String {
        val now = System.currentTimeMillis()
        val itemId = id ?: UUID.randomUUID().toString()
        db.withTransaction {
            val prev = db.itemDao().get(itemId)
            write(
                ItemEntity(
                    id = itemId,
                    kind = kind,
                    dataJson = json.encodeToString(serializer, data),
                    occurredOn = occurredOn?.toString() ?: prev?.occurredOn,
                    refId = refId ?: prev?.refId,
                    createdAt = prev?.createdAt ?: now,
                    updatedAt = maxOf(now, (prev?.updatedAt ?: 0) + 1),
                    deletedAt = null,
                ),
            )
        }
        if (notify) onLocalChange()
        return itemId
    }

    suspend fun delete(id: String) {
        val prev = db.itemDao().get(id) ?: return
        val now = System.currentTimeMillis()
        db.withTransaction { write(prev.copy(deletedAt = now, updatedAt = maxOf(now, prev.updatedAt + 1))) }
        onLocalChange()
    }

    private suspend fun write(item: ItemEntity) {
        db.itemDao().upsert(item)
        db.outboxDao().enqueue(OutboxEntry(tableName = "items", rowId = item.id, enqueuedAt = System.currentTimeMillis()))
    }

    companion object {
        fun stableId(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
    }
}
