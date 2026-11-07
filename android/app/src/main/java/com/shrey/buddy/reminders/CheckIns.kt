package com.shrey.buddy.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.inMyDay
import com.shrey.buddy.data.tasks.isDone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 8 am "here's your day" and 9 pm "what's left" — both local, no AI or network needed. */
object CheckIns {
    private const val CHANNEL = "checkins"
    private const val MORNING_ID = 47
    const val EVENING_ID = 48

    private fun TaskEntity.dueDay(zone: ZoneId) = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Open tasks that belong to today: in My Day, due today, or overdue. */
    fun today(tasks: List<TaskEntity>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) =
        tasks.filter { t -> !t.isDone && t.deletedAt == null && (t.inMyDay(today) || t.dueDay(zone)?.isAfter(today) == false) }

    fun morning(context: Context, tasks: List<TaskEntity>) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val todays = today(tasks, today, zone)
        val reminders = tasks.filter { !it.isDone && it.remindAt != null && Instant.ofEpochMilli(it.remindAt).atZone(zone).toLocalDate() == today }
            .sortedBy { it.remindAt }
        if (todays.isEmpty() && reminders.isEmpty()) return
        val summary = listOfNotNull(
            todays.size.takeIf { it > 0 }?.let { "$it task${if (it == 1) "" else "s"}" },
            reminders.size.takeIf { it > 0 }?.let { "$it reminder${if (it == 1) "" else "s"} (first at ${formatTime(reminders.first().remindAt!!)})" },
        ).joinToString(" · ")
        val lines = (todays + reminders).distinctBy { it.id }.take(6).joinToString("\n") { "• ${it.title}" }
        post(context, MORNING_ID, "Your day: $summary", lines, actions = emptyList())
    }

    fun evening(context: Context, tasks: List<TaskEntity>) {
        val left = today(tasks)
        if (left.isEmpty()) return
        val rollover = PendingIntent.getBroadcast(
            context, 49, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_ROLLOVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context, EVENING_ID,
            "${left.size} thing${if (left.size == 1) "" else "s"} left today",
            left.take(6).joinToString("\n") { "• ${it.title}" },
            actions = listOf("Move all to tomorrow" to rollover),
        )
    }

    fun movedConfirmation(context: Context, moved: Int) {
        if (moved == 0) return
        post(context, EVENING_ID, "Moved $moved to tomorrow", "Fresh start in the morning.", actions = emptyList(), silent = true)
    }

    private fun post(context: Context, id: Int, title: String, text: String, actions: List<Pair<String, PendingIntent>>, silent: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Morning plan & evening wrap-up", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "tasks"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            .apply { actions.forEach { (label, intent) -> addAction(0, label, intent) } }
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
