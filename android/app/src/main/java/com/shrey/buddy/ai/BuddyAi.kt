package com.shrey.buddy.ai

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.flowOn
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

@Serializable
data class ChatMessage(val role: String, val text: String)

@Serializable
data class ChatReply(
    val text: String = "",
    val tools: List<String> = emptyList(),
    val error: String? = null,
    val model: String? = null,
    val ms: Long = 0,
)

@Serializable
data class VoiceAction(
    val type: String,
    @SerialName("amount_rupees") val amountRupees: Double? = null,
    val payee: String? = null,
    val category: String? = null,
    val account: String? = null,
    val title: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    /** HH:mm, 24-hour. */
    @SerialName("due_time") val dueTime: String? = null,
    /** Local "YYYY-MM-DDTHH:mm". */
    @SerialName("remind_at") val remindAt: String? = null,
    val repeat: String? = null,
    val question: String? = null,
)

@Serializable
data class CommandReply(val actions: List<VoiceAction> = emptyList(), val speech: String = "", val error: String? = null)

@Serializable
data class ReceiptItem(val name: String = "", @SerialName("amount_rupees") val amountRupees: Double = 0.0)

@Serializable
data class ReceiptReply(
    val merchant: String = "",
    @SerialName("total_rupees") val totalRupees: Double = 0.0,
    val date: String? = null,
    val category: String = "other",
    val items: List<ReceiptItem> = emptyList(),
    val error: String? = null,
)

@Serializable
data class MealReply(
    val description: String = "",
    val kcal: Int = 0,
    @SerialName("protein_g") val proteinG: Int = 0,
    val error: String? = null,
)

@Serializable
data class BriefingReply(val title: String = "", val body: String = "", val error: String? = null)

@Serializable
data class InsightItem(val title: String = "", val body: String = "")

@Serializable
data class InsightsReply(val insights: List<InsightItem> = emptyList(), val error: String? = null)

/** Upcoming debit passed to the briefing (captures stay on the phone, so the server can't see them). */
@Serializable
data class UpcomingForAi(val payee: String, val amount: Double, val due: String?)

/** Client for the `buddy-ai` Edge Function. The Gemini key lives only on the server. */
class BuddyAi(private val supabase: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun receipt(imageBase64: String, mime: String): ReceiptReply = call(
        buildJsonObject { put("mode", "vision"); put("purpose", "receipt"); put("image", imageBase64); put("mime", mime) },
        ReceiptReply.serializer(),
    )

    suspend fun mealPhoto(imageBase64: String, mime: String): MealReply = call(
        buildJsonObject { put("mode", "vision"); put("purpose", "meal"); put("image", imageBase64); put("mime", mime) },
        MealReply.serializer(),
    )

    suspend fun mealText(text: String): MealReply = call(
        buildJsonObject { put("mode", "meal_text"); put("text", text) },
        MealReply.serializer(),
    )

    private fun briefingBody(kind: String, steps: List<Long>?, upcoming: List<UpcomingForAi>, screenMinutes: Int?) = buildJsonObject {
        put("mode", "briefing")
        put("kind", kind)
        put("today", LocalDate.now().toString())
        if (steps != null) putJsonArray("steps") { steps.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("upcoming", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(UpcomingForAi.serializer()), upcoming))
        if (screenMinutes != null) put("screenMinutes", screenMinutes)
    }

    suspend fun briefing(kind: String, steps: List<Long>?, upcoming: List<UpcomingForAi>, screenMinutes: Int?): BriefingReply =
        call(briefingBody(kind, steps, upcoming, screenMinutes), BriefingReply.serializer())

    suspend fun insights(steps: List<Long>?, upcoming: List<UpcomingForAi>, screenMinutes: Int?): InsightsReply =
        call(briefingBody("insights", steps, upcoming, screenMinutes), InsightsReply.serializer())

    private fun chatBody(mode: String, history: List<ChatMessage>, snapshot: JsonObject?) = buildJsonObject {
        put("mode", mode)
        put("today", LocalDate.now().toString())
        put("now", java.time.LocalTime.now().withSecond(0).withNano(0).toString())
        snapshot?.let { put("snapshot", it.toString()) }
        putJsonArray("messages") {
            history.forEach { m -> addJsonObject { put("role", m.role); put("text", m.text) } }
        }
    }

    suspend fun chat(history: List<ChatMessage>, snapshot: JsonObject? = null): ChatReply =
        call(chatBody("chat", history, snapshot), ChatReply.serializer())

    sealed interface StreamEvent {
        data class Text(val delta: String) : StreamEvent
        data class Status(val text: String) : StreamEvent
        data class Done(val model: String?, val ms: Long, val tools: List<String>) : StreamEvent
        data class Failed(val message: String) : StreamEvent
    }

    /**
     * Streams an answer (Server-Sent Events) so words appear as Gemini writes them. Uses a plain
     * HTTP connection because the Supabase client buffers whole responses.
     */
    fun chatStream(history: List<ChatMessage>, snapshot: JsonObject?): kotlinx.coroutines.flow.Flow<StreamEvent> = kotlinx.coroutines.flow.flow {
        val token = supabase.auth.currentAccessTokenOrNull() ?: run {
            emit(StreamEvent.Failed("Your session expired — sign out and back in."))
            return@flow
        }
        val conn = (java.net.URL("${com.shrey.buddy.BuildConfig.SUPABASE_URL}/functions/v1/buddy-ai").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 30_000 // between chunks; the server itself finishes within ~25 s
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("apikey", com.shrey.buddy.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        }
        try {
            conn.outputStream.use { it.write(chatBody("chat_stream", history, snapshot).toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                emit(StreamEvent.Failed(errorField.find(err)?.groupValues?.get(1) ?: "Buddy AI error ${conn.responseCode}"))
                return@flow
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val obj = runCatching { json.parseToJsonElement(line.removePrefix("data:").trim()) as JsonObject }.getOrNull() ?: continue
                    fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    when {
                        obj.containsKey("t") -> emit(StreamEvent.Text(str("t").orEmpty()))
                        obj.containsKey("status") -> emit(StreamEvent.Status(str("status").orEmpty()))
                        obj.containsKey("error") -> emit(StreamEvent.Failed(str("error").orEmpty()))
                        obj.containsKey("done") -> emit(
                            StreamEvent.Done(
                                model = str("model"),
                                ms = str("ms")?.toLongOrNull() ?: 0,
                                tools = (obj["tools"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                            ),
                        )
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Failed("Buddy took too long to answer. Try again."))
        } catch (e: java.io.IOException) {
            emit(StreamEvent.Failed("You seem to be offline."))
        } finally {
            conn.disconnect()
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun command(text: String, accounts: List<String>, categories: List<String>): CommandReply = call(
        buildJsonObject {
            put("mode", "command")
            put("text", text)
            put("today", LocalDate.now().toString())
            put("now", java.time.LocalTime.now().withSecond(0).withNano(0).toString())
            putJsonArray("accounts") { accounts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("categories") { categories.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        },
        CommandReply.serializer(),
    )

    suspend fun trainingPlan(
        goal: String, details: String, days: Int, experience: String, equipment: String, weeks: Int, stats: JsonObject,
        /** Weeks [fromWeek]..[toWeek] only; [soFar] is the plan built so far (outline + last week) for continuity. */
        fromWeek: Int = 1, toWeek: Int = weeks, soFar: JsonObject? = null,
    ): com.shrey.buddy.data.items.TrainingPlan = callLong(
        buildJsonObject {
            put("mode", "training_plan")
            put("goal", goal); put("details", details); put("days", days)
            put("experience", experience); put("equipment", equipment); put("weeks", weeks)
            put("stats", stats)
            put("from_week", fromWeek); put("to_week", toWeek)
            soFar?.let { put("so_far", it) }
        },
        com.shrey.buddy.data.items.TrainingPlan.serializer(),
    )

    /**
     * For slow, detailed generations (training plans): waits up to 2.5 minutes instead of the usual 60 s.
     * A plain HTTP call, because the Supabase client's timeout is fixed for every request.
     */
    private suspend fun <T> callLong(body: JsonObject, serializer: KSerializer<T>, timeoutMs: Int = 150_000): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val token = supabase.auth.currentAccessTokenOrNull()
                ?: throw BuddyAiException("Your session expired — sign out and back in.", IllegalStateException("no session"))
            val conn = (java.net.URL("${com.shrey.buddy.BuildConfig.SUPABASE_URL}/functions/v1/buddy-ai").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = timeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("apikey", com.shrey.buddy.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val ok = conn.responseCode in 200..299
                val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                if (!ok) throw IllegalStateException(errorField.find(text)?.groupValues?.get(1) ?: "Buddy AI error ${conn.responseCode}")
                json.decodeFromString(serializer, text)
            } catch (e: BuddyAiException) {
                throw e
            } catch (e: Exception) {
                throw BuddyAiException(friendlyMessage(e), e)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun healthReport(stats: JsonObject): com.shrey.buddy.data.items.HealthReport = call(
        buildJsonObject { put("mode", "health_report"); put("stats", stats) },
        com.shrey.buddy.data.items.HealthReport.serializer(),
    )

    private suspend fun <T> call(body: JsonObject, serializer: KSerializer<T>): T {
        try {
            val response = supabase.functions.invoke(function = "buddy-ai", body = body)
            return json.decodeFromString(serializer, response.bodyAsText())
        } catch (e: Exception) {
            throw BuddyAiException(friendlyMessage(e), e)
        }
    }
}

class BuddyAiException(message: String, cause: Throwable) : Exception(message, cause)

private val errorField = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** The SDK's exception text includes request headers; show only what the user can act on. */
internal fun friendlyMessage(e: Exception): String {
    val raw = e.message.orEmpty()
    errorField.find(raw)?.let { return it.groupValues[1].replace("\\\"", "\"").replace("\\n", " ") }
    val lower = raw.lowercase()
    return when {
        "timeout" in lower || "timed out" in lower -> "Buddy took too long to answer. Try again."
        "unable to resolve host" in lower || "failed to connect" in lower || "network is unreachable" in lower ->
            "You seem to be offline."
        "401" in lower || "jwt" in lower -> "Your session expired — sign out and back in."
        else -> raw.lineSequence().firstOrNull()?.take(160)?.ifBlank { null } ?: e.javaClass.simpleName
    }
}
