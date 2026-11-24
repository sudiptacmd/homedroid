package dev.homedroid

import android.content.Context
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Local images and fonts for the deck look: one small file each, referenced by id from macropads.json. */
class MacroAssets private constructor(ctx: Context) {
    companion object {
        @Volatile private var instance: MacroAssets? = null
        fun get(ctx: Context): MacroAssets = instance ?: synchronized(this) { instance ?: MacroAssets(ctx.applicationContext).also { instance = it } }
        /** Total size of embedded assets in an exported deck; above this the export references ids only. */
        const val EXPORT_CAP = 1_500_000
    }
    private val dir = File(ctx.filesDir, "macropad-assets").apply { mkdirs() }
    private fun files() = dir.listFiles { f -> f.extension != "name" && MacroAssetsCore.isId(f.nameWithoutExtension) }.orEmpty()
    fun file(id: String): File? = if (MacroAssetsCore.isId(id)) files().firstOrNull { it.nameWithoutExtension == id } else null
    fun kind(id: String): String? = file(id)?.let { MacroAssetsCore.kindOf(it.extension) }
    fun has(id: String, kind: String) = kind(id) == kind
    fun mime(id: String) = file(id)?.let { MacroAssetsCore.mime(it.extension) }

    /** Stores validated bytes and returns the new asset id. Fonts must also load as a typeface. */
    @Synchronized fun put(kind: String, bytes: ByteArray, name: String = ""): String {
        require(bytes.isNotEmpty() && bytes.size <= MacroAssetsCore.MAX_BYTES) { "Files must be 1 byte–1 MB" }
        val ext = MacroAssetsCore.extension(kind, bytes) ?: throw IllegalArgumentException(
            if (kind == "font") "Use a .ttf or .otf font file" else if (kind == "image") "Use a JPEG, PNG or WebP image" else "kind must be image or font")
        if (files().size >= MacroAssetsCore.MAX_FILES) throw IllegalArgumentException("Too many images and fonts; remove unused ones first")
        val id = MacroAssetsCore.newId()
        val target = File(dir, "$id.$ext")
        target.writeBytes(bytes)
        if (kind == "font") try { Typeface.createFromFile(target) ?: error("bad font") } catch (_: Exception) {
            target.delete(); throw IllegalArgumentException("That file is not a usable font")
        }
        name.trim().take(40).takeIf { it.isNotEmpty() }?.let { File(dir, "$id.name").writeText(it) }
        return id
    }
    @Synchronized fun delete(id: String) { file(id)?.delete(); File(dir, "$id.name").delete() }
    fun describe(f: File) = JSONObject().put("id", f.nameWithoutExtension).put("kind", MacroAssetsCore.kindOf(f.extension)).put("bytes", f.length())
        .put("name", File(dir, "${f.nameWithoutExtension}.name").takeIf { it.exists() }?.readText().orEmpty())
    fun list(): JSONArray = JSONArray().apply { files().sortedBy { it.nameWithoutExtension }.forEach { put(describe(it)) } }
    fun referenced(model: JSONObject, id: String) = model.toString().contains("\"$id\"")

    /** Deletes assets no deck refers to. Recent files are kept so an upload can precede the save that uses it. */
    @Synchronized fun prune(model: JSONObject) {
        val text = model.toString(); val cutoff = System.currentTimeMillis() - 10 * 60_000
        files().filter { !text.contains("\"${it.nameWithoutExtension}\"") && it.lastModified() < cutoff }.forEach { delete(it.nameWithoutExtension) }
    }

    /** `assets` object for an exported deck, or null when the referenced files exceed [EXPORT_CAP]. */
    fun export(model: JSONObject): JSONObject? {
        val text = model.toString()
        val used = files().filter { text.contains("\"${it.nameWithoutExtension}\"") }
        if (used.isEmpty()) return JSONObject()
        if (used.sumOf { it.length() } > EXPORT_CAP) return null
        return JSONObject().apply { used.forEach { f -> put(f.nameWithoutExtension, describe(f).put("data", Base64.getEncoder().encodeToString(f.readBytes()))) } }
    }
    /** Stores the assets embedded by [export]; existing ids are kept. */
    fun import(assets: JSONObject) {
        require(assets.length() <= MacroAssetsCore.MAX_FILES) { "Too many embedded files" }
        assets.keys().forEach { id ->
            require(MacroAssetsCore.isId(id)) { "Invalid asset id" }
            if (file(id) != null) return@forEach
            val o = assets.getJSONObject(id)
            val bytes = Base64.getDecoder().decode(o.getString("data"))
            val newId = put(o.getString("kind"), bytes, o.optString("name"))
            // Keep the original id so references in the imported deck still resolve.
            val moved = file(newId) ?: return@forEach
            moved.renameTo(File(dir, "$id.${moved.extension}")); File(dir, "$newId.name").takeIf { it.exists() }?.renameTo(File(dir, "$id.name"))
        }
    }
}
