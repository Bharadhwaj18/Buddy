package com.shrey.buddy.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A note in a hum: starts [at] ms from the beginning, lasts [len] ms. */
data class NoteEvent(val at: Int, val len: Int, val midi: Int) {
    val end get() = at + len
}

/** Turns a stream of pitch frames into notes. */
object Transcriber {
    /** Shortest note kept; anything shorter is a slide between notes. */
    const val MIN_NOTE_MS = 80
    /** Silence shorter than this inside a held note doesn't split it. */
    private const val GAP_MS = 45

    /**
     * [frames] holds one fractional MIDI pitch (or null for silence) every [hopMs] ms.
     * Steps: fix octave jumps → smooth → retune to the hummer's own tuning → cut into notes → tidy.
     */
    fun notes(frames: List<Float?>, hopMs: Float): List<NoteEvent> {
        if (frames.isEmpty()) return emptyList()
        val fixed = fixOctaves(frames)
        val smooth = median(fixed, 5)
        val offset = tuningOffset(smooth)
        val tuned = smooth.map { it?.minus(offset) }

        // Cut where the voice stops or moves more than ~¾ of a semitone away from the note being held.
        data class Run(val start: Int, var end: Int, val pitches: MutableList<Float>)
        val runs = mutableListOf<Run>()
        var cur: Run? = null
        for ((i, m) in tuned.withIndex()) {
            if (m == null) { cur = null; continue }
            val c = cur
            if (c != null && abs(m - medianOf(c.pitches.takeLast(12))) <= 0.75f) {
                c.end = i; c.pitches += m
            } else {
                cur = Run(i, i, mutableListOf(m)).also { runs += it }
            }
        }
        var notes = runs.map { r ->
            NoteEvent(
                at = (r.start * hopMs).roundToInt(),
                len = ((r.end - r.start + 1) * hopMs).roundToInt(),
                midi = medianOf(r.pitches).roundToInt(),
            )
        }
        notes = mergeSame(notes)
        // Fold very short blips into the note they touch, or drop them.
        val out = mutableListOf<NoteEvent>()
        for (n in notes) {
            if (n.len >= MIN_NOTE_MS) { out += n; continue }
            val prev = out.lastOrNull()
            if (prev != null && n.at - prev.end <= GAP_MS) out[out.lastIndex] = prev.copy(len = n.end - prev.at)
        }
        return mergeSame(out)
    }

    /** Joins neighbours with the same pitch and only a tiny gap between them. */
    private fun mergeSame(notes: List<NoteEvent>): List<NoteEvent> {
        val out = mutableListOf<NoteEvent>()
        for (n in notes) {
            val p = out.lastOrNull()
            if (p != null && p.midi == n.midi && n.at - p.end <= GAP_MS) out[out.lastIndex] = p.copy(len = n.end - p.at)
            else out += n
        }
        return out
    }

    /** YIN sometimes jumps an octave for a frame or two; pull those back next to their neighbours. */
    private fun fixOctaves(f: List<Float?>): List<Float?> = f.mapIndexed { i, m ->
        if (m == null) return@mapIndexed null
        val around = (maxOf(0, i - 4)..minOf(f.lastIndex, i + 4)).mapNotNull { if (it == i) null else f[it] }
        if (around.size < 3) return@mapIndexed m
        val med = medianOf(around)
        when {
            abs(m - med - 12) < 1.5f -> m - 12
            abs(m - med + 12) < 1.5f -> m + 12
            else -> m
        }
    }

    /** Median filter that keeps silence where most of the window is silent. */
    private fun median(f: List<Float?>, size: Int): List<Float?> {
        val h = size / 2
        return f.indices.map { i ->
            val win = (maxOf(0, i - h)..minOf(f.lastIndex, i + h)).map { f[it] }
            val voiced = win.filterNotNull()
            if (f[i] == null || voiced.size * 2 <= win.size) null else medianOf(voiced)
        }
    }

    /**
     * Most people hum a little sharp or flat of concert pitch, but consistently so. This finds that offset
     * (in semitones, -0.5..0.5) so notes round to what was meant rather than flipping between neighbours.
     */
    fun tuningOffset(f: List<Float?>): Float {
        var x = 0.0; var y = 0.0
        for (m in f) if (m != null) {
            val a = 2 * PI * (m - m.roundToInt())
            x += cos(a); y += sin(a)
        }
        if (x == 0.0 && y == 0.0) return 0f
        return (kotlin.math.atan2(y, x) / (2 * PI)).toFloat()
    }

    fun medianOf(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

object Tempo {
    /**
     * Best-guess tempo from when notes start. Tries every tempo from 60 to 180 and keeps the one whose eighth-note
     * grid the note starts line up with best, gently preferring tempos near 100 so it doesn't pick double or half.
     * Null with fewer than four notes.
     */
    fun bpm(notes: List<NoteEvent>): Int? {
        if (notes.size < 4) return null
        val onsets = notes.map { it.at.toDouble() }
        val weights = notes.map { sqrt(it.len.toDouble().coerceAtMost(1000.0)) }
        var best = 0; var bestScore = -1.0
        for (bpm in 60..180) {
            val grid = 60_000.0 / bpm / 2
            var x = 0.0; var y = 0.0; var wsum = 0.0
            for ((i, t) in onsets.withIndex()) {
                val a = 2 * PI * (t / grid)
                x += weights[i] * cos(a); y += weights[i] * sin(a); wsum += weights[i]
            }
            val fit = sqrt(x * x + y * y) / wsum
            val octaves = ln(bpm / 100.0) / ln(2.0)
            val score = fit * exp(-octaves * octaves / (2 * 0.45 * 0.45))
            if (score > bestScore) { bestScore = score; best = bpm }
        }
        return best
    }

    /** The phase (ms) of the beat grid, so bars line up with the first note rather than with 0. */
    fun firstBeat(notes: List<NoteEvent>): Int = notes.firstOrNull()?.at ?: 0

    /** Snaps starts and lengths to [perBeat] steps of a beat (2 = eighths, 4 = sixteenths). */
    fun snap(notes: List<NoteEvent>, bpm: Int, perBeat: Int = 4): List<NoteEvent> {
        if (notes.isEmpty()) return notes
        val step = 60_000.0 / bpm / perBeat
        val origin = firstBeat(notes)
        val out = mutableListOf<NoteEvent>()
        for (n in notes) {
            val s = ((n.at - origin) / step).roundToInt()
            val e = maxOf(s + 1, ((n.end - origin) / step).roundToInt())
            val at = (origin + s * step).roundToInt()
            val note = NoteEvent(at, (origin + e * step).roundToInt() - at, n.midi)
            val prev = out.lastOrNull()
            // Two notes snapped onto the same step: keep the longer one.
            if (prev != null && prev.at == note.at) { if (note.len > prev.len) out[out.lastIndex] = note }
            else if (prev != null && prev.end > note.at) { out[out.lastIndex] = prev.copy(len = note.at - prev.at); out += note }
            else out += note
        }
        return out
    }
}
