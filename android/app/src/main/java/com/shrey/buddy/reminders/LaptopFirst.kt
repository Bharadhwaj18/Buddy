package com.shrey.buddy.reminders

import android.content.Context
import android.util.Log
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.data.db.TaskEntity
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Laptop first, phone 30 s later (the Buddy laptop app).
 *
 * When a reminder is due and a laptop said "I'm on" in the last couple of minutes (table `devices`),
 * the phone waits [HEAD_START_MS] and then looks in `reminder_acks`: answered on the laptop → stay
 * quiet (or follow its snooze); not answered → ring as usual. Any doubt (no network, tables missing,
 * laptop off) → ring right away, so a reminder is never lost.
 */
object LaptopFirst {
    private const val TAG = "BuddyLaptopFirst"
    const val HEAD_START_MS = 30_000L
    private const val LAPTOP_ACTIVE_MS = 150_000L
    /** Server and phone clocks differ a little. */
    private const val CLOCK_SLACK_MS = 5_000L

    sealed interface Gate {
        data object Ring : Gate
        data class Wait(val until: Long) : Gate
        /** Answered on the laptop (done, dismissed, moved or snoozed). */
        data object Handled : Gate
    }

    private fun prefs(context: Context) = context.getSharedPreferences("laptop_first", Context.MODE_PRIVATE)
    private fun supabase(context: Context) = (context.applicationContext as BuddyApp).container.supabase

    /** Decides what to do about [task] whose reminder is due at [at] (its snoozed time, if snoozed). */
    suspend fun gate(context: Context, task: TaskEntity, at: Long, now: Long = System.currentTimeMillis()): Gate {
        val base = task.remindAt ?: return Gate.Ring
        val p = prefs(context)
        val waitingFor = p.getLong("wait:${task.id}", -1)
        if (waitingFor != at) {
            // First look at this ring. The phone woke late (Doze, off): don't add more delay.
            if (now > at + HEAD_START_MS) return Gate.Ring
            val laptopOn = withTimeoutOrNull(4_000) { runCatching { laptopSeenRecently(context, now) }.getOrDefault(false) } ?: false
            if (!laptopOn) return Gate.Ring
            p.edit().putLong("wait:${task.id}", at).apply()
            return Gate.Wait(at + HEAD_START_MS)
        }
        if (now < at + HEAD_START_MS - 500) return Gate.Wait(at + HEAD_START_MS)
        p.edit().remove("wait:${task.id}").apply()
        val ack = withTimeoutOrNull(5_000) { runCatching { fetchAck(context, task.id, base) }.getOrNull() }
        if (ack == null || ack.ackedAt < at - CLOCK_SLACK_MS) return Gate.Ring
        return when (ack.action) {
            "snoozed" -> {
                val until = ack.snoozeUntil?.takeIf { it > now } ?: return Gate.Ring
                ReminderScheduler.snoozeUntil(context, task, until)
                Gate.Handled
            }
            else -> Gate.Handled
        }
    }

    internal suspend fun laptopSeenRecently(context: Context, now: Long): Boolean {
        val row = supabase(context).from("devices").select {
            filter { eq("kind", "laptop") }
            order("last_seen", Order.DESCENDING)
            limit(1)
        }.decodeList<JsonObject>().firstOrNull() ?: return false
        val seen = row.time("last_seen") ?: return false
        return now - seen < LAPTOP_ACTIVE_MS
    }

    private data class Ack(val action: String, val snoozeUntil: Long?, val ackedAt: Long)

    private suspend fun fetchAck(context: Context, taskId: String, base: Long): Ack? {
        val row = supabase(context).from("reminder_acks").select {
            filter {
                eq("task_id", taskId)
                eq("remind_at", Instant.ofEpochMilli(base).toString())
            }
        }.decodeList<JsonObject>().firstOrNull() ?: return null
        return Ack(
            action = row.text("action") ?: return null,
            snoozeUntil = row.time("snooze_until"),
            ackedAt = row.time("acked_at") ?: return null,
        )
    }

    /** Tells the laptop what was done here, so its pop-up closes too. Best effort. */
    suspend fun ack(context: Context, task: TaskEntity, action: String, snoozeUntil: Long? = null) {
        val base = task.remindAt ?: return
        withTimeoutOrNull(5_000) {
            runCatching {
                supabase(context).from("reminder_acks").upsert(
                    listOf(
                        buildJsonObject {
                            put("task_id", task.id)
                            put("remind_at", Instant.ofEpochMilli(base).toString())
                            put("action", action)
                            put("snooze_until", snoozeUntil?.let { Instant.ofEpochMilli(it).toString() })
                            put("device", "phone")
                        },
                    ),
                ) { onConflict = "task_id,remind_at" }
            }.onFailure { Log.w(TAG, "ack failed", it) }
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.time(key: String) = text(key)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}
