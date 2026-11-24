package com.shrey.buddy.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.awt.Window
import java.awt.event.KeyEvent
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

/** Reminder chime: one of Windows' own sounds. */
object Sound {
    // A short notification sound, not an alarm: the laptop shows a simple pop-up, it doesn't ring like a call.
    private val candidates = listOf("Windows Notify System Generic.wav", "Windows Notify.wav", "notify.wav")

    fun chime() {
        val media = File(System.getenv("WINDIR") ?: "C:\\Windows", "Media")
        val file = candidates.map { File(media, it) }.firstOrNull { it.exists() } ?: return
        Thread {
            runCatching {
                AudioSystem.getAudioInputStream(file).use { stream ->
                    val clip: Clip = AudioSystem.getClip()
                    clip.open(stream)
                    clip.start()
                    Thread.sleep((clip.microsecondLength / 1000).coerceAtMost(2_000))
                    clip.close()
                }
            }.onFailure { Log.w("chime failed", it) }
        }.apply { isDaemon = true }.start()
    }
}

object Win {
    /**
     * Windows won't let a background app take focus just by asking. Joining the foreground window's
     * input queue for a moment is the accepted way round that (the hotkey press counts as user input).
     */
    fun bringToFront(window: Window) {
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val u = User32.INSTANCE
            val fg = u.GetForegroundWindow()
            if (fg == hwnd) return
            val fgThread = u.GetWindowThreadProcessId(fg, null)
            val myThread = u.GetWindowThreadProcessId(hwnd, null)
            val attached = fgThread != myThread && u.AttachThreadInput(WinDef.DWORD(fgThread.toLong()), WinDef.DWORD(myThread.toLong()), true)
            u.ShowWindow(hwnd, User32.SW_SHOW)
            u.BringWindowToTop(hwnd)
            u.SetForegroundWindow(hwnd)
            u.SetFocus(hwnd)
            if (attached) u.AttachThreadInput(WinDef.DWORD(fgThread.toLong()), WinDef.DWORD(myThread.toLong()), false)
        }.onFailure { Log.w("bringToFront failed", it) }
        window.toFront()
        window.requestFocus()
    }

    // ---------------- start with Windows ----------------

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** The installed Buddy.exe (null when running from Gradle during development). */
    val exePath: String? get() = System.getProperty("jpackage.app-path")

    fun setStartWithWindows(on: Boolean) {
        val exe = exePath ?: return
        runCatching {
            val cmd = if (on) listOf("reg", "add", RUN_KEY, "/v", "Buddy", "/t", "REG_SZ", "/d", "\"$exe\" --background", "/f")
            else listOf("reg", "delete", RUN_KEY, "/v", "Buddy", "/f")
            ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
        }.onFailure { Log.w("startup setting failed", it) }
    }

    // ---------------- Wispr Flow ----------------

    /**
     * Wispr Flow's push-to-talk key, read from its own settings (%APPDATA%\Wispr Flow\config.json,
     * prefs.user.shortcuts: "20" → "ptt" means Caps Lock). Returned as java.awt key codes for Robot.
     * Falls back to Wispr's default on Windows (Ctrl + Win).
     */
    fun wisprTalkKeys(): List<Int> {
        val fallback = listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_WINDOWS)
        val file = File(System.getenv("APPDATA") ?: return fallback, "Wispr Flow\\config.json")
        val combo = runCatching {
            val root = Local.json.parseToJsonElement(file.readText()).jsonObject
            val shortcuts = (root["prefs"] as? JsonObject)?.get("user")?.jsonObject?.get("shortcuts")?.jsonObject
            shortcuts?.entries?.firstOrNull { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content == "ptt" }?.key
        }.getOrNull() ?: return fallback
        return combo.split("+").mapNotNull { it.trim().toIntOrNull() }.map(::vkToAwt).ifEmpty { fallback }
    }

    /** Windows virtual-key code → java.awt.event.KeyEvent code (left/right modifiers collapse). */
    fun vkToAwt(vk: Int): Int = when (vk) {
        160, 161 -> KeyEvent.VK_SHIFT
        162, 163 -> KeyEvent.VK_CONTROL
        164, 165 -> KeyEvent.VK_ALT
        91, 92 -> KeyEvent.VK_WINDOWS
        20 -> KeyEvent.VK_CAPS_LOCK
        13 -> KeyEvent.VK_ENTER
        in 112..123 -> KeyEvent.VK_F1 + (vk - 112)
        in 124..135 -> KeyEvent.VK_F13 + (vk - 124)
        else -> vk // letters, digits, space match
    }
}
