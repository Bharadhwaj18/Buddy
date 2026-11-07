package com.shrey.buddy.ai

import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.ui.inr

/**
 * Common questions answered instantly on the phone (no AI, works offline): spending today / yesterday /
 * this week / this month, balances, what's due today, steps, sleep, water, workouts this week.
 * Anything more specific ("on food", "at Swiggy", "in August") goes to Buddy AI.
 */
object LocalAnswers {
    private fun has(q: String, vararg words: String) = words.any { Regex("\\b$it\\b").containsMatchIn(q) }

    fun answer(question: String, f: Facts): String? {
        val q = question.lowercase().replace(Regex("[?!.,]"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.split(' ').size > 12) return null // long, nuanced questions → AI

        // Anything narrowing the question (a category, merchant, other period) needs the AI's tools.
        // Strip the periods we can answer, then look for what's left ("on food", "at Swiggy", "in August").
        val rest = q.replace(Regex("\\b(so far|in total|this month|this week|today|yesterday|till now|until now)\\b"), " ")
        val narrowed = has(rest, "on", "at", "for", "from", "in", "category", "categories", "merchant", "last", "between", "since")
        val money = has(q, "spend", "spent", "spending", "expenses", "expense")
        if (money && !narrowed) {
            return when {
                has(q, "today") -> "You've spent ${inr(f.spentToday)} today."
                has(q, "yesterday") -> "You spent ${inr(f.spentYesterday)} yesterday."
                has(q, "this week", "week") -> "You've spent ${inr(f.spentWeek)} this week (since Monday)."
                has(q, "this month", "month") -> buildString {
                    append("You've spent ${inr(f.spentMonth)} this month")
                    if (f.spentLastMonthSamePoint > 0) {
                        val diff = f.spentMonth - f.spentLastMonthSamePoint
                        append(" — ${inr(kotlin.math.abs(diff))} ${if (diff >= 0) "more" else "less"} than at this point last month")
                    }
                    append(".")
                    if (f.topCategoriesMonth.isNotEmpty()) append(" Biggest: " + f.topCategoriesMonth.take(3).joinToString(", ") { "${it.first} ${inr(it.second)}" } + ".")
                }
                else -> null
            }
        }
        if (has(q, "balance", "balances") && !narrowed && f.accounts.isNotEmpty()) {
            return "Balances: " + f.accounts.joinToString(", ") { "${it.first} ${inr(it.second)}" } + "."
        }
        if (has(q, "due today", "tasks today", "todo today", "to do today", "what's on today", "whats on today", "my day", "plan for today", "today's tasks", "todays tasks")) {
            val parts = mutableListOf<String>()
            parts += if (f.tasksToday.isEmpty()) "Nothing's due today." else "Today: " + f.tasksToday.take(8).joinToString(", ") + "."
            if (f.remindersToday.isNotEmpty()) parts += "Reminders: " + f.remindersToday.joinToString(", ") { "${it.first} at ${formatTime(it.second)}" } + "."
            if (f.tasksOverdue.isNotEmpty()) parts += "Overdue: " + f.tasksOverdue.take(5).joinToString(", ") + "."
            return parts.joinToString(" ")
        }
        if (has(q, "steps", "step count", "walked")) {
            return when {
                has(q, "yesterday") -> f.stepsYesterday?.let { "You walked %,d steps yesterday.".format(it) }
                else -> f.stepsToday?.let { s ->
                    "%,d steps today — ".format(s) + if (s >= f.stepGoal) "goal reached 🎉" else "%,d to go for your %,d goal.".format(f.stepGoal - s, f.stepGoal)
                }
            }
        }
        if (has(q, "sleep", "slept") && !has(q, "average", "week", "month")) {
            return f.sleepLastNightMin?.let { "You slept ${it / 60}h ${it % 60}m last night." }
        }
        if (has(q, "water")) {
            return "You've had %.1f L of water today.".format(f.waterTodayMl / 1000.0)
        }
        if (has(q, "workouts", "workout", "worked out", "gym", "runs", "ran") && has(q, "this week", "week")) {
            return "${f.workoutsThisWeek} workout${if (f.workoutsThisWeek == 1) "" else "s"} this week" +
                (if (f.runKmThisWeek > 0) ", %.1f km of running.".format(f.runKmThisWeek) else ".")
        }
        if (has(q, "weigh", "weight") && !has(q, "goal", "lose", "gain", "should")) {
            return f.weightKg?.let { "Your latest weigh-in is %.1f kg.".format(it) }
        }
        return null
    }
}
