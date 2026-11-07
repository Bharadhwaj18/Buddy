package com.shrey.buddy.health

import com.shrey.buddy.data.items.RunSplit
import com.shrey.buddy.data.items.RunSummary

/** Builds the stored run from the recorded points, and reads it back as a track. */
object RunRecord {
    fun summarize(type: String, state: RunState, kg: Double?): RunSummary {
        val pts = state.points
        val track = pts.map { TrackPoint(it.lat, it.lng, it.alt, it.activeMs / 1000.0) }
        val moving = RunMath.movingSeconds(track).takeIf { it > 0 } ?: (state.elapsedMs / 1000).toInt()
        // A light sketch for list thumbnails / older readers (every 3rd point).
        val sketch = pts.filterIndexed { i, _ -> i % 3 == 0 || i == pts.lastIndex }.map { it.lat to it.lng }
        return RunSummary(
            distanceM = state.distanceM,
            durationS = state.elapsedMs / 1000,
            polyline = Polyline.encode(sketch),
            elevationGainM = state.elevationGainM,
            track = Polyline.encode(pts.map { it.lat to it.lng }),
            tSec = pts.map { (it.activeMs / 1000).toInt() },
            altM = pts.map { it.alt.toInt() },
            movingS = moving,
            splits = RunMath.splits(track).map { RunSplit(it.km, it.seconds, it.distanceM, it.elevationM) },
            best1kS = RunMath.bestEffort(track, 1000.0),
            best5kS = RunMath.bestEffort(track, 5000.0),
            best10kS = RunMath.bestEffort(track, 10_000.0),
            kcal = RunMath.kcal(type, state.distanceM, moving, kg),
            startedAt = state.startedAtWall,
        )
    }

    /** Full track if saved, else the older thinned sketch (no times/altitudes). */
    fun track(r: RunSummary): List<TrackPoint> {
        val full = r.track.isNotEmpty()
        val latLng = Polyline.decode(if (full) r.track else r.polyline)
        return latLng.mapIndexed { i, (lat, lng) ->
            TrackPoint(lat, lng, r.altM.getOrNull(i)?.toDouble() ?: 0.0, r.tSec.getOrNull(i)?.toDouble() ?: 0.0)
        }
    }

    fun hasDetail(r: RunSummary) = r.track.isNotEmpty() && r.tSec.size > 1

    fun avgPace(r: RunSummary): Long? =
        if (r.distanceM < 50) null else ((if (r.movingS > 0) r.movingS.toLong() else r.durationS) / (r.distanceM / 1000)).toLong()
}
