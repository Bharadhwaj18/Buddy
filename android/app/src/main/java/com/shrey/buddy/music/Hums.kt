package com.shrey.buddy.music

import com.shrey.buddy.data.items.Hum
import com.shrey.buddy.data.items.HumNote

/** Glue between the vault's saved [Hum]s and the music maths. */
object Hums {
    fun NoteEvent.saved() = HumNote(at, len, midi)
    fun HumNote.event() = NoteEvent(at, len, midi)

    fun events(h: Hum) = h.notes.map { it.event() }
    fun key(h: Hum) = Key.parse(h.key)

    /** Chords as [Chord]s for playback / MIDI; empty when the key is unknown. */
    fun chords(h: Hum): List<Chord> {
        val k = key(h) ?: return emptyList()
        val options = Theory.diatonic(k)
        return h.chords.mapNotNull { sym -> options.firstOrNull { it.symbol(k.flats) == sym } }
    }

    /** Notes, key, tempo and chords for a fresh take. Null if no tune was heard. */
    fun analyse(frames: List<Float?>, hopMs: Float, title: String, audio: String?, now: Long = System.currentTimeMillis()): Hum? {
        val notes = Transcriber.notes(frames, hopMs)
        if (notes.size < 2) return null
        val start = notes.first().at
        val shifted = notes.map { it.copy(at = it.at - start) }
        val bpm = Tempo.bpm(shifted) ?: 100
        return withTempo(
            Hum(title = title, notes = shifted.map { it.saved() }, lengthMs = shifted.maxOf { it.end }, audio = audio, createdAt = now, audioOffsetMs = start),
            bpm,
        )
    }

    /** Re-works key and chords for a new tempo (or the same one). */
    fun withTempo(h: Hum, bpm: Int): Hum {
        val notes = events(h)
        val k = Theory.key(notes)
        val chords = if (k == null) emptyList() else Theory.chords(notes, k, bpm).map { it.symbol(k.flats) }
        return h.copy(bpm = bpm.coerceIn(40, 240), key = k?.name ?: "", chords = chords)
    }

    /** Moves every note by [semitones] (and the key and chords with them). */
    fun transpose(h: Hum, semitones: Int): Hum =
        withTempo(h.copy(notes = h.notes.map { it.copy(midi = (it.midi + semitones).coerceIn(24, 108)) }), h.bpm)

    fun midi(h: Hum, snap: Boolean, withChords: Boolean): ByteArray {
        val notes = events(h).let { if (snap) Tempo.snap(it, h.bpm) else it }
        return Midi.file(notes, h.bpm, if (withChords) chords(h) else emptyList(), h.title.ifBlank { "Buddy hum" })
    }

    fun render(h: Hum, snap: Boolean, withChords: Boolean): ShortArray {
        val notes = events(h).let { if (snap) Tempo.snap(it, h.bpm) else it }
        return Synth.render(notes, if (withChords) chords(h) else emptyList(), h.bpm)
    }

    /** "C4 – G5" */
    fun range(h: Hum): String {
        if (h.notes.isEmpty()) return ""
        val flats = key(h)?.flats ?: false
        return NoteNames.name(h.notes.minOf { it.midi }, flats) + " – " + NoteNames.name(h.notes.maxOf { it.midi }, flats)
    }
}
