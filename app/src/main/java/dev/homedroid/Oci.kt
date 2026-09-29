package dev.homedroid

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** What an image needs to run: its default command, environment and working directory. */
class ImageConfig(
    val entrypoint: List<String>,
    val cmd: List<String>,
    val env: Map<String, String>,
    val workdir: String,
) {
    companion object {
        fun read(rootfs: File): ImageConfig? {
            val f = File(rootfs, Oci.CONFIG_FILE)
            if (!f.exists()) return null
            val c = JSONObject(f.readText())
            fun list(key: String) = c.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            val env = list("Env").associate { it.substringBefore('=') to it.substringAfter('=', "") }
            return ImageConfig(list("Entrypoint"), list("Cmd"), env, c.optString("WorkingDir").ifEmpty { "/" })
        }
    }
}

/**
 * Pulls container images (Docker Hub, ghcr.io, any registry with anonymous pulls) into a
 * directory, without a container runtime: layers are streamed through gunzip into [Tar],
 * which applies whiteouts and stays inside the rootfs. Each layer's digest is verified.
 */
class Oci(private val arch: String) {
    /** Pulls [ref] (like ghcr.io/org/app:tag) into [dest], replacing what is there. */
    fun pull(ref: String, dest: File, log: (String) -> Unit) {
        val image = parse(ref)
        log("Pulling $ref")
        var manifest = fetchJson(image, "manifests/${image.reference}", MANIFEST_TYPES)
        if (manifest.has("manifests")) {
            val list = manifest.getJSONArray("manifests")
            val match = (0 until list.length()).map(list::getJSONObject).firstOrNull { m ->
                val p = m.optJSONObject("platform") ?: return@firstOrNull false
                p.optString("os") == "linux" && p.optString("architecture") == arch &&
                    (arch != "arm" || p.optString("variant", "v7") == "v7")
            } ?: throw IOException("$ref has no linux/$arch image")
            manifest = fetchJson(image, "manifests/${match.getString("digest")}", MANIFEST_TYPES)
        }
        val config = fetchJson(image, "blobs/${manifest.getJSONObject("config").getString("digest")}", "*/*")

        val partial = File(dest.path + ".partial")
        partial.deleteRecursivelyNoFollow()
        val tar = Tar(partial, oci = true)
        val layers = manifest.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val digest = layer.getString("digest")
            val type = layer.optString("mediaType")
            if (!type.endsWith("gzip") && !type.endsWith(".tar")) throw IOException("unsupported layer type $type")
            log("  layer ${i + 1}/${layers.length()}: ${layer.optLong("size") shr 20} MB")
            open(image, "blobs/$digest", "*/*").use { raw ->
                val md = MessageDigest.getInstance("SHA-256")
                val counted = DigestInputStream(raw.buffered(1 shl 16), md)
                val input: InputStream = if (type.endsWith("gzip")) GZIPInputStream(counted, 1 shl 16) else counted
                tar.extract(input)
                // Finish reading so the digest covers the whole blob.
                val buf = ByteArray(1 shl 16)
                while (counted.read(buf) >= 0) Unit
                val actual = "sha256:" + md.digest().joinToString("") { "%02x".format(it) }
                if (actual != digest) throw IOException("layer $digest is corrupt")
            }
        }
        File(partial, CONFIG_FILE).writeText(config.getJSONObject("config").toString())
        dest.deleteRecursivelyNoFollow()
        if (!partial.renameTo(dest)) throw IOException("could not move image into place")
        log("  done")
    }

    private class ImageRef(val registry: String, val repo: String, val reference: String) {
        var token: String? = null
    }

    private fun parse(ref: String): ImageRef {
        var name = ref
        var reference = "latest"
        val at = name.indexOf('@')
        if (at >= 0) {
            reference = name.substring(at + 1)
            name = name.substring(0, at)
        } else {
            val colon = name.lastIndexOf(':')
            if (colon > name.lastIndexOf('/')) {
                reference = name.substring(colon + 1)
                name = name.substring(0, colon)
            }
        }
        val first = name.substringBefore('/')
        val hasRegistry = name.contains('/') && (first.contains('.') || first.contains(':') || first == "localhost")
        var registry = if (hasRegistry) first else "docker.io"
        var repo = if (hasRegistry) name.substringAfter('/') else name
        if (registry == "docker.io") {
            registry = "registry-1.docker.io"
            if (!repo.contains('/')) repo = "library/$repo"
        }
        return ImageRef(registry, repo, reference)
    }

    private fun fetchJson(image: ImageRef, path: String, accept: String) =
        open(image, path, accept).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }

    /** GET from the registry, handling anonymous token auth and blob redirects. */
    private fun open(image: ImageRef, path: String, accept: String): InputStream {
        var url = URL("https://${image.registry}/v2/${image.repo}/$path")
        var withAuth = true
        repeat(6) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("Accept", accept)
                if (withAuth) image.token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            when (val code = c.responseCode) {
                200 -> return c.inputStream
                301, 302, 303, 307, 308 -> {
                    // Blob storage URLs are pre-signed; the registry token must not follow.
                    url = URL(url, c.getHeaderField("Location"))
                    withAuth = false
                    c.disconnect()
                }
                401 -> {
                    if (image.token != null || !withAuth) throw IOException("${image.repo}: not authorized")
                    image.token = token(c.getHeaderField("WWW-Authenticate") ?: throw IOException("no auth challenge"), image)
                    c.disconnect()
                }
                else -> throw IOException("${image.repo} $path: HTTP $code")
            }
        }
        throw IOException("too many redirects for ${image.repo}")
    }

    private fun token(challenge: String, image: ImageRef): String {
        val params = Regex("""(\w+)="([^"]*)"""").findAll(challenge).associate { it.groupValues[1] to it.groupValues[2] }
        val realm = params["realm"] ?: throw IOException("bad auth challenge")
        val service = params["service"]?.let { "&service=$it" }.orEmpty()
        val scope = params["scope"] ?: "repository:${image.repo}:pull"
        val c = URL("$realm?scope=$scope$service").openConnection() as HttpURLConnection
        if (c.responseCode != 200) throw IOException("token request failed: HTTP ${c.responseCode}")
        val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        return o.optString("token").ifEmpty { o.getString("access_token") }
    }

    companion object {
        const val CONFIG_FILE = ".homedroid-image.json"

        private val MANIFEST_TYPES = listOf(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        ).joinToString()

        /** Registry architecture name for the ABI the app runs as. */
        fun archFor(nativeLibDir: File) = when (nativeLibDir.name) {
            "arm64" -> "arm64"
            "arm" -> "arm"
            "x86_64" -> "amd64"
            else -> throw IOException("unsupported CPU ${nativeLibDir.name}")
        }

        private fun File.deleteRecursivelyNoFollow() {
            if (exists()) ProcessBuilder("/system/bin/rm", "-rf", path).start().waitFor()
        }
    }
}
