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
import android.media.Image
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.view.Surface
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** An explicitly armed camera foreground service. All hardware access runs on one looper. */
class CameraService : Service() {
    companion object {
        @Volatile var instance: CameraService? = null
            private set
        private const val CHANNEL = "camera"
        private const val STOP = "dev.homedroid.STOP_CAMERA"
        private const val VIEWER_TIMEOUT_MS = 15_000L
        fun arm(ctx: Context) = ctx.startForegroundService(Intent(ctx, CameraService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CameraService::class.java))
        fun directory(ctx: Context) = File(ctx.filesDir, "camera").apply { mkdirs() }
        fun cameras(ctx: Context): List<JSONObject> {
            val manager = ctx.getSystemService(CameraManager::class.java)
            return manager.cameraIdList.map { id ->
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                    else -> "External"
                }
                JSONObject().put("id", id).put("name", "$facing camera ($id)")
                    .put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                    // The live view is sent as the sensor delivers it; the page rotates (and mirrors) it.
                    .put("rotation", c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                    .put("front", facing == "Front")
            }
        }
    }

    private val thread = HandlerThread("camera")
    private lateinit var handler: Handler
    private lateinit var manager: CameraManager
    private var speech: TextToSpeech? = null
    @Volatile private var speechReady = false
    private lateinit var microphone: Microphone
    private var wakeLock: PowerManager.WakeLock? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewReader: ImageReader? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var torchId: String? = null
    private var selected: String? = null
    private var recording = false
    private var busy = false
    private var flash = false
    private var generation = 0
    private var error: String? = null
    @Volatile private var closing = false

    // Live view: the camera the page asked to watch (kept while photos and videos interrupt it),
    // whether its preview session is open, and the newest JPEG frame for the MJPEG stream.
    private var liveWanted: String? = null
    private var liveActive = false
    private var liveTorch = false
    private val frameLock = Object()
    private var frame: ByteArray? = null
    private var frameSeq = 0L
    @Volatile private var lastViewer = 0L
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(CameraManager::class.java)
        microphone = Microphone(directory(this))
        thread.start()
        handler = Handler(thread.looper)
        if (!Config(this).cameraEnabled || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "IPCam", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            })
            val notification = notification("Camera ready for remote controls")
            if (Build.VERSION.SDK_INT >= 30) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(2, notification)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homedroid:camera")
                .apply { setReferenceCounted(false); acquire() }
            instance = this
            speech = TextToSpeech(this) { speechReady = it == TextToSpeech.SUCCESS }
        } catch (e: Exception) {
            Jobs.line("Camera could not start: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf()
        return START_NOT_STICKY // Camera access must be armed again on the phone after a restart.
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, CameraService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Homedroid IPCam").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop IPCam", stop).build()).build()
    }

    private fun notifyState(text: String) = getSystemService(NotificationManager::class.java).notify(2, notification(text))

    /** Short commands are serialized; asynchronous camera callbacks report completion via status. */
    fun request(action: String, body: JSONObject = JSONObject()): Response {
        if (closing) return Response.error(409, "Camera access is stopping")
        val result = CompletableFuture<Response>()
        handler.post {
            if (closing || !Config(this).cameraEnabled) {
                result.complete(Response.error(409, "Camera module is disabled"))
                return@post
            }
            try {
                when (action) {
                    "status" -> Unit
                    "announce" -> {
                        val text = body.getString("text").trim()
                        require(text.isNotEmpty() && text.length <= 500) { "Enter an announcement of 1–500 characters" }
                        check(speechReady) { "Text-to-speech is unavailable or still starting; check the phone speech settings" }
                        speech?.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        check(speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ipcam-announce") == TextToSpeech.SUCCESS) { "Announcement failed" }
                    }
                    "microphone/start" -> {
                        microphone.start(body.optBoolean("record"))
                        notifyState("IPCam microphone active")
                    }
                    "microphone/stop" -> { microphone.stop(); notifyState(if (recording) "Recording video" else "IPCam ready") }
                    "stop" -> { finishRecording(); cleanup() }
                    "live/start" -> {
                        val id = body.getString("id")
                        require(manager.cameraIdList.contains(id)) { "Unknown camera" }
                        lastViewer = SystemClock.elapsedRealtime()
                        if (liveWanted != id) liveTorch = false
                        liveWanted = id
                        if (!busy && !recording && !liveActive) startLive(id)
                        else if (liveActive && selected != id) startLive(id)
                        handler.postDelayed(::checkViewers, VIEWER_TIMEOUT_MS)
                    }
                    "live/stop" -> stopLive()
                    "photo", "record" -> {
                        check(!busy && !recording) { "Camera is busy; stop the current capture first" }
                        begin(body.getString("id"), action == "record", body.optBoolean("flash"))
                    }
                    "torch" -> {
                        check(!busy && !recording) { "Stop capture before changing the torch" }
                        val id = body.getString("id")
                        require(cameras(this).any { it.getString("id") == id && it.getBoolean("flash") }) { "This camera has no flash" }
                        if (liveActive && liveWanted == id) {
                            // The camera is open, so the torch goes through the live request instead.
                            liveTorch = body.getBoolean("enabled")
                            startLive(id)
                        } else {
                            torchId?.let { manager.setTorchMode(it, false) }
                            torchId = null
                            if (body.getBoolean("enabled")) {
                                manager.setTorchMode(id, true)
                                torchId = id
                            }
                            notifyState(if (torchId == null) "Camera ready for remote controls" else "Camera $id torch on")
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown camera action")
                }
                result.complete(Response.json(status()))
            } catch (e: Exception) {
                result.complete(Response.error(if (e is IllegalArgumentException) 400 else 409, e.message ?: "Camera operation failed"))
            }
        }
        return try { result.get(5, TimeUnit.SECONDS) } catch (_: Exception) {
            Response.error(503, "Camera is not responding; check its status before retrying")
        }
    }

    fun audio(after: Long) = microphone.audio(after)

    private fun status() = JSONObject().put("armed", true).put("busy", busy).put("recording", recording)
        .put("selected", selected.takeUnless { liveActive } ?: JSONObject.NULL)
        .put("torch", torchId ?: liveWanted.takeIf { liveTorch } ?: JSONObject.NULL)
        .put("live", liveWanted ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL).put("microphone", microphone.status())

    @Suppress("MissingPermission", "DEPRECATION")
    private fun begin(id: String, video: Boolean, useFlash: Boolean) {
        require(manager.cameraIdList.contains(id)) { "Unknown camera" }
        val characteristics = manager.getCameraCharacteristics(id)
        require(!useFlash || characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) { "This camera has no flash" }
        check(directory(this).usableSpace > 128L * 1024 * 1024) { "At least 128 MB free storage is required" }
        cleanup()
        error = null
        selected = id
        busy = true
        flash = useFlash
        val token = generation
        output = File(directory(this), "${System.currentTimeMillis()}-${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}.partial")
        try {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: error("No supported camera outputs")
            val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val surfaces = mutableListOf<Surface>()
            val surface: Surface
            if (video) {
                val sizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
                val size = sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
                    ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: error("Video recording is unsupported")
                recorder = MediaRecorder().apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(output!!.absolutePath)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(8_000_000)
                    setOrientationHint(orientation)
                    setMaxDuration(30 * 60 * 1000)
                    setMaxFileSize(minOf(1024L * 1024 * 1024, directory(this@CameraService).usableSpace - 64L * 1024 * 1024))
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                            handler.post { if (generation == token) { finishRecording(); cleanup() } }
                        }
                    }
                    setOnErrorListener { _, _, _ -> handler.post { if (generation == token) fail("Video recorder failed") } }
                    prepare()
                }
                surface = recorder!!.surface
                if (liveWanted != null) {
                    val live = liveSize(map)
                    previewReader = ImageReader.newInstance(live.width, live.height, ImageFormat.YUV_420_888, 2).apply {
                        setOnImageAvailableListener(::onPreviewFrame, handler)
                    }
                    surfaces += previewReader!!.surface
                }
            } else {
                val sizes = map.getOutputSizes(ImageFormat.JPEG).orEmpty()
                val size = sizes.filter { it.width.toLong() * it.height <= 12_000_000 }.maxByOrNull { it.width.toLong() * it.height }
                    ?: sizes.minByOrNull { it.width.toLong() * it.height } ?: error("JPEG capture is unsupported")
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ source ->
                        if (generation == token) {
                            try {
                                val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                                image.use {
                                    val buffer = it.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    output!!.writeBytes(bytes)
                                }
                                publish()
                                cleanup()
                            } catch (e: Exception) { fail(e.message ?: "Photo could not be saved") }
                        }
                    }, handler)
                }
                surface = reader!!.surface
                val previewSize = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                    .minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - 640L * 480) }
                    ?: error("Photo metering is unsupported")
                previewReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener(::onPreviewFrame, handler)
                }
                surfaces += previewReader!!.surface
            }
            surfaces += surface
            notifyState(if (video) "Starting video on camera $id" else "Taking photo on camera $id")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation != token || closing) { camera.close(); return }
                    device = camera
                    try {
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            var withLive = video && previewReader != null
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (generation != token || closing) { s.close(); return }
                                session = s
                                try {
                                    val request = camera.createCaptureRequest(if (video) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(surface)
                                        if (video && withLive) addTarget(previewReader!!.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.FLASH_MODE, if (flash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                                        if (!video) set(CaptureRequest.JPEG_ORIENTATION, orientation)
                                    }.build()
                                    if (video) {
                                        s.setRepeatingRequest(request, null, handler)
                                        recorder!!.start()
                                        recording = true
                                        busy = false
                                        notifyState("Recording video on camera $id")
                                    } else {
                                        var captured = false
                                        val capture = {
                                            if (!captured && generation == token && !closing) {
                                                captured = true
                                                try {
                                                    s.stopRepeating()
                                                    s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                                            if (generation == token) fail("Photo capture failed (${failure.reason})")
                                                        }
                                                    }, handler)
                                                } catch (e: Exception) { fail(e.message ?: "Photo capture failed") }
                                            }
                                        }
                                        val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(previewReader!!.surface)
                                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                            set(CaptureRequest.FLASH_MODE, if (flash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                                            val modes = (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf())
                                            if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) {
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            }
                                        }.build()
                                        val started = SystemClock.elapsedRealtime()
                                        s.setRepeatingRequest(preview, object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                                if (SystemClock.elapsedRealtime() - started >= 500 &&
                                                    result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_CONVERGED) capture()
                                            }
                                        }, handler)
                                        handler.postDelayed({ capture() }, 1500)
                                    }
                                } catch (e: Exception) { fail(e.message ?: "Capture failed") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                s.close()
                                // Some cameras can't record and stream at once: record without the live view.
                                if (withLive && generation == token && !closing) {
                                    withLive = false
                                    try { camera.createCaptureSession(listOf(surface), this, handler) } catch (e: Exception) { fail(e.message ?: "Camera setup failed") }
                                    return
                                }
                                if (generation == token) fail("Camera does not support this capture configuration")
                            }
                        }, handler)
                    } catch (e: Exception) { fail(e.message ?: "Camera setup failed") }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (generation == token) fail("Camera disconnected or is in use by another app")
                }
                override fun onError(camera: CameraDevice, code: Int) {
                    camera.close()
                    if (generation == token) fail("Camera unavailable (error $code); check permission and Android camera privacy settings")
                }
            }, handler)
            handler.postDelayed({ if (generation == token && busy) fail("Camera capture timed out") }, 20_000)
        } catch (e: Exception) {
            fail(e.message ?: "Camera could not start")
            throw e
        }
    }

    private fun publish() {
        val file = output ?: return
        check(file.renameTo(File(file.parentFile, file.name.removeSuffix(".partial")))) { "Could not finalize capture" }
        output = null
    }

    private fun finishRecording() {
        if (!recording) return
        try {
            session?.stopRepeating()
            recorder?.stop()
            recording = false
            publish()
        } catch (e: Exception) { error = "Recording could not be saved: ${e.message}" }
    }

    private fun fail(message: String) {
        // A live view that fails would only fail again; give up on it.
        if (liveActive) liveWanted = null
        error = message
        cleanup()
    }

    private fun cleanup() {
        generation++
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }; recorder = null
        runCatching { reader?.close() }; reader = null
        runCatching { previewReader?.close() }; previewReader = null
        torchId?.let { runCatching { manager.setTorchMode(it, false) } }; torchId = null
        output?.delete(); output = null
        selected = null
        recording = false
        busy = false
        liveActive = false
        if (!closing) notifyState(if (microphone.running) "IPCam microphone active" else "Camera ready for remote controls")
        // A photo or video interrupted the live view: bring it back once the camera is free.
        if (!closing && liveWanted != null) handler.post(::resumeLive)
    }

    private fun resumeLive() {
        val id = liveWanted ?: return
        if (!liveActive && !busy && !recording && !closing) startLive(id)
    }

    private fun stopLive() {
        val wasLive = liveActive
        liveWanted = null
        liveTorch = false
        if (wasLive) cleanup()
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    /** Stops the live view once no browser has fetched a frame for a while. */
    private fun checkViewers() {
        if (liveWanted == null) return
        if (SystemClock.elapsedRealtime() - lastViewer >= VIEWER_TIMEOUT_MS) stopLive()
        else handler.postDelayed(::checkViewers, VIEWER_TIMEOUT_MS)
    }

    private fun liveSize(map: android.hardware.camera2.params.StreamConfigurationMap) =
        map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            .minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - 640L * 480) }
            ?: error("Live view is unsupported")

    /** Opens [id] with only a small preview stream, for watching. */
    @Suppress("MissingPermission")
    private fun startLive(id: String) {
        cleanup()
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No supported camera outputs")
        val size = liveSize(map)
        val token = generation
        liveActive = true
        selected = id
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(::onPreviewFrame, handler)
        }
        previewReader = reader
        notifyState("Live view on camera $id")
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (generation != token || closing) { camera.close(); return }
                device = camera
                try {
                    camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (generation != token || closing) { s.close(); return }
                            session = s
                            try {
                                s.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.FLASH_MODE, if (liveTorch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                                    val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                                    if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes) {
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    }
                                }.build(), null, handler)
                            } catch (e: Exception) { fail(e.message ?: "Live view failed") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); if (generation == token) fail("Live view is unsupported on this camera") }
                    }, handler)
                } catch (e: Exception) { fail(e.message ?: "Live view failed") }
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (generation == token) fail("Camera disconnected or is in use by another app") }
            override fun onError(camera: CameraDevice, code: Int) { camera.close(); if (generation == token) fail("Camera unavailable (error $code)") }
        }, handler)
    }

    /** Turns preview frames into JPEGs for the live view, about 10 a second, and only while someone watches. */
    private fun onPreviewFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (liveWanted == null || now - lastViewer > VIEWER_TIMEOUT_MS || now - lastFrameAt < 100) return
            lastFrameAt = now
            val jpeg = runCatching { jpeg(it) }.getOrNull() ?: return
            synchronized(frameLock) { frame = jpeg; frameSeq++; frameLock.notifyAll() }
        }
    }

    private fun jpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val (y, u, v) = image.planes
        val yBuf = y.buffer
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * y.rowStride)
            yBuf.get(nv21, pos, w)
            pos += w
        }
        val uBuf = u.buffer
        val vBuf = v.buffer
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            nv21[pos++] = vBuf.get(row * v.rowStride + col * v.pixelStride)
            nv21[pos++] = uBuf.get(row * u.rowStride + col * u.pixelStride)
        }
        return ByteArrayOutputStream().also { YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, it) }.toByteArray()
    }

    /**
     * The next live frame after [after], waiting up to a second for one; null if none came.
     * The page asks again as soon as it has shown a frame, so it gets as many as it can show.
     */
    fun nextFrame(after: Long): Pair<Long, ByteArray>? {
        lastViewer = SystemClock.elapsedRealtime()
        synchronized(frameLock) {
            if (frameSeq == after && !closing) frameLock.wait(1000)
            val f = frame
            return if (frameSeq == after || f == null) null else frameSeq to f
        }
    }

    override fun onDestroy() {
        closing = true
        synchronized(frameLock) { frameLock.notifyAll() }
        speech?.stop()
        speech?.shutdown()
        if (instance === this) instance = null
        handler.post {
            microphone.stop()
            finishRecording()
            cleanup()
            wakeLock?.release()
            thread.quitSafely()
        }
        super.onDestroy()
    }
}
