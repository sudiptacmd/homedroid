package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// The parts of deployments that don't need Android, so they can be tested off the phone.

/** Language runtimes a deployment can use, how each is recognised, installed, built and started in Alpine. */
object Runtimes {
    class Runtime(val id: String, val install: String, val build: String, val start: String)

    /** Checked in order: the first runtime with one of its files in the project wins. */
    val RULES = listOf(
        "deno" to listOf("deno.json", "deno.jsonc"),
        "bun" to listOf("bun.lockb", "bun.lock"),
        // Before node: PHP frameworks ship a package.json for their assets.
        "php" to listOf("composer.json"),
        "node" to listOf("package.json"),
        "go" to listOf("go.mod"),
        "python" to listOf("requirements.txt", "pyproject.toml"),
        "php" to listOf("index.php", "public/index.php"),
    )

    // Bun isn't packaged for Alpine: the official musl build, checked against the release's checksums.
    private const val BUN_VERSION = "1.3.0"
    private val BUN_INSTALL = """
        |command -v bun >/dev/null || {
        |  case ${'$'}(uname -m) in aarch64) a=aarch64 ;; x86_64) a=x64 ;; *) echo "Bun has no build for ${'$'}(uname -m)" >&2; exit 1 ;; esac
        |  command -v curl >/dev/null || apk add --no-cache curl
        |  t=${'$'}(mktemp -d); f=bun-linux-${'$'}a-musl
        |  u=https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION
        |  curl -fsSL -o "${'$'}t/${'$'}f.zip" "${'$'}u/${'$'}f.zip"
        |  curl -fsSL -o "${'$'}t/SHASUMS256.txt" "${'$'}u/SHASUMS256.txt"
        |  (cd "${'$'}t" && grep " ${'$'}f.zip${'$'}" SHASUMS256.txt | sha256sum -c -)
        |  apk add --no-cache libstdc++ libgcc
        |  unzip -q "${'$'}t/${'$'}f.zip" -d "${'$'}t" && install -m 755 "${'$'}t/${'$'}f/bun" /usr/local/bin/bun
        |  rm -rf "${'$'}t"
        |}
        |bun install""".trimMargin()

    private const val PHP_PACKAGES = "php84 php84-ctype php84-curl php84-dom php84-fileinfo php84-iconv php84-mbstring " +
        "php84-openssl php84-pdo php84-pdo_sqlite php84-phar php84-session php84-tokenizer php84-xml"

    val ALL = listOf(
        Runtime(
            "node",
            "command -v npm >/dev/null || apk add --no-cache nodejs npm\nif [ -f package-lock.json ]; then npm ci; else npm install; fi",
            "npm run build --if-present",
            "npm start",
        ),
        Runtime(
            "python",
            "command -v python3 >/dev/null || apk add --no-cache python3\n[ -d .venv ] || python3 -m venv .venv\n" +
                "if [ -f requirements.txt ]; then .venv/bin/pip install -r requirements.txt\n" +
                "elif [ -f pyproject.toml ]; then .venv/bin/pip install .; fi",
            "",
            "for f in main.py app.py server.py; do [ -f \$f ] && exec .venv/bin/python \$f; done; " +
                "echo 'Set a start command for this Python app' >&2; exit 1",
        ),
        Runtime(
            "go",
            "command -v go >/dev/null || apk add --no-cache go git\ngo mod download",
            "mkdir -p .homedroid && go build -o .homedroid/server .",
            "exec ./.homedroid/server",
        ),
        Runtime(
            "php",
            "command -v php84 >/dev/null || apk add --no-cache $PHP_PACKAGES\n" +
                "if [ -f composer.json ]; then command -v composer >/dev/null || apk add --no-cache composer; " +
                "composer install --no-dev --no-interaction --optimize-autoloader; fi",
            "",
            // PHP's own server; several workers so one slow request doesn't block the rest.
            "d=.; [ -f public/index.php ] && d=public; PHP_CLI_SERVER_WORKERS=4 exec php84 -S 0.0.0.0:\$PORT -t \$d",
        ),
        Runtime(
            "bun",
            BUN_INSTALL,
            "if grep -q '\"build\"[[:space:]]*:' package.json 2>/dev/null; then bun run build; fi",
            "if grep -q '\"start\"[[:space:]]*:' package.json 2>/dev/null; then exec bun run start; fi; " +
                "for f in index.ts index.js server.ts server.js; do [ -f \$f ] && exec bun run \$f; done; " +
                "echo 'Set a start command for this Bun app' >&2; exit 1",
        ),
        Runtime(
            "deno",
            "command -v deno >/dev/null || apk add --no-cache deno\ndeno install",
            "if grep -qs '\"build\"[[:space:]]*:' deno.json deno.jsonc; then deno task build; fi",
            "if grep -qs '\"start\"[[:space:]]*:' deno.json deno.jsonc; then exec deno task start; fi; " +
                "for f in main.ts main.js server.ts mod.ts; do [ -f \$f ] && exec deno run -A \$f; done; " +
                "echo 'Set a start command for this Deno app' >&2; exit 1",
        ),
        Runtime("static", "", "", ""),
    )

    /** What a deployment's runtime may be set to. */
    val CHOICES = listOf("auto") + ALL.map { it.id }

    fun get(id: String) = ALL.firstOrNull { it.id == id }

    /** The runtime for a project, given whether each relative path exists in it. */
    fun detect(exists: (String) -> Boolean): String =
        RULES.firstOrNull { (_, files) -> files.any(exists) }?.first ?: "static"

    /** The same rules as [detect], as shell that sets rt in the project folder. */
    fun detectScript(): String = RULES.mapIndexed { i, (rt, files) ->
        (if (i == 0) "if " else "elif ") + files.joinToString(" || ") { "[ -f $it ]" } + "; then rt=$rt"
    }.joinToString("\n") + "\nelse rt=static; fi"

    /** Shell case statement over rt: install dependencies, then the default build unless one is set in BUILD. */
    fun installScript(): String = buildString {
        append("case \$rt in\n")
        for (r in ALL.filter { it.install.isNotEmpty() }) append("${r.id})\n").append(r.install.prependIndent("  ")).append(" ;;\n")
        append("esac\n")
        append("if [ -n \"\$BUILD\" ]; then sh -c \"\$BUILD\"\nelse case \$rt in\n")
        for (r in ALL.filter { it.build.isNotEmpty() }) append("${r.id}) ${r.build} ;;\n")
        append("esac; fi")
    }

    fun defaultStart(id: String) = get(id)?.start.orEmpty()
}

/** Deploy tokens and webhook signatures for the inbound hooks. Tokens are stored only as hashes. */
object DeployHooksCore {
    private val random = SecureRandom()

    fun newSecret(prefix: String) = prefix + ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun hash(token: String) = sha256(token.toByteArray())

    /** Constant-time check of a presented token against its stored hash. */
    fun tokenMatches(given: String?, storedHash: String): Boolean {
        if (given.isNullOrEmpty() || storedHash.isEmpty()) return false
        return MessageDigest.isEqual(hash(given).toByteArray(), storedHash.toByteArray())
    }

    /** GitHub's X-Hub-Signature-256: "sha256=" and the hex HMAC-SHA256 of the raw body with the webhook secret. */
    fun githubSignatureValid(secret: String, body: ByteArray, header: String?): Boolean {
        if (secret.isEmpty() || header == null || !header.startsWith("sha256=")) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val expected = "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), header.lowercase().toByteArray())
    }

    /** The branch a GitHub push event is for, or null for tags and other refs. */
    fun pushBranch(payload: JSONObject): String? =
        payload.optString("ref").takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
}

/** A pipeline step: a shell command run in the project folder inside Alpine. */
class PipelineStep(val name: String, val run: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("run", run)
}

object Pipelines {
    const val MAX_STEPS = 30

    /**
     * Steps from a deployment's settings or .homedroid/pipeline.json: a list of {name, run},
     * or an object with such a list in "steps". Throws IllegalArgumentException when invalid.
     */
    fun parse(value: Any?): List<PipelineStep> {
        val arr = when (value) {
            null, JSONObject.NULL -> return emptyList()
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("steps") ?: throw IllegalArgumentException("pipeline needs a \"steps\" list")
            is String -> if (value.isBlank()) return emptyList() else try {
                val t = value.trim()
                return parse(if (t.startsWith("[")) JSONArray(t) else JSONObject(t))
            } catch (_: org.json.JSONException) {
                throw IllegalArgumentException("pipeline isn't valid JSON")
            }
            else -> throw IllegalArgumentException("pipeline must be a list of steps")
        }
        require(arr.length() <= MAX_STEPS) { "a pipeline can have at most $MAX_STEPS steps" }
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("pipeline step ${i + 1} must be {name, run}")
            val run = o.optString("run").trim()
            require(run.isNotEmpty() && run.length <= 4000) { "pipeline step ${i + 1} needs a run command" }
            val name = o.optString("name").filter { !it.isISOControl() }.trim().take(80).ifEmpty { "Step ${i + 1}" }
            PipelineStep(name, run)
        }
    }

    fun toJson(steps: List<PipelineStep>) = JSONArray().apply { steps.forEach { put(it.toJson()) } }
}

/**
 * A deployment's pipeline runs, one JSON file each in [dir], newest kept. A run is
 * {id, trigger, status, started, ms, steps: [{name, status, ms, log}]}; status is queued,
 * running, passed or failed.
 */
class PipelineRuns(private val dir: File, private val keep: Int = 20, private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized
    fun create(trigger: String, steps: List<PipelineStep>): JSONObject {
        dir.mkdirs()
        val now = clock()
        // Sortable by name: millisecond time, then a random suffix.
        val id = "%013d-%04x".format(now, SecureRandom().nextInt(0x10000))
        val run = JSONObject().put("id", id).put("trigger", trigger).put("status", "queued").put("started", now).put("ms", 0)
            .put("steps", JSONArray().apply { steps.forEach { put(JSONObject().put("name", it.name).put("status", "pending").put("ms", 0).put("log", "")) } })
        save(run)
        prune()
        return run
    }

    @Synchronized
    fun save(run: JSONObject) {
        val f = file(run.getString("id")) ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(run.toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun get(id: String): JSONObject? = file(id)?.takeIf { it.isFile }?.let { try { JSONObject(it.readText()) } catch (_: Exception) { null } }

    /** Newest first, without step logs. */
    @Synchronized
    fun list(): List<JSONObject> = ids().asReversed().mapNotNull(::get).map { r ->
        JSONObject(r.toString()).apply {
            val steps = getJSONArray("steps")
            for (i in 0 until steps.length()) steps.getJSONObject(i).remove("log")
        }
    }

    private fun ids() = dir.list().orEmpty().filter { ID.matches(it.removeSuffix(".json")) && it.endsWith(".json") }
        .map { it.removeSuffix(".json") }.sorted()

    private fun prune() = ids().dropLast(keep).forEach { file(it)?.delete() }

    private fun file(id: String) = if (ID.matches(id)) File(dir, "$id.json") else null

    companion object {
        private val ID = Regex("[0-9]{13}-[0-9a-f]{4}")
        const val MAX_STEP_LOG = 64 * 1024

        /** Keeps the end of a step's output. */
        fun clip(log: String) = if (log.length <= MAX_STEP_LOG) log else "…\n" + log.takeLast(MAX_STEP_LOG)
    }
}

/** How a deployment is served from several phones at once. */
class Balance(val port: Int, val policy: String, val healthUri: String, val interval: Int) {
    fun toJson(): JSONObject = JSONObject().put("port", port).put("policy", policy).put("healthUri", healthUri).put("interval", interval)

    companion object {
        val POLICIES = setOf("round_robin", "first", "least_conn", "ip_hash", "random")

        /** Throws IllegalArgumentException when invalid; null when [o] is null. */
        fun parse(o: JSONObject?): Balance? {
            o ?: return null
            val port = o.optInt("port", 0)
            require(port in 1024..65535) { "the balancer port must be between 1024 and 65535" }
            val policy = o.optString("policy", "round_robin").ifEmpty { "round_robin" }
            require(policy in POLICIES) { "unknown balancing policy $policy" }
            val uri = o.optString("healthUri", "/").ifEmpty { "/" }
            // It's written into the Caddyfile: nothing that could end a token or a block.
            require(uri.length <= 200 && Regex("/[A-Za-z0-9._~/?&=%+-]*").matches(uri)) { "the health check path must start with / and have no spaces" }
            val interval = o.optInt("interval", 10)
            require(interval in 2..3600) { "the health check interval must be 2–3600 seconds" }
            return Balance(port, policy, uri, interval)
        }
    }
}

/** The Caddy configuration for load-balanced deployments. */
object Balancer {
    const val IMPORT = "import Caddyfile.d/*.caddy"
    const val SNIPPET = "homedroid-balancer.caddy"

    class Site(val id: String, val balance: Balance, val upstreams: List<String>)

    /** A Caddy upstream for [host]:[port], or null for anything that isn't a plain address or name. */
    fun upstream(host: String, port: Int): String? {
        if (port !in 1..65535 || !Peer.validHost(host) || '%' in host) return null
        return if (':' in host) "[$host]:$port" else "$host:$port"
    }

    /** This phone first, then each other phone running the deployment. */
    fun upstreams(localPort: Int, peers: List<Pair<String, Int>>): List<String> =
        (listOf("127.0.0.1:$localPort") + peers.mapNotNull { (h, p) -> upstream(h, p) }).distinct()

    fun snippet(sites: List<Site>): String = buildString {
        append("# Managed by Homedroid: load-balanced deployments. Changes here are overwritten.\n")
        for (s in sites.sortedBy { it.id }) {
            if (s.upstreams.isEmpty()) continue
            val b = s.balance
            append("\n# ${s.id}\n:${b.port} {\n")
            append("\treverse_proxy ${s.upstreams.joinToString(" ")} {\n")
            append("\t\tlb_policy ${b.policy}\n")
            append("\t\tlb_try_duration 5s\n")
            append("\t\thealth_uri ${b.healthUri}\n")
            append("\t\thealth_interval ${b.interval}s\n")
            append("\t\thealth_timeout 5s\n")
            append("\t\tfail_duration 30s\n")
            append("\t}\n}\n")
        }
    }

    /** [caddyfile] with the import of Homedroid's snippets added at the end, or null if it's already there. */
    fun withImport(caddyfile: String): String? =
        if (caddyfile.contains(IMPORT)) null else caddyfile.trimEnd() + "\n\n" + IMPORT + "\n"
}

/**
 * A project uploaded as a .zip, .tar.gz or .tar, unpacked into a fresh folder. Only files and
 * folders are created: links, devices and anything that would land outside the folder are
 * refused or skipped, and the total size and entry count are capped.
 */
object UploadArchive {
    const val MAX_ENTRIES = 100_000

    /** A safe relative path for an entry, "" for the root itself, or null if it's unsafe. */
    fun entryPath(name: String): String? {
        if ('\u0000' in name || '\\' in name || name.startsWith("/")) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Unpacks [archive] into [dest] (which must not exist yet); returns the bytes written. */
    fun extract(archive: File, dest: File, maxBytes: Long): Long {
        require(!dest.exists()) { "destination exists" }
        dest.mkdirs()
        val head = ByteArray(512)
        val n = archive.inputStream().use { readUpTo(it, head) }
        val writer = Writer(dest, maxBytes)
        archive.inputStream().buffered(1 shl 16).use { input ->
            when {
                n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte() -> unzip(input, writer)
                n >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> untar(GZIPInputStream(input, 1 shl 16), writer)
                n >= 262 && String(head, 257, 5) == "ustar" -> untar(input, writer)
                else -> throw IOException("Upload a .zip, .tar.gz or .tar file")
            }
        }
        hoist(dest)
        return writer.total
    }

    /** Archives often hold one top folder (repo-main/): its contents become the project. */
    fun hoist(dest: File) {
        val children = dest.listFiles().orEmpty()
        val only = children.singleOrNull() ?: return
        if (!only.isDirectory || Files.isSymbolicLink(only.toPath())) return
        val tmp = File(dest.parentFile, dest.name + ".hoist")
        if (tmp.exists() || !only.renameTo(tmp)) return
        dest.delete()
        tmp.renameTo(dest)
    }

    private class Writer(val root: File, val maxBytes: Long) {
        var total = 0L
        var entries = 0

        fun target(name: String): File? {
            if (++entries > MAX_ENTRIES) throw IOException("The archive has too many files")
            val rel = entryPath(name) ?: throw IOException("The archive has an unsafe path: ${name.take(100)}")
            // macOS metadata, never part of the project.
            if (rel.isEmpty() || rel == "__MACOSX" || rel.startsWith("__MACOSX/")) return null
            var f = root
            for (part in rel.split('/')) {
                if (Files.isSymbolicLink(f.toPath())) throw IOException("The archive writes through a link")
                f = File(f, part)
            }
            if (Files.isSymbolicLink(f.toPath())) throw IOException("The archive writes through a link")
            return f
        }

        fun dir(name: String) {
            val f = target(name) ?: return
            if (!f.isDirectory && !f.mkdirs()) throw IOException("Couldn't create ${f.name}")
        }

        fun file(name: String, input: InputStream, size: Long, executable: Boolean) {
            val f = target(name)
            if (f == null) { copy(input, null, size); return }
            if (f.isDirectory) throw IOException("The archive has a file and a folder called ${f.name}")
            f.parentFile!!.mkdirs()
            Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { copy(input, it, size) }
            if (executable) f.setExecutable(true, false)
        }

        /** Copies [size] bytes, or everything when size is -1. */
        private fun copy(input: InputStream, out: java.io.OutputStream?, size: Long) {
            val buf = ByteArray(1 shl 16)
            var left = if (size < 0) Long.MAX_VALUE else size
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) { if (size < 0) return; throw EOFException("The archive is cut off") }
                total += r
                if (total > maxBytes) throw IOException("The unpacked project is larger than ${maxBytes shr 20} MB")
                out?.write(buf, 0, r)
                left -= r
            }
        }
    }

    private fun unzip(input: InputStream, w: Writer) {
        val zip = ZipInputStream(input)
        var any = false
        while (true) {
            val e = zip.nextEntry ?: break
            any = true
            if (e.isDirectory) w.dir(e.name) else w.file(e.name, zip, -1, false)
        }
        if (!any) throw IOException("The zip file is empty")
    }

    private fun untar(raw: InputStream, w: Writer) {
        val input = BufferedInputStream(raw, 1 shl 16)
        val header = ByteArray(512)
        var longName: String? = null
        var paxPath: String? = null
        while (true) {
            if (readUpTo(input, header) < 512) throw EOFException("The archive is cut off")
            if (header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            if (size < 0) throw IOException("Invalid tar file")
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> { longName = String(meta(input, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> { paxPath = paxPath(meta(input, size)); continue }
            }
            val prefix = string(header, 345, 155)
            val short = string(header, 0, 100)
            val name = paxPath ?: longName ?: if (prefix.isEmpty()) short else "$prefix/$short"
            longName = null; paxPath = null
            val mode = octal(header, 100, 8)
            when (type) {
                '5' -> { w.dir(name); skip(input, size) }
                '0', '\u0000', '7' -> { w.file(name, input, size, mode and 0x49L != 0L); padding(input, size) }
                // Links, devices and the like: never created.
                else -> skip(input, size)
            }
        }
    }

    private fun meta(input: InputStream, size: Long): ByteArray {
        if (size !in 0..(1L shl 20)) throw IOException("Invalid tar file")
        val b = ByteArray(size.toInt())
        if (readUpTo(input, b) < b.size) throw EOFException("The archive is cut off")
        padding(input, size)
        return b
    }

    private fun paxPath(data: ByteArray): String? {
        var i = 0
        while (i < data.size) {
            val sp = (i until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val len = String(data, i, sp - i).toIntOrNull() ?: break
            if (len <= sp - i + 2 || len > data.size - i) throw IOException("Invalid tar file")
            val record = String(data, sp + 1, len - (sp - i) - 2, Charsets.UTF_8)
            if (record.startsWith("path=")) return record.removePrefix("path=")
            i += len
        }
        return null
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException("The archive is cut off")
            left -= r
        }
        padding(input, size)
    }

    private fun padding(input: InputStream, size: Long) {
        val pad = ((512 - size % 512) % 512).toInt()
        if (pad > 0 && readUpTo(input, ByteArray(pad)) < pad) throw EOFException("The archive is cut off")
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun string(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        val s = string(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: -1
    }
}
