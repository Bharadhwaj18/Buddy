package com.shrey.buddy.health

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS sample: degrees, metres, and seconds since the start of the activity (paused time removed). */
data class TrackPoint(val lat: Double, val lng: Double, val alt: Double, val t: Double)

data class Split(val km: Int, val seconds: Int, val distanceM: Double, val elevationM: Double) {
    /** Pace for this split, s/km (the last split may be shorter than a km). */
    val pace: Int get() = (seconds / (distanceM / 1000)).roundToInt()
}

/** Splits, best efforts, moving time, pace/elevation series and calories from a GPS track. */
object RunMath {
    private const val EARTH_R = 6_371_000.0

    fun distance(a: TrackPoint, b: TrackPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(h))
    }

    /** Cumulative distance at each point. */
    fun cumulative(track: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(track.size)
        for (i in 1 until track.size) out[i] = out[i - 1] + distance(track[i - 1], track[i])
        return out
    }

    /** Time (s) at which the track reaches [m] metres, interpolated between points. */
    private fun timeAt(track: List<TrackPoint>, cum: DoubleArray, m: Double): Double? {
        val i = cum.indexOfFirst { it >= m }
        if (i <= 0) return if (i == 0) track[0].t else null
        val frac = (m - cum[i - 1]) / (cum[i] - cum[i - 1]).coerceAtLeast(1e-9)
        return track[i - 1].t + frac * (track[i].t - track[i - 1].t)
    }

    fun splits(track: List<TrackPoint>): List<Split> {
        if (track.size < 2) return emptyList()
        val cum = cumulative(track)
        val total = cum.last()
        val out = mutableListOf<Split>()
        var km = 1
        var prevT = track.first().t
        var prevM = 0.0
        while (prevM < total - 1) {
            val endM = minOf(km * 1000.0, total)
            val endT = timeAt(track, cum, endM) ?: break
            // Climb within the split: sum of positive altitude changes.
            val from = cum.indexOfFirst { it >= prevM }.coerceAtLeast(0)
            val to = cum.indexOfFirst { it >= endM }.let { if (it < 0) track.lastIndex else it }
            var climb = 0.0
            for (i in from + 1..to) climb += (track[i].alt - track[i - 1].alt).coerceIn(0.0, 15.0)
            if (endM - prevM >= 50) out += Split(km, (endT - prevT).roundToInt(), endM - prevM, climb)
            prevT = endT
            prevM = endM
            km++
        }
        return out
    }

    /** Fastest time (s) over [meters] anywhere in the track, or null if the track is shorter. */
    fun bestEffort(track: List<TrackPoint>, meters: Double): Int? {
        if (track.size < 2) return null
        val cum = cumulative(track)
        if (cum.last() < meters) return null
        var best = Double.MAX_VALUE
        var j = 0
        for (i in track.indices) {
            val target = cum[i] + meters
            if (target > cum.last()) break
            while (j < track.lastIndex && cum[j] < target) j++
            val tEnd = timeAt(track, cum, target) ?: continue
            best = minOf(best, tEnd - track[i].t)
        }
        return best.takeIf { it < Double.MAX_VALUE }?.roundToInt()
    }

    /** Time actually moving (gaps slower than ~1.8 km/h are treated as standing still). */
    fun movingSeconds(track: List<TrackPoint>): Int {
        var s = 0.0
        for (i in 1 until track.size) {
            val dt = track[i].t - track[i - 1].t
            if (dt <= 0) continue
            if (distance(track[i - 1], track[i]) / dt >= 0.5) s += dt
        }
        return s.roundToInt()
    }

    fun elevationGain(track: List<TrackPoint>): Double {
        var g = 0.0
        for (i in 1 until track.size) g += (track[i].alt - track[i - 1].alt).coerceIn(0.0, 15.0)
        return g
    }

    /** Pace (s/km) every ~[stepM] metres, smoothed, for the pace chart: (distance m, pace). */
    fun paceSeries(track: List<TrackPoint>, stepM: Double = 100.0): List<Pair<Double, Int>> {
        if (track.size < 2) return emptyList()
        val cum = cumulative(track)
        val out = mutableListOf<Pair<Double, Int>>()
        var m = stepM
        while (m <= cum.last()) {
            val t1 = timeAt(track, cum, m - stepM) ?: break
            val t2 = timeAt(track, cum, m) ?: break
            out += m to ((t2 - t1) / (stepM / 1000)).roundToInt()
            m += stepM
        }
        // 3-point moving average so GPS jitter doesn't make a saw-tooth.
        return out.mapIndexed { i, (d, _) ->
            val w = out.subList(maxOf(0, i - 1), minOf(out.size, i + 2))
            d to w.map { it.second }.average().roundToInt()
        }
    }

    fun elevationSeries(track: List<TrackPoint>): List<Pair<Double, Double>> {
        val cum = cumulative(track)
        return track.indices.map { cum[it] to track[it].alt }
    }

    /**
     * Calories from the ACSM metabolic equations (oxygen cost of running / walking at a speed), which
     * scale with body weight. Cycling uses MET by speed.
     */
    fun kcal(type: String, distanceM: Double, movingS: Int, kg: Double?): Int {
        if (movingS <= 0) return 0
        val weight = kg ?: 70.0
        val speedMpm = distanceM / (movingS / 60.0) // metres per minute
        val vo2 = when (type) {
            "walk" -> 3.5 + 0.1 * speedMpm
            "cycle" -> {
                val kmh = speedMpm * 60 / 1000
                val met = when {
                    kmh < 16 -> 4.0
                    kmh < 19 -> 6.8
                    kmh < 22 -> 8.0
                    kmh < 25 -> 10.0
                    else -> 12.0
                }
                return (met * weight * movingS / 3600.0).roundToInt()
            }
            else -> 3.5 + 0.2 * speedMpm
        }
        // ml O2/kg/min → kcal/min: VO2 × kg / 1000 × 5
        return (vo2 * weight / 1000 * 5 * movingS / 60.0).roundToInt()
    }
}
