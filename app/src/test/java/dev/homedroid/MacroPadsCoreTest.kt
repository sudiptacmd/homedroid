package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MacroPadsCoreTest {
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
    }
    private fun action(json: String) = MacroPadsCore.validateAction(JSONObject(json))
    private fun profile(m: JSONObject) = m.getJSONArray("profiles").getJSONObject(0)
    private fun page(m: JSONObject) = profile(m).getJSONArray("pages").getJSONObject(0)
    private fun button(m: JSONObject) = page(m).getJSONArray("buttons").getJSONObject(0)
    @Test fun everyActionKindHasAValidExample() {
        listOf(
            """{"kind":"hotkey","keys":["ctrl","shift","m"]}""",
            """{"kind":"key","key":"f24","repeat":100}""",
            """{"kind":"text","text":"Hello বাংলা"}""",
            """{"kind":"media","key":"play_pause"}""",
            """{"kind":"open","target":"https://example.com"}""",
            """{"kind":"launch","path":"/usr/bin/editor","args":["a b", ""]}""",
            """{"kind":"command","command":"echo hello","shell":true}""",
            """{"kind":"mouse","action":"move","x":-20,"y":50}""",
            """{"kind":"sequence","steps":[{"kind":"key","key":"enter","delayMs":200}]}""",
            """{"kind":"page","page":"pg-2"}""",
            """{"kind":"profile","profile":"p-work"}""",
            """{"kind":"back"}""",
            """{"kind":"http","method":"POST","url":"http://homeassistant.local/api/webhook/a","body":"{}"}"""
        ).forEach(::action)
        MacroPadsCore.mediaKeys.forEach { action(JSONObject().put("kind", "media").put("key", it).toString()) }
        action("""{"kind":"mouse","action":"click"}""")
        action("""{"kind":"mouse","action":"right_click"}""")
        action("""{"kind":"mouse","action":"scroll","dy":-10}""")
    }
    @Test fun malformedFieldsAreRejectedForEveryKind() {
        listOf(
            """{"kind":"hotkey","keys":["ctrl","ctrl"]}""",
            """{"kind":"key","key":"f25"}""",
            """{"kind":"text","text":12}""",
            """{"kind":"media","key":"eject"}""",
            """{"kind":"open","target":""}""",
            """{"kind":"launch","path":"app","args":[2]}""",
            """{"kind":"command","command":"ls","shell":"true"}""",
            """{"kind":"mouse","action":"move","x":1.1,"y":0}""",
            """{"kind":"sequence","steps":[]}""",
            """{"kind":"page","page":"bad/id"}""",
            """{"kind":"profile","profile":true}""",
            """{"kind":"back","delayMs":-1}""",
            """{"kind":"http","method":"GET","url":"file:///etc/passwd"}""",
            """{"kind":"unknown"}"""
        ).forEach { invalid { action(it) } }
        invalid { action("""{"kind":"key","key":"enter","repeat":"2"}""") }
        invalid { action("""{"kind":"hotkey","keys":["\n"]}""") }
        invalid { action("""{"kind":"key","key":"a","computer":""}""") }
    }
    @Test fun keysAcceptPrintableUnicodeButRejectControlAndBrokenSurrogates() {
        listOf("a", "9", "@", "é", "😀", "f1", "f24", "printscreen").forEach { assertTrue(it, MacroPadsCore.key(it)) }
        listOf("", "aa", "f25", "\n", "\uD800", "\u0000").forEach { assertFalse(it, MacroPadsCore.key(it)) }
    }
    @Test fun urlsAndLengthsAreBounded() {
        listOf("ftp://example.com", "javascript:alert(1)", "http:///path", "https://user:pass@example.com", "http://[broken").forEach {
            invalid { MacroPadsCore.httpUrl(it) }
        }
        invalid { MacroPadsCore.validateAction(JSONObject().put("kind", "text").put("text", "x".repeat(8193))) }
        invalid { MacroPadsCore.validateAction(JSONObject().put("kind", "hotkey").put("keys", JSONArray(('a'..'i').map { it.toString() }))) }
    }
    @Test fun sequencesCannotRouteElsewhereOrContainLocalActionsAndAreBounded() {
        listOf("""{"kind":"back"}""", """{"kind":"key","key":"a","computer":"c-1"}""").forEach {
            invalid { action("""{"kind":"sequence","steps":[$it]}""") }
        }
        var nested = JSONObject().put("kind", "key").put("key", "a")
        repeat(5) { nested = JSONObject().put("kind", "sequence").put("steps", JSONArray().put(nested)) }
        invalid { MacroPadsCore.validateAction(nested) }
        val steps = JSONArray(); repeat(33) { steps.put(JSONObject().put("kind", "back")) }
        invalid { MacroPadsCore.validateAction(JSONObject().put("kind", "sequence").put("steps", steps)) }
    }
    @Test fun starterIsValidAndHttpExampleIsDisabled() {
        val model = MacroPadsCore.validate(MacroPadsCore.starter())
        val buttons = page(model).getJSONArray("buttons")
        assertEquals(15, buttons.length())
        assertTrue(buttons.getJSONObject(14).getBoolean("disabled"))
        assertEquals("http", buttons.getJSONObject(14).getJSONObject("action").getString("kind"))
    }
    @Test fun modelRejectsWrongVersionTypesGridSizesDuplicatesAndAppearance() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 3) }, { profile(it).put("rows", 1) }, { profile(it).put("rows", 7) },
            { profile(it).put("cols", 9) }, { profile(it).put("cols", "5") },
            { button(it).put("row", 3) }, { button(it).put("col", -1) },
            { button(it).put("id", profile(it).getString("id")) },
            { page(it).getJSONArray("buttons").getJSONObject(1).put("col", 0) },
            { button(it).put("color", "red") }, { button(it).put("label", "x".repeat(81)) },
            { button(it).put("disabled", "false") }, { button(it).put("toggle", true) },
            { it.put("profiles", JSONArray()) }
        )
        changes.forEach { change -> invalid { MacroPadsCore.validate(MacroPadsCore.starter().also(change)) } }
        MacroPadsCore.validate(MacroPadsCore.starter().also { profile(it).put("rows", 6).put("cols", 8) })
        MacroPadsCore.validate(MacroPadsCore.starter().also { profile(it).put("rows", 2).put("cols", 2); page(it).put("buttons", JSONArray()) })
    }
    @Test fun navigationLinksResolveOnlyToExistingDestinations() {
        val model = MacroPadsCore.starter()
        button(model).put("action", JSONObject().put("kind", "page").put("page", "pg-2"))
        invalid { MacroPadsCore.validate(model) }
        profile(model).getJSONArray("pages").put(JSONObject().put("id", "pg-2").put("name", "Two").put("buttons", JSONArray()))
        MacroPadsCore.validate(model)
        button(model).put("action", JSONObject().put("kind", "profile").put("profile", "missing"))
        invalid { MacroPadsCore.validate(model) }
        button(model).getJSONObject("action").put("profile", "p-default")
        MacroPadsCore.validate(model)
    }
}
