package dev.homedroid

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Bundle
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run only with -PcameraTest=true: never records into an existing release's capture library. */
@Suppress("DEPRECATION")
class CameraHardwareTest : Instrumentation() {
    private val instrumentation get() = this
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            testDashboardAuthProtectsEveryCameraRoute()
            testNativeDaemonsAndSshDeadline()
            testContinuousMonitoringAndPlayableBoundedMotionClips()
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nDashboard auth and native runtime checks passed\nOK (1 camera hardware test)\n") })
        } catch (e: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", e.stackTraceToString()) })
        }
    }
    private fun assertEquals(expected: Any?, actual: Any?) = check(expected == actual) { "Expected $expected; actual $actual" }
    private fun assertEquals(message: String, expected: Any?, actual: Any?) = check(expected == actual) { "$message: expected $expected; actual $actual" }
    private fun assertTrue(value: Boolean) = check(value)
    private fun assertTrue(message: String, value: Boolean) = check(value) { message }
    private fun testNativeDaemonsAndSshDeadline() {
        val ctx = targetContext
        val paths = Paths(ctx)
        for ((name, arg) in listOf("caddy" to "version", "cloudflared" to "--version", "tailscaled" to "--version")) {
            val process = ProcessBuilder(paths.exe(name), arg).redirectErrorStream(true).start()
            try {
                check(process.waitFor(15, TimeUnit.SECONDS)) { "$name version command timed out" }
                val output = process.inputStream.bufferedReader().readText()
                check(process.exitValue() == 0) { "$name runtime failed: $output" }
            } finally { process.destroy() }
        }
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(paths.exe("sshd"), "-listen", "127.0.0.1:$port", "-hostkey",
            File(ctx.cacheDir, "test-hostkey").path, "-authorized-keys", File(ctx.cacheDir, "test-authorized-keys").path,
            "-home", ctx.cacheDir.path).redirectErrorStream(true).start()
        try {
            Thread.sleep(500)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 15_000
                val input = socket.getInputStream().bufferedReader()
                assertEquals("SSH-2.0-homedroid", input.readLine())
                assertEquals("Idle SSH handshake must be closed by the deadline", -1, input.read())
            }
        } finally { process.destroy(); process.waitFor(5, TimeUnit.SECONDS) }
    }
    private fun testDashboardAuthProtectsEveryCameraRoute() {
        val ctx = targetContext
        check(ctx.packageName == "dev.homedroid.cameratest")
        val cfg = Config(ctx)
        cfg.dashboardAuth = "{}"
        cfg.dashboardPassword = "hardware-test-password"
        cfg.cameraEnabled = true
        var dashboard = Dashboard(ctx)
        val handle = Dashboard::class.java.getDeclaredMethod("handle", Request::class.java).apply { isAccessible = true }
        val headers = mapOf("host" to "phone:8800", "origin" to "http://phone:8800", "x-homedroid-request" to "1")
        fun request(path: String, method: String = "GET", body: JSONObject = JSONObject(), extra: Map<String, String> = emptyMap()): Response {
            val bytes = if (method == "GET") byteArrayOf() else body.toString().toByteArray()
            return handle.invoke(dashboard, Request(method, path, emptyMap(), headers + extra,
                ByteArrayInputStream(bytes), bytes.size.toLong(), "test-peer")) as Response
        }
        for (path in listOf("/api/camera", "/api/camera/audio", "/api/camera/live/frame", "/api/camera/files/1-a.mp4", "/api/terminal", "/api/files")) {
            assertEquals(401, request(path).status)
        }
        assertEquals(401, request("/api/camera/monitor/start", "POST").status)
        val login = request("/api/login", "POST", JSONObject().put("password", cfg.dashboardPassword))
        assertEquals(200, login.status)
        val cookie = login.headers.getValue("Set-Cookie").substringBefore(';')
        assertTrue(login.headers.getValue("Set-Cookie").contains("HttpOnly"))
        assertTrue(login.headers.getValue("Set-Cookie").contains("Max-Age=86400"))
        assertEquals(200, request("/api/camera", extra = mapOf("cookie" to cookie)).status)
        assertEquals(403, request("/api/camera", extra = mapOf("cookie" to cookie, "origin" to "http://phone:8080")).status)
        repeat(4) { assertEquals(401, request("/api/login", "POST", JSONObject().put("password", "wrong")).status) }
        assertEquals(429, request("/api/camera", extra = mapOf("authorization" to "Bearer wrong")).status)
        assertEquals(429, request("/api/login", "POST", JSONObject().put("password", cfg.dashboardPassword)).status)
        assertEquals(200, request("/api/camera", extra = mapOf("cookie" to cookie)).status)
        assertEquals(429, request("/api/settings", "POST", JSONObject().put("password", "new-test-password")
            .put("currentPassword", cfg.dashboardPassword), mapOf("cookie" to cookie)).status)
        dashboard = Dashboard(ctx)
        assertEquals(429, request("/api/login", "POST", JSONObject().put("password", cfg.dashboardPassword)).status)
        assertEquals(200, request("/api/camera", extra = mapOf("cookie" to cookie)).status)
        assertEquals(200, request("/api/logout", "POST", extra = mapOf("cookie" to cookie)).status)
        assertEquals(401, request("/api/camera", extra = mapOf("cookie" to cookie)).status)
    }
    fun testContinuousMonitoringAndPlayableBoundedMotionClips() {
        val ctx = instrumentation.targetContext
        assertEquals("dev.homedroid.cameratest", ctx.packageName)
        Config(ctx).cameraEnabled = true
        Config(ctx).autostart = false
        val activity = instrumentation.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { CameraService.arm(ctx) }
        await { CameraService.instance != null }
        val service = CameraService.instance!!
        val cameras = CameraService.cameras(ctx)
        val id = cameras.first().getString("id")
        val options = CameraOptions(resolution = 480, fps = 5, rotation = 90, sensitivity = 50, quietSeconds = 2, clipSeconds = 5)
        try {
            command(service, "settings", options.json().put("id", id))
            command(service, "monitor/start", JSONObject().put("id", id))
            command(service, "live/start", JSONObject().put("id", id))
            await {
                val s = status(service)
                check(s.isNull("error")) { s.optString("error") }
                service.nextFrame(-1) != null
            }
            command(service, "live/stop")
            val generationField = CameraService::class.java.getDeclaredField("generation").apply { isAccessible = true }
            val generation = generationField.getInt(service)
            val recorderField = CameraService::class.java.getDeclaredField("motionRecorder").apply { isAccessible = true }
            val handler = CameraService::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(service) as Handler
            val until = System.currentTimeMillis() + 7500
            while (System.currentTimeMillis() < until) {
                val done = CountDownLatch(1)
                handler.post { (recorderField.get(service) as MotionRecorder).motion(); done.countDown() }
                assertTrue(done.await(2, TimeUnit.SECONDS))
                Thread.sleep(200)
            }
            assertEquals("Motion clips must not reopen the camera", generation, generationField.getInt(service))
            assertEquals(id, status(service).getJSONObject("monitor").getString("id"))
            command(service, "monitor/stop")
            val clips = CameraService.directory(ctx).listFiles().orEmpty().filter { it.extension == "mp4" }
            assertTrue("Motion should create at least two bounded clips", clips.size >= 2)
            clips.forEach { file ->
                val meta = MediaMetadataRetriever()
                try {
                    meta.setDataSource(file.absolutePath)
                    val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("Clip must have playable duration", duration > 0)
                    assertTrue("Clip exceeds selected maximum: $duration ms", duration <= 5500)
                    val rotation = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
                    assertEquals((cameras.first().getInt("rotation") + 90) % 360, rotation)
                } finally { meta.release() }
            }
            assertEquals(options, CameraPreferences(ctx).get(id))
            for (camera in cameras.drop(1)) {
                val other = camera.getString("id")
                assertEquals(CameraOptions(), CameraPreferences(ctx).get(other))
                val otherOptions = CameraOptions(resolution = 240, fps = 2, rotation = 270, mode = "watch")
                command(service, "settings", otherOptions.json().put("id", other))
                command(service, "monitor/start", JSONObject().put("id", other))
                command(service, "live/start", JSONObject().put("id", other))
                await {
                    val s = status(service)
                    check(s.isNull("error")) { s.optString("error") }
                    service.nextFrame(-1) != null
                }
                command(service, "live/stop")
                assertEquals(other, status(service).getJSONObject("monitor").getString("id"))
                assertEquals(otherOptions, CameraPreferences(ctx).get(other))
                assertEquals(options, CameraPreferences(ctx).get(id))
                command(service, "monitor/stop")
            }
        } finally {
            CameraService.stop(ctx)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    private fun command(service: CameraService, action: String, body: JSONObject = JSONObject()) {
        val response = service.request(action, body)
        assertEquals(String(response.body), 200, response.status)
    }
    private fun status(service: CameraService) = JSONObject(String(service.request("status").body))
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Camera startup timed out" }
            Thread.sleep(100)
        }
    }
}
