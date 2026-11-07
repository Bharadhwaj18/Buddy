package com.shrey.buddy.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class, OutboxEntry::class, SyncState::class, CaptureEntity::class, VoiceEvent::class,
        AccountEntity::class, TransactionEntity::class, PlanEntity::class, BucketEntity::class, ItemEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class BuddyDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun captureDao(): CaptureDao
    abstract fun voiceEventDao(): VoiceEventDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun planDao(): PlanDao
    abstract fun bucketDao(): BucketDao
    abstract fun itemDao(): ItemDao
}
