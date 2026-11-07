package com.shrey.buddy.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.shrey.buddy.data.items.HeartDay
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.RunSummary
import com.shrey.buddy.data.items.SleepNight
import com.shrey.buddy.data.items.WeightEntry
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.ScreenTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pulls everything a watch / scale writes to Health Connect (Galaxy Fit 3 → Samsung Health → Health
 * Connect, or any other brand) into Buddy's own synced history. Watch-agnostic: it only reads
 * standard Health Connect records. Without a watch, sleep is estimated from the phone's screen-off time.
 */
class HealthSync(
    private val context: Context,
    private val items: ItemStore,
    private val health: HealthRepository,
) {
    private val zone get() = ZoneId.systemDefault()
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        private const val TAG = "BuddyHealthSync"

        /** Everything Buddy reads (each is optional — partial grants work). */
        val READ_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )

        /** Health Connect exercise types → Buddy workout types. */
        fun mapType(t: Int): String = when (t) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "walk"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycle"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "strength"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, ExerciseSessionRecord.EXERCISE_TYPE_CRICKET,
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN, ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_SQUASH,
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "sport"
            else -> "other"
        }
    }

    private suspend fun granted(): Set<String> = runCatching {
        if (health.sdkStatus() != HealthConnectClient.SDK_AVAILABLE) emptySet() else client.permissionController.getGrantedPermissions()
    }.getOrDefault(emptySet())

    /** Which watch / scale apps have written data recently (for the "Connected" card). */
    suspend fun sources(days: Long = 14): Set<String> = runCatching {
        val g = granted()
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in g && HealthPermission.getReadPermission(SleepSessionRecord::class) !in g) return emptySet()
        val range = TimeRangeFilter.between(Instant.now().minusSeconds(days * 86_400), Instant.now())
        val hr = if (HealthPermission.getReadPermission(HeartRateRecord::class) in g) client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 20)).records.map { it.metadata.dataOrigin.packageName } else emptyList()
        val sl = if (HealthPermission.getReadPermission(SleepSessionRecord::class) in g) client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 20)).records.map { it.metadata.dataOrigin.packageName } else emptyList()
        (hr + sl).toSet()
    }.getOrDefault(emptySet())

    /** Pull the last [days] days of everything available. Safe to call often. */
    suspend fun sync(days: Long = 7) {
        val g = granted()
        val from = LocalDate.now().minusDays(days)
        runCatching { if (HealthPermission.getReadPermission(SleepSessionRecord::class) in g) syncSleep(from) }.onFailure { Log.w(TAG, "sleep", it) }
        runCatching { syncHeart(from, g) }.onFailure { Log.w(TAG, "heart", it) }
        runCatching { if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in g) syncWorkouts(from, g) }.onFailure { Log.w(TAG, "workouts", it) }
        runCatching { if (HealthPermission.getReadPermission(WeightRecord::class) in g) syncWeight(from) }.onFailure { Log.w(TAG, "weight", it) }
        // No watch sleep for a night? Estimate it from the phone.
        runCatching { estimatePhoneSleep() }.onFailure { Log.w(TAG, "phone sleep", it) }
    }

    private fun range(from: LocalDate) = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), Instant.now())

    private suspend fun syncSleep(from: LocalDate) {
        val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range(from))).records
        // Merge naps/segments per wake-up date; the longest session's date wins.
        sessions.groupBy { it.endTime.atZone(zone).toLocalDate() }.forEach { (day, list) ->
            var deep = 0L; var light = 0L; var rem = 0L; var awake = 0L
            list.forEach { s ->
                s.stages.forEach { st ->
                    val m = (st.endTime.toEpochMilli() - st.startTime.toEpochMilli()) / 60_000
                    when (st.stage) {
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                        SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                        SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> light += m
                        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += m
                    }
                }
            }
            val start = list.minOf { it.startTime.toEpochMilli() }
            val end = list.maxOf { it.endTime.toEpochMilli() }
            val total = list.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000 } - awake
            items.put(
                Kinds.SLEEP,
                SleepNight(start, end, total.toInt(), deep.toInt(), light.toInt(), rem.toInt(), awake.toInt(), list.first().metadata.dataOrigin.packageName),
                SleepNight.serializer(), id = ItemStore.stableId("sleep:$day"), occurredOn = day, notify = false,
            )
        }
    }

    private suspend fun syncHeart(from: LocalDate, g: Set<String>) {
        val hr = HealthPermission.getReadPermission(HeartRateRecord::class) in g
        val resting = HealthPermission.getReadPermission(RestingHeartRateRecord::class) in g
        val spo2 = HealthPermission.getReadPermission(OxygenSaturationRecord::class) in g
        val hrv = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) in g
        if (!hr && !resting && !spo2) return
        var day = from
        val today = LocalDate.now()
        val restingRecs = if (resting) client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range(from))).records else emptyList()
        val spo2Recs = if (spo2) client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range(from))).records else emptyList()
        val hrvRecs = if (hrv) client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range(from))).records else emptyList()
        while (!day.isAfter(today)) {
            val d = day
            val window = TimeRangeFilter.between(d.atStartOfDay(zone).toInstant(), minOf(d.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now()))
            val agg = if (hr) client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX), window)) else null
            val rest = restingRecs.filter { it.time.atZone(zone).toLocalDate() == d }.map { it.beatsPerMinute }.minOrNull()?.toInt()
            val sat = spo2Recs.filter { it.time.atZone(zone).toLocalDate() == d }.map { it.percentage.value }.takeIf { it.isNotEmpty() }?.average()
            val variability = hrvRecs.filter { it.time.atZone(zone).toLocalDate() == d }.map { it.heartRateVariabilityMillis }.takeIf { it.isNotEmpty() }?.average()
            val entry = HeartDay(
                minBpm = agg?.get(HeartRateRecord.BPM_MIN)?.toInt(),
                avgBpm = agg?.get(HeartRateRecord.BPM_AVG)?.toInt(),
                maxBpm = agg?.get(HeartRateRecord.BPM_MAX)?.toInt(),
                restingBpm = rest,
                spo2Pct = sat,
                hrvMs = variability,
                source = agg?.dataOrigins?.firstOrNull()?.packageName.orEmpty(),
            )
            if (entry.avgBpm != null || entry.restingBpm != null || entry.spo2Pct != null) {
                items.put(Kinds.HEART_DAY, entry, HeartDay.serializer(), id = ItemStore.stableId("heart:$d"), occurredOn = d, notify = false)
            }
            day = day.plusDays(1)
        }
    }

    /** Watch workouts become Buddy workouts (once each — the id is derived from the Health Connect record). */
    private suspend fun syncWorkouts(from: LocalDate, g: Set<String>) {
        val sessions = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range(from))).records
        sessions.forEach { s ->
            val id = ItemStore.stableId("hc:${s.metadata.id}")
            val window = TimeRangeFilter.between(s.startTime, s.endTime)
            val metrics = buildSet {
                if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in g) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in g) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                if (HealthPermission.getReadPermission(DistanceRecord::class) in g) add(DistanceRecord.DISTANCE_TOTAL)
                if (HealthPermission.getReadPermission(HeartRateRecord::class) in g) add(HeartRateRecord.BPM_AVG)
            }
            val agg = if (metrics.isNotEmpty()) client.aggregate(AggregateRequest(metrics, window)) else null
            val kcal = (agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) ?: agg?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL))?.inKilocalories?.roundToInt() ?: 0
            val distance = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters
            val avgHr = agg?.get(HeartRateRecord.BPM_AVG)
            val durationS = (s.endTime.epochSecond - s.startTime.epochSecond)
            val type = mapType(s.exerciseType)
            val w = Workout(
                type = type,
                title = s.title?.takeIf { it.isNotBlank() } ?: "Watch ${type.replaceFirstChar(Char::uppercase)}",
                durationMin = (durationS / 60).toInt(),
                notes = listOfNotNull("From ${sourceName(s.metadata.dataOrigin.packageName)}", avgHr?.let { "avg ♥ $it bpm" }).joinToString(" · "),
                kcal = kcal,
                startedAt = s.startTime.toEpochMilli(),
                run = distance?.takeIf { it > 50 && type in setOf("run", "walk", "cycle") }?.let { RunSummary(distanceM = it, durationS = durationS, movingS = durationS.toInt(), kcal = kcal, startedAt = s.startTime.toEpochMilli()) },
            )
            items.put(Kinds.WORKOUT, w, Workout.serializer(), id = id, occurredOn = s.startTime.atZone(zone).toLocalDate(), notify = false)
        }
    }

    /** Smart-scale / watch weigh-ins, unless you logged one by hand that day. */
    private suspend fun syncWeight(from: LocalDate) {
        client.readRecords(ReadRecordsRequest(WeightRecord::class, range(from))).records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .forEach { (day, list) ->
                val id = ItemStore.stableId("weight:$day")
                val existing = items.get(id, WeightEntry.serializer())
                if (existing != null && existing.data.source == "manual") return@forEach
                val latest = list.maxBy { it.time }
                items.put(Kinds.WEIGHT, WeightEntry(kg = latest.weight.inKilograms, source = latest.metadata.dataOrigin.packageName), WeightEntry.serializer(), id = id, occurredOn = day, notify = false)
            }
    }

    /** Last few nights: the longest screen-off stretch between 8 pm and noon counts as sleep (≥ 3 h). */
    private suspend fun estimatePhoneSleep() {
        if (!ScreenTime.hasPermission(context)) return
        val today = LocalDate.now()
        for (back in 0L..2L) {
            val wake = today.minusDays(back)
            val id = ItemStore.stableId("sleep:$wake")
            val existing = items.get(id, SleepNight.serializer())
            if (existing != null && existing.data.source != "phone") continue // the watch knows better
            val from = wake.minusDays(1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
            val to = minOf(wake.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
            if (to <= from) continue
            val gap = ScreenTime.longestScreenOff(context, from, to) ?: continue
            val minutes = ((gap.second - gap.first) / 60_000).toInt()
            if (minutes < 180) continue
            items.put(Kinds.SLEEP, SleepNight(gap.first, gap.second, minutes, source = "phone"), SleepNight.serializer(), id = id, occurredOn = wake, notify = false)
        }
    }
}

fun sourceName(pkg: String): String = when {
    "samsung" in pkg || "shealth" in pkg -> "Samsung Health"
    "google" in pkg && "fit" in pkg -> "Google Fit"
    "fitbit" in pkg -> "Fitbit"
    "garmin" in pkg -> "Garmin"
    "xiaomi" in pkg || "mi.health" in pkg || "mifit" in pkg -> "Mi Fitness"
    "huawei" in pkg -> "Huawei Health"
    "amazfit" in pkg || "zepp" in pkg -> "Zepp"
    "noise" in pkg -> "NoiseFit"
    pkg == "phone" -> "Phone"
    else -> pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)
}
