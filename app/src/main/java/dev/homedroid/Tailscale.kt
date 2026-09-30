package dev.homedroid

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tailscale in userspace mode: joins the phone to a tailnet without root or a VPN slot.
 *
 * tailscaled forwards connections to the phone's Tailscale address to 127.0.0.1 on the same
 * port, so every module is reachable at 100.x.y.z:<port> (or <hostname>:<port> with MagicDNS).
 * Settings are applied with `tailscale up --reset`, so the dashboard is the source of truth;
 * status is read straight from tailscaled's local API over its Unix socket.
 */
class Tailscale(private val p: Paths, private val c: Config) {
    val dir = File(p.root, "tailscale")
    private val socket = File(dir, "tailscaled.sock")
    private val authKeyFile = File(dir, "authkey")

    fun spec(): Spec {
        dir.mkdirs()
        val proxy = if (c.tailscaleProxy) listOf(
            "--socks5-server=127.0.0.1:$PROXY_PORT", "--outbound-http-proxy-listen=127.0.0.1:$PROXY_PORT",
        ) else emptyList()
        return Spec(
            "tailscaled",
            listOf(
                p.exe("tailscaled"), "--tun=userspace-networking", "--statedir=${dir.path}",
                "--socket=${socket.path}", "--port=0",
            ) + proxy,
            mapOf("TS_LOGS_DIR" to File(dir, "logs").path, "XDG_CACHE_HOME" to File(dir, "cache").path),
        )
    }

    /** Runs the CLI (tailscaled answers to the name "tailscale"); returns exit code and output. */
    private fun cli(args: List<String>, timeoutS: Long = 30): Pair<Int, String> {
        val proc = ProcessBuilder(listOf(File(p.bin, "tailscale").path, "--socket=${socket.path}") + args)
            .redirectErrorStream(true).apply { environment().putAll(p.env()) }.start()
        val out = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }
            .apply { isDaemon = true; start() }
        val done = proc.waitFor(timeoutS, TimeUnit.SECONDS)
        if (!done) proc.destroy()
        reader.join(1000)
        return (if (done) proc.exitValue() else -1) to synchronized(out) { out.toString().trim() }
    }

    /**
     * Applies the saved settings. Without an auth key this starts an interactive login: the
     * URL shows up in [status] for the user to open. Returns an error message, or null.
     */
    fun apply(): String? = synchronized(LOCK) {
        if (!waitForSocket()) return remember("tailscaled isn't running")
        val key = c.tailscaleAuthKey
        if (key.isNotEmpty()) {
            authKeyFile.writeText(key)
            authKeyFile.setReadable(false, false); authKeyFile.setReadable(true, true)
        } else authKeyFile.delete()
        val args = mutableListOf("up", "--reset", "--timeout=${if (key.isEmpty()) 5 else 45}s", "--hostname=${c.tailscaleHostname}")
        if (key.isNotEmpty()) args += "--auth-key=file:${authKeyFile.path}"
        if (c.tailscaleExitNode) args += "--advertise-exit-node"
        c.tailscaleRoutes.takeIf { it.isNotEmpty() }?.let { args += "--advertise-routes=$it" }
        c.tailscaleTags.takeIf { it.isNotEmpty() }?.let { args += "--advertise-tags=$it" }
        val (rc, out) = cli(args, 60)
        // A timeout while waiting for the user to log in is expected.
        val loginPending = "To authenticate" in out || "timeout waiting" in out
        if (rc != 0 && !loginPending) return remember(out.lines().lastOrNull { it.isNotBlank() } ?: "tailscale up failed ($rc)")
        return remember(applyServe())
    }

    /** Publishes the dashboard over HTTPS on the tailnet (Serve) or the internet (Funnel). */
    private fun applyServe(): String? {
        if (state().optString("BackendState") != "Running") return null
        val port = c.dashboardPort.toString()
        val (rc, out) = when (c.tailscaleServe) {
            "tailnet" -> { cli(listOf("funnel", "reset"), 15); cli(listOf("serve", "--bg", "--yes", port), 20) }
            "funnel" -> cli(listOf("funnel", "--bg", "--yes", port), 20)
            else -> { cli(listOf("serve", "reset"), 15); return null }
        }
        if (rc == 0) return null
        // The CLI asks the admin to turn on HTTPS or Funnel for the tailnet and prints where.
        return out.lines().firstOrNull { "https://login.tailscale.com" in it }?.trim()?.let { "Needs a tailnet setting: $it" }
            ?: out.lines().lastOrNull { it.isNotBlank() } ?: "tailscale ${c.tailscaleServe} failed ($rc)"
    }

    fun logout(): String? = synchronized(LOCK) {
        if (!waitForSocket(2)) return "tailscaled isn't running"
        val (rc, out) = cli(listOf("logout"), 20)
        return remember(if (rc == 0) null else out.ifEmpty { "logout failed ($rc)" })
    }

    private fun waitForSocket(seconds: Int = 20): Boolean {
        repeat(seconds * 4) {
            if (socket.exists() && get("/localapi/v0/status?peers=false") != null) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun state() = get("/localapi/v0/status?peers=false")?.let(::JSONObject) ?: JSONObject()

    /** What the dashboard shows: login state, addresses, peers and the Serve address. */
    fun status(): JSONObject {
        val o = JSONObject().put("error", lastError ?: JSONObject.NULL)
        val raw = get("/localapi/v0/status") ?: return o.put("state", "Stopped")
        val s = JSONObject(raw)
        val self = s.optJSONObject("Self")
        val dns = self?.optString("DNSName").orEmpty().trimEnd('.')
        val peers = s.optJSONObject("Peer")
        val online = peers?.keys()?.asSequence()?.count { peers.getJSONObject(it).optBoolean("Online") } ?: 0
        o.put("state", s.optString("BackendState"))
            .put("authUrl", s.optString("AuthURL"))
            .put("ips", s.optJSONArray("TailscaleIPs") ?: org.json.JSONArray())
            .put("dnsName", dns)
            .put("tailnet", s.optJSONObject("CurrentTailnet")?.optString("Name").orEmpty())
            .put("peers", peers?.length() ?: 0)
            .put("peersOnline", online)
            .put("version", s.optString("Version"))
        if (dns.isNotEmpty() && c.tailscaleServe != "off" && s.optString("BackendState") == "Running") o.put("serveUrl", "https://$dns")
        s.optJSONArray("Health")?.let { o.put("health", it) }
        return o
    }

    /** GET on the local API over the Unix socket; null if tailscaled isn't answering. */
    private fun get(path: String): String? = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = 5000
            s.outputStream.write("GET $path HTTP/1.0\r\nHost: local-tailscaled.sock\r\n\r\n".toByteArray())
            val text = s.inputStream.readBytes().toString(Charsets.UTF_8)
            val status = text.substringBefore("\r\n").split(' ').getOrNull(1)
            if (status == "200") text.substringAfter("\r\n\r\n") else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PROXY_PORT = 1055
        private val LOCK = Any()

        /** The last error from applying settings, until the next successful apply. */
        @Volatile
        private var lastError: String? = null

        private fun remember(error: String?) = error.also { lastError = it }
    }
}
