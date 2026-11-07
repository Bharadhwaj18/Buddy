package com.shrey.buddy.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Mirrors public.tasks. Timestamps are epoch millis locally, ISO-8601 on the wire. */
@Entity(tableName = "tasks", indices = [Index("planId"), Index("bucketId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    /** todo (not started) | in_progress | done */
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "NULL") val planId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val bucketId: String? = null,
    /** low | normal | high | urgent */
    @ColumnInfo(defaultValue = "normal") val priority: String = "normal",
    /** Epoch millis: start of the due day when [dueAllDay], otherwise the exact due time. */
    @ColumnInfo(defaultValue = "NULL") val dueAt: Long? = null,
    @ColumnInfo(defaultValue = "1") val dueAllDay: Boolean = true,
    /** When Buddy rings (call-style reminder), epoch millis. */
    @ColumnInfo(defaultValue = "NULL") val remindAt: Long? = null,
    /** Repeat rule key (see Repeat); completing the task creates the next one. */
    @ColumnInfo(defaultValue = "NULL") val repeat: String? = null,
    /** ISO date the task was added to My Day; it's "in My Day" only while this equals today. */
    @ColumnInfo(defaultValue = "NULL") val myDay: String? = null,
    @ColumnInfo(defaultValue = "0") val position: Double = 0.0,
    @ColumnInfo(defaultValue = "NULL") val completedAt: Long? = null,
    /** JSON array of ChecklistItem. */
    @ColumnInfo(defaultValue = "[]") val checklistJson: String = "[]",
)

/** Mirrors public.items — one row of any "life" module; [dataJson] is the module's payload. */
@Entity(tableName = "items", indices = [Index("kind"), Index("refId"), Index("occurredOn")])
data class ItemEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val dataJson: String,
    /** ISO date, when the row belongs to a day (habit logs, workouts, journal, meals...). */
    val occurredOn: String?,
    val refId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** Mirrors public.plans — a Planner board. */
@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String?,
    val position: Double,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** Mirrors public.buckets — a column on a plan's board. */
@Entity(tableName = "buckets", indices = [Index("planId")])
data class BucketEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val name: String,
    val position: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** One row per local write; drained by the sync engine. Written in the same transaction as the data. */
@Entity(tableName = "outbox", indices = [Index("tableName", "rowId")])
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val tableName: String,
    val rowId: String,
    val enqueuedAt: Long,
)

/**
 * Raw captures (DESIGN.md §5). Local only — never synced; raw SMS text must not leave the device.
 * Only bank transactions, mandates and unrecognised money messages are stored; OTPs and info messages never are.
 */
@Entity(tableName = "capture_inbox", indices = [Index(value = ["bodyHash"], unique = true)])
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val source: String,
    val receivedAt: Long,
    val rawText: String,
    val bodyHash: String,
    /** parsed | unrecognised */
    val status: String,
    /** SmsParse as JSON. */
    val payload: String,
    /** live (receiver) | scan (inbox backfill) */
    val via: String,
)

/** Mirrors public.accounts. Auto-created from SMS (bank + last4) or added manually. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bank: String?,
    /** savings | current | credit_card | wallet | cash | loan | other */
    val kind: String,
    val last4: String?,
    val isPrimary: Boolean,
    val includeInTotals: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    /** Balance the user typed in, and when. */
    @ColumnInfo(defaultValue = "NULL") val manualBalanceMinor: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val manualBalanceAt: Long? = null,
)

/** Mirrors public.transactions. [captureId] is local only (links back to the raw SMS, never synced). */
@Entity(tableName = "transactions", indices = [Index("accountId"), Index("occurredAt"), Index("captureId")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String?,
    /** debit | credit */
    val direction: String,
    val amountMinor: Long,
    val occurredAt: Long,
    val payee: String?,
    val counterpartyRaw: String?,
    val channel: String?,
    val reference: String?,
    val category: String,
    /** auto | user — user choices are never overwritten by rules. */
    val categorySource: String,
    val note: String?,
    val isTransfer: Boolean,
    val transferGroup: String?,
    /** sms | manual | voice | import */
    val source: String,
    val dedupeKey: String,
    val balanceAfterMinor: Long?,
    val captureId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** Wake-word spike log (local only): detections, transcribed commands, service lifecycle, errors. */
@Entity(tableName = "voice_events")
data class VoiceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** wake | command | start | stop | error */
    val kind: String,
    val text: String?,
    val batteryPct: Int?,
)

/** Per-table pull cursor (server_updated_at of the last row seen) plus last-run status. */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val tableName: String,
    val cursor: String?,
    val lastSyncedAt: Long?,
    val lastError: String?,
)
