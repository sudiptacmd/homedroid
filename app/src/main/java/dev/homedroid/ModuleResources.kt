package dev.homedroid

import android.content.Context
import org.json.JSONObject

/** Typical memory and CPU use of a module, from assets/resources.json. */
data class ModuleResources(val ram: String, val cpu: String, val note: String) {
    /** "Memory 150–200 MB · CPU low": one line for cards. */
    val summary get() = "Memory $ram · CPU $cpu"

    fun json(): JSONObject = JSONObject().put("ram", ram).put("cpu", cpu).put("note", note)

    companion object {
        @Volatile private var cache: Map<String, ModuleResources>? = null

        fun all(ctx: Context): Map<String, ModuleResources> = cache
            ?: parse(ctx.assets.open("resources.json").bufferedReader().use { it.readText() }).also { cache = it }

        fun of(ctx: Context, id: String): ModuleResources? = all(ctx)[id]

        fun parse(json: String): Map<String, ModuleResources> {
            val o = JSONObject(json)
            return o.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { id ->
                val r = o.getJSONObject(id)
                ModuleResources(r.getString("ram"), r.getString("cpu"), r.optString("note"))
            }
        }
    }
}
