package dev.homedroid

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The web dashboard: a single-page UI (assets/dashboard.html) plus a JSON API to manage
 * modules, apps and deployments. Runs inside [ServerService], beside the supervisor, so
 * restarting services doesn't take it down.
 *
 * Auth: log in with the dashboard password (shown in the app) for a session cookie, or send
 * it as "Authorization: Bearer <password>" from scripts.
 */
class Dashboard(private val ctx: Context) {
    private val cfg = Config(ctx)
    private val paths = Paths(ctx)
    private val apps = Apps(ctx, paths)
    private val deploys = Deploys(ctx, paths)
    private val alpine = Alpine(ctx, paths)
    private val files = FileBrowser(ctx, paths)
    private val ssh = SshAdmin(paths, alpine)
    private val auth = AuthSecurity(cfg.dashboardAuth, persist = { cfg.dashboardAuth = it })
    // Room for live views and downloads forwarded to other phones, which hold a thread each.
    private val http = Http(cfg.dashboardPort, threads = 32, handler = ::handle)
    private val cluster = Cluster(ctx) { r, peer -> route(r, peer) }
    private val ai = Ai(ctx)

    fun start() {
        http.start()
        ai.start()
        try {
            cluster.start()
        } catch (e: java.io.IOException) {
            Jobs.line("cluster port could not open: ${e.message}")
        }
    }

    fun stop() {
        ai.stop()
        cluster.stop()
        http.stop()
    }

    private fun handle(r: Request): Response {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) return page(r)
        if (r.method == "GET" && r.path == "/favicon.svg") {
            val svg = ctx.assets.open("favicon.svg").use { it.readBytes() }
            return Response(200, svg, "image/svg+xml", mapOf("Cache-Control" to "max-age=86400"))
        }
        if (r.method == "GET" && r.path.startsWith("/vendor/")) return vendor(r)
        if (r.method == "GET" && r.path.startsWith("/guides/")) return guideImage(r.path.removePrefix("/guides/"))
        if (!r.path.startsWith("/api/")) return Response.error(404, "not found")
        val terminal = r.path == "/api/terminal" || r.path.startsWith("/api/nodes/") && r.path.endsWith("/terminal")
        if (!AuthSecurity.browserAllowed(r.method, r.headers, terminal)) {
            return Response.error(403, "Use the dashboard from its own address")
        }
        if (r.path == "/api/login" && r.method == "POST") return login(r)
        authorized(r)?.let { return it }
        val seg = r.path.removePrefix("/api/").split('/')
        if (seg[0] == "nodes" && seg.size >= 3) return cluster.proxy(r, seg[1], seg.drop(2)) { sessionValid(r) }
        if (seg[0] == "cluster") return clusterRoute(r, seg.drop(1))
        return route(r)
    }

    /** Still logged in: rechecked while a forwarded terminal is open. */
    private fun sessionValid(r: Request) = synchronized(auth) {
        auth.session(r.cookie(COOKIE)) || AuthSecurity.bearer(r.headers)?.let(::passwordMatches) == true
    }

    private fun clusterRoute(r: Request, seg: List<String>): Response {
        val body = if (r.method == "POST") r.json() else JSONObject()
        return when {
            r.method == "GET" && seg.isEmpty() -> Response.json(cluster.json())
            r.method != "POST" -> Response.error(404, "No such cluster endpoint")
            seg == listOf("discover") -> { cluster.discovery.discover(); Response.ok() }
            seg == listOf("pair") -> {
                val target = body.optString("address").trim()
                // "host", "host:port" or "[v6]:port"
                val m = Regex("""^\[?([0-9A-Za-z.:%-]+?)]?(?::(\d{1,5}))?$""").matchEntire(target)
                    ?: return Response.error(400, "Enter the other phone's address, like 192.168.1.20")
                cluster.pair(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: ClusterState.PORT)
            }
            seg == listOf("pair", "cancel") -> { cluster.cancelPairing(); Response.ok() }
            seg.size == 3 && seg[0] == "requests" && seg[2] in setOf("approve", "reject") ->
                if (if (seg[2] == "approve") cluster.approve(seg[1]) else cluster.reject(seg[1])) Response.ok()
                else Response.error(404, "That request expired; ask again from the other phone")
            seg.size == 3 && seg[0] == "members" && seg[2] == "remove" ->
                if (cluster.remove(seg[1])) Response.ok() else Response.error(404, "No such phone in the cluster")
            seg == listOf("leave") -> { cluster.leave(); Response.ok() }
            seg == listOf("name") -> {
                val name = Peer.cleanName(body.optString("name"))
                if (name.isEmpty()) return Response.error(400, "Enter a name")
                cluster.state.rename(name)
                Response.ok()
            }
            else -> Response.error(404, "No such cluster endpoint")
        }
    }

    /**
     * The single-page UI, gzipped once and revalidated by ETag: it only changes when the app
     * is updated, so browsers usually get a 304 instead of the page.
     */
    private fun page(r: Request): Response {
        val etag = "\"${ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime}\""
        val headers = SECURITY_HEADERS + mapOf("ETag" to etag, "Cache-Control" to "no-cache", "Vary" to "Accept-Encoding")
        if (r.headers["if-none-match"] == etag) return Response(304, ByteArray(0), "text/html; charset=utf-8", headers)
        val gzip = r.headers["accept-encoding"].orEmpty().contains("gzip")
        return Response(
            200, if (gzip) pageGzip else pageRaw, "text/html; charset=utf-8",
            if (gzip) headers + ("Content-Encoding" to "gzip") else headers,
        )
    }

    /** Third-party files (xterm.js), gzipped once; the page asks for them with a version, so cache them long. */
    private val vendorCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun vendor(r: Request): Response {
        val name = r.path.removePrefix("/vendor/")
        if (name !in VENDOR) return Response.error(404, "not found")
        val gz = vendorCache.getOrPut(name) {
            java.io.ByteArrayOutputStream().also { out ->
                java.util.zip.GZIPOutputStream(out).use { z -> ctx.assets.open("vendor/$name").use { it.copyTo(z) } }
            }.toByteArray()
        }
        val type = when { name.endsWith(".woff2") -> "font/woff2"; name.endsWith(".css") -> "text/css"; else -> "text/javascript" }
        val headers = mapOf("Cache-Control" to "max-age=2592000, immutable", "Vary" to "Accept-Encoding")
        return if (r.headers["accept-encoding"].orEmpty().contains("gzip")) Response(200, gz, type, headers + ("Content-Encoding" to "gzip"))
        else Response(200, ctx.assets.open("vendor/$name").use { it.readBytes() }, type, headers)
    }

    /** Screenshots for the setup guides; they change only with the app. */
    private fun guideImage(name: String): Response {
        if (!Regex("[a-z0-9-]+\\.png").matches(name)) return Response.error(404, "not found")
        val bytes = try {
            ctx.assets.open("guides/$name").use { it.readBytes() }
        } catch (_: java.io.IOException) {
            return Response.error(404, "not found")
        }
        return Response(200, bytes, "image/png", mapOf("Cache-Control" to "max-age=86400"))
    }

    private val pageRaw by lazy { ctx.assets.open("dashboard.html").use { it.readBytes() } }
    private val pageGzip by lazy {
        java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(pageRaw) } }.toByteArray()
    }

    /** The API itself; [peer] is set when another phone in the cluster sent the request. */
    private fun route(r: Request, peer: Peer? = null): Response {
        val seg = r.path.removePrefix("/api/").split('/')
        return when {
            r.method == "POST" && seg == listOf("logout") -> {
                auth.logout(r.cookie(COOKIE))
                Response.json(JSONObject().put("ok", true), headers = mapOf("Set-Cookie" to "$COOKIE=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"))
            }
            seg.firstOrNull() == "camera" -> cameraRoute(r, seg.drop(1))
            seg.firstOrNull() == "ai" -> ai.handle(r, seg.drop(1))
            r.method == "GET" && seg == listOf("status") -> Response.json(status())
            // Everything the overview shows, in one request instead of four.
            r.method == "GET" && seg == listOf("overview") -> Response.json(
                JSONObject().put("status", status()).put("modules", modules()).put("deploys", deploysJson()).put("services", servicesJson())
            )
            r.method == "GET" && seg == listOf("settings") -> Response.json(settings())
            r.method == "POST" && seg == listOf("settings") -> saveSettings(r)
            r.method == "GET" && seg == listOf("modules") -> Response.json(modules())
            r.method == "GET" && seg.size == 3 && seg[0] == "modules" && seg[2] == "export" -> exportApp(seg[1], r, peer)
            r.method == "POST" && seg.size == 3 && seg[0] in setOf("modules", "deploys") && seg[2] == "move" -> startMove(seg[0], seg[1], r)
            r.method == "GET" && seg.size == 3 && seg[0] == "deploys" && seg[2] == "config" -> {
                // The full config includes secrets (env, repo token): only for a phone taking it over.
                if (peer == null) return Response.error(403, "Only other phones in the cluster can read this")
                deploys.get(seg[1])?.let { Response.json(it.toStored()) } ?: Response.error(404, "no deployment ${seg[1]}")
            }
            r.method == "POST" && seg.size == 3 && seg[0] == "modules" -> moduleAction(seg[1], seg[2], r)
            r.method == "GET" && seg.size == 3 && seg[0] == "services" && seg[2] == "logs" -> logs(seg[1])
            r.method == "POST" && seg.size == 3 && seg[0] == "services" && seg[2] == "restart" -> restart(seg[1])
            r.method == "POST" && seg == listOf("server", "restart") -> {
                ServerService.restart(ctx)
                Response.ok()
            }
            r.method == "GET" && seg == listOf("job") -> Response.json(job())
            r.method == "GET" && seg == listOf("services") -> Response.json(servicesJson())
            r.method == "GET" && seg == listOf("deploys") -> Response.json(deploysJson())
            r.method == "POST" && seg == listOf("deploys") -> createDeploy(r)
            r.method == "POST" && seg.size == 3 && seg[0] == "deploys" -> deployAction(seg[1], seg[2])
            r.method == "DELETE" && seg.size == 2 && seg[0] == "deploys" -> deleteDeploy(seg[1])
            seg[0] == "files" -> files.handle(r, seg.drop(1).filter { it.isNotEmpty() })
            r.method == "GET" && seg == listOf("terminal") ->
                if (peer != null) Terminal.open(r, cfg.sshEnabled && ServerService.running, fromPeer = true) { cluster.state.byFingerprint(r.peer) != null }
                else Terminal.open(r, cfg.sshEnabled && ServerService.running) { sessionValid(r) }
            seg[0] == "ssh" -> ssh.handle(r, seg.drop(1))
            seg[0] == "tailscale" -> tailscaleRoute(r, seg.drop(1))
            r.method == "GET" && seg == listOf("update") -> Response.json(Updater.json(ctx, r.query["refresh"] == "1"))
            r.method == "POST" && seg == listOf("update", "install") ->
                Updater.installLatest(ctx)?.let { Response.error(409, it) } ?: Response.ok()
            r.method == "POST" && seg == listOf("update", "upload") ->
                Updater.installUpload(ctx, r)?.let { Response.error(409, it) }
                    ?: Updater.error?.let { Response.error(400, it) } ?: Response.ok()
            else -> Response.error(404, "no such endpoint")
        }
    }

    // --- auth ---------------------------------------------------------------------------

    private fun login(r: Request): Response = synchronized(auth) {
        val password = r.json().optString("password")
        val result = auth.password(r.remote) { passwordMatches(password) }
        if (result.accepted) newSession(r) else authError(result)
    }

    private fun newSession(r: Request): Response {
        val token = auth.newSession()
        return Response.json(
            JSONObject().put("ok", true),
            headers = mapOf("Set-Cookie" to "$COOKIE=$token; Max-Age=${AuthSecurity.SESSION_MS / 1000}; Path=/; HttpOnly; SameSite=Strict" +
                if (AuthSecurity.secureCookie(r.headers)) "; Secure" else ""),
        )
    }

    private fun authorized(r: Request): Response? = synchronized(auth) {
        if (auth.session(r.cookie(COOKIE))) return null
        val bearer = AuthSecurity.bearer(r.headers) ?: return Response.error(401, "log in first")
        val result = auth.password(r.remote) { passwordMatches(bearer) }
        if (result.accepted) null else authError(result)
    }

    private fun authError(result: AuthSecurity.Result, status: Int = 401): Response = if (result.retrySeconds > 0) {
        Response.json(JSONObject().put("error", "Too many password attempts. Try again in ${result.retrySeconds} seconds.")
            .put("retryAfter", result.retrySeconds), 429, mapOf("Retry-After" to result.retrySeconds.toString()))
    } else Response.error(status, "Incorrect password")

    private fun passwordMatches(given: String) =
        MessageDigest.isEqual(given.toByteArray(), cfg.dashboardPassword.toByteArray())

    // --- views --------------------------------------------------------------------------

    private fun status(): JSONObject {
        val d = Device(ctx)
        return JSONObject()
            .put("model", d.model)
            .put("android", d.android)
            .put("battery", d.batteryPercent)
            .put("batteryTemp", d.batteryTempC.toDouble())
            .put("charging", d.charging)
            .put("hot", d.hot)
            .put("ramFreeMb", d.ramFreeMb)
            .put("ramTotalMb", d.ramTotalMb)
            .put("storageFreeMb", d.storageFreeMb)
            .put("storageTotalMb", d.storageTotalMb)
            .put("ips", jsonArray(d.ips))
            .put("uptime", d.uptimeS)
            .put("running", ServerService.running)
            .put("version", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
            .put("metrics", Metrics.sample(ctx))
            .put("node", JSONObject().put("id", cluster.state.id).put("name", cluster.name))
    }

    private fun servicesJson() = jsonArray(
        ServerService.supervisor?.daemons.orEmpty().map { d ->
            JSONObject().put("name", d.spec.name).put("state", d.state.name.lowercase()).put("restarts", d.restarts)
        }
    )

    private fun deploysJson() = jsonArray(deploys.all().map { it.toJson(daemon(it.service), deploys.state(it, alpine)) })

    private fun daemon(name: String) = ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == name }

    private fun serviceJson(o: JSONObject, name: String): JSONObject {
        val d = daemon(name) ?: return o.put("state", "stopped")
        return o.put("state", d.state.name.lowercase()).put("restarts", d.restarts)
    }

    /** Aggregate state of an app's processes: the least healthy one wins. */
    private fun appState(o: JSONObject, a: AppDef): JSONObject {
        val ds = a.serviceNames.map(::daemon)
        if (ds.any { it == null }) return o.put("state", "stopped")
        val order = listOf("running", "starting", "stopped", "backoff")
        val worst = ds.filterNotNull().maxByOrNull { order.indexOf(it.state.name.lowercase()) }!!
        return o.put("state", worst.state.name.lowercase()).put("restarts", ds.sumOf { it!!.restarts })
    }

    /** Every module the user can add or remove: built-ins and catalog apps. */
    private fun modules(): JSONObject {
        val core = jsonArray(CORE.map { m ->
            serviceJson(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("description", m.description)
                    .put("port", m.port)
                    .put("kind", "core")
                    .put("installed", true)
                    .put("enabled", coreEnabled(m.id))
                    .apply { if (m.id == "tailscale") put("tailscale", tailscaleJson()) },
                m.service,
            )
        })
        val catalog = jsonArray(apps.catalog.map { a ->
            val o = JSONObject()
                .put("id", a.id)
                .put("name", a.name)
                .put("description", a.description)
                .put("port", a.port)
                .put("size", a.size)
                .put("kind", "app")
                .put("installed", apps.isInstalled(a))
                .put("enabled", !cfg.isDisabled(a.id))
                .put("storageLabel", a.storageLabel ?: JSONObject.NULL)
                .put("storageDir", cfg.storageDir(a) ?: JSONObject.NULL)
                .put("sharedWith", jsonArray(apps.sharing(a).map { it.name }))
            a.serviceNames.mapNotNull(::daemon).lastOrNull()?.let { d ->
                a.noticePattern?.let { p ->
                    d.log.tail(400).asReversed().firstNotNullOfOrNull { l -> p.find(l) }?.let { m ->
                        o.put("notice", a.noticeText.orEmpty().replace("$1", m.groupValues.getOrElse(1) { "" }))
                    }
                }
            }
            o.put("services", jsonArray(a.serviceNames))
            appState(o, a)
        })
        core.put(JSONObject().put("id", "camera").put("name", "IPCam")
            .put("description", "Remote photos, video, flash, microphone and speaker announcements. Enable access in the phone app.")
            .put("port", 0).put("kind", "core").put("installed", true).put("enabled", cfg.cameraEnabled)
            .put("state", if (CameraService.instance != null) "ready" else "needs phone setup"))
        core.put(JSONObject().put("id", "ai").put("name", "AI")
            .put("description", "Chat and an OpenAI-compatible API for your other apps, with models on this phone or cloud services you connect.")
            .put("port", AiCore.PORT).put("kind", "core").put("installed", true).put("enabled", ai.cfg.enabled)
            .put("state", if (ai.running) "running" else "stopped"))
        return JSONObject().put("core", core).put("apps", catalog).put("job", job())
    }

    private fun job() = JSONObject()
        .put("title", Jobs.title ?: JSONObject.NULL)
        .put("running", Jobs.running)
        .put("error", Jobs.error ?: JSONObject.NULL)
        .put("log", jsonArray(Jobs.log.tail(200)))

    private fun logs(name: String): Response {
        val d = daemon(name) ?: return Response.error(404, "$name is not running")
        return Response.json(JSONObject().put("name", name).put("lines", jsonArray(d.log.tail(200))))
    }

    // --- actions ------------------------------------------------------------------------

    private fun restart(name: String): Response {
        val d = daemon(name) ?: return Response.error(404, "$name is not running")
        d.restart()
        return Response.ok()
    }

    private fun moduleAction(id: String, action: String, r: Request): Response {
        if (id == "camera") {
            if (action !in setOf("enable", "disable")) return Response.error(400, "IPCam can only be enabled or disabled")
            cfg.cameraEnabled = action == "enable"
            if (!cfg.cameraEnabled) CameraService.stop(ctx)
            return Response.ok()
        }
        if (id == "ai") {
            if (action !in setOf("enable", "disable")) return Response.error(400, "AI can only be turned on or off")
            ai.cfg.enabled = action == "enable"
            if (ai.cfg.enabled) ai.start() else ai.stop()
            // Starts or stops the on-phone model with the other services.
            ServerService.restart(ctx)
            return Response.ok()
        }
        CORE.firstOrNull { it.id == id }?.let { m ->
            when (action) {
                "enable", "disable" -> setCoreEnabled(m.id, action == "enable", r)?.let { return it }
                else -> return Response.error(400, "built-in modules can only be enabled or disabled")
            }
            ServerService.restart(ctx)
            return Response.ok()
        }
        val app = apps.catalog.firstOrNull { it.id == id } ?: return Response.error(404, "no module $id")
        if (Jobs.running && action in setOf("install", "remove", "clear")) return Response.error(409, "another install is running")
        when (action) {
            "install" -> ServerService.install(ctx, app)
            "remove" -> r.json().let {
                val library = it.optBoolean("deleteLibrary")
                ServerService.uninstall(ctx, app, it.optBoolean("deleteData") || library, library)
            }
            "clear" -> {
                if (!apps.isInstalled(app)) return Response.error(409, "${app.name} isn't installed")
                ServerService.clearData(ctx, app, r.json().optBoolean("deleteLibrary"))
            }
            "enable", "disable" -> {
                cfg.setDisabled(app.id, action == "disable")
                ServerService.restart(ctx)
            }
            else -> return Response.error(400, "unknown action $action")
        }
        return Response.ok()
    }

    // --- moving apps between phones ----------------------------------------------------------

    /**
     * An app's data for the phone taking it over (see [ServerService.move]); ?size=1 only
     * measures it. The app must be off, so its database isn't copied mid-write.
     */
    private fun exportApp(id: String, r: Request, peer: Peer?): Response {
        if (peer == null) return Response.error(403, "Only other phones in the cluster can copy an app's data")
        val app = apps.catalog.firstOrNull { it.id == id } ?: return Response.error(404, "no module $id")
        if (!apps.isInstalled(app)) return Response.error(409, "${app.name} isn't installed on ${cluster.name}")
        val library = r.query["library"] == "1"
        val roots = apps.archiveRoots(app, cfg, alpine, library)
        if (r.query["size"] == "1") {
            val lib = roots.firstOrNull { it.name == "library" }
            return Response.json(JSONObject()
                .put("bytes", AppArchive.size(roots.filter { it !== lib }))
                .put("libraryBytes", lib?.let { AppArchive.size(listOf(it)) } ?: 0)
                .put("sharedWith", jsonArray(apps.sharing(app).map { it.name })))
        }
        if (!cfg.isDisabled(app.id) || app.serviceNames.any { daemon(it) != null }) {
            return Response.error(409, "Turn ${app.name} off on ${cluster.name} first")
        }
        return Response(200, ByteArray(0), "application/octet-stream", stream = { AppArchive.write(roots, it) })
    }

    /** Takes over app or deployment [id] from another phone; runs as a job on this phone. */
    private fun startMove(kind: String, id: String, r: Request): Response {
        val body = r.json()
        val from = cluster.state.peer(body.optString("from")) ?: return Response.error(404, "Pick a phone in the cluster to move it from")
        if (Jobs.running) return Response.error(409, "Another job is running on ${cluster.name}; wait for it to finish")
        if (kind == "modules") {
            val app = apps.catalog.firstOrNull { it.id == id } ?: return Response.error(404, "no module $id")
            val arch = Oci.archFor(paths.libDir)
            if (app.arches.isNotEmpty() && arch !in app.arches) return Response.error(409, "${app.name} isn't available for ${cluster.name}'s CPU ($arch)")
        } else {
            if (!Regex("[a-z0-9-]{1,64}").matches(id)) return Response.error(400, "invalid deployment")
            if (deploys.get(id) != null) return Response.error(409, "${cluster.name} already has a deployment called $id")
        }
        ServerService.move(ctx, if (kind == "modules") "app" else "deploy", id, from.id, body.optBoolean("library"), body.optBoolean("removeSource"))
        return Response.ok()
    }

    private fun coreEnabled(id: String) = when (id) {
        "ssh" -> cfg.sshEnabled
        "web" -> cfg.webEnabled
        "tunnel" -> cfg.tunnelToken.isNotEmpty() && !cfg.isDisabled("tunnel")
        "tailscale" -> cfg.tailscaleEnabled
        else -> false
    }

    /** Returns an error response, or null on success. */
    private fun setCoreEnabled(id: String, on: Boolean, r: Request): Response? {
        when (id) {
            "ssh" -> cfg.sshEnabled = on
            "web" -> cfg.webEnabled = on
            "tunnel" -> {
                val token = r.json().optString("token").trim()
                if (token.isNotEmpty()) cfg.tunnelToken = token
                if (on && cfg.tunnelToken.isEmpty()) return Response.error(400, "a tunnel token is needed")
                cfg.setDisabled("tunnel", !on)
            }
            "tailscale" -> {
                if (on) saveTailscale(r.json())?.let { return it }
                cfg.tailscaleEnabled = on
            }
        }
        return null
    }

    // --- Tailscale ----------------------------------------------------------------------

    private val tailscale get() = Tailscale(paths, cfg)

    private fun tailscaleJson() = JSONObject()
        .put("enabled", cfg.tailscaleEnabled)
        .put("hostname", cfg.tailscaleHostname)
        .put("hasAuthKey", cfg.tailscaleAuthKey.isNotEmpty())
        .put("exitNode", cfg.tailscaleExitNode)
        .put("routes", cfg.tailscaleRoutes)
        .put("tags", cfg.tailscaleTags)
        .put("serve", cfg.tailscaleServe)
        .put("proxy", cfg.tailscaleProxy)
        .put("proxyPort", Tailscale.PROXY_PORT)
        .put("status", if (cfg.tailscaleEnabled) tailscale.status() else JSONObject().put("state", "Stopped"))

    private fun tailscaleRoute(r: Request, seg: List<String>): Response {
        if (r.method == "GET" && seg.isEmpty()) return Response.json(tailscaleJson())
        if (r.method != "POST") return Response.error(404, "No such Tailscale endpoint")
        if (!cfg.tailscaleEnabled || daemon("tailscaled") == null) return Response.error(409, "Turn on the Tailscale module first")
        when (seg) {
            emptyList<String>() -> {
                val proxy = cfg.tailscaleProxy
                saveTailscale(r.json())?.let { return it }
                // The proxy is a tailscaled flag, so it needs a new process (which then applies
                // everything); the rest is applied to the running one with `tailscale up`.
                if (proxy != cfg.tailscaleProxy) ServerService.restart(ctx) else background { tailscale.apply() }
            }
            listOf("login") -> background { tailscale.apply() }
            listOf("logout") -> tailscale.logout()?.let { return Response.error(500, it) }
            else -> return Response.error(404, "No such Tailscale endpoint")
        }
        return Response.ok()
    }

    private fun background(block: () -> Unit) = Thread(block, "tailscale-up").apply { isDaemon = true; start() }

    /** Validates and saves Tailscale settings present in [body]; returns an error, or null. */
    private fun saveTailscale(body: JSONObject): Response? {
        val host = body.optString("hostname", cfg.tailscaleHostname).trim().lowercase()
        if (!Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?").matches(host)) {
            return Response.error(400, "Use letters, digits and dashes for the device name")
        }
        val cidr = Regex("[0-9a-fA-F:.]+/[0-9]{1,3}")
        val routes = body.optString("routes", cfg.tailscaleRoutes).split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        routes.firstOrNull { !cidr.matches(it) }?.let { return Response.error(400, "$it isn't a subnet like 192.168.1.0/24") }
        val tags = body.optString("tags", cfg.tailscaleTags).split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("tag:")) it else "tag:$it" }
        val serve = body.optString("serve", cfg.tailscaleServe)
        if (serve !in setOf("off", "tailnet", "funnel")) return Response.error(400, "Unknown Serve mode")
        cfg.tailscaleHostname = host
        cfg.tailscaleRoutes = routes.joinToString(",")
        cfg.tailscaleTags = tags.joinToString(",")
        cfg.tailscaleServe = serve
        if (body.has("exitNode")) cfg.tailscaleExitNode = body.optBoolean("exitNode")
        if (body.has("proxy")) cfg.tailscaleProxy = body.optBoolean("proxy")
        // An empty key keeps the saved one; "clearAuthKey" forgets it.
        body.optString("authKey").trim().takeIf { it.isNotEmpty() }?.let { cfg.tailscaleAuthKey = it }
        if (body.optBoolean("clearAuthKey")) cfg.tailscaleAuthKey = ""
        return null
    }

    private fun settings() = JSONObject().put("autostart", cfg.autostart).put("cameraEnabled", cfg.cameraEnabled)
        .put("dashboardPort", cfg.dashboardPort).put("sshPort", cfg.sshPort).put("webPort", cfg.webPort)

    private fun saveSettings(r: Request): Response = synchronized(auth) {
        val body = r.json()
        val password = body.optString("password")
        if (password.isNotEmpty()) {
            val result = auth.password(r.remote) { passwordMatches(body.optString("currentPassword")) }
            if (!result.accepted) return authError(result, 403)
            if (password.length !in 12..128 || password.any { it.isISOControl() }) {
                return Response.error(400, "Use 12–128 characters without control characters")
            }
        }
        if (body.has("autostart") && body.opt("autostart") !is Boolean) return Response.error(400, "Invalid startup preference")
        if (body.has("autostart")) cfg.autostart = body.getBoolean("autostart")
        if (password.isNotEmpty()) {
            synchronized(auth) {
                cfg.dashboardPassword = password
                auth.revokeSessions()
                return newSession(r)
            }
        }
        return Response.ok()
    }

    private fun cameraRoute(r: Request, seg: List<String>): Response {
        if (!cfg.cameraEnabled) return Response.error(409, "Enable the IPCam module first")
        val service = CameraService.instance
        val dir = CameraService.directory(ctx)
        if (r.method == "GET" && seg.isEmpty()) {
            val state = service?.request("status")?.let {
                if (it.status == 200) JSONObject(String(it.body)) else null
            } ?: JSONObject().put("armed", false)
            state.put("cameras", jsonArray(CameraService.cameras(ctx)))
            val preferences = CameraPreferences(ctx)
            state.put("storage", JSONObject().put("quotaMb", preferences.quotaMb)
                .put("usedBytes", CaptureStore(dir) { preferences.quotaMb * 1024L * 1024 }.used()))
            state.put("files", jsonArray(dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension in setOf("jpg", "mp4", "wav") }
                .sortedByDescending { it.name }.take(100).map {
                    JSONObject().put("name", it.name).put("bytes", it.length())
                }))
            return Response.json(state)
        }
        if (r.method == "GET" && seg == listOf("audio")) {
            return service?.let { Response.json(it.audio(r.query["after"]?.toLongOrNull() ?: 0)) }
                ?: Response.error(409, "Enable IPCam access in the phone app")
        }
        if (r.method == "GET" && seg.size == 2 && seg[0] == "files") {
            val name = seg[1]
            if (!Regex("[0-9]+-[0-9a-f-]+\\.(jpg|mp4|wav)").matches(name)) return Response.error(404, "No such capture")
            val file = java.io.File(dir, name)
            if (!file.isFile) return Response.error(404, "No such capture")
            return files.download(r, file)
        }
        if (r.method == "GET" && seg == listOf("live", "frame")) {
            val live = service ?: return Response.error(409, "Enable IPCam access in the phone app")
            val (seq, jpeg) = live.nextFrame(r.query["after"]?.toLongOrNull() ?: -1) ?: return Response(204, byteArrayOf())
            return Response(200, jpeg, "image/jpeg", mapOf("X-Frame" to seq.toString(), "Cache-Control" to "no-store"))
        }
        if (r.method == "POST" && seg == listOf("settings") && service == null) {
            return try {
                val body = r.json()
                val id = body.getString("id")
                require(id in ctx.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList) { "Unknown camera" }
                CameraPreferences(ctx).save(id, body)
                Response.json(JSONObject().put("saved", true))
            } catch (e: Exception) { Response.error(400, e.message ?: "Invalid camera settings") }
        }
        if (r.method == "POST" && seg == listOf("storage") && service == null) {
            return try {
                val preferences = CameraPreferences(ctx)
                val quota = r.json().getInt("quotaMb")
                require(quota in 128..102400) { "Storage must be between 128 and 102400 MB" }
                val store = CaptureStore(dir) { preferences.quotaMb * 1024L * 1024 }
                check(store.activeBytes() <= quota * 1024L * 1024) { "Incomplete captures occupy more than the requested quota" }
                preferences.quotaMb = quota
                check(store.reserve(0)) { "Could not free enough capture storage" }
                Response.json(JSONObject().put("saved", true))
            } catch (e: Exception) { Response.error(400, e.message ?: "Invalid storage settings") }
        }
        if (r.method == "POST" && seg.joinToString("/") in setOf("photo", "record", "stop", "torch", "live/start", "live/stop", "microphone/start", "microphone/stop", "announce", "settings", "storage", "monitor/start", "monitor/stop")) {
            return service?.request(seg.joinToString("/"), r.json())
                ?: Response.error(409, "Enable IPCam camera and microphone access in the phone app first")
        }
        return Response.error(404, "No such IPCam endpoint")
    }

    private fun createDeploy(r: Request): Response {
        val d = try {
            deploys.create(r.json())
        } catch (e: IllegalArgumentException) {
            return Response.error(400, e.message ?: "invalid deployment")
        }
        ServerService.deploy(ctx, d.id)
        return Response.json(d.toJson(null))
    }

    private fun deployAction(id: String, action: String): Response {
        deploys.get(id) ?: return Response.error(404, "no deployment $id")
        when (action) {
            "redeploy" -> ServerService.deploy(ctx, id)
            "restart" -> return restart(Deploys.serviceName(id))
            else -> return Response.error(400, "unknown action $action")
        }
        return Response.ok()
    }

    private fun deleteDeploy(id: String): Response {
        deploys.get(id) ?: return Response.error(404, "no deployment $id")
        ServerService.undeploy(ctx, id)
        return Response.ok()
    }

    private class Core(val id: String, val name: String, val description: String, val port: Int, val service: String)

    companion object {
        private const val COOKIE = "homedroid_session"
        private val VENDOR = setOf("xterm.js", "xterm.css", "addon-fit.js", "JetBrainsMono-Regular.woff2")

        private val SECURITY_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
            "X-Frame-Options" to "DENY",
            "Referrer-Policy" to "no-referrer",
        )

        private val CORE = listOf(
            Core("ssh", "SSH & SFTP", "Shell and file transfer, public-key login only.", 8022, "sshd"),
            Core("web", "Web hosting", "Caddy web server for ~/www and deployed sites.", 8080, "caddy"),
            Core("tunnel", "Cloudflare Tunnel", "Publishes services on the internet, no port forwarding.", 0, "cloudflared"),
            Core("tailscale", "Tailscale", "Private access from your own devices, anywhere. No VPN slot or root needed.", 0, "tailscaled"),
        )
    }
}
