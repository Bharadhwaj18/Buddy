package com.shrey.buddy.ai

import android.content.Context
import com.shrey.buddy.AppContainer
import com.shrey.buddy.data.items.Insight
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.health.ScreenTime
import com.shrey.buddy.ingest.sms.SmsParse
import java.time.LocalDate

/**
 * Daily briefing / weekly review / insights. The server aggregates synced data; the phone adds
 * what only it knows (steps, upcoming debits from SMS, screen time). Results are saved as
 * `insight` items so they sync and show up in the Insights screen.
 */
class Briefings(private val context: Context, private val c: AppContainer) {

    private suspend fun localContext(): Triple<List<Long>?, List<UpcomingForAi>, Int?> {
        val steps = runCatching {
            if (c.health.sdkStatus() == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE && c.health.hasPermissions()) {
                c.health.stepsLast7Days().map { it.steps }
            } else null
        }.getOrNull()
        val today = LocalDate.now()
        val upcoming = c.db.captureDao().allSms().mapNotNull { cap ->
            (runCatching { c.captures.decode(cap) }.getOrNull() as? SmsParse.UpcomingDebit)?.takeIf { u ->
                u.dueDate?.let { runCatching { !LocalDate.parse(it).isBefore(today) }.getOrDefault(false) } ?: false
            }
        }.distinctBy { Triple(it.payee, it.amountMinor, it.dueDate) }
            .map { UpcomingForAi(it.payee ?: "payment", it.amountMinor / 100.0, it.dueDate) }
            .take(10)
        val screen = runCatching { ScreenTime.forDay(context, today.minusDays(1))?.totalMinutes }.getOrNull()
        return Triple(steps, upcoming, screen)
    }

    /** kind: "daily" | "weekly". Returns the saved insight text. */
    suspend fun briefing(kind: String): Insight {
        val (steps, upcoming, screen) = localContext()
        val reply = c.ai.briefing(kind, steps, upcoming, screen)
        reply.error?.let { error(it) }
        val insight = Insight(title = reply.title, body = reply.body, period = kind)
        // One per day/week (deterministic ID): regenerating replaces rather than piling up.
        val key = if (kind == "weekly") "weekly:${LocalDate.now().with(java.time.DayOfWeek.MONDAY)}" else "daily:${LocalDate.now()}"
        c.items.put(Kinds.INSIGHT, insight, Insight.serializer(), id = com.shrey.buddy.data.items.ItemStore.stableId(key), occurredOn = LocalDate.now())
        return insight
    }

    /**
     * A morning briefing written on the phone from your own numbers — used when Buddy AI can't be reached,
     * so there's a briefing every day regardless.
     */
    suspend fun localDaily(): String {
        val f = AiContext.facts(c)
        fun inr(minor: Long, paise: Boolean) = com.shrey.buddy.ui.inr(minor, paise)
        val lines = buildList {
            if (f.tasksOverdue.isNotEmpty()) add("⚠️ ${f.tasksOverdue.size} overdue: ${f.tasksOverdue.take(3).joinToString(", ")}")
            add(
                if (f.tasksToday.isEmpty()) "✅ Nothing due today."
                else "✅ ${f.tasksToday.size} due today: ${f.tasksToday.take(4).joinToString(", ")}",
            )
            if (f.remindersToday.isNotEmpty()) {
                add("⏰ " + f.remindersToday.take(3).joinToString(", ") { (t, at) -> "$t ${com.shrey.buddy.reminders.formatTime(at)}" })
            }
            add("💸 Yesterday ${inr(f.spentYesterday, false)} · this month ${inr(f.spentMonth, false)}" +
                if (f.spentLastMonthSamePoint > 0) " (last month by now ${inr(f.spentLastMonthSamePoint, false)})" else "")
            f.budgets.filter { it.second > 0 && it.third * 10 >= it.second * 8 }.take(2).forEach { (name, limit, spent) ->
                add("📊 $name budget: ${inr(spent, false)} of ${inr(limit, false)}")
            }
            listOfNotNull(
                f.stepsYesterday?.let { "%,d steps yesterday".format(it) },
                f.sleepLastNightMin?.let { "slept ${it / 60}h ${it % 60}m" },
                if (f.workoutsThisWeek > 0) "${f.workoutsThisWeek} workout${if (f.workoutsThisWeek == 1) "" else "s"} this week" else null,
            ).takeIf { it.isNotEmpty() }?.let { add("🏃 " + it.joinToString(" · ")) }
        }
        val body = lines.joinToString("\n")
        c.items.put(
            Kinds.INSIGHT, Insight(title = "Good morning", body = body, period = "daily"), Insight.serializer(),
            id = com.shrey.buddy.data.items.ItemStore.stableId("daily:${LocalDate.now()}"), occurredOn = LocalDate.now(),
        )
        return body
    }

    suspend fun insights(): List<Insight> {
        val (steps, upcoming, screen) = localContext()
        val reply = c.ai.insights(steps, upcoming, screen)
        reply.error?.let { error(it) }
        return reply.insights.map { Insight(it.title, it.body, "insight") }.also { list ->
            list.forEach { c.items.put(Kinds.INSIGHT, it, Insight.serializer(), occurredOn = LocalDate.now(), notify = false) }
        }
    }
}
