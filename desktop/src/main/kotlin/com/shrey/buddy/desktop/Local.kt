package com.shrey.buddy.desktop

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Everything the laptop app keeps lives in %APPDATA%\Buddy. */
object Local {
    val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Buddy").apply { mkdirs() }
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun <T> read(name: String, serializer: KSerializer<T>): T? = runCatching {
        File(dir, name).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer, it) }
    }.getOrNull()

    /** Writes via a temp file so a crash mid-write never leaves half a file. */
    @Synchronized
    fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

/** Which key opens Buddy's bar. Holding it also holds Wispr's talk key, so speech lands in the bar. */
enum class Hotkey(val label: String) {
    CTRL_SHIFT_SPACE("Ctrl + Shift + Space"),
    RIGHT_CTRL("Right Ctrl"),
    RIGHT_ALT("Right Alt"),
    CTRL_ALT_B("Ctrl + Alt + B"),
    F9("F9"),
    COPILOT("Copilot key"),
}

@Serializable
data class Settings(
    val deviceId: String = UUID.randomUUID().toString(),
    val hotkey: Hotkey = Hotkey.CTRL_SHIFT_SPACE,
    /** Hold the hotkey to talk through Wispr Flow. */
    val holdToTalk: Boolean = true,
    /** Start with Windows. */
    val startWithWindows: Boolean = true,
    /** Play a sound when a reminder pops up. */
    val sound: Boolean = true,
    val theme: ThemeMode = ThemeMode.BY_TIME,
    /** HH:mm — dark from this time… */
    val darkFrom: String = "19:00",
    /** …until this time. */
    val lightFrom: String = "07:00",
    /** The task pill at the top of the screen. */
    val showPill: Boolean = true,
    /** Where the pill sits: its top-centre point on screen (null = top centre of the main screen). */
    val pillX: Int? = null,
    val pillY: Int? = null,
) {
    companion object {
        private const val FILE = "settings.json"
        fun load(): Settings = Local.read(FILE, serializer()) ?: Settings().also { save(it) }
        fun save(s: Settings) = Local.write(FILE, serializer(), s)
    }
}
