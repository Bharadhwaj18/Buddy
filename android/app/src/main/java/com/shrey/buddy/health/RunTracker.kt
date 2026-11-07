package com.shrey.buddy.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [t] = wall clock; [activeMs] = elapsed active time when recorded (pauses removed). */
data class RunPoint(val lat: Double, val lng: Double, val alt: Double, val t: Long, val activeMs: Long = 0)

data class RunState(
    val status: Status = Status.IDLE,
    val points: List<RunPoint> = emptyList(),
    val distanceM: Double = 0.0,
    /** Active (unpaused) time, ms. */
    val elapsedMs: Long = 0,
    val elevationGainM: Double = 0.0,
    val startedAtWall: Long = 0,
) {
    enum class Status { IDLE, RUNNING, PAUSED, FINISHED }

    /** Seconds per km, or null before 50 m. */
    val paceSecPerKm: Long? get() = if (distanceM < 50) null else (elapsedMs / 1000.0 / (distanceM / 1000.0)).toLong()
}

/**
 * GPS run recording (DESIGN.md §4.2) as a location foreground service, so it keeps recording with the
 * screen off. State is a process-wide flow the Run screen observes.
 */
class RunTrackerService : Service() {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastActiveTick = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_FINISH -> finish()
            else -> start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!hasLocation(this)) {
            stopSelf()
            return
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification("Recording your run"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        _state.value = RunState(status = RunState.Status.RUNNING, startedAtWall = System.currentTimeMillis())
        lastActiveTick = SystemClock.elapsedRealtime()
        startVoice()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateDistanceMeters(3f)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun onLocation(loc: Location) {
        val s = _state.value
        if (s.status != RunState.Status.RUNNING) return
        tick()
        if (loc.hasAccuracy() && loc.accuracy > 25f) return // drop poor fixes (tunnels, indoors)
        val p = RunPoint(loc.latitude, loc.longitude, loc.altitude, loc.time, _state.value.elapsedMs)
        val prev = _state.value.points.lastOrNull()
        var add = 0.0
        var gain = 0.0
        if (prev != null) {
            val out = FloatArray(1)
            Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, out)
            add = out[0].toDouble()
            if (add > 80) return // GPS jump
            gain = (p.alt - prev.alt).coerceAtLeast(0.0).takeIf { it < 15 } ?: 0.0
        }
        _state.value = _state.value.copy(
            points = _state.value.points + p,
            distanceM = _state.value.distanceM + add,
            elevationGainM = _state.value.elevationGainM + gain,
        )
        announceKm()
        updateNotification()
    }

    // ---- Spoken km updates ----
    private var tts: TextToSpeech? = null
    private var announcedKm = 0
    private var lastKmAtMs = 0L

    private fun startVoice() {
        announcedKm = 0
        lastKmAtMs = 0L
        if (!voiceOn(this)) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { if (it.setLanguage(Locale.forLanguageTag("en-IN")) < TextToSpeech.LANG_AVAILABLE) it.setLanguage(Locale.ENGLISH) }
            }
        }
    }

    private fun announceKm() {
        val s = _state.value
        val km = (s.distanceM / 1000).toInt()
        if (km <= announcedKm) return
        announcedKm = km
        val lap = s.elapsedMs - lastKmAtMs
        lastKmAtMs = s.elapsedMs
        val text = "$km kilometre${if (km == 1) "" else "s"}. Time ${spoken(s.elapsedMs)}. Last kilometre ${spoken(lap)}."
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "km$km")
    }

    /** 342000 ms → "5 minutes 42", 3725000 → "1 hour 2 minutes". */
    private fun spoken(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "$h hour${if (h == 1L) "" else "s"} $m minute${if (m == 1L) "" else "s"}"
            else -> "$m minute${if (m == 1L) "" else "s"} $s"
        }
    }

    private fun stopVoice() {
        tts?.runCatching { stop(); shutdown() }
        tts = null
    }

    /** Adds the active time since the last tick (paused time is not counted). */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (_state.value.status == RunState.Status.RUNNING) {
            _state.value = _state.value.copy(elapsedMs = _state.value.elapsedMs + (now - lastActiveTick))
        }
        lastActiveTick = now
    }

    private fun pause() {
        tick()
        _state.value = _state.value.copy(status = RunState.Status.PAUSED)
        updateNotification()
    }

    private fun resume() {
        lastActiveTick = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(status = RunState.Status.RUNNING)
        updateNotification()
    }

    private fun finish() {
        tick()
        client.removeLocationUpdates(callback)
        _state.value = _state.value.copy(status = RunState.Status.FINISHED)
        stopVoice()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVoice()
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Run tracking", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "run"), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle("Buddy run")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        val s = _state.value
        val text = "%.2f km · %s%s".format(
            s.distanceM / 1000, formatDuration(s.elapsedMs), if (s.status == RunState.Status.PAUSED) " · paused" else "",
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 44
        private const val ACTION_PAUSE = "com.shrey.buddy.run.PAUSE"
        private const val ACTION_RESUME = "com.shrey.buddy.run.RESUME"
        private const val ACTION_FINISH = "com.shrey.buddy.run.FINISH"

        private val _state = MutableStateFlow(RunState())
        val state: StateFlow<RunState> = _state.asStateFlow()

        fun voiceOn(context: Context) = context.getSharedPreferences("run", Context.MODE_PRIVATE).getBoolean("voice", true)
        fun setVoice(context: Context, on: Boolean) = context.getSharedPreferences("run", Context.MODE_PRIVATE).edit().putBoolean("voice", on).apply()

        fun hasLocation(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) = context.startForegroundService(Intent(context, RunTrackerService::class.java))
        fun pause(context: Context) = context.startService(Intent(context, RunTrackerService::class.java).setAction(ACTION_PAUSE))
        fun resume(context: Context) = context.startService(Intent(context, RunTrackerService::class.java).setAction(ACTION_RESUME))
        fun finish(context: Context) = context.startService(Intent(context, RunTrackerService::class.java).setAction(ACTION_FINISH))

        /** After saving (or discarding) a finished run. */
        fun reset() {
            _state.value = RunState()
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatPace(secPerKm: Long?): String = secPerKm?.let { "%d:%02d /km".format(it / 60, it % 60) } ?: "–:– /km"

/** Google encoded polyline (precision 1e5). */
object Polyline {
    fun encode(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLng = 0L
        for ((lat, lng) in points) {
            val la = Math.round(lat * 1e5)
            val ln = Math.round(lng * 1e5)
            encodeValue(la - lastLat, out)
            encodeValue(ln - lastLng, out)
            lastLat = la
            lastLng = ln
        }
        return out.toString()
    }

    fun decode(encoded: String): List<Pair<Double, Double>> {
        val result = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            val (dLat, i1) = decodeValue(encoded, index)
            val (dLng, i2) = decodeValue(encoded, i1)
            index = i2
            lat += dLat
            lng += dLng
            result += lat / 1e5 to lng / 1e5
        }
        return result
    }

    private fun encodeValue(v: Long, out: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            out.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        out.append((value + 63).toInt().toChar())
    }

    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }
}
