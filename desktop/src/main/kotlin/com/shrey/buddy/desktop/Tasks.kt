package com.shrey.buddy.desktop

import com.shrey.buddy.data.tasks.RepeatRule
import com.shrey.buddy.data.tasks.nextOccurrenceTimes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun String.isoMillis(): Long = OffsetDateTime.parse(this).toInstant().toEpochMilli()
fun Long.iso(): String = Instant.ofEpochMilli(this).toString()

fun JsonObject.with(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    toMutableMap().apply {
        for ((k, v) in pairs) put(
            k,
            when (v) {
                null -> JsonNull
                is JsonElement -> v
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            },
        )
    },
)

/** A task row exactly as the server has it. Edits copy the whole row, so columns the laptop doesn't know survive. */
class Task(val raw: JsonObject) {
    val id: String = raw.str("id")!!
    val title: String = raw.str("title").orEmpty()
    val status: String = raw.str("status") ?: "todo"
    val remindAt: Long? = raw.str("remind_at")?.isoMillis()
    val dueAt: Long? = raw.str("due_at")?.isoMillis()
    val dueAllDay: Boolean = (raw["due_all_day"] as? JsonPrimitive)?.booleanOrNull ?: true
    val repeat: String? = raw.str("repeat")
    val updatedAt: Long = raw.str("updated_at")?.isoMillis() ?: 0L
    val bucketId: String? = raw.str("bucket_id")
    val position: Double = (raw["position"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    val priority: String = raw.str("priority") ?: "normal"
    val isDone: Boolean get() = status == "done"
    val completedAt: Long? = raw.str("completed_at")?.isoMillis()
    val myDay: String? = raw.str("my_day")

    /** Ready to upsert: the server fills user_id and server_updated_at itself. */
    fun forPush(): JsonObject = JsonObject(raw - "server_updated_at" - "user_id")

    override fun equals(other: Any?) = other is Task && other.raw == raw
    override fun hashCode() = raw.hashCode()
}

/** What someone did about one ring of a reminder (on the phone or a laptop). */
data class Ack(
    val taskId: String,
    val remindAt: Long,
    val action: String,
    val snoozeUntil: Long?,
    val ackedAt: Long,
    val device: String?,
) {
    companion object {
        fun of(o: JsonObject): Ack? = runCatching {
            Ack(
                taskId = o.str("task_id")!!, remindAt = o.str("remind_at")!!.isoMillis(), action = o.str("action")!!,
                snoozeUntil = o.str("snooze_until")?.isoMillis(), ackedAt = o.str("acked_at")!!.isoMillis(),
                device = o.str("device"),
            )
        }.getOrNull()
    }
}

/** Task edits, done the same way the phone does them (TaskRepository), so both stay consistent. */
object TaskEdits {
    private fun nextStamp(t: Task, now: Long) = maxOf(now, t.updatedAt + 1)

    /**
     * Marks [t] done. A repeating task gets its next occurrence as a new task (the finished copy stops
     * repeating), exactly like on the phone. Returns the rows to upsert.
     */
    fun complete(t: Task, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<JsonObject> {
        val rule = RepeatRule.parse(t.repeat)
        val spawn = rule != null && (t.dueAt != null || t.remindAt != null)
        val done = t.forPush().with(
            "status" to "done", "completed_at" to now.iso(), "updated_at" to nextStamp(t, now).iso(),
            "repeat" to if (spawn) null else t.repeat,
        )
        if (!spawn) return listOf(done)
        val (due, remind) = nextOccurrenceTimes(rule!!, t.dueAt, t.remindAt, now, zone)
        val checklist = (t.raw["checklist"] as? JsonArray)?.map { item ->
            runCatching { item.jsonObject.with("done" to false) }.getOrDefault(item)
        } ?: emptyList()
        val next = t.forPush().with(
            "id" to UUID.randomUUID().toString(), "status" to "todo", "created_at" to now.iso(), "updated_at" to now.iso(),
            "completed_at" to null, "my_day" to null, "due_at" to due?.iso(), "remind_at" to remind?.iso(),
            "checklist" to JsonArray(checklist),
        )
        return listOf(done, next)
    }

    /** Skips a repeating task to its next time without completing it (dismissing its reminder). */
    fun skip(t: Task, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JsonObject? {
        val rule = RepeatRule.parse(t.repeat) ?: return null
        val (due, remind) = nextOccurrenceTimes(rule, t.dueAt, t.remindAt, now, zone)
        return t.forPush().with("due_at" to due?.iso(), "remind_at" to remind?.iso(), "updated_at" to nextStamp(t, now).iso())
    }

    /** Same time tomorrow (reminder and due date move together, like the phone's "Tomorrow"). */
    fun tomorrow(t: Task, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JsonObject {
        val day = LocalDate.now(zone).plusDays(1)
        fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).with(day).toInstant().toEpochMilli()
        val newDue = t.dueAt?.let(::at)
        val shift = if (t.dueAt != null && newDue != null) newDue - t.dueAt else null
        val newRemind = t.remindAt?.let { r -> if (shift != null) r + shift else at(r) }
        return t.forPush().with(
            "due_at" to newDue?.iso(), "remind_at" to newRemind?.iso(), "my_day" to null,
            "updated_at" to nextStamp(t, now).iso(),
        )
    }

    /** A brand-new task row, filed like the phone files one (first plan, first bucket). */
    fun newTask(
        title: String,
        planId: String?,
        bucketId: String?,
        position: Double,
        dueAt: Long?,
        dueAllDay: Boolean,
        remindAt: Long?,
        repeat: String?,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): JsonObject = JsonObject(emptyMap()).with(
        "id" to UUID.randomUUID().toString(), "title" to title.trim(), "notes" to null, "status" to "todo",
        "created_at" to now.iso(), "updated_at" to now.iso(), "deleted_at" to null,
        "plan_id" to planId, "bucket_id" to bucketId, "priority" to "normal",
        "due_at" to dueAt?.iso(), "my_day" to dueAt?.let { d ->
            Instant.ofEpochMilli(d).atZone(zone).toLocalDate().takeIf { it == LocalDate.now(zone) }?.toString()
        },
        "position" to position, "completed_at" to null, "checklist" to JsonArray(emptyList()),
        "due_all_day" to (dueAllDay || dueAt == null), "remind_at" to remindAt?.iso(), "repeat" to repeat,
    )

    fun delete(t: Task, now: Long = System.currentTimeMillis()): JsonObject =
        t.forPush().with("deleted_at" to now.iso(), "updated_at" to nextStamp(t, now).iso())

    fun deleteRow(row: JsonObject, now: Long = System.currentTimeMillis()): JsonObject =
        Task(row).let { delete(it, now) }
}

fun ZonedDateTime.millis() = toInstant().toEpochMilli()

/** A tiny log in %APPDATA%\Buddy\buddy.log (kept short). */
object Log {
    private val file = File(Local.dir, "buddy.log")
    @Synchronized
    fun w(msg: String, e: Throwable? = null) {
        runCatching {
            if (file.length() > 512 * 1024) file.writeText("")
            val trace = e?.let { StringWriter().also { sw -> it.printStackTrace(PrintWriter(sw)) }.toString().lines().take(6).joinToString("\n") }
            file.appendText("${Instant.now()} $msg${trace?.let { "\n$it" } ?: ""}\n")
        }
    }
}
