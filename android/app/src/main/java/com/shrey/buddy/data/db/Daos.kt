package com.shrey.buddy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE deletedAt IS NULL AND kind = :kind ORDER BY occurredOn DESC, updatedAt DESC")
    fun observeKind(kind: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE deletedAt IS NULL AND kind = :kind")
    suspend fun allOfKind(kind: String): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: String): ItemEntity?

    /** Ticks whenever any item changes (widgets refresh off this). */
    @Query("SELECT MAX(updatedAt) FROM items")
    fun observeLastChange(): Flow<Long?>

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<ItemEntity>

    @Upsert
    suspend fun upsert(item: ItemEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans WHERE deletedAt IS NULL ORDER BY position, createdAt")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE deletedAt IS NULL ORDER BY position, createdAt")
    suspend fun all(): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun get(id: String): PlanEntity?

    @Query("SELECT * FROM plans WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<PlanEntity>

    @Upsert
    suspend fun upsert(plan: PlanEntity)
}

@Dao
interface BucketDao {
    @Query("SELECT * FROM buckets WHERE deletedAt IS NULL ORDER BY position, createdAt")
    fun observeAll(): Flow<List<BucketEntity>>

    @Query("SELECT * FROM buckets WHERE deletedAt IS NULL AND planId = :planId ORDER BY position, createdAt")
    suspend fun forPlan(planId: String): List<BucketEntity>

    @Query("SELECT * FROM buckets WHERE id = :id")
    suspend fun get(id: String): BucketEntity?

    @Query("SELECT * FROM buckets WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<BucketEntity>

    @Upsert
    suspend fun upsert(bucket: BucketEntity)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL ORDER BY status = 'done', position, updatedAt DESC")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE deletedAt IS NULL")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(entry: OutboxEntry)

    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    suspend fun peek(limit: Int): List<OutboxEntry>

    @Query("SELECT * FROM outbox WHERE tableName NOT IN (:excluded) ORDER BY seq LIMIT :limit")
    suspend fun peekExcluding(excluded: List<String>, limit: Int): List<OutboxEntry>

    @Query("DELETE FROM outbox WHERE seq IN (:seqs)")
    suspend fun remove(seqs: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE tableName = :tableName AND rowId = :rowId)")
    suspend fun hasPending(tableName: String, rowId: String): Boolean

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>
}

@Dao
interface CaptureDao {
    /** Returns -1 when the same message was already captured (live + scan overlap). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: CaptureEntity): Long

    @Query("SELECT * FROM capture_inbox ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureEntity>>

    @Query("SELECT COUNT(*) FROM capture_inbox WHERE status = :status")
    fun observeCount(status: String): Flow<Int>

    @Query("SELECT * FROM capture_inbox WHERE status = 'parsed' ORDER BY receivedAt DESC")
    fun observeParsed(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM capture_inbox WHERE kind = 'sms'")
    suspend fun allSms(): List<CaptureEntity>

    @Query("UPDATE capture_inbox SET status = :status, payload = :payload WHERE id = :id")
    suspend fun updateParse(id: Long, status: String, payload: String)

    @Query("DELETE FROM capture_inbox WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL ORDER BY isPrimary DESC, archived, name")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL")
    suspend fun all(): List<AccountEntity>

    @Upsert
    suspend fun upsert(account: AccountEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<TransactionEntity>

    @Query("SELECT id FROM transactions WHERE captureId = :captureId AND deletedAt IS NULL LIMIT 1")
    suspend fun byCapture(captureId: Long): String?

    @Query("SELECT id FROM transactions")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL")
    suspend fun all(): List<TransactionEntity>

    @Upsert
    suspend fun upsert(txn: TransactionEntity)
}

@Dao
interface VoiceEventDao {
    @Insert
    suspend fun insert(event: VoiceEvent)

    @Query("SELECT * FROM voice_events ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VoiceEvent>>

    @Query("SELECT COUNT(*) FROM voice_events WHERE kind = 'wake' AND at >= :since")
    fun observeWakeCount(since: Long): Flow<Int>

    @Query("SELECT * FROM voice_events WHERE kind = 'start' ORDER BY at DESC LIMIT 1")
    fun observeLastStart(): Flow<VoiceEvent?>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE tableName = :tableName")
    suspend fun get(tableName: String): SyncState?

    @Query("SELECT * FROM sync_state WHERE tableName = :tableName")
    fun observe(tableName: String): Flow<SyncState?>

    @Query("SELECT * FROM sync_state")
    fun observeAll(): Flow<List<SyncState>>

    @Upsert
    suspend fun put(state: SyncState)
}
