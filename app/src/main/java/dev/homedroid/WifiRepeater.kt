package dev.homedroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wi-Fi repeater without root: a Wi-Fi Direct group the phone hosts while staying a client of its own
 * Wi-Fi, and proxies (see [ProxyServer]) on the group's address that carry the clients' traffic.
 * Phones cannot route or tether without root, so clients set a proxy instead.
 *
 * It starts and stops with the server (the dashboard calls [resume] and [suspend]); [setEnabled] is the
 * user's switch, which survives restarts.
 */
class WifiRepeater private constructor(private val app: Context) {
    private val cfg = Config(app)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "repeater-control").apply { isDaemon = true } }
    private val manager: WifiP2pManager? get() = app.getSystemService(WifiP2pManager::class.java)

    private var channel: WifiP2pManager.Channel? = null
    private var proxy: ProxyServer? = null

    // What the status shows; written by the worker, read by request threads.
    @Volatile private var state = "off"
    @Volatile private var message = ""
    @Volatile private var address = RepeaterCore.DEFAULT_ADDRESS
    @Volatile private var iface = ""
    @Volatile private var clients: List<Pair<String, String>> = emptyList()
    @Volatile private var staKept: Boolean? = null
    @Volatile private var running = false
    @Volatile private var retryDelaySec = 3L
    @Volatile private var generation = 0

    val supported get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && manager != null

    fun hasPermission() = app.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    // --- lifecycle -------------------------------------------------------------------------------------------

    /** The server started: bring the repeater up if the user left it on. */
    fun resume() { if (cfg.repeaterEnabled) begin() else refreshIdle() }

    /** The server stopped: take the group down, but remember that the user wants it on. */
    fun suspend() { end("off", "Starts with the server") }

    /**
     * The user's switch. Returns an explanation if it can't be turned on right now (no permission, no
     * Wi-Fi Direct); the setting is kept either way, so it starts when the problem is fixed.
     */
    fun setEnabled(on: Boolean): String? {
        cfg.repeaterEnabled = on
        if (!on) { end("off", ""); return null }
        refreshIdle()
        if (!supported) return message
        if (!hasPermission()) return message
        if (ServerService.active) begin()
        else { message = "On. It starts with the server."; state = "waiting" }
        return null
    }

    /** Applies new network settings; a running group is rebuilt with them. */
    fun configure(name: String?, passphrase: String?, band: String?, allowLan: Boolean?) {
        name?.let { cfg.repeaterName = it }
        passphrase?.let { cfg.repeaterPassphrase = it }
        band?.let { cfg.repeaterBand = it }
        allowLan?.let { cfg.repeaterAllowLan = it }
        if (running && cfg.repeaterEnabled) { end("starting", "Applying the new settings"); begin() }
    }

    private fun refreshIdle() {
        when {
            !supported -> { state = "unsupported"; message = "This phone doesn't support Wi-Fi Direct, which the repeater needs." }
            !hasPermission() -> { state = "needs_permission"; message = "Allow \"${permissionLabel()}\" in the Homedroid app (Settings, Wi-Fi repeater) so the phone can create the network." }
            !cfg.repeaterEnabled -> { state = "off"; message = "" }
        }
    }

    private fun begin() {
        refreshIdle()
        if (!supported || !hasPermission()) return
        if (running) return
        running = true
        val gen = ++generation
        state = "starting"; message = "Creating the Wi-Fi network…"
        worker.execute { try { create(gen) } catch (e: Exception) { fail(gen, e.message ?: "Couldn't create the network") } }
    }

    private fun end(newState: String, newMessage: String) {
        running = false
        generation++
        worker.execute { teardown() }
        state = newState; message = newMessage; clients = emptyList(); staKept = null
        if (newState == "off") refreshIdle()
    }

    private fun fail(gen: Int, why: String) {
        if (gen != generation) return
        teardown()
        state = "error"; message = why
        Jobs.line("Wi-Fi repeater: $why")
        // Try again: Wi-Fi Direct is often busy for a moment, or Wi-Fi was off.
        val delay = retryDelaySec
        retryDelaySec = (retryDelaySec * 2).coerceAtMost(60)
        worker.schedule({ if (running && gen == generation && cfg.repeaterEnabled) { try { create(gen) } catch (e: Exception) { fail(gen, e.message ?: why) } } }, delay, TimeUnit.SECONDS)
    }

    // --- the group ------------------------------------------------------------------------------------------------------

    private fun <T> await(timeoutSec: Long = 15, block: ((T?) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        block { result = it; latch.countDown() }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return result
    }

    @SuppressLint("MissingPermission")
    private fun groupInfo(ch: WifiP2pManager.Channel): WifiP2pGroup? =
        await<WifiP2pGroup> { done -> manager!!.requestGroupInfo(ch) { done(it) } }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "This phone doesn't support Wi-Fi Direct."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Close other apps that use it (screen casting, file sharing) and Homedroid will try again."
        else -> "Android couldn't create the network. Make sure Wi-Fi is on."
    }

    @SuppressLint("MissingPermission")
    private fun create(gen: Int) {
        if (gen != generation) return
        val m = manager ?: throw IllegalStateException("No Wi-Fi Direct")
        val ch = channel ?: m.initialize(app, Looper.getMainLooper(), null).also { channel = it }
            ?: throw IllegalStateException("Wi-Fi Direct isn't available right now")
        val wifiBefore = wifiOnline()
        // A group left over from before (an earlier run, or the system's own) has to go first.
        if (groupInfo(ch) != null) {
            await<Boolean> { done -> m.removeGroup(ch, listener({ done(true) }, { done(false) })) }
            Thread.sleep(500)
        }
        val band = RepeaterCore.bandCode(cfg.repeaterBand) ?: 0
        val config = WifiP2pConfig.Builder()
            .setNetworkName(cfg.repeaterName)
            .setPassphrase(cfg.repeaterPassphrase)
            .setGroupOperatingBand(when (band) { 1 -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ; 2 -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ; else -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO })
            .enablePersistentMode(false)
            .build()
        var failure: Int? = null
        val ok = await<Boolean> { done -> m.createGroup(ch, config, listener({ done(true) }, { failure = it; done(false) })) }
        if (ok != true) throw IllegalStateException(reason(failure ?: WifiP2pManager.ERROR))
        var group: WifiP2pGroup? = null
        for (i in 0 until 20) {
            group = groupInfo(ch)?.takeIf { it.isGroupOwner }
            if (group != null && addressOf(group) != null) break
            Thread.sleep(500)
        }
        group ?: throw IllegalStateException("The network was created but didn't come up. Try again.")
        if (gen != generation) return
        val addr = addressOf(group) ?: InetAddress.getByName(RepeaterCore.DEFAULT_ADDRESS)
        val own = ownAddresses()
        val server = ProxyServer(addr, pac = { RepeaterCore.pac(addr.hostAddress.orEmpty()) },
            policy = { RepeaterCore.allowed(it, cfg.repeaterAllowLan, own) })
        server.start()
        proxy = server
        address = addr.hostAddress.orEmpty(); iface = group.`interface`.orEmpty()
        clients = clientsOf(group)
        state = "running"; message = ""
        retryDelaySec = 3
        Jobs.line("Wi-Fi repeater: ${group.networkName} on $address (proxy $address:${RepeaterCore.HTTP_PORT})")
        worker.schedule({ if (gen == generation) staKept = if (wifiBefore) wifiOnline() else null }, 5, TimeUnit.SECONDS)
        worker.schedule({ watch(gen) }, 5, TimeUnit.SECONDS)
    }

    /** Keeps the client list fresh and notices when Android has torn the group down. */
    @SuppressLint("MissingPermission")
    private fun watch(gen: Int) {
        if (gen != generation || !running) return
        try {
            val ch = channel
            val group = ch?.let { groupInfo(it) }?.takeIf { it.isGroupOwner }
            if (group == null) { fail(gen, "Android closed the Wi-Fi network (Wi-Fi was turned off, or another app took Wi-Fi Direct)."); return }
            clients = clientsOf(group)
        } catch (_: Exception) { }
        worker.schedule({ watch(gen) }, 5, TimeUnit.SECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        proxy?.stop(); proxy = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            try { await<Boolean>(5) { done -> m.removeGroup(ch, listener({ done(true) }, { done(false) })) } } catch (_: Exception) { }
            try { ch.close() } catch (_: Exception) { }
        }
        channel = null
    }

    private fun listener(ok: () -> Unit, bad: (Int) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = ok()
        override fun onFailure(reason: Int) = bad(reason)
    }

    private fun clientsOf(g: WifiP2pGroup) = g.clientList.map { it.deviceName.orEmpty().ifEmpty { "Device" } to it.deviceAddress.orEmpty() }

    private fun addressOf(g: WifiP2pGroup): InetAddress? = try {
        NetworkInterface.getByName(g.`interface`)?.inetAddresses?.toList()?.filterIsInstance<Inet4Address>()?.firstOrNull()
    } catch (_: Exception) { null }

    private fun ownAddresses(): Set<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    } catch (_: Exception) { emptySet() }

    @Suppress("DEPRECATION")
    private fun wifiOnline(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        }
    }

    // --- the API (/api/repeater) ----------------------------------------------------------------------------------------------

    private fun permissionLabel() = if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"

    /** The status page's data. */
    fun json(): JSONObject {
        val ssid = cfg.repeaterName
        val pass = cfg.repeaterPassphrase
        val addr = address
        val p = proxy
        val concurrent = JSONObject().put("stationKept", staKept ?: JSONObject.NULL).put("note", when (staKept) {
            true -> "The phone stayed connected to its own Wi-Fi while hosting the network."
            false -> "The phone's own Wi-Fi dropped when the network started: this phone can't do both at once, so it has no internet source other than mobile data."
            null -> "Whether the phone can stay on its Wi-Fi while hosting is checked a few seconds after the network starts."
        })
        return JSONObject()
            .put("enabled", cfg.repeaterEnabled)
            .put("state", state).put("message", message)
            .put("supported", supported)
            .put("permission", JSONObject().put("granted", hasPermission()).put("name", if (Build.VERSION.SDK_INT >= 33) "NEARBY_WIFI_DEVICES" else "ACCESS_FINE_LOCATION").put("label", permissionLabel()))
            .put("network", JSONObject().put("name", ssid).put("passphrase", pass).put("band", cfg.repeaterBand)
                .put("interface", iface).put("address", addr))
            .put("wifiQr", RepeaterCore.wifiQr(ssid, pass))
            .put("clients", JSONObject().put("count", clients.size).put("devices", JSONArray(clients.map { JSONObject().put("name", it.first).put("mac", it.second) })))
            .put("traffic", JSONObject().put("bytesUp", p?.bytesUp?.get() ?: 0L).put("bytesDown", p?.bytesDown?.get() ?: 0L)
                .put("connections", p?.connections?.get() ?: 0).put("totalConnections", p?.totalConnections?.get() ?: 0L))
            .put("proxy", JSONObject().put("http", "$addr:${RepeaterCore.HTTP_PORT}").put("socks", "$addr:${RepeaterCore.SOCKS_PORT}")
                .put("pac", RepeaterCore.pacUrl(addr)).put("allowLan", cfg.repeaterAllowLan))
            .put("concurrent", concurrent)
            .put("instructions", RepeaterCore.instructions(addr, ssid = ssid, passphrase = pass))
            .put("limits", JSONArray(listOf(
                "Only apps that follow the device's proxy settings are carried. Many games, VoIP and streaming apps ignore them.",
                "TCP goes through the HTTP proxy or SOCKS5. UDP (some games, calls, QUIC) works only in apps that speak SOCKS5 UDP.",
                "Speed is limited to what Wi-Fi Direct and the phone's single radio can share with its own Wi-Fi; expect a fraction of the line speed.",
                "The phone's radio and battery work harder: keep it charging.",
            )))
    }

    fun handle(r: Request, seg: List<String>): Response {
        if (seg.isNotEmpty()) return Response.error(404, "No such repeater endpoint")
        return when (r.method) {
            "GET" -> Response.json(json())
            "POST" -> {
                val b = r.json()
                var error: String? = null
                if (b.has("name") || b.has("passphrase") || b.has("band") || b.has("allowLan") || b.optBoolean("regenerate")) {
                    val pass = if (b.optBoolean("regenerate")) RepeaterCore.newPassphrase() else b.optString("passphrase").takeIf { b.has("passphrase") && it.isNotEmpty() }
                    if (pass != null && !RepeaterCore.validPassphrase(pass)) return Response.error(400, "The passphrase needs 8 to 63 letters, digits or symbols")
                    val band = b.optString("band").takeIf { b.has("band") }
                    if (band != null && RepeaterCore.bandCode(band) == null) return Response.error(400, "Band must be auto, 2.4 or 5")
                    configure(b.optString("name").takeIf { b.has("name") }, pass, band, if (b.has("allowLan")) b.optBoolean("allowLan") else null)
                }
                if (b.has("enabled")) error = setEnabled(b.optBoolean("enabled"))
                if (error != null) Response.json(json().put("error", error), 409) else Response.json(json())
            }
            else -> Response.error(405, "GET or POST only")
        }
    }

    companion object {
        // Holds the application context only.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: WifiRepeater? = null

        fun get(ctx: Context): WifiRepeater = instance ?: synchronized(this) {
            instance ?: WifiRepeater(ctx.applicationContext).also { instance = it }
        }

        /** The runtime permission creating the group needs: nearby devices on Android 13+, location before. */
        fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    }
}
