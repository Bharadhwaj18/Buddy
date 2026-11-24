package com.shrey.buddy.lights

import com.shrey.buddy.data.items.LightAction
import com.shrey.buddy.voice.VoiceIntent

/**
 * Offline understanding of light commands:
 *  - "lights off", "turn on the bedroom lights", "switch off the lamp"
 *  - "dim the lights", "lights to 40 percent", "brighten the hall lights"
 *  - "make the lights warm" / "cool" / "red", "lights relax" (a WiZ scene)
 *  - a routine by name: "movie mode", "start movie mode", "run good night"
 */
object LightVoice {
    sealed interface Command {
        data class Set(val action: LightAction) : Command
        data class Routine(val name: String) : Command
    }

    private val LIGHT_WORD = Regex("\\b(lights?|lamps?|bulbs?)\\b")
    private val OFF = Regex("\\b(off|switch off|turn off|shut)\\b")
    private val PERCENT = Regex("\\b(\\d{1,3})\\s?(%|percent|per cent)")
    private val TO_NUMBER = Regex("\\bto (\\d{1,3})\\b")
    private val DIM = Regex("\\b(dim|dimmer|low|lower)\\b")
    private val BRIGHT = Regex("\\b(brighten|brighter|bright|full|max(?:imum)?)\\b")

    private val WARMTH = linkedMapOf(
        "warm white" to 2700, "warm" to 2700, "yellow white" to 2700,
        "daylight" to 4200, "natural" to 4200, "neutral" to 4200,
        "cool white" to 6500, "cool" to 6500, "cold" to 6500, "white" to 4200,
    )
    val COLORS = linkedMapOf(
        "red" to "#FF0000", "green" to "#00FF00", "blue" to "#0000FF", "purple" to "#8000FF",
        "pink" to "#FF3399", "orange" to "#FF7A00", "yellow" to "#FFD000", "cyan" to "#00FFFF",
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        .removeSuffix(" mode").removeSuffix(" scene").removeSuffix(" routine").trim()

    fun parse(input: String, rooms: List<String> = emptyList(), routines: List<String> = emptyList()): Command? {
        val text = VoiceIntent.normalise(input)
        if (text.isBlank()) return null

        // A routine you named ("movie mode", "start good night").
        val bare = norm(text.replace(Regex("^(?:start|run|activate|turn on|switch on|set|do|go|put on|play)\\s+(?:the\\s+)?"), ""))
        routines.firstOrNull { norm(it) == bare && bare.isNotEmpty() }?.let { return Command.Routine(it) }

        if (!LIGHT_WORD.containsMatchIn(text)) return null
        val inRooms = rooms.filter { r -> r.isNotBlank() && Regex("\\b${Regex.escape(r.lowercase())}\\b").containsMatchIn(text) }

        if (OFF.containsMatchIn(text) && !Regex("\\bon\\b").containsMatchIn(text)) {
            return Command.Set(LightAction(on = false, rooms = inRooms))
        }
        val scene = Wiz.SCENES.entries.firstOrNull { (_, name) -> Regex("\\b${name.lowercase()}\\b").containsMatchIn(text) }?.key
        val color = if (scene == null) COLORS.entries.firstOrNull { (name, _) -> Regex("\\b$name\\b").containsMatchIn(text) }?.value else null
        val warmth = if (scene == null && color == null) WARMTH.entries.firstOrNull { (name, _) -> Regex("\\b$name\\b").containsMatchIn(text) }?.value else null
        val brightness = (PERCENT.find(text) ?: TO_NUMBER.find(text))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(10, 100)
            ?: when {
                DIM.containsMatchIn(text) -> 30
                BRIGHT.containsMatchIn(text) -> 100
                else -> null
            }
        return Command.Set(LightAction(on = true, brightness = brightness, warmth = warmth, color = color, sceneId = scene, rooms = inRooms))
    }

    /** "Bedroom lights off", "Lights 40% warm" — what Buddy says back. */
    fun describe(a: LightAction): String {
        val where = if (a.rooms.isEmpty()) "Lights" else a.rooms.joinToString(" & ") + " lights"
        if (!a.on) return "$where off"
        val bits = listOfNotNull(
            a.sceneId?.let { Wiz.SCENES[it] ?: "scene $it" },
            a.color?.let { c -> COLORS.entries.firstOrNull { it.value.equals(c, true) }?.key ?: "colour" },
            a.warmth?.let { if (it <= 3000) "warm" else if (it >= 5500) "cool" else "daylight" },
            a.brightness?.let { "$it%" },
            if (a.effect == "flash") "flash" else null,
        )
        return if (bits.isEmpty()) "$where on" else "$where on · ${bits.joinToString(" · ")}"
    }
}
