package com.shrey.buddy.desktop

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.time.Instant

/** One write that couldn't be sent yet (offline); retried on every poll. */
@Serializable
data class PendingWrite(val table: String, val row: JsonObject, val onConflict: String)

/** The same Supabase backend as the phone app, signed in as the same user. */
class Cloud(private val deviceId: String) {
    val supabase: SupabaseClient = createSupabaseClient(BuildInfo.SUPABASE_URL, BuildInfo.SUPABASE_KEY) {
        install(Auth) {
            alwaysAutoRefresh = true
            autoLoadFromStorage = true
        }
        install(Postgrest)
        install(Functions)
        install(Storage)
    }

    val deviceName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "Laptop"

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() = runCatching { supabase.auth.signOut() }

    val email: String? get() = supabase.auth.currentUserOrNull()?.email

    // ---------------- tasks ----------------

    /** Every task that isn't finished or deleted, plus those finished today (for "2 of 6 done"). */
    suspend fun openTasks(): List<Task> {
        val startOfToday = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString()
        return supabase.from("tasks").select {
            filter {
                exact("deleted_at", null)
                or {
                    neq("status", "done")
                    gte("completed_at", startOfToday)
                }
            }
        }.decodeList<JsonObject>().map(::Task)
    }

    suspend fun firstPlanAndBucket(): Pair<String?, String?> {
        val plan = supabase.from("plans").select {
            filter { exact("deleted_at", null); eq("archived", false) }
            order("position", Order.ASCENDING)
            limit(1)
        }.decodeList<JsonObject>().firstOrNull()?.str("id")
        val bucket = plan?.let { p ->
            supabase.from("buckets").select {
                filter { eq("plan_id", p); exact("deleted_at", null) }
                order("position", Order.ASCENDING)
                limit(1)
            }.decodeList<JsonObject>().firstOrNull()?.str("id")
        }
        return plan to bucket
    }

    // ---------------- writes (queued when offline) ----------------

    private val outboxFile = "outbox.json"
    private val outboxSerializer = ListSerializer(PendingWrite.serializer())
    private val outbox = (Local.read(outboxFile, outboxSerializer) ?: emptyList()).toMutableList()

    /** Sends now if possible; otherwise keeps it for the next [flush]. Returns true when it was sent. */
    suspend fun write(table: String, row: JsonObject, onConflict: String = "id"): Boolean {
        synchronized(outbox) {
            outbox += PendingWrite(table, row, onConflict)
            Local.write(outboxFile, outboxSerializer, outbox.toList())
        }
        return flush()
    }

    /** Sends queued writes in order. Stops at the first failure so order is kept. */
    suspend fun flush(): Boolean {
        while (true) {
            val next = synchronized(outbox) { outbox.firstOrNull() } ?: return true
            val ok = runCatching {
                supabase.from(next.table).upsert(listOf(next.row)) { onConflict = next.onConflict }
            }.onFailure { Log.w("write ${next.table} failed", it) }.isSuccess
            if (!ok) return false
            synchronized(outbox) {
                outbox.remove(next)
                Local.write(outboxFile, outboxSerializer, outbox.toList())
            }
        }
    }

    val pendingWrites: Int get() = synchronized(outbox) { outbox.size }

    // ---------------- laptop first, phone later ----------------

    /** "This laptop is on" (the phone waits 30 s for it before ringing). */
    suspend fun heartbeat() = runCatching {
        supabase.from("devices").upsert(
            listOf(buildJsonObject { put("id", deviceId); put("name", deviceName); put("kind", "laptop") }),
        ) { onConflict = "id" }
    }.onFailure { Log.w("heartbeat failed", it) }

    /** Recent answers to reminders, from any device. */
    suspend fun recentAcks(since: Instant): List<Ack> =
        supabase.from("reminder_acks").select {
            filter { gte("acked_at", since.toString()) }
            order("acked_at", Order.ASCENDING)
        }.decodeList<JsonObject>().mapNotNull(Ack::of)

    suspend fun ack(taskId: String, remindAt: Long, action: String, snoozeUntil: Long? = null) = write(
        "reminder_acks",
        buildJsonObject {
            put("task_id", taskId)
            put("remind_at", Instant.ofEpochMilli(remindAt).toString())
            put("action", action)
            put("snooze_until", snoozeUntil?.let { Instant.ofEpochMilli(it).toString() })
            put("device", "laptop:$deviceName")
        },
        onConflict = "task_id,remind_at",
    )

    // ---------------- AI ----------------

    /** Calls the buddy-ai Edge Function (same one the phone uses) and returns the raw reply. */
    suspend fun ai(body: JsonObject): String =
        supabase.functions.invoke(function = "buddy-ai", body = body).bodyAsText()

    // ---------------- updates ----------------

    suspend fun download(path: String): ByteArray = supabase.storage.from("releases").downloadAuthenticated(path)
}
