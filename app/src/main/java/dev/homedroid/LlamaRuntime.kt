package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * llama.cpp built for Android (native/build-llama.sh): bionic programs that can use the
 * phone's GPU through Vulkan or OpenCL. Not in the APK; downloaded from this version's GitHub
 * release on first use and accepted only if it matches the hash the APK was built with
 * (assets/llama-runtime.json).
 *
 * Android won't execve() a file in app storage, so the programs run through the system's
 * dynamic linker (/system/bin/linker64 <program>), which maps them like libraries. Backends are
 * found in the working directory, which is the runtime's folder.
 */
class LlamaRuntime(context: Context) {
    private val ctx: Context = context.applicationContext
    private val prefs = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)
    val dir = File(ctx.filesDir, "ai/runtime")
    private val stamp = File(dir, ".sha256")

    val abi: String = ctx.applicationInfo.nativeLibraryDir.let { File(it).name }.let {
        when (it) { "arm64" -> "arm64-v8a"; "x86_64" -> "x86_64"; else -> it }
    }

    /** This build's bundle for this phone, or null (32-bit phones, or a build made without it). */
    val bundle: JSONObject? = try {
        JSONObject(ctx.assets.open("llama-runtime.json").use { it.readBytes().toString(Charsets.UTF_8) })
            .optJSONObject("abis")?.optJSONObject(abi)
    } catch (_: IOException) { null }

    val supported get() = bundle != null
    val installed get() = File(dir, "llama-server").isFile && stamp.isFile
    /** Installed, but from another app version: it should be replaced. */
    val outdated get() = installed && bundle != null && stamp.readText().trim() != bundle.optString("sha256")

    // --- install ------------------------------------------------------------------------------

    @Volatile var progress: Pair<Long, Long>? = null; private set

    /** Downloads this version's bundle from GitHub; returns an error, or null. */
    fun download(): String? {
        val b = bundle ?: return "This build has no on-phone AI runtime for this phone's CPU"
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        val url = "https://github.com/$REPO/releases/download/v$version/${b.getString("file")}"
        val tmp = File(ctx.cacheDir, "llama-runtime.tar.gz")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
            try {
                if (conn.responseCode != 200) return "Couldn't download the runtime (HTTP ${conn.responseCode}) from $url"
                val total = conn.contentLengthLong
                conn.inputStream.use { input -> tmp.outputStream().use { out -> copy(input, out, total) } }
            } finally { conn.disconnect() }
            return install(tmp)
        } catch (e: IOException) {
            return "Couldn't download the runtime: ${e.message}"
        } finally { tmp.delete(); progress = null }
    }

    /** Installs a bundle uploaded from the dashboard (for testing before a release exists). */
    fun upload(r: Request): String? {
        if (r.length !in 1..(400L shl 20)) return "Upload the llama-runtime-$abi.tar.gz file"
        val tmp = File(ctx.cacheDir, "llama-runtime.tar.gz")
        try {
            tmp.outputStream().use { if (!r.bodyTo(it)) return "The upload was cut off" }
            return install(tmp)
        } finally { tmp.delete() }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, total: Long) {
        val buf = ByteArray(1 shl 16)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            progress = done to total
        }
    }

    /** Checks [tgz] against the hash this APK was built with and unpacks it into [dir]. */
    @Synchronized private fun install(tgz: File): String? {
        val want = bundle?.optString("sha256") ?: return "This build has no runtime hash to check against"
        val got = MessageDigest.getInstance("SHA-256").let { md ->
            tgz.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (got != want) return "That runtime doesn't belong to this version of Homedroid (checksum mismatch)"
        val next = File(dir.parentFile, "runtime.new")
        next.deleteRecursively()
        GZIPInputStream(tgz.inputStream().buffered(), 1 shl 16).use { Tar(next).extract(it) }
        if (!File(next, "llama-server").isFile) { next.deleteRecursively(); return "The runtime archive has no llama-server" }
        File(next, ".sha256").writeText(want)
        val old = File(dir.parentFile, "runtime.old")
        old.deleteRecursively()
        if (dir.exists() && !dir.renameTo(old)) return "Couldn't replace the old runtime"
        if (!next.renameTo(dir)) return "Couldn't install the runtime"
        old.deleteRecursively()
        devices = null
        return null
    }

    fun remove() { dir.deleteRecursively(); devices = null }

    // --- running ----------------------------------------------------------------------------

    /** argv that runs [program] from the runtime through the system linker. */
    fun command(program: String, args: List<String>) =
        listOf(if (abi == "x86_64" || abi == "arm64-v8a") "/system/bin/linker64" else "/system/bin/linker", File(dir, program).path) + args

    /** Libraries come from the runtime first, then the vendor's (OpenCL lives there on Qualcomm phones). */
    fun env(): Map<String, String> = mapOf("LD_LIBRARY_PATH" to listOf(dir.path, "/vendor/lib64", "/system/lib64").joinToString(":"))

    /** Runs a runtime program to completion; returns its exit code and output. */
    fun run(program: String, args: List<String>, timeoutSeconds: Long = 600): Pair<Int, String> {
        val p = ProcessBuilder(command(program, args)).directory(dir).redirectErrorStream(true)
            .apply { environment().putAll(env()) }.start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { if (out.length < 200_000) out.append(it).append('\n') } } }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); throw IOException("$program took too long") }
        reader.join(2000)
        return p.exitValue() to synchronized(out) { out.toString() }
    }

    /** GPU devices llama.cpp finds on this phone, like "Vulkan0: Xclipse 940". Cached. */
    @Volatile private var devices: List<Pair<String, String>>? = null

    /** Asked once per installed runtime (it takes a few seconds) and remembered. */
    fun devices(): List<Pair<String, String>> {
        devices?.let { return it }
        if (!installed) return emptyList()
        val key = stamp.readText().trim()
        prefs.getString("devices", null)?.let { JSONObject(it) }?.takeIf { it.optString("runtime") == key }?.let { saved ->
            val a = saved.getJSONArray("list")
            return (0 until a.length()).map { a.getJSONArray(it).let { d -> d.getString(0) to d.getString(1) } }.also { devices = it }
        }
        val (rc, out) = try { run("llama-server", listOf("--list-devices"), 60) } catch (_: IOException) { return emptyList() }
        val found = if (rc != 0) emptyList() else parseDevices(out)
        devices = found
        prefs.edit().putString("devices", JSONObject().put("runtime", key)
            .put("list", JSONArray().apply { found.forEach { put(JSONArray().put(it.first).put(it.second)) } }).toString()).apply()
        return found
    }

    /** Where the model runs: "cpu", "gpu" or "auto" (the benchmark's winner, else the CPU). */
    var device: String
        get() = prefs.getString("device", "auto")!!
        set(v) = prefs.edit().putString("device", v).apply()

    /** The last benchmark: {"model", "cpu": {pp, tg}, "gpu": {pp, tg, device}}. */
    var benchmark: JSONObject?
        get() = prefs.getString("benchmark", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        set(v) = prefs.edit().putString("benchmark", v?.toString()).apply()

    /** The GPU to use if the GPU is chosen: the benchmarked one, else the first found. */
    private fun gpu(): String? {
        benchmark?.optJSONObject("gpu")?.optString("device")?.takeIf { it.isNotEmpty() }?.let { return it }
        // Not one the benchmark saw crash.
        val failed = benchmark?.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("device") } }.orEmpty()
        return devices().firstOrNull { it.first !in failed }?.first
    }

    /** The benchmark's writing speed (tokens/s) where the model runs with the current choice, if measured. */
    fun measuredSpeed(): Double? {
        val b = benchmark ?: return null
        val cpu = b.optJSONObject("cpu")?.optDouble("tg")?.takeIf { it > 0 }
        val gpu = b.optJSONObject("gpu")?.optDouble("tg")?.takeIf { it > 0 }
        return when (device) { "cpu" -> cpu; "gpu" -> gpu ?: cpu; else -> listOfNotNull(cpu, gpu).maxOrNull() }
    }

    /** llama-server's device flags for the current choice. */
    fun deviceArgs(): List<String> {
        val useGpu = when (device) {
            "gpu" -> true
            "cpu" -> false
            else -> benchmark?.let { b -> (b.optJSONObject("gpu")?.optDouble("tg") ?: 0.0) > (b.optJSONObject("cpu")?.optDouble("tg") ?: 0.0) } ?: false
        }
        val g = if (useGpu) gpu() else null
        return if (g != null) listOf("--device", g, "-ngl", "999") else listOf("--device", "none", "-ngl", "0")
    }

    /**
     * Measures prompt reading and writing speed with [model] on the CPU and on each GPU
     * (llama-bench, a short run). The model server must be stopped first, for the memory.
     */
    fun bench(model: File, log: (String) -> Unit): JSONObject {
        val result = JSONObject().put("model", model.name).put("bytes", model.length()).put("at", System.currentTimeMillis())
        fun one(label: String, args: List<String>): JSONObject? {
            log("Benchmarking on $label…")
            val (rc, out) = run("llama-bench", listOf("-m", model.path, "-p", "128", "-n", "48", "-r", "2", "-o", "json") + args, 900)
            if (rc != 0) {
                // The cause is rarely on the last line (backend loading is logged after it).
                log("  $label failed (exit $rc):")
                out.lines().filter { it.isNotBlank() && !it.startsWith("load_backend:") }.takeLast(8).forEach { log("    $it") }
                return null
            }
            val rows = try { JSONArray(out.substring(out.indexOf('['))) } catch (_: Exception) { log("  couldn't read the results"); return null }
            var pp = 0.0; var tg = 0.0
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optInt("n_prompt") > 0) pp = row.optDouble("avg_ts") else if (row.optInt("n_gen") > 0) tg = row.optDouble("avg_ts")
            }
            log("  %s: reads %.1f tokens/s, writes %.1f tokens/s".format(label, pp, tg))
            return JSONObject().put("pp", pp).put("tg", tg)
        }
        one("the CPU", listOf("-ngl", "0", "-dev", "none"))?.let { result.put("cpu", it) }
        var best: JSONObject? = null
        for ((id, name) in devices()) {
            val r = one("$name ($id)", listOf("-ngl", "999", "-dev", id))
            if (r == null) {
                // Old GPU drivers can crash llama.cpp (a Mali-G72 does): never run the model there.
                result.put("failed", (result.optJSONArray("failed") ?: JSONArray()).put(JSONObject().put("device", id).put("name", name)))
                continue
            }
            r.put("device", id).put("name", name)
            if (best == null || r.optDouble("tg") > best.optDouble("tg")) best = r
        }
        best?.let { result.put("gpu", it) }
        if (devices().isEmpty()) log("No GPU that llama.cpp can use was found on this phone.")
        return result
    }

    companion object {
        private const val REPO = "sudiptacmd/homedroid"
        // Indented lines under "Available devices:", e.g. "  Vulkan0: Samsung Xclipse 940 (11010 MiB, 9000 MiB free)"
        // or "  GPUOpenCL: QUALCOMM Adreno(TM) 750 (…)"; log lines like "load_backend: …" aren't indented.
        /** Devices from `llama-server --list-devices` output, as (id, name). */
        fun parseDevices(out: String) = out.lines().mapNotNull { DEVICE.find(it) }.map { it.groupValues[1] to it.groupValues[2].trim() }

        private val DEVICE = Regex("^\\s{2,}([A-Za-z][A-Za-z0-9]*):\\s+(.+?)(\\s*\\([^()]*\\))?$")
    }
}
