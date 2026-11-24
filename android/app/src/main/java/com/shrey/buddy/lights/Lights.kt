package com.shrey.buddy.lights

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Light
import com.shrey.buddy.data.items.LightAction
import com.shrey.buddy.data.items.LightRoutine
import com.shrey.buddy.reminders.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Your WiZ lights: find them, set them, and run routines (time, voice, reminders, arriving home). */
object Lights {
    private const val TAG = "BuddyLights"
    const val ACTION_FIRE = "com.shrey.buddy.lights.FIRE"
    const val ACTION_NETWORK = "com.shrey.buddy.lights.NETWORK"
    const val EXTRA_ID = "id"
    /** Not seeing the lights for this long, then seeing them again, counts as arriving home. */
    private const val AWAY_MS = 20 * 60_000L

    private fun items(context: Context) = (context.applicationContext as BuddyApp).container.items
    private fun prefs(context: Context) = context.getSharedPreferences("lights", Context.MODE_PRIVATE)

    suspend fun lights(context: Context): List<Item<Light>> = items(context).all(Kinds.LIGHT, Light.serializer())
    suspend fun routines(context: Context): List<Item<LightRoutine>> = items(context).all(Kinds.LIGHT_ROUTINE, LightRoutine.serializer())

    /** Finds bulbs on this Wi-Fi; new ones are added ("Light 3"), known ones get their new address. Returns (found, new). */
    suspend fun discover(context: Context): Pair<Int, Int> {
        val known = lights(context).associateBy { it.data.mac }
        val broadcast = Wiz.discover(context)
        // Routers that block broadcasts: ask the saved addresses directly.
        val direct = known.values.filter { k -> broadcast.none { it.mac == k.data.mac } && k.data.ip.isNotBlank() }
            .mapNotNull { Wiz.probe(context, it.data.ip) }
        val found = (broadcast + direct).distinctBy { it.mac }
        var added = 0
        found.forEach { s ->
            val k = known[s.mac]
            when {
                k == null -> {
                    added++
                    items(context).put(Kinds.LIGHT, Light(s.mac, "Light ${known.size + added}", "", s.ip), Light.serializer(), id = ItemStore.stableId("light:${s.mac}"), notify = false)
                }
                k.data.ip != s.ip -> items(context).put(Kinds.LIGHT, k.data.copy(ip = s.ip), Light.serializer(), id = k.id, notify = false)
            }
        }
        if (found.isNotEmpty()) markHome(context)
        return found.size to added
    }

    /** Adds a bulb by its IP address (from the WiZ app's device info, or the router). Null if it didn't answer. */
    suspend fun addByIp(context: Context, ip: String): Light? {
        val s = Wiz.probe(context, ip.trim()) ?: return null
        val id = ItemStore.stableId("light:${s.mac}")
        val existing = items(context).get(id, Light.serializer())?.data
        val light = existing?.copy(ip = s.ip) ?: Light(s.mac, "Light ${lights(context).size + 1}", "", s.ip)
        items(context).put(Kinds.LIGHT, light, Light.serializer(), id = id, notify = false)
        markHome(context)
        return light
    }

    private fun markHome(context: Context) = prefs(context).edit().putLong("homeAt", System.currentTimeMillis()).apply()

    fun pilotFor(a: LightAction) = WizPilot(
        on = a.on,
        dimming = a.brightness,
        temp = a.warmth,
        rgb = a.color?.let(::hexRgb),
        sceneId = a.sceneId,
    )

    fun hexRgb(hex: String): Triple<Int, Int, Int>? {
        val h = hex.removePrefix("#").takeIf { it.length == 6 } ?: return null
        val v = h.toIntOrNull(16) ?: return null
        return Triple(v shr 16 and 0xff, v shr 8 and 0xff, v and 0xff)
    }

    /** Which saved lights an action is for (rooms match case-insensitively; none named = all). */
    fun targets(all: List<Light>, rooms: List<String>): List<Light> =
        if (rooms.isEmpty()) all else all.filter { l -> rooms.any { it.equals(l.room, ignoreCase = true) } }

    /** Applies [a]; if some bulbs don't answer (new address), looks them up again and retries. Returns what Buddy says. */
    suspend fun apply(context: Context, a: LightAction): String {
        val all = lights(context).map { it.data }
        if (all.isEmpty()) return "No lights set up yet. Open Lights in Buddy to find them."
        val targets = targets(all, a.rooms)
        if (targets.isEmpty()) return "No lights in ${a.rooms.joinToString(" & ")}"
        if (Wiz.wifi(context) == null) return "Can't reach the lights: the phone isn't on Wi-Fi"
        if (a.effect == "flash") { flash(context, targets); return "Lights flashed" }

        val pilot = pilotFor(a)
        val first = Wiz.set(context, targets.map { it.ip }.filter { it.isNotBlank() }, pilot)
        var ok = targets.count { first[it.ip] == true }
        val missed = targets.filter { first[it.ip] != true }
        if (missed.isNotEmpty()) {
            discover(context)
            val fresh = lights(context).map { it.data }.associateBy { it.mac }
            val retry = missed.mapNotNull { fresh[it.mac]?.ip?.takeIf { ip -> ip.isNotBlank() } }
            ok += Wiz.set(context, retry, pilot).count { it.value }
        } else markHome(context)
        val said = LightVoice.describe(a)
        return when {
            ok == 0 -> "Couldn't reach the lights. Are they powered on, on this Wi-Fi?"
            ok < targets.size -> "$said ($ok of ${targets.size} answered)"
            else -> said
        }
    }

    /** Blinks the lights three times, then puts each back the way it was. */
    private suspend fun flash(context: Context, targets: List<Light>) {
        val before = Wiz.discover(context, 1200).associateBy { it.mac }
        val ips = targets.mapNotNull { before[it.mac]?.ip ?: it.ip.ifBlank { null } }
        repeat(3) {
            Wiz.set(context, ips, WizPilot(on = true, dimming = 100, temp = 6500))
            delay(450)
            Wiz.set(context, ips, WizPilot(on = false))
            delay(450)
        }
        targets.forEach { t ->
            val s = before[t.mac] ?: return@forEach
            val restore = if (!s.on) WizPilot(on = false) else WizPilot(
                on = true, dimming = s.dimming, temp = s.temp, sceneId = s.sceneId,
                rgb = if (s.temp == null && s.sceneId == null && s.r != null) Triple(s.r, s.g ?: 0, s.b ?: 0) else null,
            )
            Wiz.set(context, listOf(s.ip), restore)
        }
    }

    suspend fun run(context: Context, r: LightRoutine): String = apply(context, r.action).let { if (r.name.isNotBlank()) "${r.name}: $it" else it }

    /** Runs every enabled routine with this trigger ("reminder", "wake"). */
    fun onEvent(context: Context, trigger: String) {
        ReminderScheduler.scope.launch {
            runCatching {
                routines(context).filter { it.data.enabled && it.data.trigger == trigger }.forEach { run(context, it.data) }
            }.onFailure { Log.w(TAG, "event $trigger", it) }
        }
    }

    /** A reminder is calling: "wake" routines for wake-up / alarm reminders, "reminder" routines for all. */
    fun onReminderRinging(context: Context, title: String) {
        if (WAKE.containsMatchIn(title)) onEvent(context, "wake")
        onEvent(context, "reminder")
    }

    private val WAKE = Regex("\\b(wake|alarm|get up|morning)\\b", RegexOption.IGNORE_CASE)

    // ---- time routines ----

    /** Next time a "time" routine fires after [now], or null if it never will. */
    fun nextFire(r: LightRoutine, now: LocalDateTime): LocalDateTime? {
        if (!r.enabled || r.trigger != "time") return null
        val t = runCatching { LocalTime.parse(r.time) }.getOrNull() ?: return null
        for (d in 0..7) {
            val day = now.toLocalDate().plusDays(d.toLong())
            val at = day.atTime(t)
            if (!at.isAfter(now)) continue
            if (r.days.isEmpty() || day.dayOfWeek.value in r.days) return at
        }
        return null
    }

    private fun firePending(context: Context, id: String, flags: Int) = PendingIntent.getBroadcast(
        context, ("light:$id").hashCode(), Intent(context, LightsReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    /** (Re)arms an exact alarm for each time routine, cancels the rest, and listens for the home Wi-Fi. */
    suspend fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(context)
        val all = routines(context)
        val now = LocalDateTime.now()
        val armed = mutableSetOf<String>()
        all.forEach { item ->
            val at = nextFire(item.data, now) ?: return@forEach
            val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, firePending(context, item.id, PendingIntent.FLAG_UPDATE_CURRENT)) }
                .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, firePending(context, item.id, PendingIntent.FLAG_UPDATE_CURRENT)) }
            armed += item.id
        }
        (p.getStringSet("armed", emptySet()).orEmpty() - armed).forEach { old ->
            firePending(context, old, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it) }
        }
        p.edit().putStringSet("armed", armed).apply()
        watchWifi(context, all.any { it.data.enabled && it.data.trigger == "arrive" })
    }

    // ---- arriving home ----

    private fun networkPending(context: Context, flags: Int) = PendingIntent.getBroadcast(
        context, 0x1197, Intent(context, LightsReceiver::class.java).setAction(ACTION_NETWORK),
        flags or PendingIntent.FLAG_MUTABLE, // the system adds the network to it
    )

    /** Hears about Wi-Fi connecting even when Buddy isn't running. */
    private fun watchWifi(context: Context, on: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        networkPending(context, PendingIntent.FLAG_NO_CREATE)?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        if (!on) return
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(req, networkPending(context, PendingIntent.FLAG_UPDATE_CURRENT)) }
            .onFailure { Log.w(TAG, "watch wifi", it) }
    }

    /**
     * On Wi-Fi (and every ~15 min from the step worker): if your lights answer after not being seen
     * for 20+ minutes, you've just got home, so "arrive" routines run (if it's past their start time).
     */
    suspend fun checkHome(context: Context) {
        val arrive = routines(context).filter { it.data.enabled && it.data.trigger == "arrive" }
        if (arrive.isEmpty() || Wiz.wifi(context) == null) return
        val known = lights(context).map { it.data.mac }.toSet()
        if (known.isEmpty()) return
        val seen = Wiz.discover(context, 1500).any { it.mac in known }
        if (!seen) return
        val last = prefs(context).getLong("homeAt", 0)
        markHome(context)
        if (last == 0L || System.currentTimeMillis() - last < AWAY_MS) return
        val now = LocalTime.now()
        arrive.filter { r -> r.data.after?.let { runCatching { !now.isBefore(LocalTime.parse(it)) }.getOrDefault(true) } ?: true }
            .forEach { run(context, it.data) }
    }

    fun dayName(d: Int) = DayOfWeek.of(d).name.take(3).lowercase().replaceFirstChar { it.uppercase() }
}

/** Time routines firing, Wi-Fi connecting (arriving home). Not exported: only our PendingIntents reach it. */
class LightsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ReminderScheduler.scope.launch {
            runCatching {
                when (intent.action) {
                    Lights.ACTION_FIRE -> {
                        val id = intent.getStringExtra(Lights.EXTRA_ID).orEmpty()
                        Lights.routines(context).firstOrNull { it.id == id }?.let { Lights.run(context, it.data) }
                        Lights.reschedule(context)
                    }
                    Lights.ACTION_NETWORK -> {
                        delay(2_500) // let the connection settle before asking the bulbs
                        Lights.checkHome(context)
                    }
                }
            }.onFailure { Log.w("BuddyLights", "receiver", it) }
            pending.finish()
        }
    }
}
