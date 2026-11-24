package com.shrey.buddy.lights

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** One bulb's answer to getPilot. */
data class WizState(
    val mac: String,
    val ip: String,
    val on: Boolean,
    val dimming: Int?,
    val temp: Int?,
    val sceneId: Int?,
    val r: Int?, val g: Int?, val b: Int?,
)

/** What to set on a bulb. Null fields are left as they are. */
data class WizPilot(
    val on: Boolean = true,
    /** 10–100 %. */
    val dimming: Int? = null,
    /** 2200–6500 K. */
    val temp: Int? = null,
    val rgb: Triple<Int, Int, Int>? = null,
    val sceneId: Int? = null,
)

/**
 * Philips WiZ bulbs, controlled directly on the home Wi-Fi (UDP port 38899, JSON). No cloud, no
 * account: the phone has to be on the same Wi-Fi as the bulbs.
 */
object Wiz {
    private const val TAG = "BuddyWiz"
    const val PORT = 38899
    private val json = Json { ignoreUnknownKeys = true }

    /** WiZ's built-in scenes (the ones worth offering). */
    val SCENES = linkedMapOf(
        11 to "Warm white", 12 to "Daylight", 13 to "Cool white", 14 to "Night light",
        6 to "Cozy", 16 to "Relax", 15 to "Focus", 18 to "TV time", 29 to "Candlelight",
        3 to "Sunset", 1 to "Ocean", 7 to "Forest", 2 to "Romance", 4 to "Party", 26 to "Club",
        9 to "Wake up", 10 to "Bedtime",
    )

    fun pilotParams(p: WizPilot): JsonObject = buildJsonObject {
        put("state", p.on)
        if (!p.on) return@buildJsonObject
        p.sceneId?.let { put("sceneId", it); put("speed", 100) }
        p.rgb?.let { (r, g, b) -> put("r", r.coerceIn(0, 255)); put("g", g.coerceIn(0, 255)); put("b", b.coerceIn(0, 255)) }
        if (p.sceneId == null && p.rgb == null) p.temp?.let { put("temp", it.coerceIn(2200, 6500)) }
        p.dimming?.let { put("dimming", it.coerceIn(10, 100)) }
    }

    fun setPilotMessage(p: WizPilot): String =
        buildJsonObject { put("id", 1); put("method", "setPilot"); put("params", pilotParams(p)) }.toString()

    fun parseState(text: String, ip: String): WizState? = runCatching {
        val r = json.parseToJsonElement(text).jsonObject["result"]?.jsonObject ?: return null
        fun int(k: String) = r[k]?.jsonPrimitive?.intOrNull
        WizState(
            mac = r["mac"]?.jsonPrimitive?.content ?: return null, ip = ip,
            on = r["state"]?.jsonPrimitive?.booleanOrNull ?: false,
            dimming = int("dimming"), temp = int("temp"), sceneId = int("sceneId")?.takeIf { it > 0 },
            r = int("r"), g = int("g"), b = int("b"),
        )
    }.getOrNull()

    /** The Wi-Fi network, if the phone is on one. */
    fun wifi(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** Broadcast addresses for the Wi-Fi subnet (e.g. 192.168.1.255), plus the all-ones one. */
    private fun broadcasts(context: Context, network: Network): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val subnet = cm?.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { la ->
            val a = la.address as? Inet4Address ?: return@mapNotNull null
            val ip = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            val mask = if (la.prefixLength == 0) 0L else (0xffffffffL shl (32 - la.prefixLength)) and 0xffffffffL
            val bc = (ip and mask) or (mask.inv() and 0xffffffffL)
            InetAddress.getByAddress(byteArrayOf((bc shr 24).toByte(), (bc shr 16).toByte(), (bc shr 8).toByte(), bc.toByte()))
        }
        return subnet + InetAddress.getByName("255.255.255.255")
    }

    private fun socket(network: Network?, timeoutMs: Int) = DatagramSocket().apply {
        broadcast = true
        soTimeout = timeoutMs
        network?.let { runCatching { it.bindSocket(this) } }
    }

    /** Every bulb that answers on this Wi-Fi within [waitMs]. Empty when not on Wi-Fi. */
    suspend fun discover(context: Context, waitMs: Long = 2500): List<WizState> = withContext(Dispatchers.IO) {
        val network = wifi(context) ?: return@withContext emptyList()
        val found = linkedMapOf<String, WizState>()
        runCatching {
            socket(network, 400).use { s ->
                // getPilot gives the state; "registration" is what the WiZ app itself broadcasts, and some
                // firmware only answers that one.
                val msgs = listOf(
                    """{"method":"getPilot","params":{}}""",
                    """{"method":"registration","params":{"phoneMac":"AAAAAAAAAAAA","register":false,"phoneIp":"1.2.3.4","id":"1"}}""",
                ).map { it.toByteArray() }
                val targets = broadcasts(context, network)
                val end = System.currentTimeMillis() + waitMs
                var nextSend = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    // Ask a few times: UDP broadcasts get dropped.
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach { t -> msgs.forEach { m -> runCatching { s.send(DatagramPacket(m, m.size, t, PORT)) } } }
                        nextSend = System.currentTimeMillis() + 700
                    }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address.hostAddress ?: continue
                        val text = String(p.data, 0, p.length)
                        parseState(text, ip)?.let { st ->
                            // Keep the getPilot answer (it has the state) over a bare registration reply.
                            if (text.contains("getPilot") || st.mac !in found) found[st.mac] = st
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        }.onFailure { Log.w(TAG, "discover", it) }
        found.values.toList()
    }

    /** Asks one address directly (for when broadcasts are blocked on the router). Null if nothing answers. */
    suspend fun probe(context: Context, ip: String): WizState? = withContext(Dispatchers.IO) {
        runCatching {
            socket(wifi(context), 500).use { s ->
                val msg = """{"method":"getPilot","params":{}}""".toByteArray()
                val buf = ByteArray(2048)
                repeat(4) {
                    s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), PORT))
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        parseState(String(p.data, 0, p.length), ip)?.let { return@runCatching it }
                    } catch (_: SocketTimeoutException) { }
                }
                null
            }
        }.getOrNull()
    }

    /** Sends [pilot] to each address; true for each bulb that acknowledged. */
    suspend fun set(context: Context, ips: List<String>, pilot: WizPilot): Map<String, Boolean> = withContext(Dispatchers.IO) {
        if (ips.isEmpty()) return@withContext emptyMap()
        val msg = setPilotMessage(pilot).toByteArray()
        val acked = mutableSetOf<String>()
        runCatching {
            socket(wifi(context), 300).use { s ->
                val buf = ByteArray(1024)
                repeat(3) { attempt ->
                    ips.filter { it !in acked }.forEach { ip ->
                        runCatching { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), PORT)) }
                    }
                    val end = System.currentTimeMillis() + 300L * (attempt + 1)
                    while (acked.size < ips.size && System.currentTimeMillis() < end) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            p.address.hostAddress?.let { if (String(p.data, 0, p.length).contains("\"success\":true")) acked += it }
                        } catch (_: SocketTimeoutException) { break }
                    }
                    if (acked.size == ips.size) return@runCatching
                }
            }
        }.onFailure { Log.w(TAG, "set", it) }
        ips.associateWith { it in acked }
    }
}
