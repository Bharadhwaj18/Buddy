package com.shrey.buddy.data.sync

import android.content.Context
import android.util.Log
import com.shrey.buddy.BuddyApp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Instant sync between the laptop and the phone. Either side sends a tiny "something changed" signal
 * (no data in it) on a channel named after the user; the other side then syncs at once instead of
 * waiting for its regular sync. Best effort: the 15-minute sync and syncing on open still run.
 */
object LiveSync {
    private const val TAG = "BuddyLiveSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var channel: RealtimeChannel? = null
    private var listening: Job? = null

    @OptIn(FlowPreview::class)
    fun start(app: BuddyApp) {
        val supabase = app.container.supabase
        // Bursts of signals (a laptop saving several rows) become one sync.
        scope.launch {
            kicks.consumeAsFlow().debounce(800).collect {
                runCatching { app.container.syncEngine.sync() }
            }
        }
        scope.launch {
            supabase.auth.sessionStatus
                .map { (it as? SessionStatus.Authenticated)?.session?.user?.id }
                .distinctUntilChanged()
                .collect { uid -> join(app, uid) }
        }
    }

    private suspend fun join(app: BuddyApp, uid: String?) {
        val supabase = app.container.supabase
        listening?.cancel()
        channel?.let { old -> runCatching { supabase.realtime.removeChannel(old) } }
        channel = null
        if (uid == null) return
        runCatching {
            val ch = supabase.channel("sync:$uid") { broadcast { receiveOwnBroadcasts = false } }
            listening = scope.launch {
                ch.broadcastFlow<JsonObject>(event = "poke").collect { kicks.trySend(Unit) }
            }
            ch.subscribe()
            channel = ch
        }.onFailure { Log.w(TAG, "couldn't listen for sync signals", it) }
    }

    /** Syncs now (opening the app, a signal from the laptop). */
    fun syncNow(context: Context) {
        kicks.trySend(Unit)
        if (context.applicationContext !is BuddyApp) SyncScheduler.syncSoon(context)
    }

    /** Tells the laptop something changed here. */
    suspend fun poke() {
        val ch = channel ?: return
        runCatching { ch.broadcast(event = "poke", message = buildJsonObject { put("from", "phone") }) }
            .onFailure { Log.w(TAG, "signal not sent", it) }
    }
}
