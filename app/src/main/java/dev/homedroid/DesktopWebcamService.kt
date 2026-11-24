package dev.homedroid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Both pipelines reserve before opening hardware, including IPCam's standalone torch. */
internal object CameraOwnership {
    private var owner: Any? = null
    @Synchronized fun acquire(who: Any): Boolean {
        if (owner != null && owner !== who) return false
        owner = who; return true
    }
    @Synchronized fun release(who: Any) { if (owner === who) owner = null }
}

/** Armed from a visible activity, as Android requires for foreground camera permission. */
class DesktopWebcamService : Service() {
    companion object {
        @Volatile var instance: DesktopWebcamService? = null
            private set
        fun arm(ctx: Context) { ctx.startForegroundService(Intent(ctx, DesktopWebcamService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, DesktopWebcamService::class.java)) }
        fun cameras(ctx: Context): JSONArray = JSONArray().apply {
            val manager = ctx.getSystemService(CameraManager::class.java)
            runCatching {
                manager.cameraIdList.forEach { id ->
                    val c = manager.getCameraCharacteristics(id)
                    put(DesktopCameraControls.describe(id, c))
                }
            }
        }
    }
    private class Capture(val computer: String) {
        @Volatile var active = true
        @Volatile var frame: Frame? = null
        var settings = JSONObject()
        var generation = 0
        var pending: CompletableFuture<Response>? = null
        val ready = CompletableFuture<String?>()
        val lock = Object()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var builder: CaptureRequest.Builder? = null
        var characteristics: CameraCharacteristics? = null
        var sequence = 0L
        var lastFrameAt = 0L
    }
    private data class Frame(val bytes: ByteArray, val sequence: Long, val time: Long)
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    @Volatile private var current: Capture? = null
    @Volatile private var stopping = false
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("desktop-camera").apply { start() }; handler = Handler(thread.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("desktop-webcam", "Desktop webcam", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 20, Intent(this, MacroPadActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 21, Intent(this, DesktopWebcamService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "desktop-webcam").setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Desktop webcam ready").setContentText("Paired computers can request the camera")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop webcam", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(8, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(8, notification)
            instance = this
        } catch (e: Exception) { Jobs.line("Desktop webcam could not start: ${e.message}"); stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }
    fun revoke(computer: String) { current?.takeIf { it.computer == computer }?.let(::finish) }
    fun stopStream() { current?.let(::finish) }
    fun stream(computer: String, query: Map<String, String>, authorized: () -> Boolean): Response {
        fun number(key: String, default: Int, range: IntRange): Int = (query[key]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid $key") } ?: default)
            .also { require(it in range) { "Invalid $key" } }
        val width = number("width", 1280, 1..8192); val height = number("height", 720, 1..8192)
        val fps = number("fps", 30, 1..120); val quality = number("quality", 80, 1..100)
        val capture = synchronized(this) {
            if (stopping) return Response.error(409, "Desktop webcam is stopping")
            if (current != null) return Response.error(409, "One webcam stream is already running")
            val c = Capture(computer)
            if (!CameraOwnership.acquire(c)) return Response.error(409, "IPCam is using the camera; stop its live view, recording, monitoring and torch first")
            current = c; c
        }
        handler.post { runCatching { open(capture, query["camera"] ?: "back", width, height, fps, quality) }
            .onFailure { fail(capture, it.message ?: "Could not open camera") } }
        val error = try { capture.ready.get(8, TimeUnit.SECONDS) } catch (_: Exception) { "Camera did not produce a frame; check camera privacy and permissions" }
        if (error != null) { finish(capture); return Response.error(409, error) }
        fun valid() = capture.active && !stopping && authorized()
        return Response(200, byteArrayOf(), "multipart/x-mixed-replace; boundary=hdframe", streamAuthorized = ::valid,
            stream = { out ->
                var sequence = -1L
                var lastFrame = SystemClock.elapsedRealtime()
                while (valid()) {
                    val frame = synchronized(capture.lock) {
                        if (capture.frame?.sequence == sequence && capture.active) capture.lock.wait(1000)
                        capture.frame
                    }
                    if (!valid()) break
                    if (frame != null && frame.sequence != sequence) {
                        sequence = frame.sequence; lastFrame = SystemClock.elapsedRealtime()
                        out.write(DesktopCore.multipart(frame.bytes, sequence, frame.time)); out.flush()
                    } else if (SystemClock.elapsedRealtime() - lastFrame > 10_000) break
                }
            }, onClose = { finish(capture) })
    }
    @Suppress("DEPRECATION", "MissingPermission")
    private fun open(c: Capture, requested: String, width: Int, height: Int, fps: Int, quality: Int) {
        if (!c.active) return
        val manager = getSystemService(CameraManager::class.java)
        val id = resolveCamera(requested)
        val generation = ++c.generation
        val characteristics = manager.getCameraCharacteristics(id); c.characteristics = characteristics
        val description = DesktopCameraControls.describe(id, characteristics)
        val usable = description.getJSONArray("sizes")
        val sizes = (0 until usable.length()).map { usable.getJSONArray(it).let { s -> android.util.Size(s.getInt(0), s.getInt(1)) } }
        val size = sizes.minByOrNull { abs(it.width - width).toLong() + abs(it.height - height) } ?: error("No supported camera sizes")
        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .minByOrNull { abs(it.upper - fps) * 100 + abs(it.lower - fps) } ?: Range(15, 30)
        if (c.settings.length() == 0) c.settings = DesktopCameraCore.defaults(description)
        c.settings.put("camera", id).put("width", size.width).put("height", size.height).put("fps", range.upper).put("quality", quality)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2); c.reader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            image?.use {
                if (c.active && c.generation == generation && SystemClock.elapsedRealtime() - c.lastFrameAt >= 1000 / fps) {
                    c.lastFrameAt = SystemClock.elapsedRealtime()
                    runCatching {
                        // REALTIME sensor timestamps share elapsedRealtimeNanos' clock. For devices
                        // with an unspecified clock, use arrival time before JPEG conversion.
                        val time = System.currentTimeMillis() - if (
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                        ) ((SystemClock.elapsedRealtimeNanos() - it.timestamp) / 1_000_000).coerceAtLeast(0) else 0
                        val frame = Frame(jpeg(it, c.settings), ++c.sequence, time)
                        synchronized(c.lock) { c.frame = frame; c.lock.notifyAll() }
                        c.ready.complete(null)
                        c.pending?.complete(Response.json(JSONObject().put("ok", true).put("settings", JSONObject(c.settings.toString())))); c.pending = null
                    }.onFailure { fail(c, it.message ?: "JPEG encoding failed") }
                }
            }
        }, handler)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!c.active || c.generation != generation) { camera.close(); return }
                c.device = camera
                try {
                    camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (!c.active || c.generation != generation) { session.close(); return }
                            c.session = session
                            runCatching {
                                c.builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                                    DesktopCameraControls.apply(this, characteristics, c.settings)
                                }
                                session.setRepeatingRequest(c.builder!!.build(), null, handler)
                            }.onFailure { fail(c, it.message ?: "Capture request failed") }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); if (c.generation == generation) fail(c, "Camera configuration is unsupported") }
                    }, handler)
                } catch (e: Exception) { fail(c, e.message ?: "Camera configuration failed") }
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (c.generation == generation) fail(c, "Camera disconnected") }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (c.generation == generation) fail(c, "Camera unavailable ($error)") }
        }, handler)
    }
    private fun resolveCamera(requested: String): String {
        val manager = getSystemService(CameraManager::class.java)
        return if (requested in setOf("front", "back")) manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                if (requested == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        } ?: throw IllegalArgumentException("No $requested camera") else requested.also { require(it in manager.cameraIdList) { "Unknown camera" } }
    }
    /** Transform Y and subsampled chroma before encoding: preview and virtual output are identical. */
    private fun jpeg(image: Image, o: JSONObject): ByteArray {
        val rotate = o.optInt("rotate"); val swap = rotate == 90 || rotate == 270
        val w = if (swap) image.height else image.width; val h = if (swap) image.width else image.height
        val data = ByteArray(w * h * 3 / 2); var offset = 0
        fun sample(plane: Int, x: Int, y: Int, ow: Int, oh: Int): Byte {
            val px = if (o.optBoolean("mirror")) ow - 1 - x else x
            val py = if (o.optBoolean("flip")) oh - 1 - y else y
            val (sx, sy) = when (rotate) { 90 -> py to ow - 1 - px; 180 -> ow - 1 - px to oh - 1 - py; 270 -> oh - 1 - py to px; else -> px to py }
            val p = image.planes[plane]
            return p.buffer.get(sy * p.rowStride + sx * p.pixelStride)
        }
        for (y in 0 until h) for (x in 0 until w) data[offset++] = sample(0, x, y, w, h)
        for (y in 0 until h / 2) for (x in 0 until w / 2) {
            data[offset++] = sample(2, x, y, w / 2, h / 2); data[offset++] = sample(1, x, y, w / 2, h / 2)
        }
        return ByteArrayOutputStream().also { check(YuvImage(data, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), o.getInt("quality"), it)) }.toByteArray()
    }
    fun control(computer: String, body: JSONObject): Response {
        val c = current?.takeIf { it.active && it.computer == computer } ?: return Response.error(409, "This computer has no active webcam stream")
        val result = CompletableFuture<Response>()
        handler.post {
            if (!c.active || c.pending != null) { result.complete(Response.error(409, "Camera is stopping or reconfiguring")); return@post }
            try {
                if (body.has("camera")) require(body.get("camera") is String) { "camera must be an id, front or back" }
                val id = resolveCamera(if (body.has("camera")) body.getString("camera") else c.settings.getString("camera"))
                val description = DesktopCameraControls.describe(id, getSystemService(CameraManager::class.java).getCameraCharacteristics(id))
                val next = DesktopCameraCore.merge(c.settings, body, description)
                val restart = listOf("camera", "width", "height", "fps").any { next.get(it).toString() != c.settings.get(it).toString() }
                if (restart) {
                    closePipeline(c); c.settings = next; c.pending = result
                    open(c, id, next.getInt("width"), next.getInt("height"), next.getInt("fps"), next.getInt("quality"))
                    val generation = c.generation
                    handler.postDelayed({ if (c.active && c.generation == generation && c.pending != null) fail(c, "Camera restart timed out") }, 8000)
                } else {
                    val device = c.device ?: error("Camera is starting")
                    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(c.reader!!.surface)
                        c.builder?.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        DesktopCameraControls.apply(this, c.characteristics!!, next, body)
                    }
                    val session = c.session ?: error("Camera is starting")
                    if (body.has("x") || body.optString("focus") == "auto") {
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        session.capture(b.build(), null, handler)
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        session.capture(b.build(), null, handler)
                        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    }
                    session.setRepeatingRequest(b.build(), null, handler)
                    c.builder = b; c.settings = next
                    result.complete(Response.json(JSONObject().put("ok", true).put("settings", JSONObject(next.toString()))))
                }
            } catch (e: Exception) {
                if (c.pending === result) { c.pending = null; fail(c, e.message ?: "Camera restart failed") }
                result.complete(Response.error(if (e is IllegalArgumentException || e is org.json.JSONException) 400 else 409, e.message ?: "Camera control failed"))
            }
        }
        return try { result.get(9, TimeUnit.SECONDS) } catch (_: Exception) { Response.error(504, "Camera control timed out; check stream state") }
    }
    private fun closePipeline(c: Capture) {
        c.generation++
        runCatching { c.session?.close() }; c.session = null
        runCatching { c.device?.close() }; c.device = null
        runCatching { c.reader?.close() }; c.reader = null; c.builder = null
    }
    private fun fail(c: Capture, message: String) { c.ready.complete(message); c.pending?.complete(Response.error(409, message)); c.pending = null; finish(c) }
    private fun finish(c: Capture) {
        c.active = false; c.ready.complete("Webcam stopped")
        synchronized(c.lock) { c.lock.notifyAll() }
        handler.post {
            closePipeline(c); c.frame = null
            c.pending?.complete(Response.error(409, "Webcam stopped")); c.pending = null
            CameraOwnership.release(c)
            synchronized(this) { if (current === c) current = null }
        }
    }
    override fun onDestroy() {
        stopping = true
        if (instance === this) instance = null
        stopStream(); handler.post { thread.quitSafely() }
        super.onDestroy()
    }
}
