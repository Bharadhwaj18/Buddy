package com.shrey.buddy.data.sync

import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.ChecklistItem
import com.shrey.buddy.data.tasks.Checklists
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** What we send. user_id and server_updated_at are set by the server (default auth.uid() / trigger). */
@Serializable
data class TaskPushDto(
    val id: String,
    val title: String,
    val notes: String?,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("plan_id") val planId: String?,
    @SerialName("bucket_id") val bucketId: String?,
    val priority: String,
    @SerialName("due_at") val dueAt: String?,
    @SerialName("my_day") val myDay: String?,
    val position: Double,
    @SerialName("completed_at") val completedAt: String?,
    val checklist: List<ChecklistItem>,
    @SerialName("due_all_day") val dueAllDay: Boolean,
    @SerialName("remind_at") val remindAt: String?,
    val repeat: String?,
)

/** What we receive. New columns default so rows from before the Planner migration still decode. */
@Serializable
data class TaskRowDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("server_updated_at") val serverUpdatedAt: String,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("bucket_id") val bucketId: String? = null,
    val priority: String = "normal",
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("my_day") val myDay: String? = null,
    val position: Double = 0.0,
    @SerialName("completed_at") val completedAt: String? = null,
    val checklist: List<ChecklistItem> = emptyList(),
    @SerialName("due_all_day") val dueAllDay: Boolean = true,
    @SerialName("remind_at") val remindAt: String? = null,
    val repeat: String? = null,
)

fun TaskEntity.toPushDto() = TaskPushDto(
    id = id,
    title = title,
    notes = notes,
    status = status,
    createdAt = createdAt.toIso(),
    updatedAt = updatedAt.toIso(),
    deletedAt = deletedAt?.toIso(),
    planId = planId,
    bucketId = bucketId,
    priority = priority,
    dueAt = dueAt?.toIso(),
    myDay = myDay,
    position = position,
    completedAt = completedAt?.toIso(),
    checklist = Checklists.decode(checklistJson),
    dueAllDay = dueAllDay,
    remindAt = remindAt?.toIso(),
    repeat = repeat,
)

fun TaskRowDto.toEntity() = TaskEntity(
    id = id,
    title = title,
    notes = notes,
    status = status,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    deletedAt = deletedAt?.toEpochMillis(),
    planId = planId,
    bucketId = bucketId,
    priority = priority,
    dueAt = dueAt?.toEpochMillis(),
    myDay = myDay,
    position = position,
    completedAt = completedAt?.toEpochMillis(),
    checklistJson = Checklists.encode(checklist),
    dueAllDay = dueAllDay,
    remindAt = remindAt?.toEpochMillis(),
    repeat = repeat,
)

internal fun Long.toIso(): String = Instant.ofEpochMilli(this).toString()
internal fun String.toEpochMillis(): Long = OffsetDateTime.parse(this).toInstant().toEpochMilli()

/** Start of [date] in the device time zone, as epoch millis (used for due dates). */
fun LocalDate.startMillis(zone: ZoneId = ZoneId.systemDefault()): Long = atStartOfDay(zone).toInstant().toEpochMilli()
