package com.shrey.buddy.desktop

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today at a glance.
 * - overdue: unfinished, dated before today (by reminder or due date)
 * - reminders: unfinished, reminder today (ones already past show as late)
 * - tasks: unfinished, due today or in My Day, without a reminder today
 * - done: finished today
 */
data class Agenda(
    val overdue: List<Task>,
    val reminders: List<Task>,
    val tasks: List<Task>,
    val done: List<Task>,
    val tomorrow: Int,
    val thisWeek: Int,
    /** The next reminder still to ring today. */
    val next: Task?,
    /** Overdue plus today's reminders whose time has passed. */
    val late: Int,
) {
    val left: Int get() = overdue.size + reminders.size + tasks.size
    val total: Int get() = left + done.size

    companion object {
        val EMPTY = Agenda(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, null, 0)

        fun build(all: List<Task>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Agenda {
            val today = LocalDate.now(zone)
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            fun dateOf(t: Task): LocalDate? = (t.remindAt ?: t.dueAt)?.let(::day)

            val open = all.filterNot { it.isDone }
            val overdue = open.filter { t -> dateOf(t)?.isBefore(today) == true }
                .sortedBy { it.remindAt ?: it.dueAt }
            val reminders = open.filter { t -> t.remindAt?.let(::day) == today }.sortedBy { it.remindAt }
            val tasks = open.filter { t ->
                t !in overdue && t !in reminders &&
                    (t.dueAt?.let(::day) == today || t.myDay == today.toString())
            }.sortedWith(compareBy({ priorityRank(it.priority) }, { it.dueAt ?: Long.MAX_VALUE }))
            val done = all.filter { t -> t.isDone && t.completedAt?.let(::day) == today }.sortedByDescending { it.completedAt }
            val tomorrow = open.count { dateOf(it) == today.plusDays(1) }
            val week = open.count { t -> dateOf(t)?.let { it.isAfter(today) && !it.isAfter(today.plusDays(7)) } == true }
            val next = reminders.firstOrNull { (it.remindAt ?: 0) > now }
            val late = overdue.size + reminders.count { (it.remindAt ?: 0) <= now }
            return Agenda(overdue, reminders, tasks, done, tomorrow, week, next, late)
        }

        private fun priorityRank(p: String) = when (p) { "urgent" -> 0; "high" -> 1; "normal" -> 2; else -> 3 }
    }
}
