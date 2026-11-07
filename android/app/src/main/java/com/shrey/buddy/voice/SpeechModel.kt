package com.shrey.buddy.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The offline "Hey Buddy" model (Vosk small Indian-English, ~37 MB, Apache-2.0) is downloaded once on
 * first use instead of being bundled — keeps the app (and every update) small.
 */
object SpeechModel {
    private const val NAME = "vosk-model-small-en-in-0.4"
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/$NAME.zip"

    private fun dir(context: Context) = File(context.filesDir, "speech-model")
    private fun ready(context: Context) = File(dir(context), ".complete")

    fun isReady(context: Context) = ready(context).exists()

    /** Path to the unpacked model, downloading it first if needed. [progress] gets 0..1. */
    suspend fun ensure(context: Context, progress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val target = dir(context)
        if (ready(context).exists()) return@withContext File(target, NAME).absolutePath
        target.deleteRecursively()
        target.mkdirs()
        val zip = File(context.cacheDir, "$NAME.zip")
        val conn = URL(URL_ZIP).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("Model download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.coerceAtLeast(1)
            var done = 0L
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(target, entry.name)
                // Zip-slip guard.
                if (!out.canonicalPath.startsWith(target.canonicalPath)) error("Bad model archive")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        zip.delete()
        ready(context).writeText(NAME)
        File(target, NAME).absolutePath
    }
}
