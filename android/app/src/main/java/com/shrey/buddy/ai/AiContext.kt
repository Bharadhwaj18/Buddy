package com.shrey.buddy.ai

import com.shrey.buddy.AppContainer
import com.shrey.buddy.data.health.HealthData
import com.shrey.buddy.data.items.Budget
import com.shrey.buddy.data.items.Habit
import com.shrey.buddy.data.items.HabitLog
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.tasks.inMyDay
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.finance.Category
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.ui.money.currentBalance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** The numbers Buddy knows right now — used for instant on-phone answers and sent (compactly) with AI questions. */
data class Facts(
    val spentToday: Long,
    val spentYesterday: Long,
    val spentWeek: Long,
    val spentMonth: Long,
    val spentLastMonth: Long,
    val spentLastMonthSamePoint: Long,
    val incomeMonth: Long,
    val topCategoriesMonth: List<Pair<String, Long>>,
    val accounts: List<Pair<String, Long>>,
    val budgets: List<Triple<String, Long, Long>>, // category label, limit, spent
    val tasksToday: List<String>,
    val tasksOverdue: List<String>,
    val remindersToday: List<Pair<String, Long>>, // title, at
    val openTasks: Int,
    val stepsToday: Long?,
    val stepsYesterday: Long?,
    val steps7dAvg: Long?,
    val stepGoal: Int,
    val sleepLastNightMin: Int?,
    val workoutsThisWeek: Int,
    val runKmThisWeek: Double,
    val weightKg: Double?,
    val waterTodayMl: Int,
    val restingHr: Int?,
    val habitsToday: List<Pair<String, Boolean>>,
)

object AiContext {
    suspend fun facts(c: AppContainer, today: LocalDate = LocalDate.now()): Facts {
        val zone = ZoneId.systemDefault()
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val accounts = c.db.accountDao().all().filter { !it.archived }
        val excluded = accounts.filter { !it.includeInTotals }.map { it.id }.toSet()
        val txns = c.db.transactionDao().all().filter { it.deletedAt == null }
        val counted = txns.filter { !it.isTransfer && it.accountId !in excluded }
        val debits = counted.filter { it.direction == "debit" }
        val month = YearMonth.from(today)
        val lastMonth = month.minusMonths(1)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun sum(list: List<com.shrey.buddy.data.db.TransactionEntity>) = list.sumOf { it.amountMinor }
        val monthDebits = debits.filter { YearMonth.from(day(it.occurredAt)) == month }
        val categories = monthDebits.groupBy { Category.of(it.category).label }.mapValues { sum(it.value) }.entries.sortedByDescending { it.value }
            .take(8).map { it.key to it.value }
        val balances = accounts.mapNotNull { a ->
            currentBalance(a, txns.filter { it.accountId == a.id }).first?.let { safeAccountName(a.name) to it }
        }
        val budgets = c.items.all(Kinds.BUDGET, Budget.serializer()).map { b ->
            val spent = if (b.data.category == "total") sum(monthDebits) else sum(monthDebits.filter { com.shrey.buddy.finance.Category.matches(it.category, b.data.category) })
            Triple(b.data.name.ifBlank { if (b.data.category == "total") "Total" else Category.of(b.data.category).label }, b.data.limitMinor, spent)
        }

        val tasks = c.db.taskDao().all().filter { !it.isDone }
        val tasksToday = tasks.filter { t -> t.inMyDay(today) || t.dueAt?.let { day(it) } == today }.map { it.title }
        val overdue = tasks.filter { t -> t.dueAt?.let { day(it).isBefore(today) } == true }.map { it.title }
        val reminders = tasks.mapNotNull { t -> t.remindAt?.takeIf { day(it) == today }?.let { t.title to it } }.sortedBy { it.second }

        val h = HealthData.load(c.items)
        val steps7 = (1..7).mapNotNull { h.steps[today.minusDays(it.toLong())]?.steps }
        val week = h.workouts.filter { it.date != null && !it.date!!.isBefore(monday) }
        val habits = c.items.all(Kinds.HABIT, Habit.serializer()).filter { !it.data.archived }
        val logs = c.items.all(Kinds.HABIT_LOG, HabitLog.serializer()).filter { it.date == today }.mapNotNull { it.refId }.toSet()
        val stepGoal = c.context.getSharedPreferences("steps", android.content.Context.MODE_PRIVATE).getInt("goal", 10_000)
        val stepsToday = h.steps[today]?.steps

        return Facts(
            spentToday = sum(debits.filter { day(it.occurredAt) == today }),
            spentYesterday = sum(debits.filter { day(it.occurredAt) == today.minusDays(1) }),
            spentWeek = sum(debits.filter { !day(it.occurredAt).isBefore(monday) }),
            spentMonth = sum(monthDebits),
            spentLastMonth = sum(debits.filter { YearMonth.from(day(it.occurredAt)) == lastMonth }),
            spentLastMonthSamePoint = sum(debits.filter { val d = day(it.occurredAt); YearMonth.from(d) == lastMonth && d.dayOfMonth <= today.dayOfMonth }),
            incomeMonth = sum(counted.filter { it.direction == "credit" && YearMonth.from(day(it.occurredAt)) == month }),
            topCategoriesMonth = categories,
            accounts = balances,
            budgets = budgets,
            tasksToday = tasksToday,
            tasksOverdue = overdue,
            remindersToday = reminders,
            openTasks = tasks.size,
            stepsToday = stepsToday,
            stepsYesterday = h.steps[today.minusDays(1)]?.steps,
            steps7dAvg = steps7.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            stepGoal = stepGoal,
            sleepLastNightMin = (h.sleep[today] ?: h.sleep[today.minusDays(1)])?.minutes,
            workoutsThisWeek = week.size,
            runKmThisWeek = week.mapNotNull { it.data.run?.distanceM }.sum() / 1000,
            weightKg = h.latestKg,
            waterTodayMl = h.water[today] ?: 0,
            restingHr = h.heart.entries.maxByOrNull { it.key }?.value?.restingBpm,
            habitsToday = habits.map { it.data.name to (it.id in logs || (it.data.auto == "steps" && (stepsToday ?: 0) >= it.data.target)) },
        )
    }

    /** Account names without numbers ("IndusInd ••1234" → "IndusInd") — Gemini never sees account digits. */
    fun safeAccountName(name: String) = name.replace(Regex("[•*xX]*\\d{2,}"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { "Account" }

    private fun rupees(minor: Long) = (minor / 100.0).roundToInt()

    /** Compact JSON for the AI (rupees as whole numbers). No payee names, account numbers or notes. */
    fun snapshot(f: Facts): JsonObject = buildJsonObject {
        putJsonObject("money_rupees") {
            put("spent_today", rupees(f.spentToday))
            put("spent_yesterday", rupees(f.spentYesterday))
            put("spent_this_week", rupees(f.spentWeek))
            put("spent_this_month", rupees(f.spentMonth))
            put("spent_last_month_same_point", rupees(f.spentLastMonthSamePoint))
            put("spent_last_month_total", rupees(f.spentLastMonth))
            put("income_this_month", rupees(f.incomeMonth))
            putJsonArray("top_categories_this_month") { f.topCategoriesMonth.forEach { (c, v) -> addJsonObject { put("category", c); put("spent", rupees(v)) } } }
            putJsonArray("account_balances") { f.accounts.forEach { (n, v) -> addJsonObject { put("account", n); put("balance", rupees(v)) } } }
            putJsonArray("budgets_this_month") { f.budgets.forEach { (c, l, s) -> addJsonObject { put("category", c); put("limit", rupees(l)); put("spent", rupees(s)) } } }
        }
        putJsonObject("tasks") {
            put("open", f.openTasks)
            putJsonArray("today") { f.tasksToday.take(12).forEach { add(it) } }
            putJsonArray("overdue") { f.tasksOverdue.take(8).forEach { add(it) } }
            putJsonArray("reminders_today") { f.remindersToday.take(8).forEach { (t, at) -> add("$t at ${formatTime(at)}") } }
        }
        putJsonObject("health") {
            f.stepsToday?.let { put("steps_today", it) }
            f.steps7dAvg?.let { put("steps_avg_last_7_days", it) }
            put("step_goal", f.stepGoal)
            f.sleepLastNightMin?.let { put("sleep_last_night_hours", (it / 6.0).roundToInt() / 10.0) }
            put("workouts_this_week", f.workoutsThisWeek)
            put("run_km_this_week", (f.runKmThisWeek * 10).roundToInt() / 10.0)
            f.weightKg?.let { put("weight_kg", (it * 10).roundToInt() / 10.0) }
            put("water_today_litres", (f.waterTodayMl / 100.0).roundToInt() / 10.0)
            f.restingHr?.let { put("resting_heart_rate", it) }
        }
        putJsonArray("habits_today") { f.habitsToday.forEach { (n, d) -> addJsonObject { put("habit", n); put("done", d) } } }
    }
}
