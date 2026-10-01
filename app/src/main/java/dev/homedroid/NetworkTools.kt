package dev.homedroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.system.Os
import android.system.OsConstants.SIGKILL
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor

/** Short-lived diagnostics; only the page's leased iperf server keeps a listener open. */
class NetworkTools(private val ctx: Context, private val paths: Paths) {
    private val prefs = ctx.getSharedPreferences("network", Context.MODE_PRIVATE)
    private val alpine = Alpine(ctx, paths)
    private val discovery = NetworkDiscovery(ctx)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val resolver = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { r -> Thread(r, "network-resolve").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    @Volatile private var serverUntil = 0L
    @Volatile private var installing = false
    @Volatile private var closed = false
    val enabled get() = prefs.getBoolean("enabled", false)

    fun module() = JSONObject().put("id", "network").put("name", "Network tools")
        .put("description", "Phone network, device discovery, DNS, TCP scans and speed tests.")
        .put("port", 0).put("kind", "core").put("installed", true).put("enabled", enabled)
        .put("state", if (installing) "installing" else if (enabled) "ready" else "stopped")

    @Synchronized
    fun enable(on: Boolean): Response {
        if (installing) return Response.error(409, "Network packages are still installing")
        if (!on) {
            prefs.edit().putBoolean("enabled", false).apply()
            jobs.values.forEach { it.cancel() }
            discovery.stop()
            return Response.ok()
        }
        if (enabled) return Response.ok()
        if (Jobs.running) return Response.error(409, "Another install is running")
        installing = true
        Jobs.begin("Install Network tools")
        Thread({
            var error: String? = null
            try {
                alpine.ensure(Jobs::line)
                check(alpine.run("apk add --no-cache nmap iperf3 bind-tools whois traceroute iputils-ping curl", Jobs::line) == 0) { "Network package installation failed" }
                if (!closed) prefs.edit().putBoolean("enabled", true).apply()
            } catch (e: Exception) { error = e.message ?: "Network package installation failed" }
            finally { installing = false; Jobs.finish(error) }
        }, "network-install").start()
        return Response.ok()
    }

    @Synchronized fun stop() {
        closed = true
        jobs.values.forEach { it.cancel() }
        discovery.close()
        timer.shutdownNow()
        resolver.shutdownNow()
    }

    fun handle(r: Request, seg: List<String>): Response {
        if (r.method == "GET" && seg == listOf("enabled")) return Response.json(JSONObject().put("enabled", enabled))
        if (r.method == "GET" && seg.isEmpty()) return Response.json(state())
        if (!enabled || closed) return Response.error(409, "Turn on Network tools in Modules first")
        return try {
            when {
                r.method == "POST" && seg == listOf("discover") -> {
                    discovery.scan()
                    Response.ok()
                }
                r.method == "POST" && seg == listOf("discover", "cancel") -> { discovery.stop(); Response.ok() }
                r.method == "POST" && seg == listOf("server") -> server(r.json().optBoolean("enabled"))
                r.method == "POST" && seg.size == 2 && seg[0] == "run" -> {
                    val kind = seg[1]
                    val body = r.json()
                    val argv = NetworkCore.command(kind, body)
                    start(kind, if (kind == "scan") 180 else 60) { job ->
                        if (kind == "tcp") tcp(job, body) else {
                            alpine.refresh()
                            val rc = job.run(argv)
                            if (kind in setOf("ping", "traceroute") && NetworkCore.pingDenied(job.text())) {
                                job.add("This phone doesn't allow ping from apps; use the TCP check instead.")
                                job.fallback = true
                            }
                            check(rc == 0 || job.cancelled) { "Tool exited with code $rc" }
                            when (kind) {
                                "scan" -> job.result = jsonArray(NetworkCore.nmap(job.text()).map { (ip, ports) -> JSONObject().put("ip", ip).put("ports", jsonArray(ports)) })
                                "dns" -> job.result = jsonArray(NetworkCore.dig(job.text()))
                                "iperf" -> job.result = NetworkCore.iperf(job.text())
                                "http" -> job.result = NetworkCore.curl(job.text())
                                "speed" -> {
                                    val down = NetworkCore.curl(job.text())
                                    check(down.optInt("Status") in 200..299) { "Cloudflare download returned HTTP ${down.optInt("Status")}" }
                                    job.add("Uploading 2 MB to Cloudflare…")
                                    val upload = job.run(NetworkCore.speedUpload(), upload = true)
                                    check(upload == 0 || job.cancelled) { "Upload failed with code $upload" }
                                    val up = NetworkCore.curl(job.text())
                                    check(up.optInt("Status") in 200..299) { "Cloudflare upload returned HTTP ${up.optInt("Status")}" }
                                    job.result = JSONObject().put("downloadMbps", down.optDouble("Download", 0.0) * 8 / 1e6)
                                        .put("uploadMbps", up.optDouble("Upload", 0.0) * 8 / 1e6)
                                        .put("latencyMs", down.optDouble("Connect", 0.0) * 1000)
                                    job.add("Download ${down.optDouble("Download", 0.0) * 8 / 1e6} Mbps; upload ${up.optDouble("Upload", 0.0) * 8 / 1e6} Mbps; connection latency ${down.optDouble("Connect", 0.0) * 1000} ms")
                                }
                            }
                        }
                    }
                }
                r.method == "POST" && seg.size == 2 && seg[0] == "cancel" -> {
                    jobs[seg[1]]?.cancel()
                    Response.ok()
                }
                else -> Response.error(404, "No such network endpoint")
            }
        } catch (e: IllegalArgumentException) { Response.error(400, e.message ?: "Invalid network input") }
        catch (e: IllegalStateException) { Response.error(409, e.message ?: "Network tool is busy") }
    }

    private fun state(): JSONObject {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let(cm::getLinkProperties)
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().map { i ->
                JSONObject().put("name", i.name).put("addresses", jsonArray(i.interfaceAddresses.map { "${it.address.hostAddress}/${it.networkPrefixLength}" }))
            }
        }.getOrDefault(emptyList())
        val ssid = runCatching {
            (cm.activeNetwork?.let(cm::getNetworkCapabilities)?.transportInfo as? WifiInfo)?.ssid
                ?.takeIf { it != "<unknown ssid>" }?.trim('"')
        }.getOrNull()
        return JSONObject().put("enabled", enabled).put("interfaces", jsonArray(interfaces))
            .put("ssid", ssid ?: JSONObject.NULL).put("gateway", jsonArray(lp?.routes.orEmpty().filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }))
            .put("dns", jsonArray(lp?.dnsServers.orEmpty().mapNotNull { it.hostAddress }))
            .put("metrics", Metrics.sample(ctx)).put("discovery", discovery.json())
            .put("jobs", jsonArray(jobs.values.sortedBy { it.kind }.map { it.json() }))
    }

    @Synchronized
    private fun start(kind: String, seconds: Int, block: (Job) -> Unit): Response {
        if (!enabled || closed) return Response.error(409, "Network tools are off")
        if (jobs[kind]?.running == true) return Response.error(409, "This tool is already running")
        val job = Job(kind)
        jobs[kind] = job
        val timeout = timer.schedule({ job.cancel("Timed out after $seconds seconds") }, seconds.toLong(), TimeUnit.SECONDS)
        Thread({
            try { block(job) }
            catch (e: Exception) {
                if (!job.cancelled) { job.error = e.message ?: "Tool failed"; job.add(job.error!!) }
            }
            finally { timeout.cancel(false); job.kill(); job.running = false }
        }, "network-$kind").apply { isDaemon = true; start() }
        return Response.ok()
    }

    @Synchronized
    private fun server(on: Boolean): Response {
        if (!on) { jobs["server"]?.cancel(); return Response.ok() }
        serverUntil = System.currentTimeMillis() + 15_000
        if (jobs["server"]?.running == true) return Response.ok()
        return start("server", 3600) { job ->
            val lease = timer.scheduleAtFixedRate({
                if (System.currentTimeMillis() >= serverUntil) job.cancel("Server stopped: page lease expired")
            }, 1, 1, TimeUnit.SECONDS)
            try {
                val rc = job.run(listOf("iperf3", "-s", "-p", "5201"))
                check(rc == 0 || job.cancelled) { "iperf3 server exited with code $rc" }
            }
            finally { lease.cancel(false) }
        }
    }

    private fun tcp(job: Job, b: JSONObject) {
        val host = NetworkCore.host(b.optString("host").trim())
        val port = NetworkCore.port(b.optString("port", "443"))
        val lookup = resolver.submit<InetAddress> { InetAddress.getByName(host) }
        val address = try { lookup.get(5, TimeUnit.SECONDS) } finally { lookup.cancel(true) }
        repeat(5) {
            if (job.cancelled) return
            val at = System.nanoTime()
            try {
                Socket().use { s ->
                    job.socket = s
                    if (job.cancelled) return
                    s.connect(InetSocketAddress(address, port), 2000)
                    job.add("$host:$port connected in ${(System.nanoTime() - at) / 1e6} ms")
                }
            } catch (e: Exception) { if (!job.cancelled) job.add("$host:$port not found: ${e.message}") }
            finally { job.socket = null }
        }
    }

    private inner class Job(val kind: String) {
        @Volatile var running = true
        @Volatile var cancelled = false
        @Volatile var fallback = false
        @Volatile var result: Any = JSONObject.NULL
        @Volatile var error: String? = null
        @Volatile var socket: Socket? = null
        private var process: Process? = null
        private var pgid = 0
        private val output = StringBuilder()
        private var capped = false

        @Synchronized fun text() = output.toString()
        @Synchronized fun add(s: String) {
            if (capped) return
            val remaining = 128 * 1024 - output.length
            output.append(s.take(remaining.coerceAtLeast(0))).append('\n')
            if (output.length >= 128 * 1024) { output.append("\nOutput limit reached\n"); capped = true }
        }

        fun cancel(reason: String = "Cancelled") {
            cancelled = true
            add(reason)
            socket?.let { runCatching { it.close() } }
            kill()
        }

        @Synchronized fun kill() {
            if (pgid > 0) runCatching { Os.kill(-pgid, SIGKILL) }
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }

        fun run(argv: List<String>, upload: Boolean = false): Int {
            synchronized(this) {
                if (cancelled) return -1
                val pb = ProcessBuilder(listOf("/system/bin/setsid") + alpine.command(argv)).directory(paths.home).redirectErrorStream(true)
                pb.environment().putAll(paths.env())
                pb.environment().putAll(alpine.processEnv())
                process = pb.start()
                // setsid execs in place: the child's pid is the group to signal, as in Supervisor.
                pgid = Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toInt() ?: 0
                check(pgid > 0) { "Cannot determine process group" }
            }
            val p = process!!
            val writer = if (upload) Thread({
                runCatching { p.outputStream.use { out -> val bytes = ByteArray(8000); repeat(250) { if (!cancelled) out.write(bytes) } } }
            }, "network-upload").apply { isDaemon = true; start() } else null
            if (!upload) p.outputStream.close()
            p.inputStream.reader().use { reader ->
                val chars = CharArray(2048)
                while (true) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    append(String(chars, 0, n))
                }
            }
            val rc = p.waitFor()
            writer?.join(1000)
            kill()
            synchronized(this) { process = null; pgid = 0 }
            return rc
        }

        @Synchronized private fun append(s: String) {
            if (capped) return
            output.append(s.take((128 * 1024 - output.length).coerceAtLeast(0)))
            if (output.length >= 128 * 1024) { output.append("\nOutput limit reached\n"); capped = true }
        }

        fun json() = JSONObject().put("kind", kind).put("running", running).put("cancelled", cancelled)
            .put("output", text()).put("result", result).put("error", error ?: JSONObject.NULL).put("tcpFallback", fallback)
    }
}
