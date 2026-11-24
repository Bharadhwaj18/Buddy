package com.shrey.buddy.desktop

import com.shrey.buddy.data.tasks.QuickParse
import com.shrey.buddy.data.tasks.RepeatRule
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Serializable
data class AiAction(
    val type: String,
    val title: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("remind_at") val remindAt: String? = null,
    val repeat: String? = null,
    val question: String? = null,
)

@Serializable
data class AiCommandReply(val actions: List<AiAction> = emptyList(), val speech: String = "", val error: String? = null)

@Serializable
private data class AiChatReply(val text: String = "", val error: String? = null)

/** Times for a new task, worked out the same way the phone does. */
data class TaskTimes(val title: String, val dueAt: Long?, val dueAllDay: Boolean, val remindAt: Long?, val repeat: String?)

/** What the bar will do with what you typed or said. Saves wait for the 2-second "Cancel" window. */
sealed interface Step {
    /** Something to save. [commit] stores it and returns the confirmation to show. */
    data class Save(val kind: String, val title: String, val detail: String, val commit: suspend () -> String) : Step
    /** An answer to a question (nothing is saved). */
    data class Answer(val text: String) : Step
    /** A short message (didn't understand, not supported here). */
    data class Info(val text: String) : Step
}

object Commands {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun LocalDateTime.ms() = atZone(zone).toInstant().toEpochMilli()

    /** An AI create_task action → task times (mirrors VoiceCommands on the phone). */
    fun timesFromAi(a: AiAction): TaskTimes? {
        val title = a.title?.trim().orEmpty().ifEmpty { return null }
        val due = a.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val time = a.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.takeIf { due != null }
        val remind = a.remindAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        return TaskTimes(
            title = title,
            dueAt = due?.let { d -> time?.let { d.atTime(it).ms() } ?: d.atStartOfDay().ms() },
            dueAllDay = time == null,
            remindAt = remind?.ms(),
            repeat = RepeatRule.parse(a.repeat)?.encode(),
        )
    }

    /** Offline fallback: the phone's own quick-add parser (mirrors AppViewModel.addParsed). */
    fun timesFromText(text: String, now: LocalDateTime = LocalDateTime.now()): TaskTimes? {
        val p = QuickParse.parse(text, now)
        if (p.title.isBlank()) return null
        val due = p.date?.let { d -> (p.time?.let { d.atTime(it) } ?: d.atStartOfDay()).ms() }
        return TaskTimes(
            title = p.title,
            dueAt = due,
            dueAllDay = p.time == null,
            remindAt = if (p.remind && p.time != null) due else null,
            repeat = p.repeat?.encode(),
        )
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun whenText(ms: Long, withTime: Boolean = true): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        val today = LocalDate.now(zone)
        val day = when (t.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> t.format(dayFmt)
        }
        return if (withTime) "$day ${t.format(timeFmt).lowercase()}" else day
    }

    fun describe(t: TaskTimes): String = buildString {
        append(t.title)
        when {
            t.remindAt != null -> append(" · ⏰ ").append(whenText(t.remindAt))
            t.dueAt != null -> append(" · due ").append(whenText(t.dueAt, withTime = !t.dueAllDay))
        }
        t.repeat?.let { RepeatRule.parse(it)?.describe(null, withTime = false)?.let { d -> append(" · ").append(d.lowercase()) } }
    }

    /** Works out what was typed or said (buddy-ai when it answers in time, else the offline parser). Saves nothing. */
    suspend fun plan(app: BuddyState, text: String): List<Step> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val reply = runCatching {
            withTimeout(12_000) {
                val raw = app.cloud.ai(buildJsonObject {
                    put("mode", "command")
                    put("text", clean)
                    put("today", LocalDate.now().toString())
                    put("now", LocalTime.now().withSecond(0).withNano(0).toString())
                    putJsonArray("categories") { add(kotlinx.serialization.json.JsonPrimitive("other")) }
                })
                Local.json.decodeFromString(AiCommandReply.serializer(), raw)
            }
        }.onFailure { Log.w("ai command failed", it) }.getOrNull()?.takeIf { it.error == null && it.actions.isNotEmpty() }

        if (reply == null) {
            val times = timesFromText(clean) ?: return listOf(Step.Info("Didn't catch that. Try again?"))
            return listOf(saveTask(app, times))
        }
        return reply.actions.map { a ->
            when (a.type) {
                "create_task" -> timesFromAi(a)?.let { saveTask(app, it) } ?: Step.Info("Didn't catch the task")
                "create_note" -> saveNote(app, a)
                "question" -> ask(app, a.question ?: clean)
                "log_expense", "log_income" -> Step.Info("Money entries: add those on the phone for now")
                else -> Step.Info("Sorry, I didn't understand that")
            }
        }
    }

    private fun saveTask(app: BuddyState, t: TaskTimes): Step.Save {
        val isReminder = t.remindAt != null
        val detail = buildString {
            when {
                t.remindAt != null -> append(whenText(t.remindAt).replaceFirstChar { it.uppercase() })
                t.dueAt != null -> append("Due ").append(whenText(t.dueAt, withTime = !t.dueAllDay))
            }
            t.repeat?.let { RepeatRule.parse(it)?.describe(null, withTime = false)?.let { d -> if (isNotEmpty()) append(" · "); append(d.lowercase()) } }
        }
        return Step.Save(if (isReminder) "Reminder" else "Task", t.title, detail) {
            app.addTask(t)
            when {
                t.remindAt != null -> "Reminder set for ${whenText(t.remindAt)}"
                t.dueAt != null -> "Task added for ${whenText(t.dueAt, withTime = !t.dueAllDay)}"
                else -> "Task added"
            }
        }
    }

    private fun saveNote(app: BuddyState, a: AiAction): Step {
        val body = (a.question ?: a.title)?.trim().orEmpty()
        if (body.isEmpty()) return Step.Info("Didn't catch the note")
        val title = a.title?.trim()?.ifEmpty { null } ?: body.take(40)
        return Step.Save("Note", title, body.takeIf { it != title }?.take(80).orEmpty()) {
            val now = System.currentTimeMillis()
            app.cloud.write("items", buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("kind", "note")
                put("data", buildJsonObject { put("title", title); put("body", body); put("pinned", false); put("tags", JsonArray(emptyList())) })
                put("occurred_on", null as String?)
                put("ref_id", null as String?)
                put("created_at", now.iso())
                put("updated_at", now.iso())
                put("deleted_at", null as String?)
            })
            "Note saved"
        }
    }

    private suspend fun ask(app: BuddyState, question: String): Step = runCatching {
        withTimeout(30_000) {
            val raw = app.cloud.ai(buildJsonObject {
                put("mode", "chat")
                put("today", LocalDate.now().toString())
                put("now", LocalTime.now().withSecond(0).withNano(0).toString())
                putJsonArray("messages") { addJsonObject { put("role", "user"); put("text", question) } }
            })
            val r = Local.json.decodeFromString(AiChatReply.serializer(), raw)
            if (r.error != null) Step.Info(r.error) else Step.Answer(r.text.ifBlank { "No answer" })
        }
    }.getOrElse { Step.Info("Couldn't reach Buddy. Check the internet and try again.") }
}
