package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** The portable v1 model. Validation is strict about types, bounds, IDs and local links. */
object MacroPadsCore {
    val kinds = listOf("hotkey", "key", "text", "media", "open", "launch", "command", "mouse", "sequence", "page", "profile", "back", "http")
    val mediaKeys = listOf("play_pause", "next", "previous", "stop", "volume_up", "volume_down", "mute")
    val localKinds = setOf("page", "profile", "back", "http")
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val namedKeys = setOf("ctrl", "shift", "alt", "meta", "enter", "tab", "escape", "space", "backspace", "delete", "up", "down", "left", "right", "home", "end", "pageup", "pagedown", "printscreen")
    fun key(value: String) = value in namedKeys || Regex("f([1-9]|1[0-9]|2[0-4])").matches(value) ||
        value.codePointCount(0, value.length) == 1 && !Character.isISOControl(value.codePointAt(0)) && value.codePointAt(0) !in 0xD800..0xDFFF
    private fun string(o: JSONObject, k: String, max: Int, empty: Boolean = false): String {
        val v = o.opt(k)
        require(v is String && v.length <= max && (empty || v.isNotBlank())) { "$k must be ${if (empty) "0" else "1"}–$max characters" }
        return v
    }
    private fun integer(o: JSONObject, k: String, range: IntRange, default: Int? = null): Int {
        if (!o.has(k) && default != null) return default
        val n = o.opt(k)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toInt().toDouble() && n.toInt() in range) { "$k must be ${range.first}–${range.last}" }
        return n.toInt()
    }
    private fun optionalString(o: JSONObject, k: String, max: Int) { if (o.has(k) && !o.isNull(k)) string(o, k, max, true) }
    private fun bool(o: JSONObject, k: String) { if (o.has(k)) require(o.opt(k) is Boolean) { "$k must be true or false" } }
    private fun id(o: JSONObject, k: String): String = string(o, k, 64).also { require(idPattern.matches(it)) { "Invalid $k" } }
    private fun target(o: JSONObject) { if (o.has("computer") && !o.isNull("computer")) id(o, "computer") }
    fun httpUrl(value: String): URI = try { URI(value) } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Use a valid HTTP or HTTPS URL")
    }.also {
        require(it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null) { "Use an http or https URL without embedded credentials" }
    }
    fun validateAction(a: JSONObject, depth: Int = 0) {
        require(depth <= 3) { "Sequences may be nested at most three levels" }
        target(a)
        integer(a, "delayMs", 0..10_000, 0)
        when (string(a, "kind", 16)) {
            "hotkey" -> {
                val keys = a.getJSONArray("keys")
                require(keys.length() in 1..8) { "Use 1–8 keys" }
                val unique = HashSet<String>()
                for (i in 0 until keys.length()) require(keys.get(i) is String && key(keys.getString(i)) && unique.add(keys.getString(i))) { "Invalid or repeated hotkey" }
            }
            "key" -> { require(key(string(a, "key", 32))) { "Unknown key" }; integer(a, "repeat", 1..100, 1) }
            "text" -> string(a, "text", 8192, true)
            "media" -> require(string(a, "key", 32) in mediaKeys) { "Unknown media key" }
            "open" -> string(a, "target", 2048)
            "launch" -> {
                string(a, "path", 2048)
                if (a.has("args")) { val args = a.getJSONArray("args"); require(args.length() <= 32); for (i in 0 until args.length()) require(args.get(i) is String && args.getString(i).length <= 2048) }
            }
            "command" -> { string(a, "command", 8192); bool(a, "shell") }
            "mouse" -> when (string(a, "action", 16)) {
                "click", "right_click" -> Unit
                "move" -> { integer(a, "x", -100000..100000); integer(a, "y", -100000..100000) }
                "scroll" -> integer(a, "dy", -10000..10000)
                else -> errorArgument("Unknown mouse action")
            }
            "sequence" -> {
                val steps = a.getJSONArray("steps")
                require(steps.length() in 1..32) { "Use 1–32 sequence steps" }
                var count = 0
                fun countSteps(obj: JSONObject) { count++; require(count <= 64) { "Sequence is too large" }; if (obj.optString("kind") == "sequence") { val s = obj.getJSONArray("steps"); for (i in 0 until s.length()) countSteps(s.getJSONObject(i)) } }
                // Validate depth before counting so malicious deeply nested input cannot recurse unboundedly.
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    require(step.optString("kind") !in localKinds && (!step.has("computer") || step.isNull("computer"))) { "Sequence steps must be desktop actions on the same computer" }
                    validateAction(step, depth + 1)
                }
                countSteps(a)
            }
            "page" -> id(a, "page")
            "profile" -> id(a, "profile")
            "back" -> Unit
            "http" -> {
                require(string(a, "method", 8) in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { "Unknown HTTP method" }
                httpUrl(string(a, "url", 2048)); optionalString(a, "body", 65536)
            }
            else -> errorArgument("Unknown action kind")
        }
    }
    private fun errorArgument(message: String): Nothing = throw IllegalArgumentException(message)
    private fun appearance(o: JSONObject, required: Boolean) {
        if (required || o.has("label")) string(o, "label", 80, true)
        optionalString(o, "icon", 40); optionalString(o, "emoji", 32)
        for (k in listOf("color", "textColor")) if (o.has(k)) require(Regex("#[0-9a-fA-F]{6}").matches(string(o, k, 7))) { "$k must be #RRGGBB" }
    }
    fun validate(model: JSONObject): JSONObject {
        require(model.toString().length <= 524288) { "Deck exceeds 512 KB" }
        require(integer(model, "version", 1..1) == 1)
        val profiles = model.getJSONArray("profiles")
        require(profiles.length() in 1..16) { "Use 1–16 profiles" }
        val ids = HashSet<String>(); val profileIds = HashSet<String>()
        val links = mutableListOf<Triple<JSONObject, Set<String>, String>>()
        for (pi in 0 until profiles.length()) {
            val p = profiles.getJSONObject(pi); val pid = id(p, "id")
            require(ids.add(pid)) { "IDs must be unique" }; profileIds.add(pid)
            string(p, "name", 80)
            val rows = integer(p, "rows", 2..6); val cols = integer(p, "cols", 2..8)
            val pages = p.getJSONArray("pages"); require(pages.length() in 1..16) { "Use 1–16 pages" }
            val pageIds = HashSet<String>()
            for (pg in 0 until pages.length()) {
                val page = pages.getJSONObject(pg); val pageId = id(page, "id")
                require(ids.add(pageId)) { "IDs must be unique" }; pageIds.add(pageId); string(page, "name", 80)
                val buttons = page.getJSONArray("buttons"); require(buttons.length() <= rows * cols)
                val positions = HashSet<Int>()
                for (bi in 0 until buttons.length()) {
                    val b = buttons.getJSONObject(bi)
                    require(ids.add(id(b, "id"))) { "IDs must be unique" }
                    val row = integer(b, "row", 0 until rows); val col = integer(b, "col", 0 until cols)
                    require(positions.add(row * cols + col)) { "Two buttons occupy the same cell" }
                    appearance(b, true); target(b); bool(b, "disabled")
                    if (b.has("toggle")) appearance(b.getJSONObject("toggle"), false)
                    val a = b.getJSONObject("action"); validateAction(a); links.add(Triple(a, pageIds, pid))
                }
            }
        }
        links.forEach { (a, pages, _) ->
            if (a.optString("kind") == "page") require(a.getString("page") in pages) { "Page link must refer to a page in this profile" }
            if (a.optString("kind") == "profile") require(a.getString("profile") in profileIds) { "Unknown profile link" }
        }
        return model
    }
    fun starter(): JSONObject {
        val buttons = JSONArray()
        fun add(label: String, icon: String, a: JSONObject, color: String = "#285A78", disabled: Boolean = false) {
            val i = buttons.length()
            buttons.put(JSONObject().put("id", "b-${i + 1}").put("row", i / 5).put("col", i % 5).put("label", label)
                .put("icon", icon).put("color", color).put("textColor", "#FFFFFF").put("action", a).put("disabled", disabled))
        }
        fun media(k: String) = JSONObject().put("kind", "media").put("key", k)
        fun hotkey(vararg keys: String) = JSONObject().put("kind", "hotkey").put("keys", JSONArray(keys.toList()))
        add("Play / pause", "play", media("play_pause")); add("Previous", "previous", media("previous")); add("Next", "next", media("next"))
        add("Mute mic", "mic-off", hotkey("ctrl", "shift", "m"), "#67509B")
        buttons.getJSONObject(3).put("toggle", JSONObject().put("label", "Unmute mic").put("icon", "mic").put("color", "#A53E54"))
        add("Screenshot", "screenshot", JSONObject().put("kind", "key").put("key", "printscreen"))
        add("Volume down", "volume-down", media("volume_down")); add("Volume up", "volume-up", media("volume_up")); add("Mute", "volume-mute", media("mute"))
        add("Lock screen", "lock", hotkey("meta", "l"), "#67509B")
        add("Browser", "browser", JSONObject().put("kind", "open").put("target", "https://example.com"))
        add("Copy", "copy", hotkey("ctrl", "c")); add("Paste", "paste", hotkey("ctrl", "v")); add("Undo", "undo", hotkey("ctrl", "z")); add("Redo", "redo", hotkey("ctrl", "shift", "z"))
        add("Home Assistant", "light", JSONObject().put("kind", "http").put("method", "POST").put("url", "http://homeassistant.local:8123/api/webhook/REPLACE_ME").put("body", ""), "#806331", true)
        return JSONObject().put("version", 1).put("profiles", JSONArray().put(JSONObject().put("id", "p-default").put("name", "Everyday")
            .put("rows", 3).put("cols", 5).put("pages", JSONArray().put(JSONObject().put("id", "pg-1").put("name", "Main").put("buttons", buttons)))))
    }
}
