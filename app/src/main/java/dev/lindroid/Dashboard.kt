package dev.lindroid

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
    private val sessions: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val http = Http(cfg.dashboardPort, ::handle)

    fun start() = http.start()

    fun stop() = http.stop()

    private fun handle(r: Request): Response {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            val html = ctx.assets.open("dashboard.html").use { it.readBytes() }
            return Response(200, html, "text/html; charset=utf-8", SECURITY_HEADERS)
        }
        if (r.method == "GET" && r.path == "/favicon.svg") {
            val svg = ctx.assets.open("favicon.svg").use { it.readBytes() }
            return Response(200, svg, "image/svg+xml", mapOf("Cache-Control" to "max-age=86400"))
        }
        if (r.path == "/api/login" && r.method == "POST") return login(r)
        if (!r.path.startsWith("/api/")) return Response.error(404, "not found")
        if (!authorized(r)) return Response.error(401, "log in first")
        return route(r)
    }

    private fun route(r: Request): Response {
        val seg = r.path.removePrefix("/api/").split('/')
        return when {
            r.method == "POST" && seg == listOf("logout") -> {
                r.cookie(COOKIE)?.let(sessions::remove)
                Response.json(JSONObject().put("ok", true), headers = mapOf("Set-Cookie" to "$COOKIE=; Max-Age=0; Path=/"))
            }
            r.method == "GET" && seg == listOf("status") -> Response.json(status())
            r.method == "GET" && seg == listOf("modules") -> Response.json(modules())
            r.method == "POST" && seg.size == 3 && seg[0] == "modules" -> moduleAction(seg[1], seg[2], r)
            r.method == "GET" && seg.size == 3 && seg[0] == "services" && seg[2] == "logs" -> logs(seg[1])
            r.method == "POST" && seg.size == 3 && seg[0] == "services" && seg[2] == "restart" -> restart(seg[1])
            r.method == "POST" && seg == listOf("server", "restart") -> {
                ServerService.restart(ctx)
                Response.ok()
            }
            r.method == "GET" && seg == listOf("job") -> Response.json(job())
            r.method == "GET" && seg == listOf("services") -> Response.json(jsonArray(
                ServerService.supervisor?.daemons.orEmpty().map { d ->
                    JSONObject().put("name", d.spec.name).put("state", d.state.name.lowercase()).put("restarts", d.restarts)
                }
            ))
            r.method == "GET" && seg == listOf("deploys") ->
                Response.json(jsonArray(deploys.all().map { it.toJson(daemon(it.service), deploys.state(it, alpine)) }))
            r.method == "POST" && seg == listOf("deploys") -> createDeploy(r)
            r.method == "POST" && seg.size == 3 && seg[0] == "deploys" -> deployAction(seg[1], seg[2])
            r.method == "DELETE" && seg.size == 2 && seg[0] == "deploys" -> deleteDeploy(seg[1])
            else -> Response.error(404, "no such endpoint")
        }
    }

    // --- auth ---------------------------------------------------------------------------

    private fun login(r: Request): Response {
        if (!passwordMatches(r.json().optString("password"))) {
            Thread.sleep(1000) // slow down guessing
            return Response.error(401, "wrong password")
        }
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
    }

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
                    .put("enabled", coreEnabled(m.id)),
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
        CORE.firstOrNull { it.id == id }?.let { m ->
            when (action) {
                "enable", "disable" -> setCoreEnabled(m.id, action == "enable", r)?.let { return it }
                else -> return Response.error(400, "built-in modules can only be enabled or disabled")
            }
            ServerService.restart(ctx)
            return Response.ok()
        }
        val app = apps.catalog.firstOrNull { it.id == id } ?: return Response.error(404, "no module $id")
        if (Jobs.running && action in setOf("install", "remove")) return Response.error(409, "another install is running")
        when (action) {
            "install" -> ServerService.install(ctx, app)
            "remove" -> ServerService.uninstall(ctx, app)
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
        }
        return null
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
        private const val COOKIE = "lindroid_session"
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
        )
    }
}
