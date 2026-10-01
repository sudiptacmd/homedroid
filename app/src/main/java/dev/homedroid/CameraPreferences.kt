package dev.homedroid

import android.content.Context
import org.json.JSONObject

/** Camera IDs are stable on a device. Capture settings are independent of browser sessions. */
class CameraPreferences(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ipcam", Context.MODE_PRIVATE)
    var monitorId: String?
        get() = prefs.getString("monitor", null)
        set(value) { prefs.edit().putString("monitor", value).apply() }
    var quotaMb: Int
        get() = prefs.getInt("quotaMb", 2048)
        set(value) { require(value in 128..102400) { "Storage must be between 128 and 102400 MB" }; prefs.edit().putInt("quotaMb", value).apply() }

    fun get(id: String) = CameraOptions.parse(JSONObject(prefs.getString("camera:$id", "{}")!!))
    fun save(id: String, body: JSONObject): CameraOptions {
        val options = CameraOptions.parse(body)
        prefs.edit().putString("camera:$id", options.json().toString()).apply()
        return options
    }
}

data class CameraOptions(
    val resolution: Int = 480, val fps: Int = 5, val rotation: Int = 0,
    val mode: String = "motion", val sensitivity: Int = 10,
    val quietSeconds: Int = 10, val clipSeconds: Int = 60,
) {
    fun json() = JSONObject().put("resolution", resolution).put("fps", fps).put("rotation", rotation)
        .put("mode", mode).put("sensitivity", sensitivity).put("quietSeconds", quietSeconds).put("clipSeconds", clipSeconds)
    companion object {
        fun parse(o: JSONObject): CameraOptions {
            val v = CameraOptions(o.optInt("resolution", 480), o.optInt("fps", 5), o.optInt("rotation", 0),
                o.optString("mode", "motion"), o.optInt("sensitivity", 10), o.optInt("quietSeconds", 10), o.optInt("clipSeconds", 60))
            require(v.resolution in setOf(240, 480, 720, 1080)) { "Choose 240p, 480p, 720p or 1080p" }
            require(v.fps in setOf(2, 5, 10, 15, 30)) { "Choose 2, 5, 10, 15 or 30 FPS" }
            require(v.rotation in setOf(0, 90, 180, 270)) { "Choose a rotation of 0, 90, 180 or 270 degrees" }
            require(v.mode in setOf("motion", "watch")) { "Choose motion recording or monitoring only" }
            require(v.sensitivity in 1..50) { "Motion threshold must be 1–50 percent" }
            require(v.quietSeconds in 2..120) { "Quiet period must be 2–120 seconds" }
            require(v.clipSeconds in 5..1800) { "Maximum clip length must be 5–1800 seconds" }
            return v
        }
    }
}
