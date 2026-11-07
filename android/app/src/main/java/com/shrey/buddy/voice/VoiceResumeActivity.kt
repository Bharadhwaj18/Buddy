package com.shrey.buddy.voice

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R

/**
 * Invisible, finishes at once. After an update or reboot Android won't let a background app start using the
 * mic, so Buddy flashes this (allowed because it may draw over other apps) to turn Hey Buddy back on.
 */
class VoiceResumeActivity : Activity() {
    override fun onResume() {
        super.onResume()
        BuddyVoiceService.restoreIfWanted(this)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val CHANNEL = "voice_resume"
        private const val ID = 81

        /** From the boot / app-updated broadcast. */
        fun resume(context: Context) {
            if (!BuddyVoiceService.wanted(context)) return
            val started = Settings.canDrawOverlays(context) && runCatching {
                context.startActivity(
                    Intent(context, VoiceResumeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }.isSuccess
            if (started) return
            // Fallback: one tap to turn it back on.
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Buddy paused", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, ID, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching {
                nm.notify(
                    ID,
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_buddy)
                        .setContentTitle("Hey Buddy is paused")
                        .setContentText("Tap to turn it back on")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }
    }
}
