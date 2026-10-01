package dev.homedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Several phones as one: each phone keeps running its own modules, and any phone's dashboard
 * can manage all of them. Phones talk over mutual TLS on [ClusterState.PORT], each trusting
 * the others' certificates learned during pairing (see [ClusterState]).
 *
 * The dashboard reaches another phone through /api/nodes/<id>/<path>: the browser logs in to
 * the phone it opened, and that phone forwards the request to /api/<path> on the other one.
 * A paired phone gets the same control as a logged-in browser, so every phone's dashboard
 * password protects the whole cluster.
 */
class Cluster(context: Context, private val route: (Request, Peer) -> Response) {
    // The application context: [instance] outlives no service this way.
    private val ctx: Context = context.applicationContext
    private val dir = File(ctx.filesDir, "cluster")
    val identity = ClusterIdentity.load(dir)
    private val stateFile = File(dir, "state.json")
    val state = ClusterState(
        if (stateFile.exists()) stateFile.readText() else "{}", identity.fingerprint,
        persist = { s -> File(dir, "state.json.tmp").apply { writeText(s) }.renameTo(stateFile) },
    )
    private val http = Http(ClusterState.PORT, ClusterTls.server(identity), 24, ::handle)
    private val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "cluster").apply { isDaemon = true } }
    val discovery = Discovery(ctx, this)

    /** Pairing this phone started with another one, shown in the dashboard until dismissed. */
    @Volatile private var outgoing: JSONObject? = null

    val name: String get() = state.name.ifEmpty { Build.MODEL }

    fun start() {
        http.start()
        instance = this
        discovery.advertise()
    }

    fun stop() {
        if (instance === this) instance = null
        discovery.stop()
        http.stop()
        pool.shutdownNow()
    }

    private fun self() = JSONObject().put("id", state.id).put("name", name).put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("version", version()).put("protocol", ClusterState.PROTOCOL).put("port", ClusterState.PORT)

    private fun version() = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()

    /** This phone as other members should record it. */
    private fun selfMember() = self().put("fingerprint", identity.fingerprint).put("address", Device(ctx).ips.firstOrNull() ?: "")

    // --- the cluster port -----------------------------------------------------------------

    private fun handle(r: Request): Response {
        val fp = r.peer ?: return Response.error(403, "A certificate is required")
        when {
            r.method == "GET" && r.path == "/cluster/hello" -> return Response.json(self())
            r.method == "POST" && r.path == "/cluster/pair" -> return pairRequest(r, fp)
            r.method == "GET" && r.path == "/cluster/pair" -> return pairStatus(fp)
        }
        val peer = state.byFingerprint(fp) ?: return Response.error(403, "This phone isn't in the cluster")
        state.seen(peer, r.remote)
        return when {
            r.method == "POST" && r.path == "/cluster/members" -> {
                val added = state.addMembers(r.json().optJSONArray("members"))
                if (added.isNotEmpty()) Jobs.line("Cluster: ${peer.name} added ${added.joinToString { it.name }}")
                Response.ok()
            }
            r.method == "POST" && r.path == "/cluster/remove" -> {
                val id = r.json().optString("id")
                if (id == state.id) state.clear() else state.remove(id)
                Response.ok()
            }
            r.path.startsWith("/api/") -> {
                // Pairing and forwarding stay with the phone they happen on.
                val first = r.path.removePrefix("/api/").substringBefore('/')
                if (first in setOf("nodes", "cluster", "login", "logout")) Response.error(403, "Not available between phones")
                else route(r, peer)
            }
            else -> Response.error(404, "no such endpoint")
        }
    }

    private fun pairRequest(r: Request, fp: String): Response {
        val body = r.json()
        if (body.optInt("protocol") != ClusterState.PROTOCOL) {
            return Response.error(409, "Update both phones to the same Homedroid version first (this one runs ${version()})")
        }
        state.byFingerprint(fp)?.let { return Response.json(self().put("state", "approved")) }
        val peer = Peer.from(JSONObject().put("id", body.optString("id")).put("name", body.optString("name"))
            .put("model", body.optString("model")).put("fingerprint", fp).put("address", r.remote)
            .put("port", body.optInt("port", ClusterState.PORT)))
            ?: return Response.error(400, "Invalid pairing request")
        // Too many requests is a rate limit; anything else (pairing with itself) is a bad request.
        val code = state.request(peer, r.remote).getOrElse {
            return Response.error(if (it is IllegalStateException) 429 else 400, it.message ?: "Try again later")
        }
        notifyRequest(peer, code)
        return Response.json(self().put("state", "pending").put("code", code))
    }

    private fun pairStatus(fp: String): Response {
        val s = state.requestState(fp)
        val o = JSONObject().put("state", s)
        if (s == "approved") o.put("members", JSONArray().apply { state.peers().filter { it.fingerprint != fp }.forEach { put(it.toJson()) } })
        return Response.json(o)
    }

    // --- pairing, from the dashboard ----------------------------------------------------

    /** Asks the phone at [host] to join; the user then approves it there. */
    fun pair(host: String, port: Int): Response {
        if (!Peer.validHost(host) || port !in 1..65535) return Response.error(400, "Enter the other phone's address, like 192.168.1.20")
        val hello = self().toString().toByteArray()
        val (status, reply, fp) = try {
            PeerHttp.request(identity, host, port, null, "POST", "/cluster/pair", mapOf("Content-Type" to "application/json"),
                hello.size.toLong(), { it.write(hello) }).use { res ->
                Triple(res.status, try { JSONObject(res.text()) } catch (_: Exception) { JSONObject() }, res.fingerprint)
            }
        } catch (e: Exception) {
            return Response.error(502, "Couldn't reach Homedroid at $host:$port. Is its server running on the same network? (${e.message ?: e.javaClass.simpleName})")
        }
        if (status != 200) return Response.error(status, reply.optString("error", "The other phone refused"))
        if (fp == identity.fingerprint) return Response.error(400, "That's this phone")
        val o = JSONObject().put("address", host).put("port", port).put("id", reply.optString("id"))
            .put("name", reply.optString("name")).put("model", reply.optString("model"))
            // Computed here, not taken from the reply: only matches B's screen if nobody is in between.
            .put("code", ClusterState.code(identity.fingerprint, fp)).put("state", "waiting").put("started", System.currentTimeMillis())
        outgoing = o
        if (reply.optString("state") == "approved") finishPairing(o, fp, JSONArray()) else pool.execute { waitForApproval(o, host, port, fp) }
        return Response.json(o)
    }

    private fun waitForApproval(o: JSONObject, host: String, port: Int, fp: String) {
        val deadline = System.currentTimeMillis() + ClusterState.PAIR_TIMEOUT
        while (outgoing === o && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000)
                val (status, reply) = PeerHttp.json(identity, host, port, fp, "GET", "/cluster/pair")
                when (if (status == 200) reply.optString("state") else "failed") {
                    "approved" -> return finishPairing(o, fp, reply.optJSONArray("members"))
                    "rejected" -> { o.put("state", "rejected"); return }
                    "expired" -> { o.put("state", "expired"); return }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // Keep trying until the deadline; the other phone may be switching networks.
            }
        }
        if (outgoing === o) o.put("state", "expired")
    }

    private fun finishPairing(o: JSONObject, fp: String, members: JSONArray?) {
        val peer = Peer.from(JSONObject(o.toString()).put("fingerprint", fp))
        if (peer == null || !state.add(peer)) { o.put("state", "failed").put("error", "That phone's identity conflicts with another member"); return }
        state.addMembers(members)
        o.put("state", "approved")
        Jobs.line("Cluster: paired with ${peer.name}")
        announceMembers()
    }

    fun cancelPairing() { outgoing = null }

    /** Tells every member about every other member, so a new phone is known everywhere. */
    private fun announceMembers() {
        val members = JSONArray().put(selfMember())
        val peers = state.peers()
        peers.forEach { members.put(it.toJson()) }
        val body = JSONObject().put("members", members)
        for (p in peers) pool.execute { call(p) { PeerHttp.json(identity, p.address, p.port, p.fingerprint, "POST", "/cluster/members", body) } }
    }

    fun approve(id: String): Boolean {
        val p = state.approve(id) ?: return false
        cancelNotification(p.id)
        announceMembers()
        return true
    }

    fun reject(id: String): Boolean = state.reject(id).also { cancelNotification(id) }

    /** Removes [id] from the cluster on every phone, including that one if it's reachable. */
    fun remove(id: String): Boolean {
        val target = state.peer(id) ?: return false
        val body = JSONObject().put("id", id)
        for (p in state.peers()) pool.execute { call(p) { PeerHttp.json(identity, p.address, p.port, p.fingerprint, "POST", "/cluster/remove", body) } }
        state.remove(target.id)
        return true
    }

    /** Leaves the cluster: the others forget this phone, and it forgets them. */
    fun leave() {
        val body = JSONObject().put("id", state.id)
        val peers = state.peers()
        state.clear()
        for (p in peers) pool.execute { call(p) { PeerHttp.json(identity, p.address, p.port, p.fingerprint, "POST", "/cluster/remove", body) } }
    }

    /** A JSON call to member [p]'s API, made by this phone. */
    fun peerJson(p: Peer, method: String, path: String, body: JSONObject? = null, readTimeoutMs: Int = 30_000) =
        PeerHttp.json(identity, p.address, p.port, p.fingerprint, method, path, body, readTimeoutMs = readTimeoutMs)

    /** A streamed GET from member [p], such as an app's data; close it when done. */
    fun peerStream(p: Peer, path: String): PeerResponse =
        PeerHttp.request(identity, p.address, p.port, p.fingerprint, "GET", path, readTimeoutMs = 120_000)

    /** Runs [block] against [p]; on a connection error, looks the phone up again on the network. */
    private fun <T> call(p: Peer, block: () -> T): T? = try {
        block()
    } catch (e: IOException) {
        discovery.discover()
        null
    }

    // --- the dashboard's view -----------------------------------------------------------

    @Volatile private var cached: Pair<Long, JSONArray>? = null

    /** Every member with its live status (asked in parallel, cached for two seconds). */
    fun membersJson(): JSONArray {
        cached?.let { (t, v) -> if (System.currentTimeMillis() - t < 2000) return v }
        val futures = state.peers().map { p ->
            p to pool.submit<JSONObject> {
                try {
                    val (status, s) = PeerHttp.json(identity, p.address, p.port, p.fingerprint, "GET", "/api/status", timeoutMs = 3000, readTimeoutMs = 6000)
                    if (status != 200) throw IOException(s.optString("error", "status $status"))
                    state.seen(p, p.address, s.optJSONObject("node")?.put("model", s.optString("model")))
                    JSONObject().put("online", true).put("status", s)
                } catch (e: Exception) {
                    discovery.discover()
                    JSONObject().put("online", false).put("error", e.message ?: e.javaClass.simpleName)
                }
            }
        }
        val out = JSONArray()
        for ((p, f) in futures) {
            val o = try { f.get(8, TimeUnit.SECONDS) } catch (_: Exception) { JSONObject().put("online", false).put("error", "No answer") }
            out.put(o.put("id", p.id).put("name", p.name).put("model", p.model).put("address", p.address).put("port", p.port)
                .put("fingerprint", p.fingerprint.take(16)))
        }
        cached = System.currentTimeMillis() to out
        return out
    }

    fun json(): JSONObject = JSONObject()
        .put("self", self().put("fingerprint", identity.fingerprint.take(16)).put("ips", jsonArray(Device(ctx).ips)))
        .put("members", membersJson())
        .put("pending", JSONArray().apply {
            val now = System.currentTimeMillis()
            state.pending().forEach { p ->
                put(p.peer.toJson().apply { remove("fingerprint") }.put("code", p.code)
                    .put("expiresIn", (ClusterState.PAIR_TIMEOUT - (now - p.created)) / 1000))
            }
        })
        .put("outgoing", outgoing ?: JSONObject.NULL)
        .put("discovering", discovery.running)
        .put("discovered", JSONArray().apply {
            discovery.found.values.filter { it.id != state.id }.sortedBy { it.name }.forEach { f ->
                put(JSONObject().put("id", f.id).put("name", f.name).put("address", f.address).put("port", f.port).put("member", state.peer(f.id) != null))
            }
        })

    fun onDiscovered(id: String, address: String, port: Int) {
        val p = state.peer(id) ?: return
        if (p.address != address || p.port != port) {
            p.port = port
            state.seen(p, address)
        }
    }

    // --- forwarding -----------------------------------------------------------------------

    /**
     * Forwards the browser's request to phone [id] as /api/[rest], streaming both ways, so file
     * downloads with ranges, uploads, live frames and the terminal work like on this phone.
     */
    fun proxy(r: Request, id: String, rest: List<String>, authorized: () -> Boolean): Response {
        val p = state.peer(id) ?: return Response.error(404, "That phone isn't in the cluster")
        if (p.address.isEmpty()) { discovery.discover(); return Response.error(502, "${p.name}'s address isn't known yet") }
        val query = r.query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val path = "/api/" + rest.joinToString("/", transform = ::enc) + if (query.isEmpty()) "" else "?$query"
        val headers = FORWARD.mapNotNull { h -> r.headers[h]?.let { h to it } }.toMap()
        val ws = r.headers["upgrade"]?.lowercase() == "websocket"
        val res = try {
            PeerHttp.request(identity, p.address, p.port, p.fingerprint, r.method, path, headers,
                if (ws) 0 else r.length, if (!ws && r.length > 0) { out -> if (!r.bodyTo(out)) throw IOException("upload cut off") } else null,
                timeoutMs = 4000, readTimeoutMs = if (ws) 0 else 120_000)
        } catch (e: Exception) {
            discovery.discover()
            return Response.error(502, "${p.name} isn't reachable right now (${e.message ?: e.javaClass.simpleName})")
        }
        state.seen(p, p.address)
        val back = RETURN.mapNotNull { h -> res.headers[h]?.let { canonical(h) to it } }.toMap()
        if (ws && res.status == 101) {
            return Response(101, ByteArray(0), "", back, upgrade = { input, output -> pipe(input, output, res) }, upgradeAuthorized = authorized)
        }
        val length = res.headers["content-length"]?.toLongOrNull()
        // Streams without a length (AI answers as they're written) are passed on piece by piece.
        return Response(res.status, ByteArray(0), res.headers["content-type"] ?: "application/octet-stream", back,
            stream = { out -> res.use { copy(it.body, out, flush = length == null) } }, length = length)
    }

    /** Relays a WebSocket between the browser and the other phone until either side closes. */
    private fun pipe(input: InputStream, output: OutputStream, res: PeerResponse) = res.use {
        res.socket.soTimeout = 0
        val up = Thread({ try { copy(input, res.socket.outputStream, flush = true) } catch (_: IOException) {} ; res.close() }, "cluster-ws").apply { isDaemon = true; start() }
        try { copy(res.body, output, flush = true) } catch (_: IOException) {}
        res.close()
        up.join(2000)
    }

    private fun copy(input: InputStream, out: OutputStream, flush: Boolean = false) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (flush) out.flush()
        }
        out.flush()
    }

    // --- notifications on the phone being added ------------------------------------------

    private fun notifyRequest(peer: Peer, code: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cluster pairing", NotificationManager.IMPORTANCE_HIGH))
        fun action(what: String) = PendingIntent.getBroadcast(
            ctx, (peer.id + what).hashCode(),
            Intent(ctx, ClusterReceiver::class.java).setAction(what).putExtra("id", peer.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Add ${peer.name} to your cluster?")
            .setStyle(Notification.BigTextStyle().bigText(
                "${peer.name} (${peer.address}) wants to join. Approve only if the other dashboard shows code $code. " +
                    "A cluster member can fully control this phone."))
            .setContentText("Code $code · approve only if it matches")
            .setTimeoutAfter(ClusterState.PAIR_TIMEOUT)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Approve", action(ClusterReceiver.APPROVE)).build())
            .addAction(Notification.Action.Builder(null, "Reject", action(ClusterReceiver.REJECT)).build())
            .build()
        nm.notify(notificationId(peer.id), n)
    }

    private fun cancelNotification(id: String) = ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(id))

    private fun notificationId(id: String) = 1000 + (id.hashCode() and 0xfff)

    companion object {
        private const val CHANNEL = "cluster"

        // Holds only the application context (see ctx), so nothing short-lived leaks.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile var instance: Cluster? = null
            private set

        private val FORWARD = listOf("content-type", "range", "upgrade", "connection", "sec-websocket-key", "sec-websocket-version")
        private val RETURN = listOf("content-range", "accept-ranges", "content-disposition", "content-security-policy", "cache-control",
            "x-frame", "retry-after", "upgrade", "connection", "sec-websocket-accept",
            "x-homedroid-model", "x-homedroid-where", "x-homedroid-note")

        private fun canonical(h: String) = h.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercase) }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Approve and Reject on the pairing notification. */
class ClusterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val cluster = Cluster.instance ?: return
        // Network calls follow an approval, so leave the main thread.
        val pending = goAsync()
        Thread({
            try { if (intent.action == APPROVE) cluster.approve(id) else cluster.reject(id) } finally { pending.finish() }
        }, "cluster-approve").start()
    }

    companion object {
        const val APPROVE = "dev.homedroid.CLUSTER_APPROVE"
        const val REJECT = "dev.homedroid.CLUSTER_REJECT"
    }
}

/**
 * Finds other Homedroid phones on the local network with mDNS (_homedroid._tcp), and keeps
 * members' addresses current when their IP changes. Searches only on demand, briefly.
 */
class Discovery(private val ctx: Context, private val cluster: Cluster) {
    class Found(val id: String, val name: String, val address: String, val port: Int, val at: Long)

    val found = ConcurrentHashMap<String, Found>()
    @Volatile var running = false; private set

    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var lastSearch = 0L

    fun advertise() = main.post {
        val info = NsdServiceInfo().apply {
            serviceName = "Homedroid ${cluster.name}".take(60)
            serviceType = TYPE
            port = ClusterState.PORT
            setAttribute("id", cluster.state.id)
            setAttribute("v", ClusterState.PROTOCOL.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Jobs.line("Cluster: couldn't announce this phone on the network ($code)") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); registration = l } catch (_: Exception) {}
    }

    /** Searches for ~20 seconds; repeated calls while searching (or just after) do nothing. */
    fun discover() = main.post {
        if (running || System.currentTimeMillis() - lastSearch < 15_000) return@post
        running = true
        lastSearch = System.currentTimeMillis()
        lock = ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("homedroid:discovery")
            .apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { queue.addLast(info); resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { finish() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (_: Exception) { finish(); return@post }
        main.postDelayed(::finish, 20_000)
    }

    private fun finish() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        lock?.release(); lock = null
        running = false
    }

    /** Resolves one service at a time; older Android versions refuse concurrent resolves. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val id = i.attributes["id"]?.toString(Charsets.UTF_8)
                        val host = i.host?.hostAddress
                        if (id != null && Peer.ID.matches(id) && host != null && Peer.validHost(host) && id != cluster.state.id) {
                            found[id] = Found(id, i.serviceName.removePrefix("Homedroid "), host, i.port, System.currentTimeMillis())
                            cluster.onDiscovered(id, host, i.port)
                        }
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) { resolving = false; main.post(::resolveNext) }
    }

    fun stop() = main.post {
        finish()
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        registration = null
    }

    companion object {
        private const val TYPE = "_homedroid._tcp"
    }
}
