package com.shrey.buddy.desktop

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeInputEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.awt.Robot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Global hotkey. A quick press opens Buddy's bar for typing. Holding it (over [HOLD_MS]) opens the
 * bar and holds Wispr Flow's talk key for as long as you hold yours, so what you say is typed
 * straight into the bar; letting go submits it.
 */
class HotkeyListener(
    private val settings: () -> Settings,
    private val onOpen: () -> Unit,
    private val onTalkStart: () -> Unit,
    private val onTalkEnd: () -> Unit,
) : NativeKeyListener {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "buddy-hotkey").apply { isDaemon = true } }
    private val robot by lazy { Robot() }
    private var wisprKeys: List<Int> = Win.wisprTalkKeys()

    @Volatile private var down = false
    @Volatile private var talking = false
    @Volatile private var otherKey = false
    /** Keys we're pressing ourselves for Wispr: their events must not count as yours. */
    @Volatile private var injecting = emptySet<Int>()
    private var holdTimer: ScheduledFuture<*>? = null

    fun start() {
        Logger.getLogger(GlobalScreen::class.java.`package`.name).apply { level = Level.OFF; useParentHandlers = false }
        runCatching {
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
        }.onFailure { Log.w("hotkey hook failed", it) }
    }

    fun stop() = runCatching { GlobalScreen.unregisterNativeHook() }

    private val modifierOnly get() = settings().hotkey in setOf(Hotkey.RIGHT_CTRL, Hotkey.RIGHT_ALT)

    private fun isMain(e: NativeKeyEvent): Boolean {
        val m = e.modifiers
        val ctrl = m and NativeInputEvent.CTRL_MASK != 0
        val shift = m and NativeInputEvent.SHIFT_MASK != 0
        val alt = m and NativeInputEvent.ALT_MASK != 0
        val meta = m and NativeInputEvent.META_MASK != 0
        return when (settings().hotkey) {
            Hotkey.CTRL_SHIFT_SPACE -> e.keyCode == NativeKeyEvent.VC_SPACE && ctrl && shift && !alt
            Hotkey.CTRL_ALT_B -> e.keyCode == NativeKeyEvent.VC_B && ctrl && alt && !shift
            Hotkey.F9 -> e.keyCode == NativeKeyEvent.VC_F9 && !ctrl && !alt && !shift && !meta
            // The Copilot key sends Win + Shift + F23.
            Hotkey.COPILOT -> e.keyCode == NativeKeyEvent.VC_F23
            Hotkey.RIGHT_CTRL -> e.keyCode == NativeKeyEvent.VC_CONTROL && e.keyLocation == NativeKeyEvent.KEY_LOCATION_RIGHT
            Hotkey.RIGHT_ALT -> e.keyCode == NativeKeyEvent.VC_ALT && e.keyLocation == NativeKeyEvent.KEY_LOCATION_RIGHT
        }
    }

    /** Releasing any key of the combo ends a hold. */
    private fun endsHold(e: NativeKeyEvent): Boolean = when (settings().hotkey) {
        Hotkey.CTRL_SHIFT_SPACE -> e.keyCode in setOf(NativeKeyEvent.VC_SPACE, NativeKeyEvent.VC_CONTROL, NativeKeyEvent.VC_SHIFT)
        Hotkey.CTRL_ALT_B -> e.keyCode in setOf(NativeKeyEvent.VC_B, NativeKeyEvent.VC_CONTROL, NativeKeyEvent.VC_ALT)
        Hotkey.F9 -> e.keyCode == NativeKeyEvent.VC_F9
        Hotkey.COPILOT -> e.keyCode == NativeKeyEvent.VC_F23
        Hotkey.RIGHT_CTRL, Hotkey.RIGHT_ALT -> isMain(e)
    }

    private fun isInjected(e: NativeKeyEvent) = injecting.isNotEmpty() && awtCode(e) in injecting

    private fun awtCode(e: NativeKeyEvent): Int = when (e.keyCode) {
        NativeKeyEvent.VC_CAPS_LOCK -> java.awt.event.KeyEvent.VK_CAPS_LOCK
        NativeKeyEvent.VC_CONTROL -> java.awt.event.KeyEvent.VK_CONTROL
        NativeKeyEvent.VC_ALT -> java.awt.event.KeyEvent.VK_ALT
        NativeKeyEvent.VC_SHIFT -> java.awt.event.KeyEvent.VK_SHIFT
        NativeKeyEvent.VC_META -> java.awt.event.KeyEvent.VK_WINDOWS
        else -> -1
    }

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        if (isInjected(e)) return
        if (isMain(e)) {
            if (down) return // key repeat
            down = true
            otherKey = false
            if (!modifierOnly) worker.execute(onOpen)
            holdTimer = worker.schedule({
                if (down && !otherKey && settings().holdToTalk) {
                    talking = true
                    if (modifierOnly) onOpen()
                    onTalkStart()
                    // Give the bar a moment to take focus, so Wispr types into it.
                    Thread.sleep(180)
                    pressWispr()
                }
            }, HOLD_MS, TimeUnit.MILLISECONDS)
        } else if (down && !talking) {
            // Another key while holding: it was a shortcut (e.g. Right Ctrl + C), not Buddy.
            otherKey = true
        }
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {
        if (isInjected(e)) return
        if (!down || !endsHold(e)) return
        down = false
        holdTimer?.cancel(false)
        if (talking) {
            talking = false
            worker.execute {
                releaseWispr()
                onTalkEnd()
            }
        } else if (modifierOnly && !otherKey) {
            worker.execute(onOpen) // a tap
        }
    }

    private fun pressWispr() {
        wisprKeys = Win.wisprTalkKeys()
        injecting = wisprKeys.toSet()
        wisprKeys.forEach { runCatching { robot.keyPress(it) } }
    }

    private fun releaseWispr() {
        wisprKeys.reversed().forEach { runCatching { robot.keyRelease(it) } }
        // Our own release events arrive a moment later; ignore them too.
        worker.schedule({ injecting = emptySet() }, 300, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val HOLD_MS = 350L
    }
}
