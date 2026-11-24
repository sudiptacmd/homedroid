package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Android-free model of the MacroPads look: validation, schema migration, deck themes and the
 * cascade deck -> profile -> page. Images and fonts live outside macropads.json as assets that
 * the model references by id (see [MacroAssetsCore]).
 */
object MacroLookCore {
    /** Schema 2 added `look` (deck, profile, page) and per-key `image`, `textSize`, `align`. */
    const val VERSION = 2
    val families = listOf("system", "sans", "serif", "mono", "rounded", "condensed", "custom")
    val backgrounds = listOf("color", "gradient", "image", "oled")
    val positions = listOf("below", "inside", "none")
    val aligns = listOf("center", "start", "end")
    private val hex = Regex("#[0-9a-fA-F]{6}")
    private val groups = mapOf(
        "bg" to setOf("type", "color", "colors", "angle", "image", "blur", "dim"),
        "font" to setOf("family", "custom", "weight", "scale", "position", "color", "upper"),
        "key" to setOf("radius", "gap", "border", "borderColor", "glow", "shadow", "gradient", "color"),
        "icon" to setOf("size", "tint"),
    )

    /** v1 decks have no look; they only need the version bump. Idempotent. */
    fun migrate(model: JSONObject): JSONObject {
        val v = model.opt("version")
        require(v is Number && v.toInt() in 1..VERSION && v.toDouble() == v.toInt().toDouble()) { "version must be 1–$VERSION" }
        if (v.toInt() < VERSION) model.put("version", VERSION)
        return model
    }

    private fun color(o: JSONObject, k: String) {
        if (o.has(k) && !o.isNull(k)) require(o.opt(k).let { it is String && hex.matches(it) }) { "$k must be #RRGGBB" }
    }
    private fun int(o: JSONObject, k: String, range: IntRange) {
        if (!o.has(k) || o.isNull(k)) return
        val n = o.opt(k)
        require(n is Number && n.toDouble() == n.toInt().toDouble() && n.toInt() in range) { "$k must be ${range.first}–${range.last}" }
    }
    private fun choice(o: JSONObject, k: String, values: List<String>) {
        if (o.has(k) && !o.isNull(k)) require(o.opt(k).let { it is String && it in values }) { "$k must be one of ${values.joinToString(", ")}" }
    }
    private fun flag(o: JSONObject, k: String) { if (o.has(k) && !o.isNull(k)) require(o.opt(k) is Boolean) { "$k must be true or false" } }
    private fun asset(o: JSONObject, k: String, kind: String, exists: (String, String) -> Boolean) {
        if (!o.has(k) || o.isNull(k)) return
        val id = o.opt(k)
        require(id is String && MacroAssetsCore.isId(id)) { "$k must be an asset id" }
        require(exists(id, kind)) { "Unknown $kind asset $id" }
    }
    fun assetRef(o: JSONObject, k: String, kind: String, exists: (String, String) -> Boolean) = asset(o, k, kind, exists)
    fun optionalColor(o: JSONObject, k: String) = color(o, k)
    fun optionalInt(o: JSONObject, k: String, range: IntRange) = int(o, k, range)
    fun optionalChoice(o: JSONObject, k: String, values: List<String>) = choice(o, k, values)

    /** Validates one (possibly partial) look object. [exists] answers "does this asset id of this kind exist". */
    fun validate(look: JSONObject, exists: (String, String) -> Boolean = { _, _ -> true }) {
        look.keys().forEach { require(it == "theme" || it in groups) { "Unknown look section: $it" } }
        if (look.has("theme") && !look.isNull("theme")) require(look.opt("theme").let { it is String && it.length in 1..24 }) { "theme must be 1–24 characters" }
        for ((name, allowed) in groups) {
            if (!look.has(name) || look.isNull(name)) continue
            val g = look.optJSONObject(name) ?: throw IllegalArgumentException("$name must be an object")
            g.keys().forEach { require(it in allowed) { "Unknown $name option: $it" } }
        }
        look.optJSONObject("bg")?.let { g ->
            choice(g, "type", backgrounds); color(g, "color"); int(g, "blur", 0..25); int(g, "dim", 0..90)
            int(g, "angle", 0..315)
            if (g.has("angle") && !g.isNull("angle")) require(g.getInt("angle") % 45 == 0) { "angle must be a multiple of 45" }
            if (g.has("colors") && !g.isNull("colors")) {
                val c = g.optJSONArray("colors") ?: throw IllegalArgumentException("colors must be a list")
                require(c.length() in 2..3) { "Use 2–3 gradient colors" }
                for (i in 0 until c.length()) require(c.opt(i).let { it is String && hex.matches(it) }) { "Gradient colors must be #RRGGBB" }
            }
            asset(g, "image", "image", exists)
            when (g.optString("type")) {
                "gradient" -> require(g.optJSONArray("colors") != null) { "A gradient needs 2–3 colors" }
                "image" -> require(g.has("image") && !g.isNull("image")) { "Choose an image for the background" }
            }
        }
        look.optJSONObject("font")?.let { g ->
            choice(g, "family", families); color(g, "color"); flag(g, "upper"); choice(g, "position", positions)
            int(g, "weight", 100..900); int(g, "scale", 50..200)
            if (g.has("weight") && !g.isNull("weight")) require(g.getInt("weight") % 100 == 0) { "weight must be 100, 200 … 900" }
            asset(g, "custom", "font", exists)
            if (g.optString("family") == "custom") require(g.has("custom") && !g.isNull("custom")) { "Import a font file to use a custom font" }
        }
        look.optJSONObject("key")?.let { g ->
            int(g, "radius", 0..40); int(g, "gap", 0..24); int(g, "border", 0..6); int(g, "glow", 0..100); int(g, "shadow", 0..100)
            color(g, "borderColor"); color(g, "color"); flag(g, "gradient")
        }
        look.optJSONObject("icon")?.let { g -> int(g, "size", 16..64); color(g, "tint") }
    }

    /** Per-key look fields added by schema 2. */
    fun validateKey(b: JSONObject, exists: (String, String) -> Boolean = { _, _ -> true }) {
        int(b, "textSize", 6..40); choice(b, "align", aligns); asset(b, "image", "image", exists)
    }

    // --- resolved look ----------------------------------------------------------------------

    class Look(
        val bgType: String, val bgColor: Int, val bgColors: List<Int>, val bgAngle: Int, val bgImage: String?, val blur: Int, val dim: Int,
        val family: String, val customFont: String?, val weight: Int, val scale: Float, val position: String, val fontColor: Int?, val upper: Boolean,
        val radius: Int, val gap: Int, val border: Int, val borderColor: Int?, val glow: Int, val shadow: Int, val gradient: Boolean, val keyColor: Int?,
        val iconSize: Int, val iconTint: Int?,
    ) {
        /** True when the deck keeps the app's own background (no look background set anywhere). */
        val themedBackground get() = bgType != "app"
    }

    fun parse(s: String): Int = (s.substring(1).toLong(16) or 0xFF000000L).toInt()

    /** Merges the deck, profile and page looks (later wins, per option) over the built-in defaults. */
    fun resolve(vararg layers: JSONObject?): Look {
        val merged = HashMap<String, Any?>()
        for (layer in layers) {
            if (layer == null) continue
            for (group in groups.keys) layer.optJSONObject(group)?.let { g -> g.keys().forEach { k -> if (!g.isNull(k)) merged["$group.$k"] = g.get(k) } }
        }
        fun str(k: String) = merged[k] as? String
        fun num(k: String, d: Int) = (merged[k] as? Number)?.toInt() ?: d
        fun clr(k: String) = str(k)?.let(::parse)
        val stops = (merged["bg.colors"] as? JSONArray)?.let { a -> (0 until a.length()).map { parse(a.getString(it)) } } ?: listOf(0xFF1B2230.toInt(), 0xFF0E121A.toInt())
        val type = str("bg.type") ?: "app"
        return Look(
            if (type == "image" && str("bg.image") == null) "app" else type, clr("bg.color") ?: 0xFF101418.toInt(), stops, num("bg.angle", 135), str("bg.image"), num("bg.blur", 0), num("bg.dim", 0),
            str("font.family") ?: "system", str("font.custom"), num("font.weight", 500), num("font.scale", 100) / 100f, str("font.position") ?: "below", clr("font.color"), merged["font.upper"] == true,
            num("key.radius", 20), num("key.gap", 6), num("key.border", 1), clr("key.borderColor"), num("key.glow", 0), num("key.shadow", 0), merged["key.gradient"] as? Boolean ?: true, clr("key.color"),
            num("icon.size", 30), clr("icon.tint"),
        )
    }

    /** Approximate relative luminance (0..1) of the deck background, for choosing readable header colors. */
    fun backgroundLuminance(l: Look, imageLuminance: Float? = null): Float {
        fun lum(c: Int) = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
        val base = when (l.bgType) {
            "oled" -> 0f
            "color" -> lum(l.bgColor)
            "gradient" -> l.bgColors.map(::lum).average().toFloat()
            "image" -> imageLuminance ?: 0.3f
            else -> return -1f
        }
        return if (l.bgType == "image") base * (1 - l.dim / 100f) else base
    }

    // --- themes -----------------------------------------------------------------------------

    class Theme(val id: String, val name: String, val look: JSONObject)
    private fun theme(id: String, name: String, json: String) = Theme(id, name, JSONObject(json).put("theme", id))
    val themes: List<Theme> = listOf(
        theme("studio", "Studio", """{"bg":{"type":"gradient","colors":["#1B2230","#0E121A"],"angle":180},"font":{"family":"system","weight":500},"key":{"radius":20,"gap":6,"border":1,"gradient":true,"shadow":30},"icon":{"size":30}}"""),
        theme("neon", "Neon", """{"bg":{"type":"color","color":"#07040F"},"font":{"family":"mono","weight":700,"color":"#E0FFFF","upper":true,"scale":90},"key":{"radius":14,"gap":8,"border":2,"borderColor":"#00F0FF","glow":70,"gradient":false,"color":"#120B22"},"icon":{"size":32,"tint":"#00F0FF"}}"""),
        theme("minimal", "Minimal", """{"bg":{"type":"color","color":"#F4F4F2"},"font":{"family":"sans","weight":400,"color":"#222222"},"key":{"radius":12,"gap":10,"border":1,"borderColor":"#DDDDDD","gradient":false,"shadow":20,"color":"#FFFFFF"},"icon":{"size":28,"tint":"#222222"}}"""),
        theme("paper", "Paper", """{"bg":{"type":"color","color":"#F1E7D0"},"font":{"family":"serif","weight":500,"color":"#4A3B22"},"key":{"radius":8,"gap":8,"border":2,"borderColor":"#C9B48A","gradient":false,"shadow":15,"color":"#FBF4E4"},"icon":{"size":28,"tint":"#6B5330"}}"""),
        theme("oled", "OLED", """{"bg":{"type":"oled"},"font":{"family":"system","weight":500,"color":"#EDEDED"},"key":{"radius":28,"gap":8,"border":1,"borderColor":"#262626","gradient":false,"color":"#0B0B0B"},"icon":{"size":30}}"""),
        theme("sunset", "Sunset", """{"bg":{"type":"gradient","colors":["#FF7E5F","#FEB47B","#6A3093"],"angle":135},"font":{"family":"rounded","weight":600,"color":"#FFFFFF"},"key":{"radius":26,"gap":8,"border":0,"gradient":false,"shadow":40,"color":"#2B1630"},"icon":{"size":32}}"""),
        theme("ocean", "Ocean", """{"bg":{"type":"gradient","colors":["#0F2027","#203A43","#2C5364"],"angle":90},"font":{"family":"condensed","weight":500,"color":"#E6F6FF"},"key":{"radius":18,"gap":6,"border":1,"borderColor":"#4FA3C7","gradient":true,"glow":20,"color":"#16384A"},"icon":{"size":30,"tint":"#9BDCF5"}}"""),
    )
    fun theme(id: String): Theme? = themes.firstOrNull { it.id == id }
    fun themeJson(): JSONArray = JSONArray().apply { themes.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("look", JSONObject(it.look.toString()))) } }
}

/** Validation of uploaded images and fonts; the byte checks are deliberately strict and cheap. */
object MacroAssetsCore {
    const val MAX_BYTES = 1 shl 20
    const val MAX_FILES = 40
    private val idPattern = Regex("a-[a-z0-9]{6,32}")
    fun isId(s: String) = idPattern.matches(s)
    fun newId(): String = "a-" + ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** File extension for the detected format of [kind] ("image" or "font"), or null when the bytes are not that kind. */
    fun extension(kind: String, b: ByteArray): String? {
        fun at(i: Int, vararg v: Int) = b.size > i + v.size && v.indices.all { (b[i + it].toInt() and 255) == v[it] }
        fun text(i: Int, s: String) = b.size > i + s.length && s.indices.all { b[i + it].toInt() == s[it].code }
        return when (kind) {
            "image" -> when {
                at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
                at(0, 0x89, 0x50, 0x4E, 0x47) -> "png"
                text(0, "RIFF") && text(8, "WEBP") -> "webp"
                else -> null
            }
            "font" -> when {
                at(0, 0, 1, 0, 0) || text(0, "true") -> "ttf"
                text(0, "OTTO") -> "otf"
                else -> null
            }
            else -> null
        }
    }
    fun mime(ext: String) = when (ext) { "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "ttf" -> "font/ttf"; "otf" -> "font/otf"; else -> "application/octet-stream" }
    fun kindOf(ext: String) = if (ext in setOf("ttf", "otf")) "font" else "image"
}
