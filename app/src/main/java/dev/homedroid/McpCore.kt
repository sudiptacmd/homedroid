package dev.homedroid

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Model Context Protocol, kept free of Android so it can be tested: JSON-RPC 2.0 framing,
 * version negotiation, the tool list with JSON schemas, and argument checks. [Mcp] runs the
 * tools and serves this over HTTP.
 */
object McpCore {
    /** Newest first: offered when a client asks for a version we don't know. */
    val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    fun negotiate(requested: String?): String = requested?.takeIf { it in VERSIONS } ?: VERSIONS[0]

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun newToken() = "hdm_" + ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    fun tokenMatches(given: String?, expected: String) =
        given != null && expected.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

    class Tool(val name: String, val title: String, val description: String, val schema: JSONObject, val write: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("name", name).put("title", title).put("description", description)
            .put("inputSchema", schema)
            .put("annotations", JSONObject().put("title", title).put("readOnlyHint", !write)
                .put("destructiveHint", write).put("openWorldHint", false))
    }

    /** A tool failure the model should see, as opposed to a protocol error. */
    class ToolError(message: String) : Exception(message)

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", JSONArray(required))
        .put("additionalProperties", false)

    private fun string(description: String, pattern: String? = null, maxLength: Int = 256) = JSONObject()
        .put("type", "string").put("description", description).put("maxLength", maxLength)
        .apply { pattern?.let { put("pattern", it) } }

    private fun integer(description: String, min: Int, max: Int, default: Int) = JSONObject()
        .put("type", "integer").put("description", description).put("minimum", min).put("maximum", max).put("default", default)

    private const val NAME = "^[A-Za-z0-9._-]{1,64}$"

    val TOOLS = listOf(
        Tool("get_status", "Phone status", "Battery, temperature, memory, storage, CPU load, uptime and addresses of the Homedroid phone.", schema()),
        Tool("list_services", "List services", "Every service the supervisor runs, with its state and restart count.", schema()),
        Tool("get_service_logs", "Service logs", "The last lines a service wrote.", schema(
            "name" to string("Service name, from list_services", NAME, 64),
            "lines" to integer("How many lines", 1, 200, 50),
            required = listOf("name"))),
        Tool("restart_service", "Restart service", "Restarts one running service.", schema(
            "name" to string("Service name, from list_services", NAME, 64), required = listOf("name")), write = true),
        Tool("list_modules", "List modules", "Built-in modules and catalog apps: installed, enabled and their state.", schema()),
        Tool("list_deployments", "List deployments", "Git deployments with their state, branch and last commit.", schema()),
        Tool("redeploy", "Redeploy", "Pulls and rebuilds one deployment.", schema(
            "id" to string("Deployment id, from list_deployments", "^[a-z0-9-]{1,64}$", 64), required = listOf("id")), write = true),
        Tool("list_files", "List files", "Lists a folder in one of the file browser's locations. Without root, lists the locations.", schema(
            "root" to string("Location id, from list_files without arguments", "^[a-z0-9]{1,40}$", 40),
            "path" to string("Folder inside the location, like \"www\"; empty for its top", maxLength = 1024))),
        Tool("read_text_file", "Read text file", "Reads the start of a text file in one of the file browser's locations.", schema(
            "root" to string("Location id, from list_files", "^[a-z0-9]{1,40}$", 40),
            "path" to string("File inside the location", maxLength = 1024),
            "maxBytes" to integer("Most bytes to return", 1, 262144, 65536),
            required = listOf("root", "path"))),
        Tool("wake_on_lan", "Wake on LAN", "Wakes a saved Wake on LAN device by its name or id.", schema(
            "device" to string("Device name or id, as saved in Wake on LAN", maxLength = 64), required = listOf("device")), write = true),
        Tool("network_info", "Network info", "The phone's network interfaces and addresses, and how the dashboard is reachable (Tailscale, Cloudflare Tunnel).", schema()),
    )

    /** Tools a client may see and call. */
    fun tools(allowWrite: Boolean) = TOOLS.filter { allowWrite || !it.write }

    /** Tool definitions in OpenAI's function-calling format, for the agent. */
    fun openAiTools(tools: List<Tool>) = JSONArray().apply {
        tools.forEach { t ->
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.name).put("description", t.description + if (t.write) " Needs the user's approval." else "")
                .put("parameters", t.schema)))
        }
    }

    /**
     * Checks [args] against the subset of JSON Schema the tools use: object, required,
     * additionalProperties, string/integer/number/boolean, pattern, lengths, ranges and enum.
     * Returns an error message, or null when they fit.
     */
    fun validate(schema: JSONObject, args: JSONObject): String? {
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) if (!args.has(required.getString(i)) || args.isNull(required.getString(i))) {
            return "Missing argument: ${required.getString(i)}"
        }
        for (key in args.keys()) {
            val p = props.optJSONObject(key)
            if (p == null) {
                if (schema.optBoolean("additionalProperties", true)) continue
                return "Unknown argument: $key"
            }
            val v = args.get(key)
            when (p.optString("type")) {
                "string" -> {
                    if (v !is String) return "$key must be a string"
                    if (p.has("maxLength") && v.length > p.getInt("maxLength")) return "$key is too long"
                    if (p.has("minLength") && v.length < p.getInt("minLength")) return "$key is too short"
                    if (p.has("pattern") && !Regex(p.getString("pattern")).containsMatchIn(v)) return "$key has an invalid format"
                }
                "integer", "number" -> {
                    val n = (v as? Number)?.toDouble() ?: return "$key must be a number"
                    if (p.optString("type") == "integer" && (n % 1.0 != 0.0 || n.isInfinite())) return "$key must be a whole number"
                    if (p.has("minimum") && n < p.getDouble("minimum")) return "$key must be at least ${p.get("minimum")}"
                    if (p.has("maximum") && n > p.getDouble("maximum")) return "$key must be at most ${p.get("maximum")}"
                }
                "boolean" -> if (v !is Boolean) return "$key must be true or false"
            }
            p.optJSONArray("enum")?.let { e -> if ((0 until e.length()).none { e.get(it) == v }) return "$key must be one of $e" }
        }
        return null
    }

    /** Arguments for the audit log: short, with long strings cut. */
    fun summarize(args: JSONObject): String {
        val o = JSONObject()
        for (k in args.keys()) {
            val v = args.get(k)
            o.put(k, if (v is String && v.length > 80) v.take(80) + "…" else if (v is JSONObject || v is JSONArray) "…" else v)
        }
        return o.toString().let { if (it.length > 300) it.take(300) + "…" else it }
    }

    const val AGENT_ROUNDS = 5
    private const val TOOL_OUTPUT = 6000

    /**
     * The agent loop: asks the model ([complete] takes the messages and returns a chat
     * completion) and runs the tools it calls, up to [rounds] times. Read-only tools run at
     * once through [execute]; write tools go to [queue], which returns the pending action's id,
     * and the model is told they wait for the user. Returns {reply, toolCalls}.
     */
    fun agent(
        messages: JSONArray,
        tools: List<Tool>,
        complete: (JSONArray) -> JSONObject,
        execute: (Tool, JSONObject) -> String,
        queue: (Tool, JSONObject) -> String,
        rounds: Int = AGENT_ROUNDS,
    ): JSONObject {
        val calls = JSONArray()
        var reply: String? = null
        for (round in 0 until rounds) {
            val message = complete(messages).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("The model sent no answer")
            val content = message.opt("content") as? String
            val requested = message.optJSONArray("tool_calls")
            if (requested == null || requested.length() == 0) { reply = content?.trim().orEmpty(); break }
            messages.put(JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL).put("tool_calls", requested))
            for (i in 0 until requested.length()) {
                val c = requested.optJSONObject(i) ?: continue
                val id = c.optString("id").ifEmpty { "call_${round}_$i" }.also { c.put("id", it) }
                val fn = c.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val record = JSONObject().put("name", name)
                val args = try {
                    when (val raw = fn.opt("arguments")) { is JSONObject -> raw; is String -> JSONObject(raw.ifBlank { "{}" }); else -> JSONObject() }
                } catch (_: JSONException) { null }
                val output = if (args == null) "Error: the arguments aren't valid JSON".also { record.put("error", it) } else try {
                    record.put("args", args)
                    val tool = tools.firstOrNull { it.name == name } ?: throw ToolError("There is no tool called $name")
                    validate(tool.schema, args)?.let { throw ToolError(it) }
                    if (tool.write) {
                        val pending = queue(tool, args)
                        record.put("pending", true).put("id", pending)
                        "Not run yet: this action waits for the user's approval in the dashboard (action $pending). Tell the user."
                    } else execute(tool, args).also { record.put("result", it.take(TOOL_OUTPUT)) }
                } catch (e: Exception) {
                    "Error: ${e.message ?: e.javaClass.simpleName}".also { record.put("error", it) }
                }
                calls.put(record)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", output.take(TOOL_OUTPUT)))
            }
        }
        return JSONObject().put("reply", reply ?: "I stopped after $rounds rounds of tool calls; ask again to continue.").put("toolCalls", calls)
    }

    fun error(id: Any?, code: Int, message: String): JSONObject = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message))

    private fun result(id: Any, result: JSONObject): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

    fun textResult(text: String, isError: Boolean) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isError)

    /**
     * Answers JSON-RPC messages. [run] executes a tool whose arguments already passed the
     * schema; it throws [ToolError] (or anything else) when the tool fails.
     */
    class Server(
        private val serverVersion: String,
        private val tools: () -> List<Tool>,
        private val run: (Tool, JSONObject) -> String,
        /** Called for a call refused before running (unknown tool, bad arguments), for the audit log. */
        private val rejected: (String, JSONObject, String) -> Unit = { _, _, _ -> },
    ) {
        /** A POST body: one message or a batch. Returns the reply, or null when none is due (HTTP 202). */
        fun handle(body: String): String? {
            val parsed = try {
                val t = body.trim()
                if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
            } catch (_: JSONException) {
                return error(null, PARSE_ERROR, "Parse error").toString()
            }
            if (parsed is JSONArray) {
                if (parsed.length() == 0) return error(null, INVALID_REQUEST, "Empty batch").toString()
                val replies = JSONArray()
                for (i in 0 until parsed.length()) message(parsed.get(i))?.let(replies::put)
                return if (replies.length() == 0) null else replies.toString()
            }
            return message(parsed)?.toString()
        }

        /** One message; null for notifications and for responses the client sends us. */
        fun message(m: Any?): JSONObject? {
            if (m !is JSONObject || m.optString("jsonrpc") != "2.0") return error(idOf(m), INVALID_REQUEST, "Invalid request")
            if (!m.has("method")) return if (m.has("result") || m.has("error")) null else error(idOf(m), INVALID_REQUEST, "Invalid request")
            val method = m.opt("method") as? String ?: return error(idOf(m), INVALID_REQUEST, "Invalid request")
            if (!m.has("id")) return null // a notification, such as notifications/initialized
            val id = idOf(m) ?: return error(null, INVALID_REQUEST, "The id must be a string or a number")
            val params = m.opt("params").let { if (it == null || it == JSONObject.NULL) JSONObject() else it as? JSONObject }
                ?: return error(id, INVALID_PARAMS, "params must be an object")
            return try {
                when (method) {
                    "initialize" -> result(id, JSONObject()
                        .put("protocolVersion", negotiate(params.opt("protocolVersion") as? String))
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "homedroid").put("title", "Homedroid").put("version", serverVersion))
                        .put("instructions", "Tools to check on and manage a Homedroid home server running on an Android phone."))
                    "ping" -> result(id, JSONObject())
                    "tools/list" -> result(id, JSONObject().put("tools", JSONArray().apply { tools().forEach { put(it.json()) } }))
                    "tools/call" -> call(id, params)
                    else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
                }
            } catch (_: Exception) {
                error(id, INTERNAL_ERROR, "Internal error")
            }
        }

        private fun call(id: Any, params: JSONObject): JSONObject {
            val name = params.opt("name") as? String ?: return error(id, INVALID_PARAMS, "Missing tool name")
            val args = params.opt("arguments").let { if (it == null || it == JSONObject.NULL) JSONObject() else it as? JSONObject }
                ?: return error(id, INVALID_PARAMS, "arguments must be an object")
            val tool = tools().firstOrNull { it.name == name }
                ?: return error(id, INVALID_PARAMS, "Unknown tool: $name").also { rejected(name.take(64), args, "unknown tool") }
            validate(tool.schema, args)?.let {
                rejected(name, args, it)
                return result(id, textResult(it, true))
            }
            val out = try {
                textResult(run(tool, args), false)
            } catch (e: ToolError) {
                textResult(e.message ?: "Failed", true)
            } catch (e: Exception) {
                textResult("Failed: ${e.message ?: e.javaClass.simpleName}", true)
            }
            return result(id, out)
        }

        private fun idOf(m: Any?): Any? = (m as? JSONObject)?.opt("id")?.takeIf { it is String || it is Number }
    }
}
