package dev.homedroid

import org.json.JSONObject
import java.io.File

/**
 * The dashboard's layout and look (overview widgets, sidebar order, theme, density, custom
 * links), kept on the phone so they follow the user to every browser. The dashboard owns the
 * format; the phone only checks it is a JSON object of reasonable size.
 */
class UiPrefs(private val file: File) {
    fun handle(r: Request, seg: List<String>): Response = when {
        seg.isEmpty() && r.method == "GET" -> Response.json(read())
        seg.isEmpty() && r.method == "PUT" -> {
            val text = String(r.body, Charsets.UTF_8)
            if (text.length > MAX) Response.error(413, "Layout is too large")
            else try {
                val o = JSONObject(text).put("updated", System.currentTimeMillis())
                write(o)
                Response.json(o)
            } catch (_: org.json.JSONException) {
                Response.error(400, "Expected a JSON object")
            }
        }
        seg.isEmpty() && r.method == "DELETE" -> { file.delete(); Response.ok() }
        else -> Response.error(404, "No such endpoint")
    }

    @Synchronized
    private fun read(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    @Synchronized
    private fun write(o: JSONObject) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) throw java.io.IOException("could not save the layout")
    }

    companion object {
        const val MAX = 256 * 1024
    }
}
