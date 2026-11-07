package com.shrey.buddy.health

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R

/**
 * Rest between sets: a live countdown notification (visible on the lock screen) and an exact alarm
 * that buzzes when rest is over — so it works with the phone in your pocket.
 */
object RestTimer {
    private const val CHANNEL = "rest_timer"
    private const val ID = 61

    fun start(context: Context, seconds: Int, nextLabel: String) {
        if (seconds <= 0) return cancel(context)
        val endsAt = System.currentTimeMillis() + seconds * 1000L
        ensureChannel(context)
        if (granted(context)) {
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_buddy)
                .setContentTitle("Rest")
                .setContentText("Next: $nextLabel")
                .setWhen(endsAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setTimeoutAfter(seconds * 1000L + 5_000)
                .setContentIntent(openApp(context))
                .build()
            NotificationManagerCompat.from(context).notify(ID, n)
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, done(context)) }
            .onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, done(context)) }
    }

    /** Rest timer changed (±15 s) — move the alarm and countdown. */
    fun reschedule(context: Context, endsAt: Long, nextLabel: String) =
        start(context, ((endsAt - System.currentTimeMillis()) / 1000).toInt(), nextLabel)

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(done(context))
        NotificationManagerCompat.from(context).cancel(ID)
    }

    fun buzz(context: Context) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 500), -1))
    }

    private fun done(context: Context) = PendingIntent.getBroadcast(
        context, ID, Intent(context, RestTimerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, ID, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "workout"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Rest timer", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
        )
    }

    internal fun finished(context: Context) {
        buzz(context)
        if (!granted(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle("Rest over 💪")
            .setContentText("Time for your next set")
            .setTimeoutAfter(30_000)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID, n)
    }
}

class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RestTimer.finished(context)
}
