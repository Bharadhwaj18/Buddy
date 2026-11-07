package com.shrey.buddy.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shrey.buddy.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

/** Written by scripts/release.ps1 next to the APK in the private "releases" bucket. */
@Serializable
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String = "",
    val notes: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * In-app updater (DESIGN.md §2): no Play Store, no USB. Checks the private releases bucket,
 * downloads with DownloadManager (resumable, survives the app being backgrounded), verifies the
 * SHA-256, then hands the APK to the system installer. Same signing key ⇒ installs in place.
 */
class Updater(private val context: Context, private val supabase: SupabaseClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val bucket get() = supabase.storage.from("releases")
    private val dir get() = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }

    suspend fun check(quiet: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        if (!quiet) _state.value = UpdateState.Checking
        _state.value = try {
            // Tolerate a UTF-8 BOM at the start of the file.
            val text = bucket.downloadAuthenticated("latest.json").decodeToString().trimStart('﻿')
            val info = json.decodeFromString(ReleaseInfo.serializer(), text)
            if (info.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            if (quiet) UpdateState.Idle else UpdateState.Failed(e.message?.lineSequence()?.firstOrNull() ?: "Couldn't check for updates")
        }
    }

    suspend fun download(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        try {
            val target = File(dir, release.apk)
            dir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
            if (target.exists() && verified(target, release)) {
                _state.value = UpdateState.ReadyToInstall(release, target)
                return@withContext
            }
            target.delete()
            val url = bucket.createSignedUrl(release.apk, 30.minutes)
            val dm = context.getSystemService(DownloadManager::class.java)
            val id = dm.enqueue(
                DownloadManager.Request(Uri.parse(url))
                    .setTitle("Buddy ${release.versionName}")
                    .setDestinationUri(Uri.fromFile(target))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
            )
            _state.value = UpdateState.Downloading(release, 0f)
            while (true) {
                delay(500)
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (!c.moveToFirst()) error("Download disappeared")
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            if (!verified(target, release)) error("Downloaded file failed its integrity check")
                            _state.value = UpdateState.ReadyToInstall(release, target)
                            return@withContext
                        }
                        DownloadManager.STATUS_FAILED -> error("Download failed")
                        else -> _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else 0f)
                    }
                }
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Download failed")
        }
    }

    /** Android must allow Buddy to install apps (one-time toggle). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun verified(file: File, release: ReleaseInfo): Boolean {
        if (release.sha256.isBlank()) return file.length() > 0
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, ignoreCase = true)
    }
}
