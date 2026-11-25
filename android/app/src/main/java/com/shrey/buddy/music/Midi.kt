package com.shrey.buddy.music

import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/** Writes a Standard MIDI File (type 1): the tune on track 1 and, optionally, the chords on track 2. */
object Midi {
    const val PPQ = 480

    fun file(notes: List<NoteEvent>, bpm: Int, chords: List<Chord> = emptyList(), title: String = "Buddy hum"): ByteArray {
        val origin = notes.firstOrNull()?.at ?: 0
        fun ticks(ms: Int) = ((ms - origin).coerceAtLeast(0) * bpm * PPQ / 60_000.0).roundToLong()

        val melody = Track().apply {
            meta(0x03, title.toByteArray().take(60).toByteArray())
            meta(0x51, byteArrayOf(((60_000_000 / bpm) shr 16).toByte(), ((60_000_000 / bpm) shr 8).toByte(), (60_000_000 / bpm).toByte()))
            meta(0x58, byteArrayOf(4, 2, 24, 8)) // 4/4
            event(0, byteArrayOf(0xC0.toByte(), 0)) // piano
            notes(notes.map { Triple(ticks(it.at), ticks(it.end), it.midi) }, channel = 0, velocity = 96)
        }
        val tracks = mutableListOf(melody)
        if (chords.isNotEmpty()) {
            val barTicks = 4L * PPQ
            tracks += Track().apply {
                meta(0x03, "Chords".toByteArray())
                event(0, byteArrayOf(0xC1.toByte(), 0))
                val ev = mutableListOf<Triple<Long, Long, Int>>()
                chords.forEachIndexed { i, c ->
                    // Root position around C3–B3.
                    val root = 48 + c.root
                    for (m in listOf(root, root + if (c.minor) 3 else 4, root + 7)) ev += Triple(i * barTicks, (i + 1) * barTicks, m)
                }
                notes(ev, channel = 1, velocity = 64)
            }
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(int32(6))
        out.write(int16(1)); out.write(int16(tracks.size)); out.write(int16(PPQ))
        for (t in tracks) {
            val body = t.finish()
            out.write("MTrk".toByteArray()); out.write(int32(body.size)); out.write(body)
        }
        return out.toByteArray()
    }

    private class Track {
        private val buf = ByteArrayOutputStream()
        private var lastTick = 0L

        fun event(tick: Long, bytes: ByteArray) {
            buf.write(vlq(tick - lastTick)); buf.write(bytes); lastTick = tick
        }

        fun meta(type: Int, data: ByteArray) = event(lastTick, byteArrayOf(0xFF.toByte(), type.toByte()) + vlq(data.size.toLong()) + data)

        /** (startTick, endTick, note) → sorted note-on / note-off events; offs before ons at the same tick. */
        fun notes(list: List<Triple<Long, Long, Int>>, channel: Int, velocity: Int) {
            val evs = mutableListOf<Triple<Long, Boolean, Int>>()
            for ((s, e, m) in list) if (e > s) { evs += Triple(s, true, m); evs += Triple(e, false, m) }
            evs.sortWith(compareBy<Triple<Long, Boolean, Int>> { it.first }.thenBy { it.second })
            for ((t, on, m) in evs) {
                val status = (if (on) 0x90 else 0x80) or channel
                event(t, byteArrayOf(status.toByte(), m.coerceIn(0, 127).toByte(), (if (on) velocity else 0).toByte()))
            }
        }

        fun finish(): ByteArray {
            event(lastTick, byteArrayOf(0xFF.toByte(), 0x2F, 0))
            return buf.toByteArray()
        }
    }

    fun vlq(v: Long): ByteArray {
        var value = v.coerceAtLeast(0)
        val bytes = ArrayDeque<Byte>()
        bytes.addFirst((value and 0x7F).toByte())
        value = value shr 7
        while (value > 0) {
            bytes.addFirst(((value and 0x7F) or 0x80).toByte())
            value = value shr 7
        }
        return bytes.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
}
