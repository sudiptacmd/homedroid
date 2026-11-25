package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MacroLookCoreTest {
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
    }
    private fun look(json: String) = MacroLookCore.validate(JSONObject(json))
    private fun deck(look: String? = null) = MacroPadsCore.starter().also { m -> look?.let { m.put("look", JSONObject(it)) } }
    private val image = "a-0123456789abcdef"

    @Test fun v1DecksMigrateToVersion2WithoutChanges() {
        val v1 = MacroPadsCore.starter().put("version", 1)
        val before = v1.getJSONArray("profiles").toString()
        val out = MacroPadsCore.validate(v1)
        assertEquals(2, out.getInt("version")); assertEquals(before, out.getJSONArray("profiles").toString()); assertFalse(out.has("look"))
        invalid { MacroPadsCore.validate(MacroPadsCore.starter().put("version", 0)) }
        invalid { MacroPadsCore.validate(MacroPadsCore.starter().put("version", "2")) }
        assertEquals(2, MacroPadsCore.starter().getInt("version"))
    }
    @Test fun everyThemeIsValidAndResolves() {
        assertTrue(MacroLookCore.themes.size >= 5)
        MacroLookCore.themes.forEach { t ->
            MacroLookCore.validate(t.look); MacroPadsCore.validate(deck().put("look", JSONObject(t.look.toString())))
            val l = MacroLookCore.resolve(t.look); assertTrue(t.id, l.radius in 0..40 && l.iconSize in 16..64)
        }
        assertEquals("oled", MacroLookCore.resolve(MacroLookCore.theme("oled")!!.look).bgType)
    }
    @Test fun validLooksAreAccepted() {
        look("""{"bg":{"type":"gradient","colors":["#112233","#445566","#778899"],"angle":45},"font":{"family":"serif","weight":700,"scale":150,"position":"inside","color":"#FFFFFF","upper":true},"key":{"radius":0,"gap":24,"border":6,"borderColor":"#000000","glow":100,"shadow":0,"gradient":false,"color":"#222222"},"icon":{"size":64,"tint":"#ABCDEF"}}""")
        look("""{"bg":{"type":"image","image":"$image","blur":25,"dim":90},"font":{"family":"custom","custom":"$image"}}""")
        look("""{"bg":{"type":"oled"}}"""); look("{}")
    }
    @Test fun invalidLooksAreRejected() {
        listOf(
            """{"unknown":1}""", """{"bg":{"type":"video"}}""", """{"bg":{"type":"color","color":"red"}}""", """{"bg":{"type":"gradient"}}""",
            """{"bg":{"type":"gradient","colors":["#000000"]}}""", """{"bg":{"type":"gradient","colors":["#000000","#111111","#222222","#333333"]}}""",
            """{"bg":{"angle":50}}""", """{"bg":{"angle":360}}""", """{"bg":{"blur":26}}""", """{"bg":{"dim":91}}""", """{"bg":{"type":"image"}}""",
            """{"bg":{"image":"../etc/passwd"}}""", """{"bg":{"nope":1}}""", """{"bg":[]}""",
            """{"font":{"family":"comic"}}""", """{"font":{"family":"custom"}}""", """{"font":{"weight":450}}""", """{"font":{"weight":1000}}""",
            """{"font":{"scale":49}}""", """{"font":{"scale":201}}""", """{"font":{"position":"above"}}""", """{"font":{"upper":"yes"}}""", """{"font":{"color":"#FFF"}}""",
            """{"key":{"radius":41}}""", """{"key":{"gap":-1}}""", """{"key":{"border":7}}""", """{"key":{"glow":101}}""", """{"key":{"shadow":1.5}}""", """{"key":{"gradient":1}}""",
            """{"icon":{"size":15}}""", """{"icon":{"size":65}}""", """{"icon":{"tint":"blue"}}""", """{"theme":""}""",
        ).forEach { invalid { look(it) } }
    }
    @Test fun assetReferencesMustExistWithTheRightKind() {
        val exists = { id: String, kind: String -> id == image && kind == "image" }
        MacroLookCore.validate(JSONObject("""{"bg":{"type":"image","image":"$image"}}"""), exists)
        invalid { MacroLookCore.validate(JSONObject("""{"bg":{"type":"image","image":"a-ffffffffffff"}}"""), exists) }
        invalid { MacroLookCore.validate(JSONObject("""{"font":{"family":"custom","custom":"$image"}}"""), exists) }
        val m = deck(); m.getJSONArray("profiles").getJSONObject(0).getJSONArray("pages").getJSONObject(0).getJSONArray("buttons").getJSONObject(0).put("image", image)
        MacroPadsCore.validate(m, exists)
        invalid { MacroPadsCore.validate(m, { _, _ -> false }) }
    }
    @Test fun looksValidateOnProfilesAndPagesToo() {
        val m = deck(); val p = m.getJSONArray("profiles").getJSONObject(0); val pg = p.getJSONArray("pages").getJSONObject(0)
        p.put("look", JSONObject("""{"key":{"radius":8}}""")); pg.put("look", JSONObject("""{"font":{"scale":120}}"""))
        MacroPadsCore.validate(m)
        pg.put("look", JSONObject("""{"font":{"scale":999}}""")); invalid { MacroPadsCore.validate(m) }
        pg.put("look", "bright"); invalid { MacroPadsCore.validate(m) }
    }
    @Test fun perKeyOptionsAreValidated() {
        fun key(change: (JSONObject) -> Unit): JSONObject = deck().also { m -> change(m.getJSONArray("profiles").getJSONObject(0).getJSONArray("pages").getJSONObject(0).getJSONArray("buttons").getJSONObject(0)) }
        MacroPadsCore.validate(key { it.put("textSize", 40).put("align", "end") })
        listOf<(JSONObject) -> Unit>({ it.put("textSize", 5) }, { it.put("textSize", 41) }, { it.put("textSize", "12") }, { it.put("align", "top") }, { it.put("image", "x") })
            .forEach { c -> invalid { MacroPadsCore.validate(key(c)) } }
    }
    @Test fun resolveCascadesDeckProfilePageOverDefaults() {
        val d = MacroLookCore.resolve(null)
        assertEquals("app", d.bgType); assertEquals(20, d.radius); assertEquals(6, d.gap); assertEquals(30, d.iconSize); assertTrue(d.gradient); assertEquals("below", d.position); assertNull(d.fontColor)
        val l = MacroLookCore.resolve(JSONObject("""{"key":{"radius":4,"gap":10},"font":{"scale":120,"color":"#FF0000"}}"""), JSONObject("""{"key":{"radius":30}}"""), JSONObject("""{"font":{"color":null,"upper":true}}"""), null)
        assertEquals(30, l.radius); assertEquals(10, l.gap); assertEquals(1.2f, l.scale, 0.001f); assertTrue(l.upper); assertEquals(0xFFFF0000.toInt(), l.fontColor)
        assertEquals("app", MacroLookCore.resolve(JSONObject("""{"bg":{"type":"image"}}""")).bgType)
    }
    @Test fun backgroundBrightnessDrivesHeaderContrast() {
        assertTrue(MacroLookCore.backgroundLuminance(MacroLookCore.resolve(JSONObject("""{"bg":{"type":"oled"}}"""))) < 0.1f)
        assertTrue(MacroLookCore.backgroundLuminance(MacroLookCore.resolve(JSONObject("""{"bg":{"type":"color","color":"#FFFFFF"}}"""))) > 0.9f)
        assertTrue(MacroLookCore.backgroundLuminance(MacroLookCore.resolve(JSONObject("""{"bg":{"type":"image","image":"$image","dim":90}}""")), 0.9f) < 0.2f)
        assertEquals(-1f, MacroLookCore.backgroundLuminance(MacroLookCore.resolve(null)), 0f)
    }
    @Test fun assetBytesAreSniffedByKind() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val webp = "RIFF0000WEBPVP8 ".toByteArray()
        val ttf = byteArrayOf(0, 1, 0, 0, 0, 0); val otf = "OTTO\u0000\u0000".toByteArray()
        assertEquals("jpg", MacroAssetsCore.extension("image", jpg)); assertEquals("png", MacroAssetsCore.extension("image", png)); assertEquals("webp", MacroAssetsCore.extension("image", webp))
        assertEquals("ttf", MacroAssetsCore.extension("font", ttf)); assertEquals("otf", MacroAssetsCore.extension("font", otf))
        assertNull(MacroAssetsCore.extension("font", png)); assertNull(MacroAssetsCore.extension("image", ttf)); assertNull(MacroAssetsCore.extension("image", "<svg/>".toByteArray()))
        assertNull(MacroAssetsCore.extension("image", ByteArray(0))); assertNull(MacroAssetsCore.extension("script", jpg))
        assertTrue(MacroAssetsCore.isId(MacroAssetsCore.newId())); assertFalse(MacroAssetsCore.isId("a-../x")); assertFalse(MacroAssetsCore.isId("b-0123456789"))
        assertEquals("image/jpeg", MacroAssetsCore.mime("jpg")); assertEquals("font", MacroAssetsCore.kindOf("otf"))
    }
    @Test fun iconSetIsLargeUniqueAndSearchable() {
        assertTrue("${MacroIcons.list.size} icons", MacroIcons.list.size >= 80)
        assertEquals(MacroIcons.list.size, MacroIcons.list.map { it.name }.toSet().size)
        assertEquals(Icon.Mic, MacroIcons.get("mic")); assertEquals(Icon.Keypad, MacroIcons.get("nope"))
        assertEquals(MacroIcons.list.size, MacroIcons.search("").size)
        assertTrue(MacroIcons.search("MAIL").any { it.name == "mail" }); assertTrue(MacroIcons.search("game controller").any { it.name == "gamepad" })
        assertTrue(MacroIcons.search("zzzz").isEmpty())
        // Every icon name used by the starter deck still exists.
        val starter = MacroPadsCore.starter().getJSONArray("profiles").getJSONObject(0).getJSONArray("pages").getJSONObject(0).getJSONArray("buttons")
        for (i in 0 until starter.length()) assertTrue(MacroIcons.all.containsKey(starter.getJSONObject(i).getString("icon")))
    }
    @Test fun themeJsonIsServedForTheDashboard() {
        val a: JSONArray = MacroLookCore.themeJson()
        assertEquals(MacroLookCore.themes.size, a.length()); assertEquals("studio", a.getJSONObject(0).getString("id")); assertTrue(a.getJSONObject(0).getJSONObject("look").has("bg"))
    }
}
