package com.shrey.buddy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

object AppIcons {
    private fun load(name: String) = BitmapPainter(
        Image.makeFromEncoded(AppIcons::class.java.getResourceAsStream("/$name")!!.readBytes()).toComposeImageBitmap(),
    )
    val window by lazy { load("buddy.png") }
    val tray by lazy { load("tray.png") }
}

/**
 * Only one Buddy runs. A second launch (e.g. the Start-menu shortcut while Buddy sits in the tray)
 * tells the running one to show its window, then quits.
 */
private object SingleInstance {
    private val portFile = File(Local.dir, "instance.port")
    private var lock: FileLock? = null

    fun claim(onShow: () -> Unit): Boolean {
        lock = runCatching { RandomAccessFile(File(Local.dir, "instance.lock"), "rw").channel.tryLock() }.getOrNull()
        if (lock == null) {
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), portFile.readText().trim().toInt()).use { it.getOutputStream().write("show\n".toByteArray()) }
            }
            return false
        }
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        portFile.writeText(server.localPort.toString())
        Thread {
            while (true) runCatching { server.accept().use { onShow() } }
        }.apply { isDaemon = true; name = "buddy-instance" }.start()
        return true
    }
}

fun main(args: Array<String>) {
    var showMain by mutableStateOf(!args.contains("--background"))
    if (!SingleInstance.claim { showMain = true }) return

    val app = BuddyState()
    val bar = Bar(app)
    val updater = Updater(app.cloud)
    val hotkey = HotkeyListener(
        settings = { app.settings.value },
        onOpen = bar::open,
        onTalkStart = bar::talkStart,
        onTalkEnd = bar::talkEnd,
    )
    app.start()
    hotkey.start()
    if (app.settings.value.startWithWindows) Win.setStartWithWindows(true)
    app.scope.launch {
        // Check for a newer laptop app now and then (quietly; Settings shows it).
        while (true) {
            if (app.signedIn.value == true) updater.check(quiet = true)
            delay(6 * 60 * 60_000L)
        }
    }

    application {
        val signedIn by app.signedIn.collectAsState()
        val rings by app.ringing.collectAsState()
        val barUi by bar.ui.collectAsState()
        val settings by app.settings.collectAsState()
        val agenda by app.agenda.collectAsState()
        // Light by day, dark in the evening: re-checked every minute and whenever settings change.
        var theme by remember { mutableStateOf(BuddyTheme.forNow(settings)) }
        LaunchedEffect(settings) {
            while (true) {
                theme = BuddyTheme.forNow(settings)
                delay(60_000)
            }
        }

        LaunchedEffect(signedIn) {
            // Not signed in yet: open the window so there's somewhere to sign in.
            if (signedIn == false) showMain = true
            if (signedIn == true) runCatching { app.refresh() }
        }

        CompositionLocalProvider(LocalTheme provides theme) {
            MaterialTheme(colorScheme = theme.material) {
                Tray(
                    icon = AppIcons.tray,
                    tooltip = "Buddy  ·  ${agenda.left} left today  ·  ${settings.hotkey.label} to add",
                    onAction = { showMain = true },
                    menu = {
                        Item("Add…  (${settings.hotkey.label})", onClick = bar::open)
                        Item("Open Buddy", onClick = { showMain = true })
                        Item(if (settings.showPill) "Hide today pill" else "Show today pill", onClick = {
                            app.updateSettings { it.copy(showPill = !it.showPill) }
                        })
                        Separator()
                        Item("Quit", onClick = {
                            hotkey.stop()
                            exitApplication()
                        })
                    },
                )

                if (showMain) {
                    Window(
                        onCloseRequest = { showMain = false },
                        title = "Buddy",
                        icon = AppIcons.window,
                        state = rememberWindowState(width = 480.dp, height = 680.dp),
                    ) {
                        Box(Modifier.fillMaxSize().background(theme.window)) {
                            when (signedIn) {
                                null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = theme.accent)
                                false -> LoginScreen(app, theme)
                                true -> HomeScreen(app, updater, theme, onQuickAdd = bar::open)
                            }
                        }
                    }
                }

                if (signedIn == true) {
                    if (settings.showPill) TopPillWindow(app, agenda, theme, onAdd = bar::open)
                    QuickBarWindow(bar, barUi, settings.hotkey.label, theme)
                    ToastWindow(bar, theme)
                    ReminderPopups(app, rings, theme)
                }
            }
        }
    }
    kotlin.system.exitProcess(0)
}

