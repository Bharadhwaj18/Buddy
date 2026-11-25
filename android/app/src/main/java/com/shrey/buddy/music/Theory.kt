package com.shrey.buddy.music

import kotlin.math.ceil
import kotlin.math.sqrt

data class Key(val tonic: Int, val minor: Boolean) {
    /** Flat keys spell notes with flats (Bb, Eb…), the rest with sharps. */
    val flats: Boolean get() = if (minor) tonic in setOf(2, 7, 0, 5, 10, 3) else tonic in setOf(5, 10, 3, 8, 1, 6)
    val name: String get() = NoteNames.pcName(tonic, flats) + if (minor) " minor" else " major"

    companion object {
        /** Reads back [name] ("A minor"); null if it isn't one. */
        fun parse(s: String): Key? {
            val m = Regex("""^([A-G])([#b]?) (major|minor)$""").find(s.trim()) ?: return null
            val base = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[m.groupValues[1][0]]!!
            val shift = when (m.groupValues[2]) { "#" -> 1; "b" -> -1; else -> 0 }
            return Key(((base + shift) % 12 + 12) % 12, m.groupValues[3] == "minor")
        }
    }
}

data class Chord(val root: Int, val minor: Boolean, val roman: String) {
    val tones: Set<Int> get() = setOf(root, (root + if (minor) 3 else 4) % 12, (root + 7) % 12)
    fun symbol(flats: Boolean) = NoteNames.pcName(root, flats) + if (minor) "m" else ""
}

object Theory {
    // Krumhansl–Kessler key profiles: how strongly each scale step belongs to a major / minor key.
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** The key the tune sits in best (longer notes count more). Null with fewer than three notes. */
    fun key(notes: List<NoteEvent>): Key? {
        if (notes.size < 3) return null
        val hist = DoubleArray(12)
        for (n in notes) hist[NoteNames.pc(n.midi)] += n.len.toDouble()
        var best: Key? = null; var bestR = Double.NEGATIVE_INFINITY
        for (minor in listOf(false, true)) for (tonic in 0 until 12) {
            val prof = if (minor) MINOR else MAJOR
            val r = correlation(hist) { prof[((it - tonic) % 12 + 12) % 12] }
            if (r > bestR) { bestR = r; best = Key(tonic, minor) }
        }
        return best
    }

    private fun correlation(x: DoubleArray, y: (Int) -> Double): Double {
        val ys = DoubleArray(12) { y(it) }
        val mx = x.average(); val my = ys.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until 12) {
            num += (x[i] - mx) * (ys[i] - my); dx += (x[i] - mx) * (x[i] - mx); dy += (ys[i] - my) * (ys[i] - my)
        }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / sqrt(dx * dy)
    }

    /** The everyday chords of a key, home chord first. */
    fun diatonic(k: Key): List<Chord> {
        fun c(step: Int, minor: Boolean, roman: String) = Chord((k.tonic + step) % 12, minor, roman)
        return if (!k.minor) listOf(
            c(0, false, "I"), c(5, false, "IV"), c(7, false, "V"), c(9, true, "vi"), c(2, true, "ii"), c(4, true, "iii"),
        ) else listOf(
            c(0, true, "i"), c(8, false, "VI"), c(3, false, "III"), c(10, false, "VII"), c(5, true, "iv"), c(7, false, "V"), c(7, true, "v"),
        )
    }

    /** Length of one bar of 4 beats. */
    fun barMs(bpm: Int) = 4 * 60_000.0 / bpm

    /**
     * One chord per bar (4 beats) that fits the notes sung in that bar: chord notes count for, other notes against,
     * with a nudge towards staying put and towards the home chord. Bars start at the first note.
     */
    fun chords(notes: List<NoteEvent>, k: Key, bpm: Int): List<Chord> {
        if (notes.isEmpty()) return emptyList()
        val bar = barMs(bpm)
        val origin = notes.first().at
        val bars = ceil((notes.maxOf { it.end } - origin) / bar).toInt().coerceIn(1, 64)
        val options = diatonic(k)
        val home = options.first()
        val out = mutableListOf<Chord>()
        for (b in 0 until bars) {
            val from = origin + b * bar; val to = from + bar
            val dur = DoubleArray(12)
            for (n in notes) {
                val o = minOf(to, n.end.toDouble()) - maxOf(from, n.at.toDouble())
                if (o > 0) dur[NoteNames.pc(n.midi)] += o
            }
            val total = dur.sum()
            val prev = out.lastOrNull()
            if (total <= 0) { out += prev ?: home; continue }
            val last = b == bars - 1
            out += options.maxBy { ch ->
                var s = 0.0
                for (pc in 0 until 12) if (dur[pc] > 0) s += dur[pc] * when (pc) {
                    ch.root -> 1.5
                    in ch.tones -> 1.0
                    else -> -0.6
                }
                if (ch == prev) s += 0.12 * total
                if (ch == home) s += (if (last) 0.3 else 0.08) * total
                s
            }
        }
        return out
    }
}
