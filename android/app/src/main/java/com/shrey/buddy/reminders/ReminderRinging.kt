package com.shrey.buddy.reminders

import com.shrey.buddy.data.db.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The reminder currently "calling". */
data class RingInfo(
    val taskId: String,
    val title: String,
    val subtitle: String,
    val repeating: Boolean,
) {
    companion object {
        fun of(task: TaskEntity) = RingInfo(
            taskId = task.id,
            title = task.title,
            subtitle = describeDue(task) ?: task.notes?.lineSequence()?.firstOrNull()?.take(80) ?: "Reminder",
            repeating = task.repeat != null,
        )
    }
}

object ReminderRinging {
    private val _current = MutableStateFlow<RingInfo?>(null)
    val current: StateFlow<RingInfo?> = _current.asStateFlow()
    internal fun set(info: RingInfo?) { _current.value = info }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(timeFmt).lowercase(Locale.ENGLISH)

/** "Due today, 6:30 pm" / "Due tomorrow" / "Due 12 Oct, 9:00 am". */
fun describeDue(task: TaskEntity, today: LocalDate = LocalDate.now()): String? {
    val due = task.dueAt ?: return null
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
    val day = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        today.minusDays(1) -> "yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
    }
    return if (task.dueAllDay) "Due $day" else "Due $day, ${formatTime(due)}"
}
