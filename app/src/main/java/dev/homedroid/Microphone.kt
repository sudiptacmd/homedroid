package dev.homedroid

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.UUID

/** Shared PCM source for browser listening and optional WAV recording. */
class Microphone(private val dir: File, private val store: CaptureStore) {
    private val chunks = ArrayDeque<Pair<Long, String>>()
    private var sequence = 0L
    @Volatile var running = false
        private set
    @Volatile var recording = false
        private set
    @Volatile var error: String? = null
        private set
    private var input: AudioRecord? = null
    private var worker: Thread? = null

    @Suppress("MissingPermission")
    fun start(save: Boolean) {
        check(!running && worker?.isAlive != true) { "Stop the microphone before starting another session" }
        check(dir.usableSpace > 128L * 1024 * 1024) { "At least 128 MB free storage is required" }
        val source = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(8192, AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        try {
            check(source.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
            source.startRecording()
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
        } catch (e: Exception) { source.release(); throw e }
        synchronized(chunks) { chunks.clear() }
        error = null
        input = source
        running = true
        recording = save
        worker = Thread({
            val file = if (save) File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.wav.partial") else null
            var out: RandomAccessFile? = null
            var count = 0L
            try {
                if (file != null) out = RandomAccessFile(file, "rw").apply { write(ByteArray(44)) }
                val buffer = ByteArray(4096)
                val started = System.nanoTime()
                while (running && System.nanoTime() - started < 30L * 60 * 1_000_000_000) {
                    val n = source.read(buffer, 0, buffer.size)
                    if (!running) break
                    check(n > 0) { "Microphone read failed ($n)" }
                    if (out != null && !store.reserve(n.toLong() + 44)) break
                    out?.write(buffer, 0, n)
                    count += n
                    synchronized(chunks) {
                        chunks.addLast(++sequence to Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP))
                        while (chunks.size > 20) chunks.removeFirst()
                    }
                    if (save && dir.usableSpace < 64L * 1024 * 1024) break
                }
                out?.let {
                    it.seek(0)
                    it.writeBytes("RIFF"); it.writeInt(Integer.reverseBytes((count + 36).toInt()))
                    it.writeBytes("WAVEfmt "); it.writeInt(Integer.reverseBytes(16))
                    it.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                    it.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                    it.writeInt(Integer.reverseBytes(RATE)); it.writeInt(Integer.reverseBytes(RATE * 2))
                    it.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
                    it.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
                    it.writeBytes("data"); it.writeInt(Integer.reverseBytes(count.toInt()))
                    it.close(); out = null
                    check(file!!.renameTo(File(dir, file.name.removeSuffix(".partial")))) { "Could not save audio" }
                }
            } catch (e: Exception) {
                error = e.message ?: "Microphone failed"
            } finally {
                runCatching { out?.close() }
                file?.delete()
                runCatching { source.stop() }
                source.release()
                running = false
                recording = false
            }
        }, "ipcam-microphone").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { input?.stop() }
        worker?.join(3000)
        if (worker?.isAlive != true) {
            input = null
            worker = null
        } else {
            error = "Microphone is still stopping; try again shortly"
        }
        recording = false
        synchronized(chunks) { chunks.clear() }
    }

    fun status() = JSONObject().put("running", running).put("recording", recording)
        .put("error", error ?: JSONObject.NULL)

    fun audio(after: Long): JSONObject = synchronized(chunks) {
        JSONObject().put("rate", RATE).put("sequence", sequence).put("running", running)
            .put("chunks", jsonArray(chunks.filter { it.first > after }.map {
                JSONObject().put("sequence", it.first).put("pcm", it.second)
            }))
    }

    companion object { const val RATE = 16000 }
}
