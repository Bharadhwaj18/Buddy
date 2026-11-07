package com.shrey.buddy.voice

import com.shrey.buddy.AppContainer
import com.shrey.buddy.ai.ChatMessage
import com.shrey.buddy.data.sync.startMillis
import com.shrey.buddy.finance.Category
import com.shrey.buddy.ui.inr
import com.shrey.buddy.data.tasks.RepeatRule
import com.shrey.buddy.reminders.formatTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * "Hey Buddy, …" → Gemini turns the transcript into actions → applied locally (offline-first data,
 * synced as usual). Returns a short human summary for the notification.
 */
object VoiceCommands {
    private val dueFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun LocalDateTime.millis() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun handle(c: AppContainer, transcript: String): String {
        val accounts = c.db.accountDao().all().filter { !it.archived }.map { it.name }
        // Keywords first ("spent…", "remind me…", "add task…", "note…") — instant and offline. Gemini only when unsure.
        VoiceIntent.parse(transcript, accounts)?.let { return perform(c, it, transcript) }
        val reply = c.ai.command(transcript, accounts, Category.entries.map { it.slug })
        reply.error?.let { return "Buddy AI error: $it" }

        val results = reply.actions.map { a ->
            when (a.type) {
                "log_expense", "log_income" -> {
                    val minor = ((a.amountRupees ?: 0.0) * 100).roundToLong()
                    if (minor <= 0) return@map "Didn't catch the amount in “$transcript”"
                    val credit = a.type == "log_income"
                    val category = Category.of(a.category).let { if (credit && !it.isIncome) Category.INCOME else it }
                    perform(
                        c,
                        VoiceIntent.Result(
                            if (credit) VoiceIntent.Kind.INCOME else VoiceIntent.Kind.EXPENSE, explicit = true,
                            amountMinor = minor, payee = a.payee, category = category, account = a.account,
                            date = a.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(LocalDate.now()) },
                            time = a.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                        ),
                        transcript,
                    )
                }
                "create_note" -> {
                    val body = (a.question ?: a.title)?.trim().orEmpty()
                    if (body.isEmpty()) return@map "Didn't catch the note"
                    perform(c, VoiceIntent.Result(VoiceIntent.Kind.NOTE, explicit = true, title = a.title?.trim()?.ifEmpty { null } ?: body.take(40), body = body), transcript)
                }
                "create_task" -> {
                    val title = a.title?.trim().orEmpty()
                    if (title.isEmpty()) return@map "Didn't catch the task"
                    val due = a.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val time = a.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }?.takeIf { due != null }
                    val remind = a.remindAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                    val repeat = RepeatRule.parse(a.repeat)
                    c.tasks.add(
                        title,
                        dueAt = due?.let { d -> time?.let { d.atTime(it).millis() } ?: d.startMillis() },
                        dueAllDay = time == null,
                        myDay = due == LocalDate.now(),
                        remindAt = remind?.millis(),
                        repeat = repeat?.encode(),
                    )
                    buildString {
                        append(if (remind != null) "Reminder set: $title" else "Task added: $title")
                        due?.let { append(" · due ${it.format(dueFormat)}") }
                        time?.let { append(" ${formatTime(due!!.atTime(it).millis())}") }
                        remind?.let { append(" · rings ${formatTime(it.millis())}") }
                        repeat?.let { append(" · ${it.describe(due?.let { d -> d.atTime(time ?: LocalTime.of(9, 0)) }, withTime = false).lowercase()}") }
                    }
                }
                "question" -> {
                    val question = a.question ?: transcript
                    val facts = runCatching { com.shrey.buddy.ai.AiContext.facts(c) }.getOrNull()
                    facts?.let { com.shrey.buddy.ai.LocalAnswers.answer(question, it) }
                        ?: c.ai.chat(listOf(ChatMessage("user", question)), facts?.let(com.shrey.buddy.ai.AiContext::snapshot)).let { it.error ?: it.text }
                }
                else -> "Didn't understand “$transcript”"
            }
        }
        return results.joinToString("\n").ifEmpty { reply.speech.ifEmpty { "Done" } }
    }

    /** Applies something [VoiceIntent] understood; returns the confirmation line. */
    suspend fun perform(c: AppContainer, r: VoiceIntent.Result, transcript: String): String = when (r.kind) {
        VoiceIntent.Kind.EXPENSE, VoiceIntent.Kind.INCOME -> {
            val credit = r.kind == VoiceIntent.Kind.INCOME
            val minor = r.amountMinor ?: 0
            val today = LocalDate.now()
            val day = r.date ?: today
            val at = when {
                r.time != null -> day.atTime(r.time).millis()
                day == today -> System.currentTimeMillis()
                else -> day.atTime(12, 0).millis()
            }
            val category = r.category ?: if (credit) Category.INCOME else Category.OTHER
            val id = java.util.UUID.randomUUID().toString()
            val account = c.finance.addManualTransaction(
                direction = if (credit) "credit" else "debit",
                amountMinor = minor,
                payee = r.payee,
                category = category,
                accountHint = r.account,
                note = null,
                source = "voice",
                occurredAt = at,
                id = id,
            )
            // Fuel goes on the vehicle too: the one you named, else the one you last filled, else your only one.
            var vehicleName: String? = null
            if (category == Category.FUEL && !credit) {
                val vehicles = c.items.all(com.shrey.buddy.data.items.Kinds.VEHICLE, com.shrey.buddy.data.items.Vehicle.serializer())
                val said = transcript.lowercase()
                val vehicle = vehicles.firstOrNull { v -> v.data.name.lowercase().split(' ').any { it.length >= 3 && it in said } }
                    ?: vehicles.firstOrNull { it.id == c.fuel.lastVehicleId() }
                    ?: vehicles.singleOrNull()
                if (vehicle != null) {
                    val (litres, km) = VoiceIntent.fuelDetails(transcript)
                    c.fuel.save(id, minor, day, com.shrey.buddy.finance.FuelEntry(vehicle.id, litres, km))
                    vehicleName = vehicle.data.name
                }
            }
            runCatching { c.planning.checkBudgets() }
            listOfNotNull(
                "${if (credit) "+" else "−"}${inr(minor)}",
                r.payee ?: category.label,
                vehicleName,
                account,
                when (day) { today -> null; today.minusDays(1) -> "yesterday"; else -> day.format(dueFormat) },
            ).joinToString(" · ")
        }
        VoiceIntent.Kind.TASK, VoiceIntent.Kind.REMINDER -> {
            val due = r.date
            val time = r.time?.takeIf { due != null }
            val remindAt = if (r.remind && due != null) due.atTime(time ?: LocalTime.of(9, 0)).millis() else null
            c.tasks.add(
                r.title,
                dueAt = due?.let { d -> time?.let { d.atTime(it).millis() } ?: d.startMillis() },
                dueAllDay = time == null,
                myDay = due == LocalDate.now(),
                remindAt = remindAt,
                repeat = r.repeat?.encode(),
            )
            buildString {
                append(if (remindAt != null) "Reminder set: ${r.title}" else "Task added: ${r.title}")
                when {
                    remindAt != null -> append(" · rings ${friendlyDay(due!!)} ${formatTime(remindAt)}")
                    due != null -> append(" · due ${friendlyDay(due)}")
                }
                r.repeat?.let { append(" · ${it.describe(due?.atTime(time ?: LocalTime.of(9, 0)), withTime = false).lowercase()}") }
            }
        }
        VoiceIntent.Kind.NOTE -> {
            c.items.put(
                com.shrey.buddy.data.items.Kinds.NOTE,
                com.shrey.buddy.data.items.Note(title = r.title, body = r.body),
                com.shrey.buddy.data.items.Note.serializer(),
            )
            "Note saved: ${r.title}"
        }
        VoiceIntent.Kind.QUESTION -> {
            val facts = runCatching { com.shrey.buddy.ai.AiContext.facts(c) }.getOrNull()
            facts?.let { com.shrey.buddy.ai.LocalAnswers.answer(transcript, it) }
                ?: c.ai.chat(listOf(ChatMessage("user", transcript)), facts?.let(com.shrey.buddy.ai.AiContext::snapshot)).let { it.error ?: it.text }
        }
    }

    private fun friendlyDay(d: LocalDate): String = when (d) {
        LocalDate.now() -> "today"
        LocalDate.now().plusDays(1) -> "tomorrow"
        else -> d.format(dueFormat)
    }
}
