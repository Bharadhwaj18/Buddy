package com.shrey.buddy.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period

data class DaySteps(val date: LocalDate, val steps: Long, val sources: List<String>)

/** M0 spike (c): read-only Health Connect access. Watch-agnostic by design (DESIGN.md §8). */
class HealthRepository(private val context: Context) {

    /** Everything Buddy can use from a watch / scale; asking for all at once, each one optional. */
    val permissions get() = HealthSync.READ_PERMISSIONS
    private val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    /** Steps is the minimum; the rest (sleep, heart, …) are used if granted. */
    suspend fun hasPermissions(): Boolean = stepsPermission in client.permissionController.getGrantedPermissions()

    /** True when some of the watch data types haven't been allowed yet. */
    suspend fun missingSome(): Boolean = !client.permissionController.getGrantedPermissions().containsAll(permissions)

    /** Per-day steps (and who wrote them) for [from]..[to] inclusive. */
    suspend fun stepsByDay(from: LocalDate, to: LocalDate): Map<LocalDate, DaySteps> =
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), minOf(to.plusDays(1).atStartOfDay(), LocalDateTime.now())),
                timeRangeSlicer = Period.ofDays(1),
            ),
        ).associate { g ->
            val day = g.startTime.toLocalDate()
            day to DaySteps(day, g.result[StepsRecord.COUNT_TOTAL] ?: 0L, g.result.dataOrigins.map { it.packageName })
        }

    /** Steps in each hour of [day]. */
    suspend fun hourlySteps(day: LocalDate): List<Int> {
        val out = MutableList(24) { 0 }
        client.aggregateGroupByDuration(
            androidx.health.connect.client.request.AggregateGroupByDurationRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(day.atStartOfDay(), minOf(day.plusDays(1).atStartOfDay(), LocalDateTime.now())),
                timeRangeSlicer = java.time.Duration.ofHours(1),
            ),
        ).forEach { g ->
            val hour = g.startTime.atZone(java.time.ZoneId.systemDefault()).hour
            out[hour] += (g.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
        }
        return out
    }

    /** Last 7 days including today, oldest first; days with no data are reported as 0. */
    suspend fun stepsLast7Days(): List<DaySteps> {
        val today = LocalDate.now()
        val firstDay = today.minusDays(6)
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(firstDay.atStartOfDay(), LocalDateTime.now()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        ).associateBy { it.startTime.toLocalDate() }

        return (0L..6L).map { offset ->
            val day = firstDay.plusDays(offset)
            val group = groups[day]
            DaySteps(
                date = day,
                steps = group?.result?.get(StepsRecord.COUNT_TOTAL) ?: 0L,
                sources = group?.result?.dataOrigins?.map { it.packageName }.orEmpty(),
            )
        }
    }
}
