package com.shrey.buddy.reminders

import android.Manifest
import android.app.Notification
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

object ReminderNotifications {
    /** Silent channel for the "calling" notification — the ring service plays the ringtone itself. */
    const val CALL_CHANNEL = "reminder_calls"
    private const val MISSED_CHANNEL = "reminder_missed"
    private const val ONGOING_CHANNEL = "reminder_ongoing"
    const val CALL_ID = 4201

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL, "Reminder calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen, call-style reminders"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "Reminder ringing", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(MISSED_CHANNEL, "Missed reminders", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun missedId(taskId: String) = 5000 + (taskId.hashCode() and 0xFFFF)

    fun action(context: Context, action: String, taskId: String, minutes: Int = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (action + taskId + minutes).hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(ReminderReceiver.EXTRA_TASK, taskId).putExtra(ReminderReceiver.EXTRA_MINUTES, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Foreground notification while ringing; its full-screen intent shows the call screen on a locked/off phone. */
    /** Once the call screen is showing, swap the heads-up for a quiet ongoing notification so it doesn't cover the screen. */
    fun quiet(context: Context, info: RingInfo) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(context).notify(CALL_ID, ringing(context, info, ONGOING_CHANNEL))
    }

    fun ringing(context: Context, info: RingInfo, channel: String = CALL_CHANNEL): Notification {
        val callScreen = PendingIntent.getActivity(
            context, 7, Intent(context, ReminderCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val loud = channel == CALL_CHANNEL
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(info.title)
            .setContentText("Buddy is calling · ${info.subtitle}")
            .setCategory(if (loud) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (loud) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(callScreen)
            .apply { if (loud) setFullScreenIntent(callScreen, true) }
            .addAction(0, "Done", action(context, ReminderReceiver.ACTION_DONE, info.taskId))
            .addAction(0, "Snooze 10 min", action(context, ReminderReceiver.ACTION_SNOOZE, info.taskId, 10))
            .addAction(0, if (info.repeating) "Skip" else "Dismiss", action(context, ReminderReceiver.ACTION_DISMISS, info.taskId))
            .build()
    }

    fun missed(context: Context, task: TaskEntity) = missed(context, RingInfo.of(task))

    /** Unanswered "keep calling" reminder: a quiet heads-up that it will ring again. */
    fun willCallAgain(context: Context, info: RingInfo, minutes: Int) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val n = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle("Missed — calling again in $minutes min")
            .setContentText(info.title)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setAutoCancel(true)
            .addAction(0, "Done", action(context, ReminderReceiver.ACTION_DONE, info.taskId))
            .addAction(0, "Stop calling", action(context, ReminderReceiver.ACTION_STOP_CALLING, info.taskId))
            .build()
        NotificationManagerCompat.from(context).notify(missedId(info.taskId), n)
    }

    fun missed(context: Context, info: RingInfo) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val open = PendingIntent.getActivity(
            context, 8, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "tasks"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, MISSED_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle("Missed reminder")
            .setContentText(info.title)
            .setSubText(info.subtitle)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", action(context, ReminderReceiver.ACTION_DONE, info.taskId))
            .addAction(0, "Snooze 10 min", action(context, ReminderReceiver.ACTION_SNOOZE, info.taskId, 10))
            .addAction(0, "Tomorrow 9 am", action(context, ReminderReceiver.ACTION_SNOOZE, info.taskId, SmartSnooze.TOMORROW_MORNING))
            .build()
        NotificationManagerCompat.from(context).notify(missedId(info.taskId), n)
    }
}
