package com.shrey.buddy.capture

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
import com.shrey.buddy.ingest.sms.SmsParse
import com.shrey.buddy.ingest.sms.describe

object CaptureNotifier {
    private const val CHANNEL = "captures"

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Captured transactions", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Tapping opens that transaction (or the Money tab when there isn't one, e.g. a balance alert). */
    fun notify(context: Context, parse: SmsParse, transactionId: String? = null) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val id = System.currentTimeMillis().toInt()
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, transactionId?.let { "txn:$it" } ?: "money")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.shrey.buddy.R.drawable.ic_stat_buddy)
            .setContentTitle("Buddy captured")
            .setContentText(parse.describe())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
