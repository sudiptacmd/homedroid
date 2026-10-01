package dev.homedroid

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.UUID

/** Hardware encoding stays on one surface. Idle samples are discarded, never written to disk. */
class MotionRecorder(
    private val dir: File, private val store: CaptureStore, private val handler: Handler,
    private val options: CameraOptions, width: Int, height: Int, private val rotation: Int,
    private val onError: (String) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType("video/avc")
    val surface: Surface
    private var format: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var output: File? = null
    private var track = -1
    private var baseUs = 0L
    private var lastMotion = 0L
    private var started = 0L
    private var lastKeyRequest = 0L
    private var closed = false
    val recording get() = muxer != null
    var storageError: String? = null
        private set
    private val drain = object : Runnable {
        override fun run() {
            if (closed) return
            try { drainFrames() } catch (e: Exception) { onError(e.message ?: "Motion recording failed"); return }
            if (!closed) handler.postDelayed(this, 40)
        }
    }
    init {
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * options.fps / 5).coerceIn(150_000, 8_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, options.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, options.fps.toFloat())
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            handler.post(drain)
        } catch (e: Exception) { codec.release(); throw e }
    }
    fun motion() {
        lastMotion = SystemClock.elapsedRealtime()
        if (!recording && lastMotion - lastKeyRequest > 500) {
            lastKeyRequest = lastMotion
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }
    private fun drainFrames() {
        val info = MediaCodec.BufferInfo()
        repeat(32) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { format = codec.outputFormat; return@repeat }
            if (index < 0) return
            try {
                val now = SystemClock.elapsedRealtime()
                val wanted = lastMotion != 0L && now - lastMotion < options.quietSeconds * 1000L
                if (recording && (!wanted || now - started >= options.clipSeconds * 1000L)) finish()
                if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return@repeat
                if (!recording && wanted && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && format != null) {
                    if (!store.reserve(1024 * 1024)) {
                        storageError = "Motion saving paused: active captures occupy the storage limit, or the phone is low on space"
                        return@repeat
                    }
                    storageError = null
                    output = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.mp4.partial")
                    val writer = MediaMuxer(output!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try { writer.apply {
                        setOrientationHint(rotation)
                        track = addTrack(format!!)
                        start()
                    } } catch (e: Exception) { writer.release(); throw e }
                    muxer = writer
                    baseUs = info.presentationTimeUs
                    started = now
                }
                muxer?.let { writer ->
                    if (!store.reserve(info.size.toLong() + 64 * 1024)) {
                        // Complete the active clip so it can participate in oldest-first eviction.
                        finish()
                        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                        return@repeat
                    }
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    info.presentationTimeUs -= baseUs
                    writer.writeSampleData(track, buffer, info)
                }
            } finally { codec.releaseOutputBuffer(index, false) }
        }
    }
    private fun finish() {
        val writer = muxer ?: return
        muxer = null
        var saved = false
        try { writer.stop(); saved = true } finally {
            runCatching { writer.release() }
            val file = output
            output = null
            if (saved && file != null) {
                if (!file.renameTo(File(dir, file.name.removeSuffix(".partial")))) { file.delete(); error("Could not finalize motion clip") }
            } else file?.delete()
        }
    }
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(drain)
        try { finish() } finally {
            runCatching { codec.stop() }; codec.release(); surface.release()
        }
    }
}
