package com.shrey.buddy.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** What a reminder "call" needs from Android, and where to switch each one on. */
object ReminderSetup {
    data class Check(val label: String, val why: String, val ok: Boolean, val fix: Intent)

    fun checks(context: Context): List<Check> {
        val pkg = Uri.parse("package:${context.packageName}")
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        return buildList {
            add(
                Check(
                    "Notifications", "Needed for the ringing + missed-reminder notifications",
                    nm.areNotificationsEnabled(),
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                ),
            )
            // Android resets this one on every sideloaded update. With "Display over other apps" (which survives
            // updates) the ring service opens the call screen itself, locked or not — so it's only a backup then.
            add(
                Check(
                    "Full-screen calls", "Shows the call screen on a locked phone",
                    Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent() || Settings.canDrawOverlays(context),
                    if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
                ),
            )
            add(
                Check(
                    "Display over other apps", "Shows the call screen while you're using the phone",
                    Settings.canDrawOverlays(context),
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
                ),
            )
            add(
                Check(
                    "Exact alarms", "Rings on the minute, even in battery saver",
                    alarms.canScheduleExactAlarms(),
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg),
                ),
            )
        }.map { it.copy(fix = it.fix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
