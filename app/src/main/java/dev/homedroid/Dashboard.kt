package dev.homedroid

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

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
    private val sessions: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val http = Http(cfg.dashboardPort, ::handle)

    fun start() = http.start()

    fun stop() = http.stop()

    private fun handle(r: Request): Response {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) return page(r)
        if (r.method == "GET" && r.path == "/favicon.svg") {
            val svg = ctx.assets.open("favicon.svg").use { it.readBytes() }
            return Response(200, svg, "image/svg+xml", mapOf("Cache-Control" to "max-age=86400"))
        }
        if (r.method == "GET" && r.path.startsWith("/vendor/")) return vendor(r)
        if (r.method == "GET" && r.path.startsWith("/guides/")) return guideImage(r.path.removePrefix("/guides/"))
        if (r.path == "/api/login" && r.method == "POST") return login(r)
        if (!r.path.startsWith("/api/")) return Response.error(404, "not found")
        if (!authorized(r)) return Response.error(401, "log in first")
        return route(r)
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

    private fun route(r: Request): Response {
        val seg = r.path.removePrefix("/api/").split('/')
        return when {
            r.method == "POST" && seg == listOf("logout") -> {
                r.cookie(COOKIE)?.let(sessions::remove)
                Response.json(JSONObject().put("ok", true), headers = mapOf("Set-Cookie" to "$COOKIE=; Max-Age=0; Path=/"))
            }
            seg.firstOrNull() == "camera" -> cameraRoute(r, seg.drop(1))
            r.method == "GET" && seg == listOf("status") -> Response.json(status())
            // Everything the overview shows, in one request instead of four.
            r.method == "GET" && seg == listOf("overview") -> Response.json(
                JSONObject().put("status", status()).put("modules", modules()).put("deploys", deploysJson()).put("services", servicesJson())
            )
            r.method == "GET" && seg == listOf("settings") -> Response.json(settings())
            r.method == "POST" && seg == listOf("settings") -> saveSettings(r)
            r.method == "GET" && seg == listOf("modules") -> Response.json(modules())
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
            r.method == "GET" && seg == listOf("terminal") -> Terminal.open(r, cfg.sshEnabled && ServerService.running)
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

    private fun login(r: Request): Response {
        val password = r.json().optString("password")
        synchronized(sessions) {
            if (passwordMatches(password)) return newSession()
        }
        Thread.sleep(1000) // slow down guessing without blocking password changes
        return Response.error(401, "wrong password")
    }

    private fun newSession(): Response {
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(sessions) {
            sessions += token
            while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.first())
        }
        return Response.json(
            JSONObject().put("ok", true),
            headers = mapOf("Set-Cookie" to "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict"),
        )
    }

    private fun authorized(r: Request): Boolean {
        r.cookie(COOKIE)?.let { if (it in sessions) return true }
        val bearer = r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return passwordMatches(bearer)
    }

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

    private fun saveSettings(r: Request): Response = synchronized(sessions) {
        val body = r.json()
        val password = body.optString("password")
        if (password.isNotEmpty()) {
            if (!passwordMatches(body.optString("currentPassword"))) return Response.error(403, "Current password is incorrect")
            if (password.length !in 12..128 || password.any { it.isISOControl() }) {
                return Response.error(400, "Use 12–128 characters without control characters")
            }
        }
        if (body.has("autostart") && body.opt("autostart") !is Boolean) return Response.error(400, "Invalid startup preference")
        if (body.has("autostart")) cfg.autostart = body.getBoolean("autostart")
        if (password.isNotEmpty()) {
            synchronized(sessions) {
                cfg.dashboardPassword = password
                sessions.clear()
                return newSession()
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
            val type = when (file.extension) { "jpg" -> "image/jpeg"; "wav" -> "audio/wav"; else -> "video/mp4" }
            return Response(200, byteArrayOf(), type, mapOf("Content-Disposition" to "attachment; filename=\"$name\""),
                stream = { out -> file.inputStream().use { it.copyTo(out) } }, length = file.length())
        }
        if (r.method == "POST" && seg.joinToString("/") in setOf("photo", "record", "stop", "torch", "microphone/start", "microphone/stop", "announce")) {
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
        private const val MAX_SESSIONS = 20

        private val SECURITY_HEADERS = mapOf(
            "Content-Security-Policy" to "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'",
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
