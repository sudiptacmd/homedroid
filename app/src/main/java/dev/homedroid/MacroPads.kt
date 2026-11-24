package dev.homedroid

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Atomic storage and bounded phone-local execution. No UI or HTTP handler thread runs network I/O. */
class MacroPads private constructor(ctx: Context) {
    companion object {
        @Volatile private var instance: MacroPads? = null
        fun get(ctx: Context): MacroPads = instance ?: synchronized(this) {
            instance ?: MacroPads(ctx.applicationContext).also { instance = it }
        }
    }
    data class Result(val error: String? = null, val navigation: JSONObject? = null)
    private val desktop = DesktopLink.get(ctx)
    private val file = AtomicFile(File(ctx.filesDir, "macropads.json"))
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "macro-http-deadlines").apply { isDaemon = true } }
    private var model = if (file.baseFile.exists()) MacroPadsCore.validate(JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })) else MacroPadsCore.starter()
    init { if (!file.baseFile.exists()) save(model) }
    @Synchronized fun read(): JSONObject = JSONObject(model.toString())
    @Synchronized fun save(value: JSONObject) {
        val copy = JSONObject(value.toString()).apply { remove("computers") }
        MacroPadsCore.validate(copy)
        val out = file.startWrite()
        try { out.write(copy.toString(2).toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        model = copy
    }
    fun press(profile: String, page: String, button: String): CompletableFuture<Result> {
        val doc = read(); val profiles = doc.getJSONArray("profiles")
        val p = (0 until profiles.length()).map { profiles.getJSONObject(it) }.firstOrNull { it.getString("id") == profile }
            ?: return CompletableFuture.completedFuture(Result("Unknown profile"))
        val pages = p.getJSONArray("pages")
        val pg = (0 until pages.length()).map { pages.getJSONObject(it) }.firstOrNull { it.getString("id") == page }
            ?: return CompletableFuture.completedFuture(Result("Unknown page"))
        val buttons = pg.getJSONArray("buttons")
        val b = (0 until buttons.length()).map { buttons.getJSONObject(it) }.firstOrNull { it.getString("id") == button }
            ?: return CompletableFuture.completedFuture(Result("Unknown button"))
        if (b.optBoolean("disabled")) return CompletableFuture.completedFuture(Result("Configure this button and enable it in the editor first"))
        val a = b.getJSONObject("action")
        return when (a.getString("kind")) {
            "page", "profile", "back" -> CompletableFuture.completedFuture(Result(navigation = a))
            "http" -> {
                val future = CompletableFuture<Result>()
                try { workers.execute { future.complete(runCatching { request(a); Result() }.getOrElse { Result(it.message ?: "HTTP request failed") }) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { future.complete(Result("Too many phone requests; try again shortly")) }
                future
            }
            else -> {
                val target = b.optString("computer").takeIf { !b.isNull("computer") && it.isNotEmpty() }
                    ?: a.optString("computer").takeIf { !a.isNull("computer") && it.isNotEmpty() }
                runCatching { desktop.send(a, target).thenApply { Result(it) } }.getOrElse { CompletableFuture.completedFuture(Result(it.message ?: "Action failed")) }
            }
        }
    }
    private fun request(a: JSONObject) {
        val connection = MacroPadsCore.httpUrl(a.getString("url")).toURL().openConnection() as HttpURLConnection
        // Redirects are deliberately not followed: they can change a user-entered request's target/method.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 4000; connection.readTimeout = 4000
        connection.requestMethod = a.getString("method")
        val deadline = deadlines.schedule({ connection.disconnect() }, 8, TimeUnit.SECONDS)
        try {
            val body = a.optString("body", "")
            if (body.isNotEmpty() && connection.requestMethod !in setOf("GET", "HEAD")) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        } finally { deadline.cancel(false); connection.disconnect() }
    }
    fun api(r: Request): Response = when (r.method to r.path) {
        "GET" to "/api/macropads" -> Response.json(read().put("computers", desktop.list()))
        "PUT" to "/api/macropads" -> { save(r.json()); Response.ok() }
        "POST" to "/api/macropads/press" -> {
            val b = r.json(); val result = press(b.getString("profile"), b.getString("page"), b.getString("button")).get(12, TimeUnit.SECONDS)
            if (result.error != null) Response.error(409, result.error)
            else Response.json(JSONObject().put("ok", true).apply { result.navigation?.let { put("navigation", it) } })
        }
        else -> Response.error(404, "No such MacroPads endpoint")
    }
}
