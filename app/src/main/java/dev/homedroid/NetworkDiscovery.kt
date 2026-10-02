package dev.homedroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A bounded, on-demand survey. Silence never establishes that a device is offline. */
class NetworkDiscovery(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val devices = linkedMapOf<String, Found>()
    private var current: Scan? = null
    private val reverse = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { r -> Thread(r, "network-reverse").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private data class Found(val ip: String, var name: String = "", val sources: MutableSet<String> = linkedSetOf(),
        val ports: MutableSet<Int> = sortedSetOf(), var at: Long = 0)

    @Synchronized fun json() = JSONObject().put("running", current?.active == true)
        .put("devices", jsonArray(devices.values.map { d -> JSONObject().put("ip", d.ip).put("name", d.name)
            .put("sources", jsonArray(d.sources)).put("ports", jsonArray(d.ports)).put("lastSeen", d.at)
            .put("found", d.at >= (current?.started ?: 0)) }))

    @Synchronized fun scan() {
        check(current?.active != true) { "Discovery is already running" }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: cm.activeNetwork
        val lp = network?.let(cm::getLinkProperties)
        val address = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        require(address != null) { "No local IPv4 network is available" }
        // On a wider LAN, survey only the phone's /24; never follow routes to another subnet.
        val hosts = NetworkCore.subnet("${address!!.address.hostAddress}/${address.prefixLength.coerceAtLeast(24)}")
        val scan = Scan(network)
        current = scan
        main.post { scan.browse() }
        Thread({
            try {
                scan.ready.await(2, TimeUnit.SECONDS)
                scan.ssdp()
                val futures = hosts.map { ip -> scan.pool.submit {
                    for (port in NetworkCore.discoveryPorts) {
                        if (!scan.active || System.currentTimeMillis() - scan.started > 45_000) break
                        try {
                            Socket().use { s ->
                                scan.sockets.add(s)
                                network?.bindSocket(s)
                                try { s.connect(InetSocketAddress(ip, port), 250); found(scan, ip, "", "TCP", port) }
                                finally { scan.sockets.remove(s) }
                            }
                        } catch (_: Exception) {}
                    }
                } }
                for (f in futures) {
                    if (!scan.active) break
                    val left = 45_000 - (System.currentTimeMillis() - scan.started)
                    if (left <= 0) break
                    runCatching { f.get(left, TimeUnit.MILLISECONDS) }
                }
                val left = 10_000 - (System.currentTimeMillis() - scan.started)
                if (scan.active && left > 0) Thread.sleep(left)
            } finally { scan.finish() }
        }, "network-discovery").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() { current?.finish() }
    fun close() { stop(); reverse.shutdownNow() }

    @Synchronized private fun found(scan: Scan, ip: String, name: String, source: String, port: Int = 0) {
        if (!scan.active || current !== scan || devices.size >= 512 && ip !in devices) return
        val fresh = ip !in devices
        val d = devices.getOrPut(ip) { Found(ip) }
        if (d.at < scan.started) { d.sources.clear(); d.ports.clear() }
        if (name.isNotBlank()) d.name = name.take(128)
        d.sources.add(source)
        if (port in 1..65535) d.ports.add(port)
        d.at = System.currentTimeMillis()
        if (fresh) runCatching { reverse.execute {
            val nameFromDns = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrDefault(ip)
            synchronized(this) { if (d.name.isEmpty() && nameFromDns != ip) d.name = nameFromDns.take(128) }
        } }
    }

    private inner class Scan(private val network: Network?) {
        val ready = java.util.concurrent.CountDownLatch(1)
        val started = System.currentTimeMillis()
        @Volatile var active = true
        val pool = Executors.newFixedThreadPool(16) { r -> Thread(r, "network-probe").apply { isDaemon = true } }
        val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        private var lock: WifiManager.MulticastLock? = null
        @Volatile private var udp: DatagramSocket? = null
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false

        fun browse() {
            if (!active) return
            lock = runCatching {
                ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("homedroid:network")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            ready.countDown()
            for (type in TYPES) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) { if (!active) runCatching { nsd.stopServiceDiscovery(this) } }
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onStartDiscoveryFailed(type: String, code: Int) {}
                    override fun onStopDiscoveryFailed(type: String, code: Int) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) { main.post {
                        if (active && queue.size < 128) { queue.addLast(info); resolveNext() }
                    } }
                }
                runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener); listeners.add(listener) }
            }
        }

        @Suppress("DEPRECATION")
        private fun resolveNext() {
            if (!active || resolving) return
            val info = queue.removeFirstOrNull() ?: return
            resolving = true
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
                    override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                        info.host?.hostAddress?.let { found(this@Scan, it, info.serviceName, "mDNS ${info.serviceType}") }
                        resolving = false; resolveNext()
                    } }
                })
            } catch (_: Exception) { resolving = false; resolveNext() }
        }

        fun ssdp() {
            Thread({
                try {
                    DatagramSocket().use { s ->
                        udp = s
                        if (!active) return@Thread
                        network?.bindSocket(s)
                        s.soTimeout = 500
                        val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
                        s.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                        val end = System.currentTimeMillis() + 5000
                        while (active && System.currentTimeMillis() < end) {
                            val packet = DatagramPacket(ByteArray(8192), 8192)
                            try { s.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                            val headers = NetworkCore.ssdp(String(packet.data, 0, packet.length, Charsets.UTF_8))
                            if (headers.isNotEmpty()) found(this, packet.address.hostAddress ?: continue, headers["server"].orEmpty(), "SSDP")
                        }
                    }
                } catch (_: Exception) {}
            }, "network-ssdp").apply { isDaemon = true; start() }
        }

        fun finish() {
            active = false
            udp?.close()
            sockets.forEach { runCatching { it.close() } }
            pool.shutdownNow()
            main.post {
                listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
                listeners.clear(); queue.clear()
                lock?.let { runCatching { if (it.isHeld) it.release() } }; lock = null
            }
        }
    }

    companion object {
        private val TYPES = listOf("_http._tcp", "_ipp._tcp", "_googlecast._tcp", "_airplay._tcp", "_smb._tcp", "_ssh._tcp",
            "_workstation._tcp", "_hap._tcp", "_spotify-connect._tcp", "_homedroid._tcp")
    }
}
