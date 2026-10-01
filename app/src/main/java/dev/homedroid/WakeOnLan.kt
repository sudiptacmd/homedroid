package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Parsing and packet building for [WakeOnLan], kept free of Android so it can be tested. */
object WolCore {
    /** A MAC address in any common spelling (aa:bb:…, AA-BB-…, aabb.ccdd.eeff, 12 hex digits), normalized to aa:bb:cc:dd:ee:ff. */
    fun parseMac(s: String): String? {
        val hex = s.trim().lowercase().filter { it !in ":-. " }
        if (!Regex("[0-9a-f]{12}").matches(hex)) return null
        if (hex == "000000000000" || hex == "ffffffffffff") return null
        return hex.chunked(2).joinToString(":")
    }

    /** The magic packet: six 0xFF bytes, then the MAC sixteen times. */
    fun magicPacket(mac: String): ByteArray {
        val m = mac.split(':').map { it.toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
    }

    fun validIpv4(s: String) = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""").matches(s)

    /** An IPv4 address or a plain hostname, for the "is it on?" check. */
    fun validHost(s: String) = s.length in 1..253 && (validIpv4(s) || Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?").matches(s))

    fun cleanName(s: String) = s.filter { !it.isISOControl() }.trim().take(40)

    /** The device from a dashboard form, or an error message. */
    fun device(o: JSONObject, id: String): Result<JSONObject> {
        val name = cleanName(o.optString("name"))
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Give the device a name"))
        val mac = parseMac(o.optString("mac")) ?: return Result.failure(IllegalArgumentException("Enter the MAC address, like 3c:7c:3f:12:34:56"))
        val host = o.optString("host").trim()
        if (host.isNotEmpty() && !validHost(host)) return Result.failure(IllegalArgumentException("The address should be an IP or a hostname, like 192.168.1.20"))
        val broadcast = o.optString("broadcast").trim()
        if (broadcast.isNotEmpty() && !validIpv4(broadcast)) return Result.failure(IllegalArgumentException("The broadcast address should look like 192.168.1.255"))
        return Result.success(JSONObject().put("id", id).put("name", name).put("mac", mac).put("host", host).put("broadcast", broadcast))
    }

    const val MAX_DEVICES = 64
    /** Ports wake-on-LAN listeners use: 9 (discard) is the usual one, 7 (echo) the other. */
    val PORTS = listOf(9, 7)
}

/**
 * Wake-on-LAN relay: wakes computers on the phone's network from anywhere the dashboard is
 * reachable (Tailscale, a Cloudflare tunnel, another phone in the cluster). Saved devices live
 * in wol.json; a wake sends the magic packet as a UDP broadcast on every local IPv4 network,
 * which needs no special permission.
 */
class WakeOnLan(ctx: Context, private val cfg: Config) {
    private val file = File(ctx.filesDir, "wol.json")
    private val rnd = SecureRandom()

    @Synchronized private fun load(): MutableList<JSONObject> = try {
        JSONArray(file.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    } catch (_: Exception) { mutableListOf() }

    @Synchronized private fun save(list: List<JSONObject>) {
        writeDurably(file, JSONArray(list).toString().toByteArray())
    }

    fun handle(r: Request, seg: List<String>): Response {
        if (!cfg.wolEnabled) return Response.error(409, "Turn on Wake on LAN in Modules first")
        return when {
            r.method == "GET" && seg.isEmpty() -> Response.json(JSONObject()
                .put("devices", JSONArray(withStatus(load())))
                .put("broadcasts", JSONArray(broadcasts().map { it.hostAddress })))
            r.method == "POST" && seg == listOf("devices") -> saveDevice(r.json())
            r.method == "DELETE" && seg.size == 2 && seg[0] == "devices" -> synchronized(this) {
                val list = load()
                if (!list.removeAll { it.optString("id") == seg[1] }) return Response.error(404, "No such device")
                save(list)
                Response.ok()
            }
            r.method == "POST" && seg == listOf("wake") -> wake(r.json())
            else -> Response.error(404, "No such endpoint")
        }
    }

    private fun saveDevice(body: JSONObject): Response = synchronized(this) {
        val list = load()
        val id = body.optString("id").takeIf { id -> list.any { it.optString("id") == id } }
            ?: ByteArray(6).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        val d = WolCore.device(body, id).getOrElse { return Response.error(400, it.message ?: "Invalid device") }
        val i = list.indexOfFirst { it.optString("id") == id }
        if (i >= 0) list[i] = d else {
            if (list.size >= WolCore.MAX_DEVICES) return Response.error(409, "That's the most devices this list holds")
            list.add(d)
        }
        save(list)
        Response.json(d)
    }

    /** Wakes a saved device ({"id"}) or any MAC ({"mac", "broadcast"?}), for scripts and shortcuts. */
    private fun wake(body: JSONObject): Response {
        val d = body.optString("id").takeIf { it.isNotEmpty() }?.let { id -> load().firstOrNull { it.optString("id") == id } ?: return Response.error(404, "No such device") }
            ?: WolCore.device(body.put("name", body.optString("name").ifEmpty { "device" }), "").getOrElse { return Response.error(400, it.message ?: "Invalid request") }
        val targets = d.optString("broadcast").takeIf { it.isNotEmpty() }?.let { listOf(InetAddress.getByName(it)) }
            ?: (broadcasts() + InetAddress.getByName("255.255.255.255")).distinct()
        val packet = WolCore.magicPacket(d.getString("mac"))
        val sent = mutableListOf<String>()
        DatagramSocket().use { s ->
            s.broadcast = true
            for (t in targets) for (port in WolCore.PORTS) {
                try { s.send(DatagramPacket(packet, packet.size, t, port)); sent += "${t.hostAddress}:$port" } catch (_: Exception) {}
            }
        }
        if (sent.isEmpty()) return Response.error(502, "Couldn't send on any network; is the phone on Wi-Fi or Ethernet?")
        Jobs.line("Wake on LAN: woke ${d.optString("name")} (${d.getString("mac")})")
        return Response.json(JSONObject().put("sent", JSONArray(sent)))
    }

    /** Broadcast addresses of the phone's IPv4 networks (Wi-Fi, Ethernet; not mobile data). */
    private fun broadcasts(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
            .distinct()
    } catch (_: Exception) { emptyList() }

    /** Adds "online" (true, false or absent) to devices with an address, checking them all at once. */
    private fun withStatus(list: List<JSONObject>): List<JSONObject> {
        val checks = list.filter { it.optString("host").isNotEmpty() }
        if (checks.isEmpty()) return list
        val pool = Executors.newFixedThreadPool(minOf(checks.size, 8))
        try {
            val futures = checks.associateWith { d -> pool.submit<Boolean> { reachable(d.getString("host")) } }
            futures.forEach { (d, f) -> try { d.put("online", f.get(2, TimeUnit.SECONDS)) } catch (_: Exception) { d.put("online", false) } }
        } finally { pool.shutdownNow() }
        return list
    }

    /** Whether something answers on a port computers usually have open; a refusal counts too, it means the host is up. */
    private fun reachable(host: String): Boolean = STATUS_PORTS.any { port ->
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 400) }
            true
        } catch (e: java.net.ConnectException) {
            e.message?.contains("refused", ignoreCase = true) == true
        } catch (_: Exception) { false }
    }

    companion object {
        private val STATUS_PORTS = listOf(445, 3389, 22, 80, 5900)
    }
}
