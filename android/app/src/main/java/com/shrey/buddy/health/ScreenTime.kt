package com.shrey.buddy.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AppUsage(val label: String, val packageName: String, val minutes: Int, val opens: Int = 0)

data class ScreenTimeDay(
    val day: LocalDate,
    val totalMinutes: Int,
    val apps: List<AppUsage>,
    /** Times the phone was unlocked. */
    val unlocks: Int = 0,
    /** First unlock of the day, epoch millis. */
    val firstPickupAt: Long? = null,
    /** Minutes of use in each hour, 0..23. */
    val hourly: List<Int> = List(24) { 0 },
)

/** One stretch of an app being on screen. */
internal data class Session(val pkg: String, val start: Long, val end: Long)

/** The raw events we care about, so the session logic can be tested without Android. */
internal data class UsageEvent(val time: Long, val type: Int, val pkg: String, val cls: String?)

/**
 * Phone usage, computed the way Digital Wellbeing does it: from the raw event log (app came to the
 * foreground / left it / screen turned off), cut exactly at local midnight. The aggregated
 * `UsageStats` buckets we used before don't line up with midnight and over-count background time,
 * which is why the old numbers were off.
 */
object ScreenTime {
    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** How far back Android keeps the detailed event log (it's usually about a week). */
    const val DAYS_AVAILABLE = 7

    fun forDay(context: Context, day: LocalDate = LocalDate.now()): ScreenTimeDay? {
        if (!hasPermission(context)) return null
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        if (end <= start) return ScreenTimeDay(day, 0, emptyList())

        // Look back a few hours so an app already open at midnight is counted from 00:00.
        val events = readEvents(context, start - LOOKBACK_MS, end)
        val pm = context.packageManager
        val ignore = ignoredPackages(context)
        val sessions = sessions(events, start, end).filter { it.pkg !in ignore }

        val perApp = sessions.groupBy { it.pkg }
        val apps = perApp.map { (pkg, list) ->
            AppUsage(label(pm, pkg), pkg, (list.sumOf { it.end - it.start } / 60_000).toInt(), opens = list.size)
        }.filter { it.minutes > 0 || it.opens > 0 }.sortedByDescending { it.minutes }

        val hourly = MutableList(24) { 0L }
        sessions.forEach { s ->
            var t = s.start
            while (t < s.end) {
                val hour = Instant.ofEpochMilli(t).atZone(zone).hour
                val hourEnd = Instant.ofEpochMilli(t).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val chunkEnd = minOf(hourEnd, s.end)
                hourly[hour] += chunkEnd - t
                t = chunkEnd
            }
        }

        val unlockTimes = events.filter { it.type == UsageEvents.Event.KEYGUARD_HIDDEN && it.time in start until end }.map { it.time }
        val totalMs = sessions.sumOf { it.end - it.start }
        return ScreenTimeDay(
            day = day,
            totalMinutes = (totalMs / 60_000).toInt(),
            apps = apps,
            unlocks = unlockTimes.size,
            firstPickupAt = unlockTimes.minOrNull(),
            hourly = hourly.map { (it / 60_000).toInt() },
        )
    }

    /** The longest stretch the screen stayed off within [from, to) — the phone's best guess at sleep. */
    fun longestScreenOff(context: Context, from: Long, to: Long): Pair<Long, Long>? {
        if (!hasPermission(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val raw = usm.queryEvents(from - LOOKBACK_MS, to)
        val e = UsageEvents.Event()
        val events = mutableListOf<Pair<Long, Boolean>>() // time, screenOn
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> events += e.timeStamp to true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> events += e.timeStamp to false
            }
        }
        return longestOff(events, from, to)
    }

    /**
     * Pure helper (tested): longest off-interval clipped to the window. Glancing at the phone for under
     * [bridgeMs] (checking the time at 3 am) doesn't break the night in two.
     */
    internal fun longestOff(events: List<Pair<Long, Boolean>>, from: Long, to: Long, bridgeMs: Long = 10 * 60_000L): Pair<Long, Long>? {
        // Build off-intervals, then merge those separated by short on-periods.
        val offs = mutableListOf<Pair<Long, Long>>()
        var offSince: Long? = null
        for ((t, on) in events.sortedBy { it.first }) {
            if (!on) { if (offSince == null) offSince = t }
            else { offSince?.let { offs += it to t }; offSince = null }
        }
        offSince?.let { offs += it to to }
        val merged = mutableListOf<Pair<Long, Long>>()
        offs.forEach { iv ->
            val last = merged.lastOrNull()
            if (last != null && iv.first - last.second < bridgeMs) merged[merged.lastIndex] = last.first to iv.second else merged += iv
        }
        return merged.map { (s, e) -> maxOf(s, from) to minOf(e, to) }.filter { it.second > it.first }.maxByOrNull { it.second - it.first }
    }

    private fun readEvents(context: Context, from: Long, to: Long): List<UsageEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val raw = usm.queryEvents(from, to)
        val out = ArrayList<UsageEvent>(4096)
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            if (e.eventType in RELEVANT) out += UsageEvent(e.timeStamp, e.eventType, e.packageName, e.className)
        }
        return out
    }

    /**
     * Turns the event stream into on-screen sessions, clipped to [start, end).
     * An app counts as on screen while at least one of its activities is resumed; the screen turning
     * off or the phone shutting down ends everything.
     */
    internal fun sessions(events: List<UsageEvent>, start: Long, end: Long): List<Session> {
        val out = mutableListOf<Session>()
        val resumed = mutableMapOf<String, MutableSet<String>>() // pkg -> resumed activity classes
        val openSince = mutableMapOf<String, Long>()

        fun close(pkg: String, at: Long) {
            val from = openSince.remove(pkg) ?: return
            val s = maxOf(from, start)
            val e = minOf(at, end)
            if (e > s) out += Session(pkg, s, e)
        }
        fun closeAll(at: Long) {
            openSince.keys.toList().forEach { close(it, at) }
            resumed.clear()
        }

        for (ev in events.sortedBy { it.time }) {
            when (ev.type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumed.getOrPut(ev.pkg) { mutableSetOf() } += ev.cls.orEmpty()
                    openSince.putIfAbsent(ev.pkg, ev.time)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val set = resumed[ev.pkg] ?: continue
                    set -= ev.cls.orEmpty()
                    if (set.isEmpty()) {
                        resumed.remove(ev.pkg)
                        close(ev.pkg, ev.time)
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> closeAll(ev.time)
            }
        }
        // Still open now (e.g. the app you're looking at) → counts up to the end of the window.
        closeAll(end)
        return out
    }

    private fun ignoredPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        // The home screen and system UI aren't "apps you used" (Digital Wellbeing leaves them out too).
        return (launchers + listOf("com.android.systemui", "android")).toSet()
    }

    private fun label(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))

    private const val LOOKBACK_MS = 6 * 60 * 60_000L
    private val RELEVANT = setOf(
        UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED,
        UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.KEYGUARD_HIDDEN,
    )
}
