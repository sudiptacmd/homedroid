package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject

/** Android-free, atomic validation of camera control patches against advertised capabilities. */
object DesktopCameraCore {
    val fields = setOf("camera", "width", "height", "fps", "quality", "zoom", "torch", "focus", "focusDistance", "x", "y",
        "ev", "aeLock", "wb", "awbLock", "manualSensor", "iso", "shutterNs", "stabilization", "antiBanding", "mirror", "rotate", "flip", "reset")
    fun numbers(a: JSONArray) = (0 until a.length()).map { a.getDouble(it) }
    private fun number(o: JSONObject, k: String, min: Double, max: Double, integer: Boolean = false): Double {
        val n = o.opt(k)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() in min..max && (!integer || n.toDouble() % 1.0 == 0.0)) { "$k must be ${if (integer) "an integer " else ""}in $min..$max" }
        return n.toDouble()
    }
    fun defaults(camera: JSONObject): JSONObject {
        val cap = camera.getJSONObject("capabilities")
        val sizes = camera.getJSONArray("sizes")
        val size = (0 until sizes.length()).map { sizes.getJSONArray(it) }.minByOrNull {
            kotlin.math.abs(it.getInt(0) - 1280) + kotlin.math.abs(it.getInt(1) - 720)
        } ?: throw IllegalArgumentException("Camera has no usable sizes")
        val rates = numbers(camera.getJSONArray("fps"))
        val o = JSONObject().put("camera", camera.getString("id")).put("width", size.getInt(0)).put("height", size.getInt(1))
            .put("fps", rates.minByOrNull { kotlin.math.abs(it - 30) } ?: 30).put("quality", 80)
            .put("mirror", false).put("rotate", 0).put("flip", false)
        for (key in listOf("torch", "aeLock", "awbLock", "manualSensor", "stabilization")) if (cap.optBoolean(key)) o.put(key, false)
        cap.optJSONArray("zoom")?.let { o.put("zoom", 1.0.coerceIn(it.getDouble(0), it.getDouble(1))) }
        if (cap.has("ev")) o.put("ev", 0)
        for ((key, preferred) in listOf("focus" to "continuous", "wb" to "auto", "antiBanding" to "auto")) {
            val a = cap.getJSONArray(key); val values = (0 until a.length()).map { a.getString(it) }
            if (values.isNotEmpty()) o.put(key, if (preferred in values) preferred else values.first())
        }
        return o
    }
    fun merge(current: JSONObject, patch: JSONObject, camera: JSONObject): JSONObject {
        patch.keys().forEach { require(it in fields) { "Unknown camera control: $it" } }
        val cap = camera.getJSONObject("capabilities")
        val reset = patch.opt("reset")?.also { require(it is Boolean) { "reset must be boolean" } } == true
        val o = if (reset || current.optString("camera") != camera.getString("id")) defaults(camera) else JSONObject(current.toString())
        for (k in patch.keys()) {
            val v = patch.get(k)
            when (k) {
                "reset", "camera" -> Unit
                "mirror", "flip" -> require(v is Boolean) { "$k must be boolean" }
                "torch", "aeLock", "awbLock", "manualSensor", "stabilization" -> {
                    require(v is Boolean) { "$k must be boolean" }; require(cap.optBoolean(k)) { "$k is unsupported on this camera" }
                }
                "width", "height" -> number(patch, k, 2.0, 4096.0, true)
                "fps" -> { number(patch, k, 1.0, 120.0, true); require(v.toString().toDouble() in numbers(camera.getJSONArray("fps"))) { "Unsupported fps" } }
                "quality" -> number(patch, k, 1.0, 100.0, true)
                "rotate" -> { number(patch, k, 0.0, 270.0, true); require((v as Number).toInt() % 90 == 0) { "rotate must be 0, 90, 180 or 270" } }
                "zoom", "ev", "iso", "shutterNs" -> {
                    val bounds = cap.optJSONArray(k) ?: throw IllegalArgumentException("$k is unsupported on this camera")
                    number(patch, k, bounds.getDouble(0), bounds.getDouble(1), k != "zoom" && k != "ev")
                    if (k == "ev") { val step = cap.getDouble("evStep"); val steps = (v as Number).toDouble() / step
                        require(kotlin.math.abs(steps - kotlin.math.round(steps)) < 0.0001) { "ev must be a multiple of $step" } }
                }
                "focusDistance" -> { require(cap.optDouble("focusDistance", 0.0) > 0) { "Manual focus is unsupported" }; number(patch, k, 0.0, cap.getDouble(k)) }
                "focus", "wb", "antiBanding" -> {
                    require(v is String && (0 until cap.getJSONArray(k).length()).any { cap.getJSONArray(k).get(it) == v }) { "Unsupported $k: $v" }
                }
                "x", "y" -> { require(cap.optBoolean("tapFocus")) { "Tap focus is unsupported" }; number(patch, k, 0.0, 1.0) }
            }
            if (k !in setOf("reset", "camera", "x", "y")) o.put(k, v)
        }
        require(patch.has("x") == patch.has("y")) { "Tap focus requires both x and y" }
        if (patch.has("x")) {
            require(!patch.has("focus") || patch.getString("focus") == "auto") { "Tap focus requires auto focus" }
            o.put("focus", "auto")
        }
        val sizes = camera.getJSONArray("sizes")
        require((0 until sizes.length()).any { sizes.getJSONArray(it).let { s -> s.getInt(0) == o.getInt("width") && s.getInt(1) == o.getInt("height") } }) { "Unsupported resolution" }
        require(!patch.has("focusDistance") || o.optString("focus") == "manual") { "focusDistance requires manual focus" }
        require(o.optString("focus") != "manual" || o.has("focusDistance")) { "Manual focus requires focusDistance (diopters)" }
        require(!(patch.has("iso") || patch.has("shutterNs")) || o.optBoolean("manualSensor")) { "ISO/shutter require manualSensor=true" }
        if (o.optBoolean("manualSensor")) {
            require(o.has("iso") && o.has("shutterNs")) { "Manual sensor requires iso and shutterNs" }
            require(!o.optBoolean("aeLock")) { "AE lock is unavailable in manual exposure" }
        }
        return o
    }
    /** Undo output-space flips and clockwise rotation for normalized tap coordinates. */
    fun sensorPoint(x: Double, y: Double, o: JSONObject): Pair<Double, Double> {
        val px = if (o.optBoolean("mirror")) 1 - x else x
        val py = if (o.optBoolean("flip")) 1 - y else y
        return when (o.optInt("rotate")) { 90 -> py to 1 - px; 180 -> 1 - px to 1 - py; 270 -> 1 - py to px; else -> px to py }
    }
}
