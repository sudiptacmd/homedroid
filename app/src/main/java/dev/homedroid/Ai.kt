package dev.homedroid

import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** The AI module's settings, in their own private preferences. Provider keys never leave the phone. */
class AiConfig(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** The key other apps use for the API on port 8090, made on first use. */
    var key: String
        get() = prefs.getString("key", null) ?: newKey().also { key = it }
        set(v) { prefs.edit().putString("key", v).commit() }

    var providers: List<Provider>
        get() = try {
            JSONArray(prefs.getString("providers", "[]")).let { a -> (0 until a.length()).map { Provider.from(a.getJSONObject(it)) } }
        } catch (_: Exception) { emptyList() }
        set(v) { prefs.edit().putString("providers", JSONArray().apply { v.forEach { put(it.toStored()) } }.toString()).commit() }

    /** The downloaded model file the phone runs, if any. */
    var localModel: String?
        get() = prefs.getString("local_model", null)
        set(v) = prefs.edit().putString("local_model", v).apply()

    var contextSize: Int
        get() = prefs.getInt("context", 4096)
        set(v) = prefs.edit().putInt("context", v).apply()

    /** CPU threads for the local model; 0 picks half the cores (the fast ones, usually). */
    var threads: Int
        get() = prefs.getInt("threads", 0)
        set(v) = prefs.edit().putInt("threads", v).apply()

    /** A cloud model to use when the local one can't answer, or "". */
    var fallback: String
        get() = prefs.getString("fallback", "")!!
        set(v) = prefs.edit().putString("fallback", v).apply()

    /** Where models are kept; null is inside the app. */
    var modelsDir: String?
        get() = prefs.getString("models_dir", null)
        set(v) = prefs.edit().putString("models_dir", v).apply()

    val effectiveThreads get() = threads.takeIf { it > 0 } ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    companion object {
        fun newKey() = "hd-" + ByteArray(20).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The AI module: an OpenAI-compatible API on [AiCore.PORT] for other apps (with its own key),
 * the dashboard's chat, model downloads, and the cloud services the user connected. Models
 * on the phone run in llama.cpp built for Android (see [LlamaRuntime], [spec]); the API forwards to it or
 * to a cloud service by the model's id.
 */
class Ai(context: Context) {
    private val ctx: Context = context.applicationContext
    val cfg = AiConfig(ctx)
    private val paths = Paths(ctx)
    private val llama = LlamaRuntime(ctx)
    private var router: Http? = null
    // Wrong keys on the API count like wrong dashboard passwords: a cooldown per address.
    private val guard = AuthSecurity()

    class Download(val name: String, val file: String) {
        @Volatile var total = 0L
        @Volatile var done = 0L
        @Volatile var state = "running"
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        fun json(): JSONObject = JSONObject().put("name", name).put("file", file).put("total", total).put("done", done)
            .put("state", state).put("error", error ?: JSONObject.NULL)
    }

    @Volatile private var download: Download? = null

    @Synchronized fun start() {
        if (!cfg.enabled || router != null) return
        router = Http(AiCore.PORT, threads = 8, handler = ::handleApi).also {
            try { it.start() } catch (e: IOException) { Jobs.line("AI: port ${AiCore.PORT} could not open: ${e.message}"); router = null }
        }
    }

    @Synchronized fun stop() { router?.stop(); router = null }

    val running get() = router != null

    val modelsDir get() = dir(ctx)
    val localSupported get() = llama.supported
    val runtimeInstalled get() = llama.installed
    private val localAlias get() = cfg.localModel?.takeIf { File(modelsDir, it).isFile }?.let(::alias)

    /** The local server answers /health once the model is loaded. */
    private fun localUp(): Boolean = localAlias != null && try {
        (URL("http://127.0.0.1:${AiCore.LOCAL_PORT}/health").openConnection() as HttpURLConnection).run {
            connectTimeout = 500; readTimeout = 1000
            try { (responseCode == 200).also { healthError = if (it) null else "health check answered $responseCode" } } finally { disconnect() }
        }
    } catch (e: IOException) {
        // Expected while the model loads; shown in the dashboard if it stays that way.
        healthError = e.message ?: e.javaClass.simpleName
        false
    }

    /** Why the last health check of the local model failed, for the dashboard. */
    @Volatile private var healthError: String? = null

    private fun hot() = ctx.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    fun target(model: String) = AiCore.resolve(model, cfg.providers, localAlias, localUp(), hot(), cfg.fallback)

    /** Every model a chat can use, local first. */
    fun models(): JSONArray = JSONArray().apply {
        localAlias?.let { put(JSONObject().put("id", "local/$it").put("name", it).put("local", true).put("label", "On this phone")) }
        for (p in cfg.providers) for (m in p.models) put(JSONObject().put("id", "${p.id}/$m").put("name", m).put("local", false).put("label", p.name))
    }

    // --- chat -----------------------------------------------------------------------------

    private fun open(t: AiCore.Target, body: JSONObject): HttpURLConnection {
        val bytes = JSONObject(body.toString()).put("model", t.model).toString().toByteArray()
        return (URL(t.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            // A phone may take minutes to answer a long prompt.
            readTimeout = if (t.local) 600_000 else 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (t.key.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${t.key}")
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
        }
    }

    /** Forwards an OpenAI chat request, streaming the answer (server-sent events when asked). */
    fun proxyChat(body: JSONObject): Response {
        val t = try { target(body.optString("model")) } catch (e: IllegalArgumentException) {
            return Response.error(404, e.message ?: "Unknown model")
        } catch (e: IllegalStateException) { return Response.error(503, e.message ?: "Not available") }
        val conn = try { open(t, body) } catch (e: IOException) {
            return Response.error(502, "${t.label} isn't reachable: ${e.message ?: e.javaClass.simpleName}")
        }
        val status = try { conn.responseCode } catch (e: IOException) { conn.disconnect(); return Response.error(502, "${t.label}: ${e.message}") }
        val input: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
        if (status >= 400) {
            val text = input?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            conn.disconnect()
            return Response.error(status, "${t.label}: ${AiCore.errorMessage(text).ifEmpty { "error $status" }}")
        }
        val headers = buildMap {
            put("X-Homedroid-Model", t.id)
            put("X-Homedroid-Where", if (t.local) "local" else "cloud")
            t.note?.let { put("X-Homedroid-Note", it) }
        }
        return Response(status, ByteArray(0), conn.contentType ?: "application/json", headers, stream = { out ->
            try {
                input?.use { s ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush() // each token as it comes
                    }
                }
            } finally { conn.disconnect() }
        })
    }

    /** One complete answer, for routines. Returns the text and where it ran. */
    fun chat(model: String, messages: JSONArray, maxTokens: Int = 700): Pair<String, AiCore.Target> {
        val t = target(model)
        val conn = open(t, JSONObject().put("messages", messages).put("stream", false).put("max_tokens", maxTokens).put("temperature", 0.3))
        try {
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status >= 400) throw IOException("${t.label}: ${AiCore.errorMessage(text)}")
            return AiCore.content(JSONObject(text)) to t
        } finally { conn.disconnect() }
    }

    // --- the API for other apps -------------------------------------------------------------

    private fun handleApi(r: Request): Response {
        if (r.method == "GET" && r.path == "/") return Response(200, "Homedroid AI: OpenAI-compatible API at /v1\n".toByteArray(), "text/plain")
        val given = AuthSecurity.bearer(r.headers) ?: return Response.error(401, "Send the Homedroid AI key as: Authorization: Bearer <key>")
        val result = guard.password(r.remote) { MessageDigest.isEqual(given.toByteArray(), cfg.key.toByteArray()) }
        if (!result.accepted) {
            return if (result.retrySeconds > 0) Response.json(JSONObject().put("error", "Too many wrong keys; try again in ${result.retrySeconds} seconds"), 429)
            else Response.error(401, "Wrong key")
        }
        return when {
            r.method == "GET" && r.path == "/v1/models" -> Response.json(JSONObject().put("object", "list").put("data", JSONArray().apply {
                val ms = models()
                for (i in 0 until ms.length()) put(JSONObject().put("id", ms.getJSONObject(i).getString("id")).put("object", "model")
                    .put("owned_by", ms.getJSONObject(i).getString("label")))
            }))
            r.method == "POST" && r.path == "/v1/chat/completions" -> proxyChat(r.json())
            else -> Response.error(404, "Supported: GET /v1/models, POST /v1/chat/completions")
        }
    }

    // --- the dashboard ----------------------------------------------------------------------

    fun json(): JSONObject {
        val dev = Device(ctx)
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().sortedBy { it.name }
        val daemon = ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == SERVICE }
        return JSONObject()
            .put("enabled", cfg.enabled)
            .put("running", running)
            .put("port", AiCore.PORT)
            .put("key", cfg.key)
            .put("ips", jsonArray(dev.ips))
            .put("ramTotalMb", dev.ramTotalMb)
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("localSupported", localSupported)
            .put("runtimeInstalled", runtimeInstalled)
            .put("runtimeOutdated", llama.outdated)
            .put("runtimeBytes", llama.bundle?.optLong("bytes") ?: 0)
            .put("runtimeAbi", llama.abi)
            .put("device", llama.device)
            // Only once installed: listing devices runs the runtime.
            .put("devices", JSONArray().apply { if (llama.installed) llama.devices().forEach { put(JSONObject().put("id", it.first).put("name", it.second)) } })
            .put("benchmark", llama.benchmark ?: JSONObject.NULL)
            .put("localModel", cfg.localModel ?: JSONObject.NULL)
            .put("localState", when {
                cfg.localModel == null -> "none"
                benchmarking -> "benchmarking"
                daemon == null -> "stopped"
                daemon.state != Supervisor.State.RUNNING -> daemon.state.name.lowercase()
                localUp() -> "ready"
                else -> "loading"
            })
            .put("localError", healthError ?: JSONObject.NULL)
            .put("contextSize", cfg.contextSize)
            .put("threads", cfg.threads)
            .put("effectiveThreads", cfg.effectiveThreads)
            .put("fallback", cfg.fallback)
            .put("modelsDir", modelsDir.path)
            .put("modelsFree", modelsDir.apply { mkdirs() }.usableSpace)
            .put("modelsDirs", JSONArray().apply {
                put(JSONObject().put("path", JSONObject.NULL).put("label", "Inside the app"))
                Storage.volumes(ctx).filter { it.removable }.forEach { v -> v.appDir?.let { put(JSONObject().put("path", File(it, "models").path).put("label", v.label)) } }
            })
            .put("files", JSONArray().apply { files.forEach { put(JSONObject().put("file", it.name).put("bytes", it.length())) } })
            .put("catalog", JSONArray().apply {
                val advice = AiCore.advise(AiCore.CATALOG, dev.ramTotalMb, modelsDir.usableSpace, Runtime.getRuntime().availableProcessors(),
                    llama.measuredSpeed(), llama.benchmark?.optLong("bytes")?.takeIf { it > 0 })
                AiCore.CATALOG.forEach { m ->
                    val a = advice.getValue(m.id)
                    put(JSONObject().put("id", m.id).put("name", m.name).put("bytes", m.bytes).put("ramMb", m.ramMb).put("note", m.note)
                        .put("file", m.file).put("downloaded", File(modelsDir, m.file).isFile)
                        .put("fit", a.fit).put("recommended", a.recommended).put("reason", a.reason)
                        .put("wordsPerSecond", a.wordsPerSecond ?: JSONObject.NULL))
                }
            })
            .put("download", download?.json() ?: JSONObject.NULL)
            .put("providers", JSONArray().apply { cfg.providers.forEach { put(it.toPublic()) } })
            .put("models", models())
    }

    fun handle(r: Request, seg: List<String>): Response {
        // Read only by routes that take JSON: the runtime upload streams a 17 MB body instead.
        val body by lazy { if (r.method == "POST") r.json() else JSONObject() }
        return when {
            r.method == "GET" && seg.isEmpty() -> Response.json(json())
            r.method == "POST" && seg == listOf("chat") -> proxyChat(body.put("stream", true))
            r.method == "POST" && seg == listOf("settings") -> saveSettings(body)
            r.method == "POST" && seg == listOf("key") -> { cfg.key = AiConfig.newKey(); Response.ok() }
            r.method == "POST" && seg == listOf("providers") -> saveProvider(body)
            r.method == "POST" && seg.size == 3 && seg[0] == "providers" && seg[2] == "refresh" -> {
                val p = cfg.providers.firstOrNull { it.id == seg[1] } ?: return Response.error(404, "No such service")
                refresh(p).fold({ Response.json(it.toPublic()) }, { Response.error(502, it.message ?: "Couldn't list models") })
            }
            r.method == "DELETE" && seg.size == 2 && seg[0] == "providers" -> {
                cfg.providers = cfg.providers.filter { it.id != seg[1] }
                if (cfg.fallback.startsWith(seg[1] + "/")) cfg.fallback = ""
                Response.ok()
            }
            r.method == "POST" && seg == listOf("download") -> startDownload(body)
            r.method == "POST" && seg == listOf("download", "cancel") -> { download?.cancelled = true; Response.ok() }
            r.method == "DELETE" && seg.size == 2 && seg[0] == "files" -> deleteModel(seg[1])
            r.method == "POST" && seg == listOf("runtime", "install") -> installRuntime()
            r.method == "POST" && seg == listOf("runtime", "upload") -> llama.upload(r)?.let { Response.error(400, it) } ?: Response.ok().also { if (cfg.enabled) ServerService.restart(ctx) }
            r.method == "POST" && seg == listOf("benchmark") -> benchmark()
            r.method == "POST" && seg == listOf("runtime", "remove") -> { llama.remove(); if (cfg.enabled) ServerService.restart(ctx); Response.ok() }
            else -> Response.error(404, "No such AI endpoint")
        }
    }

    private fun saveSettings(body: JSONObject): Response {
        var restart = false
        if (body.has("localModel")) {
            val f = body.optString("localModel").ifEmpty { null }
            if (f != null && !File(modelsDir, f).isFile) return Response.error(400, "Download that model first")
            if (f != cfg.localModel) { cfg.localModel = f; restart = true }
        }
        if (body.has("contextSize")) {
            val c = body.getInt("contextSize")
            if (c !in 512..32768) return Response.error(400, "Context must be 512–32768 tokens")
            if (c != cfg.contextSize) { cfg.contextSize = c; restart = true }
        }
        if (body.has("threads")) {
            val t = body.getInt("threads")
            if (t !in 0..64) return Response.error(400, "Invalid thread count")
            if (t != cfg.threads) { cfg.threads = t; restart = true }
        }
        if (body.has("device")) {
            val d = body.optString("device")
            if (d !in setOf("auto", "cpu", "gpu")) return Response.error(400, "Pick Auto, CPU or GPU")
            if (d != llama.device) { llama.device = d; restart = true }
        }
        if (body.has("fallback")) {
            val f = body.optString("fallback")
            if (f.isNotEmpty() && (f.startsWith("local/") || cfg.providers.none { f.startsWith(it.id + "/") })) {
                return Response.error(400, "Pick a cloud model as the fallback")
            }
            cfg.fallback = f
        }
        if (body.has("modelsDir")) {
            val d = body.optString("modelsDir").ifEmpty { null }
            val allowed = listOf<String?>(null) + Storage.volumes(ctx).filter { it.removable }.mapNotNull { v -> v.appDir?.let { File(it, "models").path } }
            if (d !in allowed) return Response.error(400, "Pick one of the offered locations")
            if (d != cfg.modelsDir) {
                if (download?.state == "running") return Response.error(409, "Wait for the download to finish")
                cfg.modelsDir = d
                cfg.localModel = cfg.localModel?.takeIf { File(dir(ctx), it).isFile }
                restart = true
            }
        }
        if (restart && cfg.enabled) ServerService.restart(ctx)
        return Response.ok()
    }

    private fun saveProvider(body: JSONObject): Response {
        val type = body.optString("type")
        if (type !in setOf("openai", "gemini", "compatible")) return Response.error(400, "Pick a service type")
        val existing = cfg.providers.firstOrNull { it.id == body.optString("id") }
        val name = Peer.cleanName(body.optString("name")).ifEmpty { mapOf("openai" to "OpenAI", "gemini" to "Google Gemini")[type] ?: "AI service" }
        val base = AiCore.baseUrl(type, body.optString("baseUrl"))
        if (!AiCore.validBaseUrl(base)) return Response.error(400, "Enter the API address, like http://192.168.1.10:11434/v1")
        val key = body.optString("apiKey").trim().ifEmpty { existing?.apiKey.orEmpty() }
        if (type != "compatible" && key.isEmpty()) return Response.error(400, "Paste an API key")
        if (key.any { it.isISOControl() } || key.length > 512) return Response.error(400, "That key doesn't look right")
        val id = existing?.id ?: AiCore.slug(if (type == "compatible") name else type, cfg.providers.map { it.id })
        val p = Provider(id, type, name, base, key, existing?.models.orEmpty())
        // Check the key works before saving it: listing models is free on every service.
        val listed = refresh(p, save = false).getOrElse { return Response.error(400, "Couldn't connect: ${it.message}") }
        cfg.providers = cfg.providers.filter { it.id != id } + listed
        return Response.json(listed.toPublic())
    }

    /** Lists [p]'s chat models from its /models endpoint. */
    private fun refresh(p: Provider, save: Boolean = true): Result<Provider> = runCatching {
        val conn = (URL("${p.baseUrl}/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15_000
            if (p.apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${p.apiKey}")
        }
        try {
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status >= 400) throw IOException(AiCore.errorMessage(text).ifEmpty { "HTTP $status" })
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) }
            val models = AiCore.chatModels(p.type, ids)
            if (models.isEmpty()) throw IOException("it offers no chat models")
            p.withModels(models).also { updated -> if (save) cfg.providers = cfg.providers.map { if (it.id == p.id) updated else it } }
        } finally { conn.disconnect() }
    }

    private fun startDownload(body: JSONObject): Response {
        if (!localSupported) return Response.error(409, "Models can't run on this phone's CPU; connect a cloud service instead")
        if (download?.state == "running") return Response.error(409, "Another model is downloading")
        val entry = AiCore.CATALOG.firstOrNull { it.id == body.optString("id") }
        val url = entry?.url ?: body.optString("url").trim()
        val name = Regex("[A-Za-z0-9._-]+\\.gguf").matchEntire(url.substringBefore('?').substringAfterLast('/'))?.value
        if (!url.startsWith("https://") || name == null) return Response.error(400, "Paste an https:// link to a .gguf file, like a Hugging Face \"resolve\" link")
        if (File(modelsDir, name).isFile) return Response.error(409, "$name is already downloaded")
        val d = Download(entry?.name ?: name, name)
        download = d
        Thread({ fetch(d, url, entry?.sha256) }, "ai-download").apply { isDaemon = true; start() }
        return Response.json(d.json())
    }

    /** Downloads into a .part file (resuming one left by an earlier attempt), checks it, then renames it. */
    private fun fetch(d: Download, url: String, sha256: String?) {
        val dir = modelsDir.apply { mkdirs() }
        val part = File(dir, d.file + ".part")
        try {
            var have = part.length()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val status = conn.responseCode
                if (status !in setOf(200, 206)) throw IOException("the server answered HTTP $status")
                if (status == 200) have = 0
                d.total = have + conn.contentLengthLong.coerceAtLeast(0)
                d.done = have
                if (d.total - have > dir.usableSpace - (512L shl 20)) throw IOException("not enough space: needs ${d.total shr 20} MB")
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, status == 206).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (d.cancelled) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            d.done += n
                        }
                    }
                }
            } finally { conn.disconnect() }
            if (sha256 != null) {
                d.state = "checking"
                val md = MessageDigest.getInstance("SHA-256")
                part.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                if (md.digest().joinToString("") { "%02x".format(it) } != sha256) {
                    part.delete()
                    throw IOException("the download was damaged; try again")
                }
            }
            if (!part.renameTo(File(dir, d.file))) throw IOException("could not save the model")
            // The first model is the one to run.
            if (cfg.localModel == null) { cfg.localModel = d.file; if (cfg.enabled && runtimeInstalled) ServerService.restart(ctx) }
            d.state = "done"
        } catch (e: Exception) {
            if (d.cancelled) part.delete()
            d.state = if (d.cancelled) "cancelled" else "failed"
            d.error = e.message ?: e.javaClass.simpleName
        }
    }

    private fun deleteModel(file: String): Response {
        if (!Regex("[A-Za-z0-9._-]+\\.gguf").matches(file)) return Response.error(400, "invalid file")
        val f = File(modelsDir, file)
        if (!f.isFile) return Response.error(404, "No such model")
        if (cfg.localModel == file) { cfg.localModel = null; if (cfg.enabled) ServerService.restart(ctx) }
        // Give the server a moment to let go of it.
        Thread({ Thread.sleep(1500); f.delete() }, "ai-delete").start()
        return Response.ok()
    }

    /** Runs [work] as the dashboard's job (one at a time with installs), then restarts the model. */
    private fun job(title: String, work: () -> String?): Response {
        if (Jobs.running) return Response.error(409, "Another job is running; wait for it to finish")
        Jobs.begin(title)
        Thread({
            val error = try { work() } catch (e: Exception) { e.message ?: e.toString() }
            Jobs.finish(error)
            if (cfg.enabled) ServerService.restart(ctx)
        }, "ai-runtime").apply { isDaemon = true; start() }
        return Response.ok()
    }

    /** Downloads llama.cpp for this phone from this version's release. */
    private fun installRuntime(): Response {
        if (!llama.supported) return Response.error(409, "llama.cpp isn't available for this phone's CPU in this build")
        return job("Installing llama.cpp for this phone") {
            Jobs.line("Downloading ${llama.bundle?.optString("file")} (${(llama.bundle?.optLong("bytes") ?: 0) shr 20} MB)")
            llama.download()?.also { Jobs.line(it) } ?: run {
                val devices = llama.devices()
                Jobs.line(if (devices.isEmpty()) "Installed. No GPU that llama.cpp can use was found; models run on the CPU."
                    else "Installed. GPU: " + devices.joinToString { "${it.second} (${it.first})" })
                null
            }
        }
    }

    /**
     * Measures the chosen model on the CPU and the GPU and keeps the result; "Auto" then uses
     * the faster. The model server is stopped meanwhile: the phone has memory for one copy.
     */
    private fun benchmark(): Response {
        val file = cfg.localModel?.let { File(modelsDir, it) }?.takeIf { it.isFile } ?: return Response.error(409, "Pick a model to run first")
        if (!llama.installed) return Response.error(409, "Install llama.cpp first")
        return job("Benchmarking ${file.name}") {
            benchmarking = true
            try {
                ServerService.restart(ctx)
                val deadline = System.currentTimeMillis() + 30_000
                while (ServerService.supervisor?.daemons?.any { it.spec.name == SERVICE } != false && System.currentTimeMillis() < deadline) Thread.sleep(500)
                llama.benchmark = llama.bench(file, Jobs::line)
                null
            } finally { benchmarking = false }
        }
    }

    companion object {
        const val SERVICE = "ai-llama"

        /** Set while a benchmark runs, so the model server stays stopped. */
        @Volatile var benchmarking = false

        fun dir(ctx: Context) = AiConfig(ctx).modelsDir?.let(::File) ?: File(ctx.filesDir, "ai/models")

        /** The model's name in the API: its file name without the extension. */
        fun alias(file: String) = file.removeSuffix(".gguf").lowercase()

        /** The llama.cpp server for the chosen model, if the module is on and everything is in place. */
        fun spec(ctx: Context): Spec? {
            val c = AiConfig(ctx)
            val file = c.localModel ?: return null
            val model = File(dir(ctx), file)
            val llama = LlamaRuntime(ctx)
            if (!c.enabled || benchmarking || !model.isFile || !llama.installed) return null
            val args = listOf(
                "-m", model.path, "--alias", alias(file),
                "--host", "127.0.0.1", "--port", AiCore.LOCAL_PORT.toString(),
                "-c", c.contextSize.toString(), "-t", c.effectiveThreads.toString(),
                // llama.cpp's threads busy-wait for work by default (poll 50): on a phone that kept
                // ~7 cores spinning while idle and drained the battery (0.9.0). Sleep instead, and
                // let go of the model after 10 idle minutes (it reloads in seconds when asked).
                "--poll", "0", "--sleep-idle-seconds", "600",
            ) + llama.deviceArgs()
            return Spec(SERVICE, llama.command("llama-server", args), llama.env(), workdir = llama.dir)
        }
    }
}
