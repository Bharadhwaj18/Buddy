package com.shrey.buddy.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
data class DesktopRelease(
    val versionCode: Int,
    val versionName: String,
    /** Path inside the private "releases" bucket, e.g. desktop/Buddy-1.2.0.msi. */
    val file: String,
    val sha256: String,
    val notes: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: DesktopRelease) : UpdateState
    data class Installing(val release: DesktopRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Same private updater as the phone: releases/desktop/latest.json + the .msi next to it. */
class Updater(private val cloud: Cloud) {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    suspend fun check(quiet: Boolean = false) {
        if (state.value is UpdateState.Installing) return
        if (!quiet) state.value = UpdateState.Checking
        state.value = runCatching {
            val text = cloud.download("desktop/latest.json").decodeToString().trimStart('﻿')
            val info = Local.json.decodeFromString(DesktopRelease.serializer(), text)
            if (info.versionCode > BuildInfo.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
        }.getOrElse { e ->
            if (quiet) UpdateState.Idle else UpdateState.Failed(e.message?.lineSequence()?.firstOrNull() ?: "Couldn't check for updates")
        }
    }

    /** Downloads, checks the hash, runs the installer and restarts Buddy when it's done. */
    suspend fun install(release: DesktopRelease) = withContext(Dispatchers.IO) {
        state.value = UpdateState.Installing(release)
        runCatching {
            val bytes = cloud.download(release.file)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(sha.equals(release.sha256, ignoreCase = true)) { "Download was corrupted, try again" }
            val msi = File(System.getProperty("java.io.tmpdir"), "Buddy-${release.versionName}.msi")
            msi.writeBytes(bytes)
            val exe = Win.exePath
            val relaunch = if (exe != null) " && start \"\" \"$exe\"" else ""
            ProcessBuilder("cmd", "/c", "msiexec /i \"${msi.absolutePath}\" /passive /norestart$relaunch")
                .redirectErrorStream(true).start()
            Thread.sleep(800)
            kotlin.system.exitProcess(0)
        }.onFailure { e ->
            Log.w("update failed", e)
            state.value = UpdateState.Failed(e.message ?: "Update failed")
        }
    }
}
