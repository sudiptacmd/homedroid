package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The built-in MCP server, so AI assistants (Claude, Cursor, VS Code…) can check on and manage
 * the phone; see docs/guides/mcp.md. Served by the dashboard's HTTP server:
 *
 * - Streamable HTTP: JSON-RPC POSTed to /mcp, answered as application/json.
 * - The older HTTP+SSE transport: GET /mcp/sse opens an event stream whose first event names
 *   /mcp/messages?sessionId=…, where the client POSTs; answers come back on the stream.
 *
 * Off by default. Clients use their own token ("Authorization: Bearer"), not the dashboard
 * password. Tools that change things are offered only with "allow write". Every call goes to
 * files/logs/mcp-audit.jsonl. Tools run through the dashboard's own API ([api]), so they get
 * its checks.
 *
 * Also the dashboard's agent: a chat with the AI module's models that can use the same tools,
 * running read-only ones at once and holding write ones until the user approves them.
 */
class Mcp(
    private val ctx: Context,
    private val cfg: Config,
    private val files: FileBrowser,
    private val ai: Ai,
    private val api: (Request) -> Response,
) {
    // Wrong tokens count like wrong passwords: a cooldown per address.
    private val guard = AuthSecurity()
    private val audit = File(Paths(ctx).logs, "mcp-audit.jsonl")
    private val sessions = ConcurrentHashMap<String, Session>()
    private val pending = LinkedHashMap<String, Pending>()
    private val rnd = SecureRandom()

    private class Session(val token: String) {
        val queue = LinkedBlockingQueue<String>(64)
    }

    private class Pending(val id: String, val tool: McpCore.Tool, val args: JSONObject, val created: Long = System.currentTimeMillis()) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", tool.name).put("title", tool.title)
            .put("args", args).put("created", created)
    }

    // --- the MCP endpoints ------------------------------------------------------------------

    fun handle(r: Request): Response {
        if (!cfg.mcpEnabled) return Response.error(404, "The MCP server is off; turn it on in the dashboard")
        // The origin checks alone (GET needs no custom header): stops DNS rebinding and other sites.
        if (!AuthSecurity.browserAllowed("GET", r.headers)) return Response.error(403, "Cross-origin requests are not allowed")
        val given = AuthSecurity.bearer(r.headers) ?: return unauthorized("Send the MCP token as: Authorization: Bearer <token>")
        val result = guard.password(r.remote) { McpCore.tokenMatches(given, cfg.mcpToken) }
        if (!result.accepted) {
            return if (result.retrySeconds > 0) Response.json(JSONObject().put("error", "Too many wrong tokens; try again in ${result.retrySeconds} seconds"),
                429, mapOf("Retry-After" to result.retrySeconds.toString()))
            else unauthorized("Wrong MCP token")
        }
        r.headers["mcp-protocol-version"]?.let { if (it !in McpCore.VERSIONS) return Response.error(400, "Unsupported MCP protocol version $it") }
        return when {
            r.path == "/mcp" && r.method == "POST" -> reply(server(r.remote).handle(String(r.body, Charsets.UTF_8)))
            r.path == "/mcp" -> Response(405, ByteArray(0), headers = mapOf("Allow" to "POST"))
            r.path == "/mcp/sse" && r.method == "GET" -> openStream(given)
            r.path == "/mcp/messages" && r.method == "POST" -> {
                val s = sessions[r.query["sessionId"].orEmpty()]?.takeIf { McpCore.tokenMatches(given, it.token) }
                    ?: return Response.error(404, "No such session; open /mcp/sse again")
                server(r.remote).handle(String(r.body, Charsets.UTF_8))?.let {
                    if (!s.queue.offer(it, 5, TimeUnit.SECONDS)) return Response.error(503, "The event stream isn't being read")
                }
                Response(202, ByteArray(0), "text/plain")
            }
            else -> Response.error(404, "Use POST /mcp, or GET /mcp/sse for the older SSE transport")
        }
    }

    private fun unauthorized(message: String) =
        Response.json(JSONObject().put("error", message), 401, mapOf("WWW-Authenticate" to "Bearer realm=\"homedroid-mcp\""))

    private fun reply(text: String?) = if (text == null) Response(202, ByteArray(0), "text/plain") else Response(200, text.toByteArray())

    private fun server(client: String) = McpCore.Server(
        version(),
        tools = { McpCore.tools(cfg.mcpAllowWrite) },
        run = { tool, args -> runTool(tool, args, client, "mcp") },
        rejected = { name, args, why -> record(client, "mcp", name, args, why) },
    )

    /** The legacy SSE stream: an "endpoint" event, then answers as "message" events, with keep-alives. */
    private fun openStream(token: String): Response {
        if (sessions.size >= MAX_STREAMS) return Response.error(429, "Too many open MCP streams")
        val id = ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        val session = Session(token)
        sessions[id] = session
        return Response(200, ByteArray(0), "text/event-stream", mapOf("Cache-Control" to "no-cache", "X-Accel-Buffering" to "no"), stream = { out ->
            try {
                out.write("event: endpoint\ndata: /mcp/messages?sessionId=$id\n\n".toByteArray())
                out.flush()
                // Ends when the client goes away (a write fails), MCP is turned off or the token changes.
                while (cfg.mcpEnabled && McpCore.tokenMatches(session.token, cfg.mcpToken)) {
                    val message = session.queue.poll(15, TimeUnit.SECONDS)
                    out.write((if (message != null) "event: message\ndata: $message\n\n" else ": keep-alive\n\n").toByteArray())
                    out.flush()
                }
            } finally { sessions.remove(id) }
        })
    }

    private fun version() = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // --- the dashboard's API: /api/mcp/… ------------------------------------------------------

    fun api(r: Request, seg: List<String>, fromPeer: Boolean): Response {
        val body by lazy { if (r.method == "POST") r.json() else JSONObject() }
        return when {
            seg == listOf("settings") && r.method == "GET" -> Response.json(settings())
            seg == listOf("settings") && r.method == "POST" -> {
                for (k in listOf("enabled", "allowWrite")) if (body.has(k) && body.opt(k) !is Boolean) return Response.error(400, "$k must be true or false")
                if (body.has("enabled")) cfg.mcpEnabled = body.getBoolean("enabled")
                if (body.has("allowWrite")) cfg.mcpAllowWrite = body.getBoolean("allowWrite")
                Response.json(settings())
            }
            // The token is for this dashboard's user, not for other phones in the cluster.
            seg == listOf("token") && fromPeer -> Response.error(403, "Only this phone's dashboard can see its MCP token")
            seg == listOf("token") && r.method == "GET" -> Response.json(JSONObject().put("token", cfg.mcpToken))
            seg == listOf("token") && r.method == "POST" -> {
                cfg.mcpToken = McpCore.newToken()
                Response.json(JSONObject().put("token", cfg.mcpToken))
            }
            seg == listOf("audit") && r.method == "GET" ->
                Response.json(JSONObject().put("entries", auditTail((r.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000))))
            seg == listOf("agent") && r.method == "POST" -> agent(body, r.remote)
            seg == listOf("agent", "pending") && r.method == "GET" ->
                Response.json(JSONObject().put("pending", JSONArray().apply { pendingList().forEach { put(it.json()) } }))
            seg == listOf("agent", "approve") && r.method == "POST" -> {
                val p = takePending(body.optString("id")) ?: return Response.error(404, "That action is gone; ask the agent again")
                try {
                    Response.json(JSONObject().put("ok", true).put("id", p.id).put("name", p.tool.name)
                        .put("result", runTool(p.tool, p.args, r.remote, "agent (approved)")))
                } catch (e: Exception) {
                    Response.json(JSONObject().put("ok", false).put("id", p.id).put("name", p.tool.name).put("error", e.message ?: "Failed"))
                }
            }
            seg == listOf("agent", "reject") && r.method == "POST" -> {
                val p = takePending(body.optString("id")) ?: return Response.error(404, "That action is gone")
                record(r.remote, "agent", p.tool.name, p.args, "rejected by the user")
                Response.ok()
            }
            else -> Response.error(404, "No such MCP endpoint")
        }
    }

    private fun settings(): JSONObject {
        val port = cfg.dashboardPort
        val hosts = Device(ctx).ips.map { if (':' in it) "[$it]" else it }
        return JSONObject()
            .put("enabled", cfg.mcpEnabled)
            .put("allowWrite", cfg.mcpAllowWrite)
            .put("versions", JSONArray(McpCore.VERSIONS))
            .put("urls", JSONArray(hosts.map { "http://$it:$port/mcp" }))
            .put("sseUrls", JSONArray(hosts.map { "http://$it:$port/mcp/sse" }))
            .put("tailscaleUrl", if (cfg.tailscaleEnabled && cfg.tailscaleServe != "off") "https://${cfg.tailscaleHostname}.<your-tailnet>.ts.net/mcp" else JSONObject.NULL)
            .put("tools", JSONArray().apply { McpCore.TOOLS.forEach { put(JSONObject().put("name", it.name).put("title", it.title).put("write", it.write)) } })
    }

    // --- tools ------------------------------------------------------------------------------

    /** Runs [tool] and writes the audit line; throws when it fails. */
    fun runTool(tool: McpCore.Tool, args: JSONObject, client: String, via: String): String = try {
        exec(tool, args).also { record(client, via, tool.name, args, null) }
    } catch (e: Exception) {
        record(client, via, tool.name, args, e.message ?: e.javaClass.simpleName)
        throw e
    }

    private fun exec(tool: McpCore.Tool, a: JSONObject): String = when (tool.name) {
        "get_status" -> call("GET", "/api/status")
        "list_services" -> call("GET", "/api/services")
        "get_service_logs" -> {
            val o = JSONObject(call("GET", "/api/services/${a.getString("name")}/logs"))
            val lines = o.optJSONArray("lines") ?: JSONArray()
            val n = a.optInt("lines", 50)
            (maxOf(0, lines.length() - n) until lines.length()).joinToString("\n") { lines.getString(it) }.ifEmpty { "(no output yet)" }
        }
        "restart_service" -> call("POST", "/api/services/${a.getString("name")}/restart").let { "Restarted ${a.getString("name")}" }
        "list_modules" -> {
            val o = JSONObject(call("GET", "/api/modules"))
            JSONArray().apply {
                for (group in listOf("core", "apps")) o.optJSONArray(group)?.let { list ->
                    for (i in 0 until list.length()) {
                        val m = list.getJSONObject(i)
                        put(JSONObject().put("id", m.optString("id")).put("name", m.optString("name")).put("kind", m.optString("kind"))
                            .put("installed", m.optBoolean("installed")).put("enabled", m.optBoolean("enabled"))
                            .put("state", m.optString("state")).put("port", m.optInt("port")))
                    }
                }
            }.toString()
        }
        "list_deployments" -> call("GET", "/api/deploys")
        "redeploy" -> call("POST", "/api/deploys/${a.getString("id")}/redeploy").let { "Redeploying ${a.getString("id")}; check list_deployments for progress" }
        "list_files" -> {
            val root = a.optString("root")
            if (root.isEmpty()) call("GET", "/api/files") else call("GET", "/api/files/$root", mapOf("path" to a.optString("path")))
        }
        "read_text_file" -> readText(a.getString("root"), a.getString("path"), a.optInt("maxBytes", 65536))
        "wake_on_lan" -> wake(a.getString("device"))
        "network_info" -> networkInfo().toString()
        else -> throw McpCore.ToolError("Unknown tool ${tool.name}")
    }

    /** A request to the dashboard's API, as the logged-in user would make it. */
    private fun call(method: String, path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): String {
        val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
        val res = api(Request(method, path, query, mapOf("host" to "localhost"), ByteArrayInputStream(bytes), bytes.size.toLong(), "mcp"))
        val text = res.stream?.let { s -> ByteArrayOutputStream().also(s).toString("UTF-8") } ?: String(res.body, Charsets.UTF_8)
        if (res.status >= 400) throw McpCore.ToolError(try { JSONObject(text).optString("error") } catch (_: Exception) { "" }.ifEmpty { "Failed (HTTP ${res.status})" })
        return text
    }

    /** The start of a text file, with the file browser's rules for what is inside a location. */
    private fun readText(rootId: String, path: String, max: Int): String {
        val root = files.roots().firstOrNull { it.id == rootId } ?: throw McpCore.ToolError("No location $rootId; see list_files")
        val f = files.resolve(root, path, follow = true) ?: throw McpCore.ToolError("That path is outside ${root.label}")
        if (!f.isFile) throw McpCore.ToolError("No file $path")
        val bytes = f.inputStream().use { input ->
            val buf = ByteArray(minOf(max.toLong(), f.length()).toInt())
            var n = 0
            while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
            buf.copyOf(n)
        }
        if (bytes.contains(0)) throw McpCore.ToolError("$path looks like a binary file")
        val text = String(bytes, Charsets.UTF_8)
        return if (f.length() > bytes.size) "$text\n[showing the first ${bytes.size} of ${f.length()} bytes]" else text
    }

    private fun wake(device: String): String {
        if (!cfg.wolEnabled) throw McpCore.ToolError("Wake on LAN is off; turn it on in Modules")
        val list = try { JSONArray(File(ctx.filesDir, "wol.json").readText()) } catch (_: Exception) { JSONArray() }
        val d = (0 until list.length()).map { list.getJSONObject(it) }
            .firstOrNull { it.optString("id") == device || it.optString("name").equals(device.trim(), ignoreCase = true) }
            ?: throw McpCore.ToolError("No saved device called $device")
        call("POST", "/api/wol/wake", body = JSONObject().put("id", d.getString("id")))
        return "Sent the wake packet to ${d.optString("name")} (${d.optString("mac")})"
    }

    private fun networkInfo(): JSONObject {
        val interfaces = JSONArray()
        try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { n ->
                val addrs = n.inetAddresses.toList().filter { !(it is Inet6Address && it.isLinkLocalAddress) }.mapNotNull { it.hostAddress?.substringBefore('%') }
                if (addrs.isNotEmpty()) interfaces.put(JSONObject().put("name", n.name).put("addresses", JSONArray(addrs)))
            }
        } catch (_: Exception) {}
        return JSONObject()
            .put("interfaces", interfaces)
            .put("ips", JSONArray(Device(ctx).ips))
            .put("dashboardPort", cfg.dashboardPort).put("sshPort", cfg.sshPort).put("webPort", cfg.webPort)
            .put("tailscale", JSONObject().put("enabled", cfg.tailscaleEnabled).put("hostname", cfg.tailscaleHostname).put("serve", cfg.tailscaleServe))
            .put("cloudflareTunnel", cfg.tunnelToken.isNotEmpty() && !cfg.isDisabled("tunnel"))
    }

    // --- the audit log ----------------------------------------------------------------------

    @Synchronized private fun record(client: String, via: String, tool: String, args: JSONObject, error: String?) {
        try {
            audit.parentFile?.mkdirs()
            if (audit.length() > AUDIT_BYTES) audit.renameTo(File(audit.path + ".1"))
            val line = JSONObject().put("time", System.currentTimeMillis()).put("client", client).put("via", via).put("tool", tool)
                .put("args", McpCore.summarize(args)).put("ok", error == null)
                .apply { error?.let { put("error", it.take(300)) } }
            audit.appendText(line.toString() + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized private fun auditTail(limit: Int): JSONArray = JSONArray().apply {
        val lines = try { audit.readLines() } catch (_: Exception) { emptyList() }
        lines.takeLast(limit).forEach { l -> try { put(JSONObject(l)) } catch (_: Exception) {} }
    }

    // --- the agent --------------------------------------------------------------------------

    private fun agent(body: JSONObject, remote: String): Response {
        val message = body.optString("message").trim()
        if (message.isEmpty() || message.length > 8000) return Response.error(400, "Write a message (up to 8000 characters)")
        val models = ai.models()
        val model = body.optString("model").ifEmpty { if (models.length() > 0) models.getJSONObject(0).getString("id") else "" }
        if (model.isEmpty()) return Response.error(409, "Set up a model in AI first: one on this phone or a cloud service")
        val target = try { ai.target(model) } catch (e: IllegalArgumentException) {
            return Response.error(404, e.message ?: "Unknown model")
        } catch (e: IllegalStateException) { return Response.error(503, e.message ?: "The model isn't available") }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", AGENT_PROMPT))
        body.optJSONArray("history")?.let { h ->
            for (i in maxOf(0, h.length() - 20) until h.length()) {
                val m = h.optJSONObject(i) ?: continue
                val role = m.optString("role")
                val content = m.opt("content") as? String ?: continue
                if (role in setOf("user", "assistant")) messages.put(JSONObject().put("role", role).put("content", content.take(4000)))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", message))
        val tools = McpCore.TOOLS
        val out = try {
            McpCore.agent(messages, tools,
                complete = { msgs ->
                    ai.complete(target, JSONObject().put("messages", msgs).put("tools", McpCore.openAiTools(tools))
                        .put("tool_choice", "auto").put("stream", false).put("max_tokens", 800).put("temperature", 0.2))
                },
                execute = { tool, args -> runTool(tool, args, remote, "agent") },
                queue = { tool, args -> addPending(tool, args) },
            )
        } catch (e: Exception) {
            return Response.error(502, e.message ?: "The model failed")
        }
        return Response.json(out.put("model", target.id).put("where", if (target.local) "local" else "cloud")
            .put("pending", JSONArray().apply { pendingList().forEach { put(it.json()) } }))
    }

    private fun addPending(tool: McpCore.Tool, args: JSONObject): String = synchronized(pending) {
        prune()
        while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
        val id = ByteArray(8).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        pending[id] = Pending(id, tool, args)
        id
    }

    private fun takePending(id: String): Pending? = synchronized(pending) { prune(); pending.remove(id) }

    private fun pendingList(): List<Pending> = synchronized(pending) { prune(); pending.values.toList() }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - PENDING_MS
        pending.values.filter { it.created < cutoff }.forEach { pending.remove(it.id) }
    }

    companion object {
        private const val MAX_STREAMS = 4
        private const val MAX_PENDING = 20
        private const val PENDING_MS = 3600_000L
        private const val AUDIT_BYTES = 512 * 1024L
        private const val AGENT_PROMPT = "You are the assistant of Homedroid, a home server running on an Android phone. " +
            "Use the tools to look things up instead of guessing, and keep answers short. Tools that change something " +
            "(restarts, redeploys, wake on LAN) only run after the user approves them in the dashboard: say what you queued."
    }
}
