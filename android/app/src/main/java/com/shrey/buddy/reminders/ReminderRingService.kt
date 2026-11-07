package com.shrey.buddy.reminders

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.shrey.buddy.BuddyApp
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Rings like an incoming call: ringtone on loop + call-style vibration, the full-screen call screen,
 * and an ongoing notification with Done / Snooze / Dismiss. Gives up after a minute and leaves a
 * "missed reminder" notification instead.
 */
class ReminderRingService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { missed() }
    private var rampStartedAt = 0L
    private var gradual = false
    private var tts: TextToSpeech? = null
    @Volatile private var speaking = false

    /** Ringtone volume right now: the gentle ramp, ducked while Buddy is talking. */
    private fun applyVolume() {
        val p = player ?: return
        val t = if (gradual) ((System.currentTimeMillis() - rampStartedAt).toFloat() / RAMP_MS).coerceIn(0f, 1f) else 1f
        val ramped = RAMP_FROM + (1f - RAMP_FROM) * t * t // ease-in: stays soft a little longer
        val v = if (speaking) minOf(ramped, 0.15f) else if (gradual) ramped else 1f
        runCatching { p.setVolume(v, v) }
    }

    /** Gentle start: volume climbs from a whisper to full over RAMP_MS. */
    private val ramp = object : Runnable {
        override fun run() {
            applyVolume()
            if (System.currentTimeMillis() - rampStartedAt < RAMP_MS) handler.postDelayed(this, 400)
        }
    }

    /** Says the reminder, then again every 18 s while it rings. */
    private val speakLoop = object : Runnable {
        override fun run() {
            val info = ReminderRinging.current.value ?: return
            val engine = tts ?: return
            speaking = true
            applyVolume()
            engine.speak("Hey Shrey. Reminder: ${info.title}.", TextToSpeech.QUEUE_FLUSH, null, "reminder")
            handler.postDelayed(this, 18_000)
        }
    }

    private fun startSpeaking() {
        tts = TextToSpeech(this) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            if (engine.setLanguage(Locale.forLanguageTag("en-IN")) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale.ENGLISH)
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { handler.post { speaking = false; applyVolume() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { handler.post { speaking = false; applyVolume() } }
            })
            handler.postDelayed(speakLoop, 2_500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val info = ReminderRinging.current.value
        if (info == null || intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        ReminderNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this, ReminderNotifications.CALL_ID, ReminderNotifications.ringing(this, info),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0,
        )
        startRinging()
        // While the phone is in use the full-screen intent only shows a heads-up, so open the call screen
        // directly (allowed because Buddy can draw over other apps).
        if (Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(Intent(this, ReminderCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "couldn't open call screen", it) }
        }
        handler.removeCallbacks(timeout)
        // Short foreground services are capped at ~3 minutes, so stay well under that.
        handler.postDelayed(timeout, ReminderSettings.get(this).ringSeconds.coerceIn(15, 120) * 1000L)
        return START_NOT_STICKY
    }

    private fun startRinging() {
        if (player != null) return
        val settings = ReminderSettings.get(this)
        val uri = ReminderSettings.soundUri(this)
        player = runCatching {
            MediaPlayer().apply {
                // Alarm stream: rings even on silent / Do Not Disturb, like an alarm clock.
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(this@ReminderRingService, uri)
                isLooping = true
                prepare()
                if (settings.gradual) setVolume(RAMP_FROM, RAMP_FROM)
                start()
            }
        }.onFailure { Log.w(TAG, "ringtone failed", it) }.getOrNull()
        gradual = settings.gradual
        if (settings.gradual) {
            rampStartedAt = System.currentTimeMillis()
            handler.post(ramp)
        }
        if (settings.speak) startSpeaking()

        if (!settings.vibrate) return
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        // Ring-ring … pause, repeating — like a phone call.
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 1300), intArrayOf(0, 255, 0, 255, 0), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
    }

    private fun missed() {
        val info = ReminderRinging.current.value
        val app = applicationContext
        if (info != null) {
            if (ReminderSettings.isNag(app, info.taskId)) {
                // "Keep calling until I answer": ring again in 10 min, up to 6 times per reminder.
                ReminderScheduler.scope.launch {
                    val task = (app as BuddyApp).container.tasks.get(info.taskId) ?: return@launch
                    val base = task.remindAt ?: return@launch
                    if (ReminderSettings.nextNagCount(app, task.id, base) <= MAX_RECALLS) {
                        ReminderScheduler.snooze(app, task, RECALL_MINUTES)
                        ReminderNotifications.willCallAgain(app, info, RECALL_MINUTES)
                    } else {
                        ReminderNotifications.missed(app, info)
                    }
                    ReminderScheduler.refresh(app)
                }
            } else {
                ReminderNotifications.missed(this, info)
            }
        }
        finish()
    }

    private fun stopSpeaking() {
        tts?.runCatching { stop(); shutdown() }
        tts = null
        speaking = false
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        stopSpeaking()
        player?.runCatching { stop(); release() }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        val wasRinging = ReminderRinging.current.value != null
        ReminderRinging.set(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Another reminder may have come due while this one rang.
        if (wasRinging) ReminderScheduler.refreshAsync(applicationContext)
    }

    // Short foreground services get ~3 minutes; we stop after one anyway.
    override fun onTimeout(startId: Int, fgsType: Int) = missed()

    @Deprecated("API 34 only")
    override fun onTimeout(startId: Int) = missed()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSpeaking()
        player?.runCatching { stop(); release() }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BuddyRing"
        private const val ACTION_STOP = "stop"
        private const val RAMP_MS = 30_000L
        private const val MAX_RECALLS = 6
        private const val RECALL_MINUTES = 10
        private const val RAMP_FROM = 0.06f

        fun ring(context: Context, info: RingInfo) {
            ReminderRinging.set(info)
            ContextCompat.startForegroundService(context, Intent(context, ReminderRingService::class.java))
        }

        /** Stops ringing without the "missed" notification (the user answered). */
        fun stop(context: Context) {
            if (ReminderRinging.current.value == null) return
            context.startService(Intent(context, ReminderRingService::class.java).setAction(ACTION_STOP))
        }
    }
}
