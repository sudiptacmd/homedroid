package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Where a domain (or one of its paths) sends requests, once its target is looked up. */
sealed class Upstream {
    class Proxy(val port: Int) : Upstream()
    class Static(val dir: String) : Upstream()
    class Missing(val why: String) : Upstream()
}

/** One managed Caddy site: [routes] are path prefixes (with whether to strip them) in any order. */
class Site(
    val host: String,
    val token: String,
    val headers: Map<String, String>,
    val routes: List<Triple<String, Boolean, Upstream>>,
    val target: Upstream,
)

/** Validation, Caddy config generation and access-log parsing for [Domains], free of Android so it can be tested. */
object DomainsCore {
    const val IMPORT = "import Caddyfile.d/*.caddy"
    const val VERIFY_PATH = "/.well-known/homedroid-verify/"
    const val MAX_DOMAINS = 64
    const val MAX_ROUTES = 32
    const val MAX_HEADERS = 32
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
    private val HEADER_NAME = Regex("-?[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}")
    private val SEGMENT = Regex("[A-Za-z0-9._~@+-]+")

    /** A plain hostname with at least one dot, lowercased; no wildcards (they can't be verified). */
    fun host(raw: String): String? {
        val s = raw.trim().lowercase().removeSuffix(".")
        if (s.length > 253 || '.' !in s) return null
        val labels = s.split('.')
        if (!labels.all { LABEL.matches(it) } || labels.last().all { it.isDigit() }) return null
        return s
    }

    /** A route prefix like "/api": slash-separated plain segments, no trailing slash, not "/" itself. */
    fun routePath(raw: String): String? {
        val s = raw.trim().trimEnd('/')
        if (!s.startsWith('/') || s.length > 200) return null
        return s.takeIf { s.drop(1).split('/').all { SEGMENT.matches(it) && it != "." && it != ".." } }
    }

    /**
     * A target, normalized: {"type":"deploy","id"}, {"type":"app","id"}, {"type":"static","path"}
     * (a folder under ~/www, "" for ~/www itself) or {"type":"port","port"}.
     */
    fun target(o: JSONObject?): Result<JSONObject> {
        fun fail(m: String) = Result.failure<JSONObject>(IllegalArgumentException(m))
        if (o == null) return fail("Choose where the domain points")
        return when (val type = o.optString("type")) {
            "deploy", "app" -> {
                val id = o.optString("id").trim()
                if (!Regex("[a-z0-9][a-z0-9-]{0,63}").matches(id)) fail("Choose a ${if (type == "app") "app" else "deployment"}")
                else Result.success(JSONObject().put("type", type).put("id", id))
            }
            "static" -> {
                val path = o.optString("path").trim().trim('/')
                if (path.isNotEmpty() && !path.split('/').all { SEGMENT.matches(it) && it != "." && it != ".." }) fail("The folder must be inside ~/www, like \"blog\"")
                else Result.success(JSONObject().put("type", "static").put("path", path))
            }
            "port" -> {
                val port = o.optInt("port", 0)
                // Not Caddy's own port, and not the dashboard's: Caddy is a loopback client, so the
                // dashboard would trust a Cf-Connecting-Ip header the visitor sent.
                if (port !in 1..65535 || port in setOf(8080, 8800, 8801)) fail("Enter the local port the service listens on (not 8080, 8800 or 8801, Homedroid's own)")
                else Result.success(JSONObject().put("type", "port").put("port", port))
            }
            else -> fail("Unknown target type \"${type.take(20)}\"")
        }
    }

    /** A domain from the dashboard form, keeping [id], [token] and the verification state of [old]. */
    fun domain(o: JSONObject, id: String, token: String, old: JSONObject?): Result<JSONObject> {
        fun fail(m: String) = Result.failure<JSONObject>(IllegalArgumentException(m))
        val host = host(o.optString("host")) ?: return fail("Enter a domain name like blog.example.com")
        val target = target(o.optJSONObject("target")).getOrElse { return Result.failure(it) }
        val headers = JSONObject()
        val h = o.optJSONObject("headers") ?: JSONObject()
        if (h.length() > MAX_HEADERS) return fail("That's more than $MAX_HEADERS headers")
        for (name in h.keys()) {
            val value = h.optString(name)
            if (!HEADER_NAME.matches(name)) return fail("\"${name.take(40)}\" isn't a valid header name")
            if (value.length > 1000 || value.any { it.isISOControl() || it == '\\' || it == '"' }) return fail("The value of $name can't contain quotes, backslashes or line breaks")
            headers.put(name, value)
        }
        val routes = JSONArray()
        val rs = o.optJSONArray("routes") ?: JSONArray()
        if (rs.length() > MAX_ROUTES) return fail("That's more than $MAX_ROUTES path routes")
        val seen = HashSet<String>()
        for (i in 0 until rs.length()) {
            val r = rs.optJSONObject(i) ?: return fail("Route ${i + 1} is not an object")
            val path = routePath(r.optString("path")) ?: return fail("Route ${i + 1}: the path must look like /api")
            if (!seen.add(path)) return fail("The path $path is listed twice")
            val t = target(r.optJSONObject("target")).getOrElse { return fail("Route $path: ${it.message}") }
            routes.put(JSONObject().put("path", path).put("target", t).put("strip", r.optBoolean("strip")))
        }
        val same = old != null && old.optString("host") == host
        return Result.success(
            JSONObject().put("id", id).put("host", host).put("target", target).put("headers", headers).put("routes", routes)
                .put("token", token)
                // Verification is about the name, so it survives edits to anything else.
                .put("verified", same && old!!.optBoolean("verified"))
                .put("verifiedAt", if (same) old!!.optLong("verifiedAt") else 0L)
                .put("verifyError", if (same) old!!.opt("verifyError") ?: JSONObject.NULL else JSONObject.NULL)
        )
    }

    /** The route prefix that serves [path]: the longest one equal to it or followed by "/". */
    fun matchRoute(prefixes: Collection<String>, path: String): String? =
        prefixes.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length }

    /** A Caddyfile string token. Values are validated to contain no quotes or backslashes. */
    fun quote(s: String) = "\"" + s.replace("\"", "") + "\""

    private fun StringBuilder.upstream(u: Upstream, indent: String) {
        when (u) {
            is Upstream.Proxy -> append(indent).append("reverse_proxy 127.0.0.1:").append(u.port).append('\n')
            is Upstream.Static -> append(indent).append("root * ").append(quote(u.dir)).append('\n')
                .append(indent).append("file_server\n")
            is Upstream.Missing -> append(indent).append("respond ").append(quote(u.why)).append(" 502\n")
        }
    }

    /**
     * The managed file (Caddyfile.d/homedroid-domains.caddy): snippets for the access log and
     * the "web" firewall, then one site per domain on the existing :[port] listener, plain
     * HTTP since TLS ends at the tunnel or Tailscale. Everything sits in a `route` so it runs
     * in the order written: verification first, then the firewall, headers, longest path first.
     */
    fun caddyConfig(sites: List<Site>, firewall: String?, logFile: String, port: Int = 8080): String = buildString {
        append("# Managed by Homedroid; changes here are overwritten. Edit domains in the dashboard.\n\n")
        append("(homedroid-log) {\n\tlog {\n\t\toutput file ").append(quote(logFile)).append(" {\n")
        append("\t\t\troll_size 5MiB\n\t\t\troll_keep 3\n\t\t}\n\t\tformat json\n\t}\n}\n\n")
        if (firewall != null) append("(homedroid-firewall) {\n").append(firewall).append("}\n\n")
        // For the default :8080 site in the user's Caddyfile.
        append("(homedroid-site) {\n\timport homedroid-log\n")
        if (firewall != null) append("\troute {\n\t\timport homedroid-firewall\n\t}\n")
        append("}\n")
        for (s in sites.sortedBy { it.host }) {
            append("\nhttp://").append(s.host).append(':').append(port).append(" {\n\timport homedroid-log\n\troute {\n")
            append("\t\thandle ").append(VERIFY_PATH).append(s.token).append(" {\n")
            append("\t\t\trespond ").append(quote(s.token)).append(" 200\n\t\t}\n")
            if (firewall != null) append("\t\timport homedroid-firewall\n")
            if (s.headers.isNotEmpty()) {
                append("\t\theader {\n\t\t\tdefer\n")
                for ((k, v) in s.headers) {
                    append("\t\t\t").append(k)
                    if (!k.startsWith('-')) append(' ').append(quote(v))
                    append('\n')
                }
                append("\t\t}\n")
            }
            s.routes.sortedByDescending { it.first.length }.forEachIndexed { i, (path, strip, u) ->
                append("\t\t@route").append(i).append(" path ").append(path).append(' ').append(path).append("/*\n")
                append("\t\thandle @route").append(i).append(" {\n")
                if (strip) append("\t\t\turi strip_prefix ").append(path).append('\n')
                upstream(u, "\t\t\t")
                append("\t\t}\n")
            }
            append("\t\thandle {\n")
            upstream(s.target, "\t\t\t")
            append("\t\t}\n\t}\n}\n")
        }
    }

    private val TRUSTED = listOf(
        "\t# Added by Homedroid: the tunnel connects from this phone and names the visitor in a header.",
        "\tservers {",
        "\t\ttrusted_proxies static 127.0.0.1/8 ::1",
        "\t\tclient_ip_headers Cf-Connecting-Ip X-Forwarded-For",
        "\t}",
    )
    private val IMPORT_LINE = Regex("""import\s+(\./)?Caddyfile\.d/\*\.caddy""")
    private val DEFAULT_SITE = Regex("""(http://)?:8080\s*\{""")

    /** [line] without its comment, if any (a # starting a token, outside quotes). */
    private fun code(line: String): String {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"' && (i == 0 || line[i - 1] != '\\')) quoted = !quoted
            if (c == '#' && !quoted && (i == 0 || line[i - 1].isWhitespace())) return line.substring(0, i)
        }
        return line
    }

    /** The line closing the block opened on line [start], or -1 if it never closes. */
    private fun blockEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var opened = false
        for (j in start until lines.size) {
            var quoted = false
            for (c in code(lines[j])) {
                if (c == '"') quoted = !quoted
                if (quoted) continue
                if (c == '{') { depth++; opened = true } else if (c == '}') depth--
            }
            if (opened && depth <= 0) return j
        }
        return -1
    }

    /**
     * Makes the user's Caddyfile use the managed file, touching as little as possible and only
     * once: trusted proxies in the global options (unless it already configures `servers`), the
     * import right after them, and the log and firewall in the default ":8080" site (unless it
     * already logs). A file it can't follow is returned unchanged.
     */
    fun ensureCaddyfile(text: String): String {
        val lines = text.split('\n').toMutableList()
        var i = 0
        while (i < lines.size && (lines[i].isBlank() || lines[i].trim().startsWith("#"))) i++
        var globalEnd: Int
        if (i < lines.size && code(lines[i]).trim() == "{") {
            globalEnd = blockEnd(lines, i)
            if (globalEnd < 0) return text
            val body = lines.subList(i + 1, globalEnd)
            if (body.none { code(it).trim().startsWith("servers") || "trusted_proxies" in code(it) }) {
                lines.addAll(globalEnd, TRUSTED)
                globalEnd += TRUSTED.size
            }
        } else {
            lines.addAll(i, listOf("{") + TRUSTED + listOf("}", ""))
            globalEnd = i + TRUSTED.size + 1
        }
        if (lines.none { IMPORT_LINE.matches(code(it).trim()) }) {
            lines.addAll(globalEnd + 1, listOf("", "# Sites managed by Homedroid (Domains in the dashboard).", IMPORT))
        }
        val open = lines.indexOfFirst { DEFAULT_SITE.matches(code(it).trim()) }
        if (open >= 0) {
            val end = blockEnd(lines, open)
            if (end > open) {
                val block = lines.subList(open + 1, end).map { code(it).trim() }
                if (block.none { "homedroid-site" in it || it == "log" || it.startsWith("log ") || it.startsWith("log{") }) {
                    lines.add(open + 1, "\timport homedroid-site")
                }
            }
        }
        return lines.joinToString("\n")
    }

    /** One Caddy JSON access-log line as {time (ms), host, method, uri, status, duration (ms), size, remote}, or null. */
    fun parseLog(line: String): JSONObject? {
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        val req = o.optJSONObject("request") ?: return null
        if (!o.has("status")) return null
        return JSONObject()
            .put("time", (o.optDouble("ts", 0.0) * 1000).toLong())
            .put("host", req.optString("host").substringBefore(':'))
            .put("method", req.optString("method"))
            .put("uri", req.optString("uri"))
            .put("status", o.optInt("status"))
            .put("duration", Math.round(o.optDouble("duration", 0.0) * 1_000_000) / 1000.0)
            .put("size", o.optLong("size"))
            .put("remote", req.optString("client_ip").ifEmpty { req.optString("remote_ip") })
    }

    /**
     * The newest [limit] entries from log lines (oldest first), newest first, matching [host]
     * (exact, case-insensitive) and [status]: "2xx", "4", "404" and so on.
     */
    fun filterLogs(lines: List<String>, host: String?, status: String?, limit: Int): List<JSONObject> {
        val cls = status?.trim()?.lowercase()?.removeSuffix("xx")?.takeIf { it.isNotEmpty() }
        val out = ArrayList<JSONObject>()
        for (line in lines.asReversed()) {
            if (out.size >= limit) break
            val e = parseLog(line) ?: continue
            if (!host.isNullOrBlank() && !e.getString("host").equals(host.trim(), ignoreCase = true)) continue
            if (cls != null && !e.getInt("status").toString().startsWith(cls)) continue
            out += e
        }
        return out
    }
}

/**
 * Custom domains: each hostname points at a deployment, a catalog app, a folder under ~/www
 * or a local port, served by Caddy on its usual :8080 listener. Stored in domains.json and
 * written to Caddyfile.d/homedroid-domains.caddy, which the user's Caddyfile imports.
 */
class Domains(
    ctx: Context,
    private val paths: Paths,
    private val deploys: Deploys,
    private val apps: Apps,
    private val firewall: () -> List<FirewallCore.Rule>,
) {
    private val file = File(paths.root, "domains.json")
    private val managed = File(paths.etc, "Caddyfile.d/homedroid-domains.caddy")
    val accessLog = File(paths.logs, "caddy-access.log")
    private val rnd = SecureRandom()
    @Volatile private var applyError: String? = null

    @Synchronized private fun load(): MutableList<JSONObject> = try {
        JSONObject(file.readText()).getJSONArray("domains").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    } catch (_: Exception) { mutableListOf() }

    @Synchronized private fun save(list: List<JSONObject>) =
        writeDurably(file, JSONObject().put("domains", JSONArray(list)).toString(2).toByteArray())

    fun handle(r: Request, seg: List<String>): Response = when {
        r.method == "GET" && seg.isEmpty() -> Response.json(JSONObject()
            .put("domains", JSONArray(load()))
            .put("error", applyError ?: JSONObject.NULL)
            .put("config", managed.path))
        r.method == "GET" && seg == listOf("logs") -> logs(r)
        r.method == "POST" && seg.isEmpty() -> upsert(r.json())
        r.method == "DELETE" && seg.size == 1 || r.method == "POST" && seg.size == 2 && seg[1] == "delete" -> delete(seg[0])
        r.method == "POST" && seg.size == 2 && seg[1] == "verify" -> verify(seg[0])
        else -> Response.error(404, "No such domains endpoint")
    }

    private fun upsert(body: JSONObject): Response = synchronized(this) {
        val list = load()
        val previous = load()
        val old = list.firstOrNull { it.optString("id") == body.optString("id") }
        val id = old?.optString("id") ?: hex(6)
        val d = DomainsCore.domain(body, id, old?.optString("token") ?: hex(16), old)
            .getOrElse { return Response.error(400, it.message ?: "Invalid domain") }
        if (list.any { it.optString("host") == d.getString("host") && it.optString("id") != id }) {
            return Response.error(409, "${d.getString("host")} is already set up")
        }
        val i = list.indexOf(old)
        if (i >= 0) list[i] = d else {
            if (list.size >= DomainsCore.MAX_DOMAINS) return Response.error(409, "That's the most domains this phone holds")
            list.add(d)
        }
        save(list)
        apply()?.let { error ->
            save(previous)
            apply()
            return Response.error(409, "Caddy rejected the config: $error")
        }
        Response.json(d)
    }

    private fun delete(id: String): Response = synchronized(this) {
        val list = load()
        if (!list.removeAll { it.optString("id") == id }) return Response.error(404, "No such domain")
        save(list)
        apply()?.let { return Response.error(409, "Caddy rejected the config: $it") }
        Response.ok()
    }

    /** Fetches the verification token through the domain itself, over HTTPS and then HTTP. */
    private fun verify(id: String): Response {
        val d = load().firstOrNull { it.optString("id") == id } ?: return Response.error(404, "No such domain")
        val host = d.getString("host")
        val token = d.getString("token")
        var error = "No answer"
        for (scheme in listOf("https", "http")) {
            try {
                val conn = (URL("$scheme://$host${DomainsCore.VERIFY_PATH}$token").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Homedroid")
                }
                try {
                    val code = conn.responseCode
                    val body = if (code == 200) conn.inputStream.use { String(it.readBytes().take(256).toByteArray()) }.trim() else ""
                    if (body == token) { error = ""; break }
                    error = if (code == 200) "$scheme://$host answered, but not with this phone's token" else "$scheme://$host answered $code"
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                error = "$scheme://$host: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        synchronized(this) {
            val list = load()
            val cur = list.firstOrNull { it.optString("id") == id } ?: return Response.error(404, "No such domain")
            if (error.isEmpty()) cur.put("verified", true).put("verifiedAt", System.currentTimeMillis()).put("verifyError", JSONObject.NULL)
            else cur.put("verified", false).put("verifyError", error)
            save(list)
            return Response.json(cur)
        }
    }

    private fun logs(r: Request): Response {
        val limit = (r.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        val lines = tail(accessLog, 2 shl 20)
        return Response.json(JSONObject().put("entries", JSONArray(DomainsCore.filterLogs(lines, r.query["host"], r.query["status"], limit))))
    }

    /** The complete lines in the last [bytes] of [f]. */
    private fun tail(f: File, bytes: Int): List<String> = try {
        RandomAccessFile(f, "r").use { raf ->
            val start = maxOf(0L, raf.length() - bytes)
            val buf = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            String(buf).split('\n').let { if (start > 0) it.drop(1) else it }.filter { it.isNotBlank() }
        }
    } catch (_: Exception) { emptyList() }

    private fun resolve(t: JSONObject): Upstream = when (t.optString("type")) {
        "deploy" -> deploys.get(t.optString("id"))?.let { Upstream.Proxy(it.port) } ?: Upstream.Missing("The deployment ${t.optString("id")} no longer exists")
        "app" -> apps.catalog.firstOrNull { it.id == t.optString("id") }?.let { Upstream.Proxy(it.port) } ?: Upstream.Missing("Unknown app")
        "static" -> Upstream.Static(File(paths.www, t.optString("path")).path)
        "port" -> Upstream.Proxy(t.optInt("port"))
        else -> Upstream.Missing("No target")
    }

    /**
     * Writes the managed config and makes the Caddyfile import it, then has Caddy load it. If
     * Caddy rejects it, both files are put back and the error returned. A no-op when nothing
     * changed, so it is cheap to call at startup. Called by [Firewall] for web rules too.
     */
    @Synchronized fun apply(): String? {
        val sites = load().map { d ->
            val h = d.optJSONObject("headers") ?: JSONObject()
            val rs = d.optJSONArray("routes") ?: JSONArray()
            Site(
                d.getString("host"), d.getString("token"),
                h.keys().asSequence().associateWith { h.getString(it) },
                (0 until rs.length()).map { rs.getJSONObject(it) }.map { Triple(it.getString("path"), it.optBoolean("strip"), resolve(it.getJSONObject("target"))) },
                resolve(d.getJSONObject("target")),
            )
        }
        val config = DomainsCore.caddyConfig(sites, FirewallCore.caddyRules(firewall()), accessLog.path)
        val oldConfig = managed.takeIf { it.exists() }?.readText()
        val oldCaddyfile = paths.caddyfile.takeIf { it.exists() }?.readText() ?: return "There's no Caddyfile yet"
        val caddyfile = DomainsCore.ensureCaddyfile(oldCaddyfile)
        if (config == oldConfig && caddyfile == oldCaddyfile) return null
        managed.parentFile?.mkdirs()
        if (!writeDurably(managed, config.toByteArray()) || !writeDurably(paths.caddyfile, caddyfile.toByteArray())) return "Couldn't write the Caddy config"
        val error = loadIntoCaddy(caddyfile != oldCaddyfile)
        if (error != null) {
            if (oldConfig != null) writeDurably(managed, oldConfig.toByteArray()) else managed.delete()
            writeDurably(paths.caddyfile, oldCaddyfile.toByteArray())
        }
        applyError = error
        return error
    }

    /**
     * Loads the config into the running Caddy (`caddy reload`, through its admin API) or, if
     * Caddy is off, just checks it (`caddy adapt`). If the admin API is off but the config is
     * fine, Caddy is restarted instead.
     */
    private fun loadIntoCaddy(caddyfileChanged: Boolean): String? {
        val caddy = ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == "caddy" }
        val running = caddy != null && caddy.state == Supervisor.State.RUNNING
        if (running && caddy(listOf("reload", "--config", paths.caddyfile.path, "--adapter", "caddyfile")) == null) return null
        val error = caddy(listOf("adapt", "--config", paths.caddyfile.path, "--adapter", "caddyfile"))
        if (error == null && running) caddy!!.restart()
        Jobs.line(if (error == null) "Domains: Caddy config updated${if (caddyfileChanged) " (Caddyfile now imports Caddyfile.d)" else ""}" else "Domains: Caddy rejected the config: $error")
        return error
    }

    /** Runs the caddy binary; null on success, else the last thing it printed. */
    private fun caddy(args: List<String>): String? = try {
        val pb = ProcessBuilder(listOf(paths.exe("caddy")) + args).directory(paths.home).redirectErrorStream(true)
        pb.environment().putAll(paths.env())
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { if (out.length < 8192) out.append(it).append('\n') } }.apply { isDaemon = true; start() }
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); "Caddy took too long" }
        else {
            reader.join(1000)
            // adapt prints the JSON config on success, so only read the output on failure.
            if (p.exitValue() == 0) null
            else out.lines().lastOrNull { it.isNotBlank() }?.let { Regex("\"msg\":\"([^\"]*)\".*\"error\":\"([^\"]*)\"").find(it)?.let { m -> "${m.groupValues[1]}: ${m.groupValues[2]}" } ?: it.take(500) } ?: "caddy failed"
        }
    } catch (e: Exception) { e.message ?: "Couldn't run caddy" }

    /** Refreshes the managed config in the background, if the feature has been used. */
    fun start() {
        if (!managed.exists() && !file.exists()) return
        Thread({ runCatching { apply() } }, "domains-apply").apply { isDaemon = true; start() }
    }

    private fun hex(n: Int) = ByteArray(n).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
}
