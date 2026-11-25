package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpCoreTest {
    private val ran = mutableListOf<String>()
    private val rejected = mutableListOf<String>()
    private fun server(write: Boolean = false) = McpCore.Server("1.0", { McpCore.tools(write) }, { t, a ->
        ran += t.name
        if (t.name == "get_service_logs" && a.getString("name") == "boom") throw McpCore.ToolError("no such service")
        "ok:${t.name}"
    }, { name, _, why -> rejected += "$name: $why" })

    private fun req(id: Any?, method: String, params: JSONObject? = null) = JSONObject().put("jsonrpc", "2.0").put("method", method)
        .apply { if (id != null) put("id", id); if (params != null) put("params", params) }

    private fun send(s: McpCore.Server, m: JSONObject) = JSONObject(s.handle(m.toString())!!)

    @Test fun negotiatesKnownVersionsAndFallsBackToNewest() {
        for (v in McpCore.VERSIONS) {
            val r = send(server(), req(1, "initialize", JSONObject().put("protocolVersion", v)))
            assertEquals(v, r.getJSONObject("result").getString("protocolVersion"))
        }
        val r = send(server(), req(1, "initialize", JSONObject().put("protocolVersion", "1999-01-01")))
        assertEquals("2025-06-18", r.getJSONObject("result").getString("protocolVersion"))
        assertTrue(r.getJSONObject("result").getJSONObject("capabilities").has("tools"))
        assertEquals("homedroid", r.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
    }

    @Test fun notificationsAndClientResponsesGetNoReply() {
        assertNull(server().handle(req(null, "notifications/initialized").toString()))
        assertNull(server().handle(JSONObject().put("jsonrpc", "2.0").put("id", 3).put("result", JSONObject()).toString()))
    }

    @Test fun protocolErrors() {
        assertEquals(McpCore.PARSE_ERROR, JSONObject(server().handle("{nope")!!).getJSONObject("error").getInt("code"))
        assertEquals(McpCore.INVALID_REQUEST, JSONObject(server().handle("[]")!!).getJSONObject("error").getInt("code"))
        assertEquals(McpCore.INVALID_REQUEST, send(server(), JSONObject().put("id", 1).put("method", "ping")).getJSONObject("error").getInt("code"))
        val r = send(server(), req("a", "nope/nope"))
        assertEquals("a", r.get("id"))
        assertEquals(McpCore.METHOD_NOT_FOUND, r.getJSONObject("error").getInt("code"))
        assertEquals(0, send(server(), req(7, "ping")).getJSONObject("result").length())
    }

    @Test fun batchesAnswerOnlyRequests() {
        val batch = JSONArray().put(req(1, "ping")).put(req(null, "notifications/initialized")).put(req(2, "tools/list"))
        val out = JSONArray(server().handle(batch.toString())!!)
        assertEquals(2, out.length())
        assertNull(server().handle(JSONArray().put(req(null, "notifications/initialized")).toString()))
    }

    @Test fun writeToolsAreHiddenAndUncallableWithoutAllowWrite() {
        fun names(s: McpCore.Server) = send(s, req(1, "tools/list")).getJSONObject("result").getJSONArray("tools")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertFalse("restart_service" in names(server()))
        assertTrue("restart_service" in names(server(write = true)))
        val r = send(server(), req(1, "tools/call", JSONObject().put("name", "restart_service").put("arguments", JSONObject().put("name", "sshd"))))
        assertEquals(McpCore.INVALID_PARAMS, r.getJSONObject("error").getInt("code"))
        assertTrue(ran.isEmpty())
        assertEquals(1, rejected.size)
    }

    @Test fun toolCallsValidateArgumentsAndReportToolErrors() {
        val s = server()
        val ok = send(s, req(1, "tools/call", JSONObject().put("name", "get_status")))
        assertFalse(ok.getJSONObject("result").getBoolean("isError"))
        assertEquals("ok:get_status", ok.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
        val bad = send(s, req(2, "tools/call", JSONObject().put("name", "get_service_logs").put("arguments", JSONObject().put("name", "../etc"))))
        assertTrue(bad.getJSONObject("result").getBoolean("isError"))
        val failed = send(s, req(3, "tools/call", JSONObject().put("name", "get_service_logs").put("arguments", JSONObject().put("name", "boom"))))
        assertTrue(failed.getJSONObject("result").getBoolean("isError"))
        assertEquals("no such service", failed.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(listOf("get_status", "get_service_logs"), ran)
    }

    @Test fun schemaValidation() {
        val logs = McpCore.TOOLS.first { it.name == "get_service_logs" }.schema
        assertNull(McpCore.validate(logs, JSONObject().put("name", "sshd").put("lines", 20)))
        assertNotNull(McpCore.validate(logs, JSONObject()))
        assertNotNull(McpCore.validate(logs, JSONObject().put("name", "sshd").put("lines", 500)))
        assertNotNull(McpCore.validate(logs, JSONObject().put("name", "sshd").put("lines", 1.5)))
        assertNotNull(McpCore.validate(logs, JSONObject().put("name", 5)))
        assertNotNull(McpCore.validate(logs, JSONObject().put("name", "sshd").put("extra", true)))
        val e = JSONObject().put("type", "object").put("properties", JSONObject().put("m", JSONObject().put("type", "string").put("enum", JSONArray().put("a"))))
        assertNull(McpCore.validate(e, JSONObject().put("m", "a")))
        assertNotNull(McpCore.validate(e, JSONObject().put("m", "b")))
    }

    @Test fun tokensCompareExactly() {
        val t = McpCore.newToken()
        assertTrue(Regex("hdm_[0-9a-f]{64}").matches(t))
        assertTrue(McpCore.tokenMatches(t, t))
        assertFalse(McpCore.tokenMatches(t.dropLast(1), t))
        assertFalse(McpCore.tokenMatches(null, t))
        assertFalse(McpCore.tokenMatches("", ""))
    }

    @Test fun summaryIsShort() {
        val s = McpCore.summarize(JSONObject().put("path", "x".repeat(500)).put("n", 3))
        assertTrue(s.length <= 301)
        assertTrue(s.contains("\"n\":3"))
    }

    private fun completion(content: String?, vararg calls: Pair<String, String>) = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("message", JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL).apply {
            if (calls.isNotEmpty()) put("tool_calls", JSONArray().apply {
                calls.forEachIndexed { i, (n, a) -> put(JSONObject().put("id", "c$i").put("type", "function").put("function", JSONObject().put("name", n).put("arguments", a))) }
            })
        })))

    @Test fun agentRunsReadToolsAndQueuesWriteTools() {
        val answers = ArrayDeque(listOf(
            completion(null, "get_status" to "{}", "restart_service" to "{\"name\":\"sshd\"}", "nope" to "{}", "list_services" to "{bad"),
            completion("Battery is fine; the restart waits for you."),
        ))
        val seen = mutableListOf<JSONArray>()
        val out = McpCore.agent(JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), McpCore.TOOLS,
            complete = { seen += JSONArray(it.toString()); answers.removeFirst() },
            execute = { t, _ -> "{\"battery\":80}".also { assertFalse(t.write) } },
            queue = { t, _ -> assertTrue(t.write); "p1" })
        assertEquals("Battery is fine; the restart waits for you.", out.getString("reply"))
        val calls = out.getJSONArray("toolCalls")
        assertEquals(4, calls.length())
        assertEquals("{\"battery\":80}", calls.getJSONObject(0).getString("result"))
        assertTrue(calls.getJSONObject(1).getBoolean("pending"))
        assertEquals("p1", calls.getJSONObject(1).getString("id"))
        assertTrue(calls.getJSONObject(2).has("error"))
        assertTrue(calls.getJSONObject(3).has("error"))
        // The second request carries the assistant's calls and one tool answer each.
        val second = seen[1]
        assertEquals("assistant", second.getJSONObject(1).getString("role"))
        assertEquals(4, (2 until second.length()).count { second.getJSONObject(it).getString("role") == "tool" })
    }

    @Test fun agentStopsAfterMaxRounds() {
        var n = 0
        val out = McpCore.agent(JSONArray(), McpCore.TOOLS, complete = { n++; completion(null, "get_status" to "{}") },
            execute = { _, _ -> "{}" }, queue = { _, _ -> "x" }, rounds = 3)
        assertEquals(3, n)
        assertTrue(out.getString("reply").contains("3 rounds"))
    }

    @Test fun openAiToolFormat() {
        val a = McpCore.openAiTools(McpCore.TOOLS)
        assertEquals(McpCore.TOOLS.size, a.length())
        val f = a.getJSONObject(0).getJSONObject("function")
        assertEquals("function", a.getJSONObject(0).getString("type"))
        assertEquals("object", f.getJSONObject("parameters").getString("type"))
    }
}
