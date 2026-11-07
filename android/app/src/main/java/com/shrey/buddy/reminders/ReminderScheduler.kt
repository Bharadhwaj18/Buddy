package com.shrey.buddy.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.MainActivity
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.isDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps exactly one exact alarm armed: the earliest reminder that hasn't rung yet. When it fires (or
 * whenever tasks change) we look again, ring anything that's due, and arm the next one.
 *
 * Snoozes are local-only (they don't touch the synced task, so repeating rules aren't thrown off) and
 * "already rung" is remembered per task + time, so editing a reminder's time makes it ring again.
 */
object ReminderScheduler {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private const val TAG = "BuddyReminders"

    /** Due reminders older than this (phone was off, app was killed) become a "missed" notification instead of ringing. */
    private const val RING_GRACE_MS = 10 * 60_000L
    private const val MISSED_WINDOW_MS = 12 * 60 * 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    /** When this task's reminder should ring, taking a snooze into account. */
    fun effectiveTime(context: Context, task: TaskEntity): Long? {
        val base = task.remindAt ?: return null
        val snooze = prefs(context).getString("snooze:${task.id}", null)?.split(":")
        val until = snooze?.takeIf { it.size == 2 && it[0] == base.toString() }?.get(1)?.toLongOrNull()
        return until ?: base
    }

    private fun hasRung(context: Context, task: TaskEntity, at: Long) = prefs(context).getLong("fired:${task.id}", -1) == at
    private fun markRung(context: Context, taskId: String, at: Long) = prefs(context).edit().putLong("fired:$taskId", at).apply()

    fun snooze(context: Context, task: TaskEntity, minutes: Int) =
        snoozeUntil(context, task, SmartSnooze.until(minutes))

    fun snoozeUntil(context: Context, task: TaskEntity, until: Long) {
        val base = task.remindAt ?: return
        prefs(context).edit().putString("snooze:${task.id}", "$base:$until").apply()
        ReminderSettings.snoozesChanged()
    }

    /** Ends a snooze; the original time counts as handled so it doesn't come back as "missed". */
    fun clearSnooze(context: Context, task: TaskEntity) {
        prefs(context).edit().remove("snooze:${task.id}").apply()
        task.remindAt?.let { markRung(context, task.id, it) }
        ReminderSettings.snoozesChanged()
    }

    /** Snoozed until (epoch millis), if this task's current reminder is snoozed. */
    fun snoozedUntil(context: Context, task: TaskEntity): Long? =
        effectiveTime(context, task)?.takeIf { it != task.remindAt }

    fun refreshAsync(context: Context): Job = scope.launch { refresh(context) }

    suspend fun refresh(context: Context) = lock.withLock {
        val app = context.applicationContext
        val tasks = (app as BuddyApp).container.db.taskDao().all()
        val now = System.currentTimeMillis()
        val pending = tasks.filter { !it.isDone && it.remindAt != null }
            .mapNotNull { t -> effectiveTime(app, t)?.let { t to it } }
            .filterNot { (t, at) -> hasRung(app, t, at) }
            .sortedBy { it.second }

        // Too late to ring — tell, don't call.
        pending.filter { (_, at) -> at < now - RING_GRACE_MS }.forEach { (t, at) ->
            markRung(app, t.id, at)
            if (at > now - MISSED_WINDOW_MS) ReminderNotifications.missed(app, t)
        }

        val upcoming = pending.filter { (_, at) -> at >= now - RING_GRACE_MS }
        val due = upcoming.firstOrNull { (_, at) -> at <= now + 1_000 }
        if (due != null && ReminderRinging.current.value == null) {
            try {
                ReminderRingService.ring(app, RingInfo.of(due.first))
                markRung(app, due.first.id, due.second)
            } catch (e: Exception) {
                // Background refreshes (e.g. sync) may not start a foreground service — let the alarm ring it instead.
                Log.w(TAG, "couldn't ring from here; handing off to the alarm", e)
                ReminderRinging.set(null)
                arm(app, now + 2_000)
                return@withLock
            }
        }
        val next = upcoming.firstOrNull { (_, at) -> at > now + 1_000 }
        arm(app, next?.second)
    }

    private fun arm(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val fire = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) {
            alarms.cancel(fire)
            return
        }
        try {
            // Alarm-clock alarms are exact, survive Doze, and show the ⏰ icon in the status bar.
            val show = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "tasks"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarms not allowed; falling back to inexact", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }
}
