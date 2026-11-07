package com.shrey.buddy.data.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.StepsDay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.resume

/**
 * Buddy's own daily step history, kept forever (Health Connect only lets apps read ~30 days back).
 *
 * Two sources, merged per day by taking the larger count so nothing is double counted:
 *  - the phone's step sensor, sampled every ~15 min (works with no watch);
 *  - Health Connect, which a watch (Galaxy Fit 3 via Samsung Health) writes to.
 */
class StepLog(
    private val context: Context,
    private val items: ItemStore,
    private val health: HealthRepository,
) {
    private val prefs = context.getSharedPreferences("steps", Context.MODE_PRIVATE)
    private val lock = Mutex()

    fun hasSensorPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasSensor() = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Reads the phone sensor, pulls Health Connect for the last [hcDays] days, and saves the merged days. */
    suspend fun refresh(hcDays: Long = 2) = lock.withLock {
        val now = LocalDateTime.now()
        samplePhone(now)
        val hc = readHealthConnect(now.toLocalDate().minusDays(hcDays - 1), now.toLocalDate())
        val days = (0 until hcDays).map { now.toLocalDate().minusDays(it) }
        days.forEach { save(it, hc[it]) }
    }

    /** First run / daily: fills in the last 30 days Health Connect still has. */
    suspend fun backfill() = refresh(hcDays = 30)

    // ---- phone step sensor ----

    /** The step counter counts since boot; we store the running delta per day and hour. */
    private suspend fun samplePhone(now: LocalDateTime) {
        if (!hasSensorPermission()) return
        val value = readCounter() ?: return
        val last = prefs.getFloat("lastCounter", -1f)
        prefs.edit().putFloat("lastCounter", value).apply()
        if (last < 0f) return // first reading: nothing to attribute yet
        val delta = if (value >= last) value - last else value // the counter resets on reboot
        // Ignore impossible jumps (e.g. a sensor glitch) — more than ~30k steps in one sample.
        if (delta <= 0f || delta > 30_000f) return
        if (delta >= 40f) Nudges.movedNow(context)
        val day = now.toLocalDate()
        val key = "phone:$day"
        prefs.edit()
            .putLong(key, prefs.getLong(key, 0L) + delta.toLong())
            .putLong("$key:${now.hour}", prefs.getLong("$key:${now.hour}", 0L) + delta.toLong())
            .apply()
    }

    private suspend fun readCounter(): Float? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0])
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    private fun phoneDay(day: LocalDate) = prefs.getLong("phone:$day", 0L)
    private fun phoneHourly(day: LocalDate) = List(24) { h -> prefs.getLong("phone:$day:$h", 0L).toInt() }

    // ---- Health Connect ----

    private data class HcDay(val steps: Long, val hourly: List<Int>?, val source: String?)

    private suspend fun readHealthConnect(from: LocalDate, to: LocalDate): Map<LocalDate, HcDay> = runCatching {
        if (health.sdkStatus() != HealthConnectClient.SDK_AVAILABLE || !health.hasPermissions()) return emptyMap()
        val days = health.stepsByDay(from, to)
        days.mapValues { (day, d) ->
            // Hourly detail for the last two days only (older days rarely change).
            val hourly = if (!day.isBefore(to.minusDays(1)) && d.steps > 0) health.hourlySteps(day) else null
            HcDay(d.steps, hourly, d.sources.firstOrNull())
        }
    }.onFailure { Log.w(TAG, "Health Connect read failed", it) }.getOrDefault(emptyMap())

    // ---- merge + save ----

    private suspend fun save(day: LocalDate, hc: HcDay?) {
        val id = ItemStore.stableId("steps:$day")
        val existing = items.get(id, StepsDay.serializer())?.data
        val phone = phoneDay(day)
        val hcSteps = hc?.steps ?: 0L
        val merged = when {
            hcSteps >= phone && hcSteps > 0 -> StepsDay(hcSteps, hc?.hourly ?: existing?.hourly ?: List(24) { 0 }, hc?.source ?: "health_connect")
            phone > 0 -> StepsDay(phone, phoneHourly(day), "phone")
            else -> null
        } ?: return
        // Never go backwards (e.g. after a reinstall the phone counts start again from zero).
        val best = if (existing != null && existing.steps > merged.steps) existing else merged
        if (best == existing) return
        items.put(Kinds.STEPS_DAY, best, StepsDay.serializer(), id = id, occurredOn = day, notify = false)
    }

    companion object {
        private const val TAG = "BuddySteps"
    }
}

/** Distance and calories from steps when a watch doesn't report them. */
object StepMath {
    /** Average stride from height (≈ 41.5% of height), else 0.76 m. */
    fun strideM(heightCm: Double?) = heightCm?.let { it * 0.00415 } ?: 0.76
    fun distanceKm(steps: Long, heightCm: Double? = null) = steps * strideM(heightCm) / 1000.0
    /** Walking burns roughly 0.5 kcal per kg per km. */
    fun kcal(steps: Long, weightKg: Double? = null, heightCm: Double? = null) = distanceKm(steps, heightCm) * (weightKg ?: 70.0) * 0.5
}
