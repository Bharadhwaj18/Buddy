package com.shrey.buddy.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.shrey.buddy.BuddyApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Alarm firing and the Done / Snooze / Dismiss buttons (not exported — only our PendingIntents reach it). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val job = when (intent.action) {
            ACTION_DONE, ACTION_SNOOZE, ACTION_DISMISS, ACTION_STOP_CALLING -> ReminderActions.handle(
                context, intent.action!!, intent.getStringExtra(EXTRA_TASK).orEmpty(), intent.getIntExtra(EXTRA_MINUTES, 10),
            )
            ACTION_ROLLOVER -> ReminderScheduler.scope.launch {
                val moved = (context.applicationContext as BuddyApp).container.tasks.rollOverToTomorrow()
                NotificationManagerCompat.from(context).cancel(CheckIns.EVENING_ID)
                CheckIns.movedConfirmation(context, moved)
            }
            else -> ReminderScheduler.refreshAsync(context) // ACTION_FIRE
        }
        job.invokeOnCompletion { pending.finish() }
    }

    companion object {
        const val ACTION_FIRE = "com.shrey.buddy.reminder.FIRE"
        const val ACTION_DONE = "com.shrey.buddy.reminder.DONE"
        const val ACTION_SNOOZE = "com.shrey.buddy.reminder.SNOOZE"
        const val ACTION_DISMISS = "com.shrey.buddy.reminder.DISMISS"
        /** "Keep calling" reminders: stop the re-calls without touching the task. */
        const val ACTION_STOP_CALLING = "com.shrey.buddy.reminder.STOP_CALLING"
        /** Evening wrap-up: move everything unfinished to tomorrow. */
        const val ACTION_ROLLOVER = "com.shrey.buddy.reminder.ROLLOVER"
        const val EXTRA_TASK = "task"
        const val EXTRA_MINUTES = "minutes"
    }
}

/** Boot, app update, time / time-zone change: alarms are lost or stale, so re-arm. Only ever re-arms. */
class ReminderRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            com.shrey.buddy.voice.VoiceResumeActivity.resume(context)
        }
        val pending = goAsync()
        ReminderScheduler.refreshAsync(context).invokeOnCompletion { pending.finish() }
    }
}

/** Shared by the call screen and the notification buttons. */
object ReminderActions {
    fun handle(context: Context, action: String, taskId: String, minutes: Int = 10): Job {
        val app = context.applicationContext
        // Stop the ring right away; the database work follows.
        if (ReminderRinging.current.value?.taskId == taskId) runCatching { ReminderRingService.stop(app) }
        NotificationManagerCompat.from(app).cancel(ReminderNotifications.missedId(taskId))
        return ReminderScheduler.scope.launch {
            if (taskId.isNotEmpty()) {
                val tasks = (app as BuddyApp).container.tasks
                val task = tasks.get(taskId)
                if (task != null && task.deletedAt == null) when (action) {
                    ReminderReceiver.ACTION_DONE -> tasks.save(task.copy(status = "done"))
                    ReminderReceiver.ACTION_SNOOZE -> ReminderScheduler.snooze(app, task, minutes)
                    ReminderReceiver.ACTION_DISMISS -> {
                        ReminderScheduler.clearSnooze(app, task)
                        if (task.repeat != null) tasks.skip(task)
                    }
                    ReminderReceiver.ACTION_STOP_CALLING -> ReminderScheduler.clearSnooze(app, task)
                }
            }
            ReminderScheduler.refresh(app)
        }
    }
}
