package com.shrey.buddy.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.MainActivity
import com.shrey.buddy.data.db.VoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

enum class VoiceStatus { OFF, LOADING, LISTENING, HEARING_COMMAND, ERROR }

/**
 * M0 spike (a): always-on "Hey Buddy" (DESIGN.md §7).
 *
 * Phase WAKE: Vosk with a grammar of just "hey buddy" — cheap, offline, ignores everything else.
 * Phase COMMAND: after the wake word, a free-form recognizer transcribes the next few seconds offline.
 * (In v1.5 the command audio goes to Gemini instead; this spike measures wake accuracy and battery.)
 */
class BuddyVoiceService : Service(), RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var tts: android.speech.tts.TextToSpeech? = null
    /** Buddy is reading something out until this time (epoch ms). A deadline, not a flag: if the speech engine
     *  never reports back, the wake word must not stay switched off. */
    @Volatile private var speakingUntil = 0L
    private var ttsReady = false
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    private var inCommand = false
    private var lastPartial = ""
    private var androidRecognizer: SpeechRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_status.value == VoiceStatus.LISTENING || _status.value == VoiceStatus.LOADING) return START_STICKY
        try {
            ensureChannel()
            startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+ refuses microphone services started from the background (e.g. a sticky restart).
            log("error", "startForeground refused: ${e.javaClass.simpleName}")
            _status.value = VoiceStatus.ERROR
            stopSelf()
            return START_NOT_STICKY
        }
        log("start", null)
        _status.value = VoiceStatus.LOADING
        if (!SpeechModel.isReady(this)) updateNotification("Downloading speech model (one-time, ~37 MB)…")
        logScope.launch {
            try {
                var lastPct = -1
                val path = SpeechModel.ensure(applicationContext) { p ->
                    val pct = (p * 100).toInt()
                    if (pct / 10 != lastPct / 10) { lastPct = pct; main.post { updateNotification("Downloading speech model… $pct%") } }
                }
                val m = Model(path)
                main.post {
                    model = m
                    listenForWakeWord()
                }
            } catch (e: Exception) {
                log("error", "model: ${e.message}")
                main.post {
                    _status.value = VoiceStatus.ERROR
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun listenForWakeWord() {
        val m = model ?: return
        // Word-level confidences let us reject shaky matches.
        restartSpeech(Recognizer(m, SAMPLE_RATE, WAKE_GRAMMAR).apply { setWords(true) }, timeoutMs = null)
        inCommand = false
        _status.value = VoiceStatus.LISTENING
        updateNotification("Listening for “Hey Buddy”")
    }

    private fun listenForCommand() {
        val m = model ?: return
        lastPartial = ""
        inCommand = true
        restartSpeech(Recognizer(m, SAMPLE_RATE), timeoutMs = COMMAND_TIMEOUT_MS)
        _status.value = VoiceStatus.HEARING_COMMAND
        updateNotification("Listening…")
    }

    private fun restartSpeech(recognizer: Recognizer, timeoutMs: Int?) {
        speech?.let { it.stop(); it.shutdown() }
        speech = try {
            SpeechService(recognizer, SAMPLE_RATE).also {
                if (timeoutMs != null) it.startListening(this, timeoutMs) else it.startListening(this)
            }
        } catch (e: Exception) {
            // Mic unavailable (no permission, or held by a call/another recorder).
            onError(e)
            null
        }
    }

    // --- RecognitionListener (called on the main thread) ---

    override fun onPartialResult(hypothesis: String?) {
        // Partial guesses change mid-word — never wake on them, only on a finished utterance.
        if (!inCommand) return
        val text = field(hypothesis, "partial")
        if (text.isNotBlank()) {
            lastPartial = text
            VoiceLive.set(Live.Listening(text))
        }
    }

    override fun onResult(hypothesis: String?) = handleFinal(hypothesis)
    override fun onFinalResult(hypothesis: String?) = handleFinal(hypothesis)

    private fun handleFinal(hypothesis: String?) {
        if (inCommand) {
            val text = field(hypothesis, "text")
            if (text.isNotBlank()) finishCommand(text)
        } else if (isWake(hypothesis)) {
            onWake()
        } else if (field(hypothesis, "text").contains("buddy")) {
            // Near miss — logged so we can see (and tune) what was ignored.
            val obj = runCatching { JSONObject(hypothesis!!) }.getOrNull()
            val words = obj?.optJSONArray("result")
            val minConf = words?.let { w -> (0 until w.length()).minOfOrNull { w.getJSONObject(it).optDouble("conf", 0.0) } }
            log("wake_rejected", "“${field(hypothesis, "text")}”" + (minConf?.let { " · ${(it * 100).toInt()}% sure" } ?: ""))
        }
    }

    private var lastCommandEndedAt = 0L

    /**
     * Wake only when the whole utterance is exactly "hey buddy" / "hi buddy" (no other speech around it),
     * every word is confidently recognised, and we're not straight after a command.
     */
    private fun isWake(hypothesis: String?): Boolean {
        if (System.currentTimeMillis() < speakingUntil || System.currentTimeMillis() - lastCommandEndedAt < WAKE_COOLDOWN_MS) return false
        val obj = hypothesis?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
        val text = obj.optString("text").trim()
        if (text !in WAKE_PHRASES) return false
        val words = obj.optJSONArray("result") ?: return false
        if (words.length() != 2) return false
        for (i in 0 until words.length()) {
            if (words.getJSONObject(i).optDouble("conf", 0.0) < MIN_WORD_CONF) return false
        }
        return true
    }

    override fun onTimeout() {
        if (inCommand) finishCommand(lastPartial)
    }

    override fun onError(exception: Exception?) {
        log("error", exception?.message)
        _status.value = VoiceStatus.ERROR
        // Try to recover once the mic frees up (e.g. after a call).
        main.postDelayed({ if (model != null) listenForWakeWord() }, 5_000)
    }

    private fun onWake() {
        if (inCommand) return
        inCommand = true
        log("wake", null)
        duck()
        cue()
        VoiceLive.set(Live.Listening())
        showListeningUi()
        // Switch recognizers outside the callback that is currently running.
        // A beat after the chime so it isn't heard as the start of the command.
        main.postDelayed({ startCommandCapture() }, 250)
    }

    /**
     * Command capture: Android's speech recognizer (Google, en-IN — far better with Indian English and
     * numbers than the small offline model). Falls back to offline Vosk if it isn't available or fails.
     */
    private fun startCommandCapture() {
        speech?.let { it.stop(); it.shutdown() }
        speech = null
        _status.value = VoiceStatus.HEARING_COMMAND
        updateNotification("Listening…")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listenForCommand()
            return
        }
        lastPartial = ""
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        androidRecognizer = sr
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    lastPartial = it
                    VoiceLive.set(Live.Listening(it))
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                releaseAndroidRecognizer()
                finishCommand(text.ifBlank { lastPartial })
            }
            override fun onError(error: Int) {
                releaseAndroidRecognizer()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finishCommand(lastPartial)
                    else -> {
                        log("error", "speech recognizer error $error — using offline recognizer")
                        listenForCommand()
                    }
                }
            }
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName),
        )
        // Safety net in case the recognizer never calls back.
        main.postDelayed({ if (androidRecognizer === sr) { releaseAndroidRecognizer(); finishCommand(lastPartial) } }, 12_000)
    }

    private fun releaseAndroidRecognizer() {
        androidRecognizer?.let { runCatching { it.cancel(); it.destroy() } }
        androidRecognizer = null
    }

    /** Glowing overlay if Buddy may draw over other apps; otherwise a heads-up notification. */
    private fun showListeningUi() {
        if (Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(Intent(this, ListeningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                return
            }
        }
        val n = NotificationCompat.Builder(this, LIVE_CHANNEL)
            .setSmallIcon(com.shrey.buddy.R.drawable.ic_stat_buddy)
            .setContentTitle("🎙️ Listening…")
            .setContentText("Speak now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setTimeoutAfter(12_000)
            .build()
        getSystemService(NotificationManager::class.java).notify(LIVE_NOTIFICATION_ID, n)
    }

    private fun finishCommand(text: String) {
        if (!inCommand) return
        inCommand = false
        lastCommandEndedAt = System.currentTimeMillis()
        val heard = text.trim()
        log("command", heard.ifEmpty { "(nothing heard)" })
        getSystemService(NotificationManager::class.java).cancel(LIVE_NOTIFICATION_ID)
        if (heard.isEmpty()) {
            VoiceLive.set(Live.Done("Didn't catch that — say “Hey Buddy” and try again."))
            updateNotification("Didn't catch that")
            say("Sorry, I didn't catch that.")
        } else {
            VoiceLive.set(Live.Thinking(heard))
            updateNotification("Heard: “$heard” — working on it…")
        }
        main.post { listenForWakeWord() }
        if (heard.isNotEmpty()) act(heard)
    }

    /** Hand the transcript to Gemini (via the Edge Function) and apply the resulting actions. */
    private fun act(transcript: String) {
        val container = (applicationContext as BuddyApp).container
        logScope.launch {
            val summary = try {
                VoiceCommands.handle(container, transcript)
            } catch (e: Exception) {
                Log.w(TAG, "voice command failed", e)
                "${e.message ?: "Couldn't reach Buddy AI"} — you said: “$transcript”"
            }
            log("result", summary)
            VoiceLive.set(Live.Done(summary))
            done()
            showResult(summary)
            main.post { say(summary) }
            main.post { if (_status.value == VoiceStatus.LISTENING) updateNotification("Listening for “Hey Buddy”") }
        }
    }

    private fun showResult(text: String) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(com.shrey.buddy.R.drawable.ic_stat_buddy)
            .setContentTitle("Buddy")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, n)
    }

    // --- helpers ---

    private fun field(json: String?, key: String): String =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }.orEmpty().trim()

    /** Unmistakable "I heard you": two firm buzzes + a short two-note chime (media volume, so it isn't muted with notifications). */
    private fun cue() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 160) }
        runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 90), intArrayOf(0, 255, 0, 255), -1))
        }
    }

    private var focus: android.media.AudioFocusRequest? = null
    private val unduckLater = Runnable { unduck() }

    /** Turns your music down (not off) from "Hey Buddy" until Buddy has finished answering. */
    private fun duck() {
        main.removeCallbacks(unduckLater)
        main.postDelayed(unduckLater, 30_000) // never leave the music quiet if something goes wrong
        if (focus != null) return
        val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        if (getSystemService(AudioManager::class.java).requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }

    private fun unduck() {
        main.removeCallbacks(unduckLater)
        focus?.let { runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }
        focus = null
    }

    /** Reads out what was done. The wake word is ignored while Buddy is talking (and for a moment after). */
    private fun say(summary: String) {
        val text = spoken(summary)
        if (text.isBlank()) { unduck(); return }
        val engine = tts
        if (engine != null && ttsReady) { speakNow(engine, text); return }
        // No engine yet, or the last one never came up: start a fresh one.
        engine?.runCatching { shutdown() }
        ttsReady = false
        tts = android.speech.tts.TextToSpeech(this) { status ->
            val e = tts ?: return@TextToSpeech
            if (status != android.speech.tts.TextToSpeech.SUCCESS) { tts = null; unduck(); return@TextToSpeech }
            ttsReady = true
            if (e.setLanguage(java.util.Locale.forLanguageTag("en-IN")) < android.speech.tts.TextToSpeech.LANG_AVAILABLE) e.setLanguage(java.util.Locale.ENGLISH)
            e.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finished()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
                override fun onError(utteranceId: String?, errorCode: Int) = finished()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished()
                private fun finished() {
                    speakingUntil = 0
                    lastCommandEndedAt = System.currentTimeMillis()
                    main.post { unduck() }
                }
            })
            speakNow(e, text)
        }
    }

    private fun speakNow(engine: android.speech.tts.TextToSpeech, text: String) {
        // Roughly how long this takes to say, plus a margin — the wake word is back after that no matter what.
        speakingUntil = System.currentTimeMillis() + (text.length * 90L + 2_000L).coerceAtMost(40_000L)
        val ok = runCatching { engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "result") }.getOrNull() == android.speech.tts.TextToSpeech.SUCCESS
        if (!ok) {
            speakingUntil = 0
            ttsReady = false
            unduck()
        }
    }

    /** "−₹450 · Lunch · IndusInd ••4321 · yesterday" → "Spent 450 rupees, Lunch, IndusInd, yesterday." */
    private fun spoken(summary: String): String = summary.lines().filter { it.isNotBlank() }.joinToString(". ") { line ->
        line.replace(Regex("^−₹([\\d,.]+)")) { "Spent ${it.groupValues[1].replace(",", "")} rupees" }
            .replace(Regex("^\\+₹([\\d,.]+)")) { "Received ${it.groupValues[1].replace(",", "")} rupees" }
            .replace(Regex("₹([\\d,.]+)")) { "${it.groupValues[1].replace(",", "")} rupees" }
            .replace(Regex("\\s*••\\d+"), "")
            .replace(" · ", ", ")
            .replace(Regex("[*_#`“”]"), "")
    }.take(400)

    /** Short single buzz when the command has been handled. */
    private fun done() {
        runCatching {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }

    private fun batteryPct(): Int? =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun log(kind: String, text: String?) {
        Log.i(TAG, "$kind ${text.orEmpty()}")
        val dao = (applicationContext as BuddyApp).container.db.voiceEventDao()
        val event = VoiceEvent(at = System.currentTimeMillis(), kind = kind, text = text, batteryPct = batteryPct())
        logScope.launch { dao.insert(event) }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Buddy listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "Hey Buddy results", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(LIVE_CHANNEL, "Hey Buddy is listening", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BuddyVoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.shrey.buddy.R.drawable.ic_stat_buddy)
            .setContentTitle("Buddy")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        releaseAndroidRecognizer()
        tts?.runCatching { stop(); shutdown() }
        tts = null
        runCatching { tone?.release() }
        unduck()
        VoiceLive.set(Live.Idle)
        speech?.let { it.stop(); it.shutdown() }
        speech = null
        model?.close()
        model = null
        if (_status.value != VoiceStatus.ERROR) _status.value = VoiceStatus.OFF
        log("stop", null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BuddyVoice"
        private const val CHANNEL = "voice"
        private const val NOTIFICATION_ID = 42
        private const val RESULT_CHANNEL = "voice_results"
        private const val RESULT_NOTIFICATION_ID = 43
        private const val LIVE_CHANNEL = "voice_live"
        private const val LIVE_NOTIFICATION_ID = 47
        private const val ACTION_STOP = "com.shrey.buddy.voice.STOP"
        private const val SAMPLE_RATE = 16000f
        // Only these two phrases wake Buddy. "[unk]" soaks up all other speech/noise so it isn't forced
        // onto a wake phrase (a lone "buddy" used to match "body", "daddy", TV chatter…).
        private val WAKE_PHRASES = setOf("hey buddy", "hi buddy")
        private const val WAKE_GRAMMAR = """["hey buddy", "hi buddy", "[unk]"]"""

        /** How sure the recognizer must be of every word (0–1). Vosk gives ~1.0 for a clear "hey buddy". */
        private const val MIN_WORD_CONF = 0.9

        /** Ignore wake phrases right after a command (Buddy's own chime/voice, the tail of what you said). */
        private const val WAKE_COOLDOWN_MS = 3_000L
        private const val COMMAND_TIMEOUT_MS = 6_000

        // Process-wide so the final "stop" event still gets written after the service is destroyed.
        private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _status = MutableStateFlow(VoiceStatus.OFF)
        val status: StateFlow<VoiceStatus> = _status.asStateFlow()

        private fun prefs(context: Context) = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

        /** You switched Hey Buddy on (and haven't switched it off) — survives updates and reboots. */
        fun wanted(context: Context) = prefs(context).getBoolean("on", false)

        fun start(context: Context) {
            prefs(context).edit().putBoolean("on", true).apply()
            context.startForegroundService(Intent(context, BuddyVoiceService::class.java))
        }

        fun stop(context: Context) {
            prefs(context).edit().putBoolean("on", false).apply()
            context.stopService(Intent(context, BuddyVoiceService::class.java))
        }

        /**
         * Turns Hey Buddy back on if you had it on (an update or reboot kills it). Must be called while Buddy is
         * in the foreground — Android only lets a visible app start listening to the mic. Returns false if it couldn't.
         */
        fun restoreIfWanted(context: Context): Boolean {
            if (!wanted(context) || _status.value in setOf(VoiceStatus.LOADING, VoiceStatus.LISTENING, VoiceStatus.HEARING_COMMAND)) return true
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return false
            return runCatching { context.startForegroundService(Intent(context, BuddyVoiceService::class.java)) }
                .onFailure { Log.w(TAG, "couldn't restore Hey Buddy", it) }.isSuccess
        }
    }
}
