package dev.homedroid

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureRequest as R
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Camera2 capability discovery and request construction. Never guesses support from lens facing. */
internal object DesktopCameraControls {
    val focus = linkedMapOf("continuous" to R.CONTROL_AF_MODE_CONTINUOUS_VIDEO, "auto" to R.CONTROL_AF_MODE_AUTO, "manual" to R.CONTROL_AF_MODE_OFF)
    val wb = linkedMapOf("auto" to R.CONTROL_AWB_MODE_AUTO, "incandescent" to R.CONTROL_AWB_MODE_INCANDESCENT,
        "fluorescent" to R.CONTROL_AWB_MODE_FLUORESCENT, "daylight" to R.CONTROL_AWB_MODE_DAYLIGHT, "cloudy" to R.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
    val bands = linkedMapOf("auto" to R.CONTROL_AE_ANTIBANDING_MODE_AUTO, "50hz" to R.CONTROL_AE_ANTIBANDING_MODE_50HZ, "60hz" to R.CONTROL_AE_ANTIBANDING_MODE_60HZ)
    fun describe(id: String, c: C): JSONObject {
        val map = c.get(C.SCALER_STREAM_CONFIGURATION_MAP)
        // Bound allocation and desktop decoder sizes, in either output orientation.
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().filter { it.width in 2..4096 && it.height in 2..4096 && it.width.toLong() * it.height <= 4096L * 2160 && it.width % 2 == 0 && it.height % 2 == 0 }
        val af = (c.get(C.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())
        val distance = c.get(C.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val manual = (c.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()).contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val keys = c.availableCaptureRequestKeys
        val cap = JSONObject().put("torch", c.get(C.FLASH_INFO_AVAILABLE) == true)
            .put("focus", JSONArray(focus.filter { (k, v) -> v in af && (k != "manual" || distance > 0) }.keys))
            .put("focusDistance", distance.toDouble()).put("tapFocus", (c.get(C.CONTROL_MAX_REGIONS_AF) ?: 0) > 0 && R.CONTROL_AF_MODE_AUTO in af)
            .put("aeLock", c.get(C.CONTROL_AE_LOCK_AVAILABLE) == true).put("awbLock", c.get(C.CONTROL_AWB_LOCK_AVAILABLE) == true)
            .put("wb", JSONArray(wb.filterValues { it in (c.get(C.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()) }.keys))
            .put("antiBanding", JSONArray(bands.filterValues { it in (c.get(C.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: intArrayOf()) }.keys))
            .put("stabilization", R.CONTROL_VIDEO_STABILIZATION_MODE_ON in (c.get(C.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()))
            .put("manualSensor", manual && c.get(C.SENSOR_INFO_SENSITIVITY_RANGE) != null && c.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) != null)
            .put("quality", JSONArray(listOf(1, 100))).put("mirror", true).put("flip", true).put("rotate", JSONArray(listOf(0, 90, 180, 270)))
        if (Build.VERSION.SDK_INT >= 30 && R.CONTROL_ZOOM_RATIO in keys && c.get(C.CONTROL_ZOOM_RATIO_RANGE) != null) {
            val z = c.get(C.CONTROL_ZOOM_RATIO_RANGE)!!; cap.put("zoom", JSONArray(listOf(z.lower, z.upper)))
        } else if (R.SCALER_CROP_REGION in keys && c.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE) != null) {
            cap.put("zoom", JSONArray(listOf(1f, c.get(C.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)))
        }
        val ev = c.get(C.CONTROL_AE_COMPENSATION_RANGE); val step = c.get(C.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        if (ev != null && ev.lower < ev.upper && step > 0) cap.put("ev", JSONArray(listOf(ev.lower * step, ev.upper * step))).put("evStep", step)
        if (cap.getBoolean("manualSensor")) {
            val iso = c.get(C.SENSOR_INFO_SENSITIVITY_RANGE)!!; val shutter = c.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
            cap.put("iso", JSONArray(listOf(iso.lower, iso.upper))).put("shutterNs", JSONArray(listOf(shutter.lower, shutter.upper)))
        }
        return JSONObject().put("id", id).put("facing", when (c.get(C.LENS_FACING)) { C.LENS_FACING_FRONT -> "front"; C.LENS_FACING_BACK -> "back"; else -> "external" })
            .put("sensorOrientation", c.get(C.SENSOR_ORIENTATION) ?: 0)
            .put("sizes", JSONArray().apply { sizes.forEach { put(JSONArray(listOf(it.width, it.height))) } })
            .put("fps", JSONArray(c.get(C.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.upper }.filter { it in 1..120 }.distinct().sorted()))
            .put("capabilities", cap)
    }
    fun apply(b: R.Builder, c: C, o: JSONObject, tap: JSONObject? = null) {
        b.set(R.CONTROL_MODE, R.CONTROL_MODE_AUTO)
        o.optString("focus").takeIf { it in focus }?.let { b.set(R.CONTROL_AF_MODE, focus.getValue(it)) }
        if (o.optString("focus") == "manual") b.set(R.LENS_FOCUS_DISTANCE, o.getDouble("focusDistance").toFloat())
        if (o.has("torch")) b.set(R.FLASH_MODE, if (o.getBoolean("torch")) R.FLASH_MODE_TORCH else R.FLASH_MODE_OFF)
        if (o.has("zoom")) {
            val z = o.getDouble("zoom").toFloat()
            if (Build.VERSION.SDK_INT >= 30 && R.CONTROL_ZOOM_RATIO in c.availableCaptureRequestKeys && c.get(C.CONTROL_ZOOM_RATIO_RANGE) != null) b.set(R.CONTROL_ZOOM_RATIO, z)
            else c.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { r ->
                val dx = (r.width() * (1 - 1 / z) / 2).toInt(); val dy = (r.height() * (1 - 1 / z) / 2).toInt()
                b.set(R.SCALER_CROP_REGION, Rect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy))
            }
        }
        val manual = o.optBoolean("manualSensor")
        b.set(R.CONTROL_AE_MODE, if (manual) R.CONTROL_AE_MODE_OFF else R.CONTROL_AE_MODE_ON)
        if (manual) { b.set(R.SENSOR_SENSITIVITY, o.getInt("iso")); b.set(R.SENSOR_EXPOSURE_TIME, o.getLong("shutterNs")) }
        if (o.has("ev")) b.set(R.CONTROL_AE_EXPOSURE_COMPENSATION, (o.getDouble("ev") / c.get(C.CONTROL_AE_COMPENSATION_STEP)!!.toDouble()).roundToInt())
        if (o.has("aeLock")) b.set(R.CONTROL_AE_LOCK, o.getBoolean("aeLock"))
        if (o.has("awbLock")) b.set(R.CONTROL_AWB_LOCK, o.getBoolean("awbLock"))
        if (o.has("wb")) b.set(R.CONTROL_AWB_MODE, wb.getValue(o.getString("wb")))
        if (o.has("antiBanding")) b.set(R.CONTROL_AE_ANTIBANDING_MODE, bands.getValue(o.getString("antiBanding")))
        if (o.has("stabilization")) b.set(R.CONTROL_VIDEO_STABILIZATION_MODE, if (o.getBoolean("stabilization")) R.CONTROL_VIDEO_STABILIZATION_MODE_ON else R.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(R.CONTROL_AF_TRIGGER, R.CONTROL_AF_TRIGGER_IDLE)
        if (tap?.has("x") == true) {
            val r = b.get(R.SCALER_CROP_REGION) ?: c.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val (x, y) = DesktopCameraCore.sensorPoint(tap.getDouble("x"), tap.getDouble("y"), o)
            // Account for the center crop to the stream aspect ratio as well as digital zoom.
            val aspect = o.getDouble("width") / o.getDouble("height")
            val w = minOf(r.width().toDouble(), r.height() * aspect); val h = minOf(r.height().toDouble(), r.width() / aspect)
            val cx = r.centerX() + (x - 0.5) * w; val cy = r.centerY() + (y - 0.5) * h
            val rw = (w / 10).toInt().coerceAtLeast(1); val rh = (h / 10).toInt().coerceAtLeast(1)
            val left = (cx - rw / 2).toInt().coerceIn(r.left, r.right - rw); val top = (cy - rh / 2).toInt().coerceIn(r.top, r.bottom - rh)
            b.set(R.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(left, top, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX)))
        }
    }
}
