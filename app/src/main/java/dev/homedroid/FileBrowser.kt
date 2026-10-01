package dev.homedroid

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Copies from other phones into this one: running and recent, for the dashboard. */
object Transfers {
    class Transfer(val id: Int, val name: String, val from: String, val move: Boolean) {
        @Volatile var total = 0L
        @Volatile var done = 0L
        @Volatile var state = "running"
        @Volatile var error: String? = null
        val started = System.currentTimeMillis()
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("from", from).put("move", move)
            .put("total", total).put("done", done).put("state", state).put("error", error ?: JSONObject.NULL)
    }

    private val list = ArrayDeque<Transfer>()
    private var next = 1

    @Synchronized fun start(name: String, from: String, move: Boolean) = Transfer(next++, name, from, move).also {
        list.addLast(it)
        while (list.size > 20) list.removeFirst()
    }

    @Synchronized fun json() = jsonArray(list.map { it.json() })
}

/** A folder the file browser may show, and nothing outside it. */
class FileRoot(val id: String, val label: String, val dir: File)

/**
 * The dashboard's file browser: list, download (with ranges, so videos seek; folders as zip),
 * upload (streamed to disk), create folders, rename and delete, inside a few [roots]: app data
 * locations, the SSH home (with the web root) and, with storage access, the phone's volumes.
 *
 * Paths are relative to a root. Reading or writing through a symlink is allowed only when it
 * points inside the same root; renames and deletes act on the link itself, and zips skip links.
 */
class FileBrowser(private val ctx: Context, private val paths: Paths) {
    private val cfg = Config(ctx)
    private val apps = Apps(ctx, paths)
    private val alpine = Alpine(ctx, paths)

    fun roots(): List<FileRoot> = buildList {
        for ((key, group) in apps.installed().filter { it.storagePath != null }.groupBy { it.storageKey }) {
            val app = group.first()
            val dir = apps.libraryDir(app, cfg, alpine)!!
            add(FileRoot(key.lowercase(), "${app.storageLabel} (${group.joinToString { it.name }})", dir))
        }
        add(FileRoot("home", "Home (SSH, web hosting in www)", paths.home))
        if (Storage.hasAccess(ctx)) {
            @Suppress("DEPRECATION")
            val primary = Environment.getExternalStorageDirectory()
            if (primary.canRead()) add(FileRoot("phone", "Phone storage", primary))
        }
        Storage.volumes(ctx).filter { it.removable }.forEachIndexed { i, v ->
            val dir = if (v.appDirOnly || !Storage.hasAccess(ctx)) v.appDir else v.root
            if (dir != null && dir.canRead()) add(FileRoot("volume${i + 1}", v.label, dir))
        }
    }.filter { it.dir.isDirectory }

    fun handle(r: Request, seg: List<String>): Response {
        if (seg.isEmpty()) return Response.json(jsonArray(roots().map { root ->
            JSONObject().put("id", root.id).put("label", root.label)
                .put("free", root.dir.usableSpace).put("total", root.dir.totalSpace)
        }))
        if (seg == listOf("transfers") && r.method == "GET") return Response.json(Transfers.json())
        val root = roots().firstOrNull { it.id == seg[0] } ?: return Response.error(404, "no such location")
        if (seg.getOrNull(1) == "pack" && r.method == "GET") return pack(r, root)
        if (seg.getOrNull(1) == "fetch" && r.method == "POST") return fetch(r, root)
        val rel = r.query["path"].orEmpty()
        val op = r.method to seg.getOrNull(1)
        val onLink = op == ("POST" to "move") || op == ("DELETE" to null)
        val f = resolve(root, rel, follow = !onLink) ?: return Response.error(403, "outside ${root.label}")
        return when (op) {
            "GET" to null -> list(root, f)
            "GET" to "download" -> download(r, f)
            "PUT" to null -> upload(r, root, f)
            "POST" to "mkdir" -> {
                if (f.exists()) return Response.error(409, "${f.name} already exists")
                if (!f.mkdirs()) return Response.error(500, "could not create ${f.name}")
                Response.ok()
            }
            "POST" to "move" -> {
                val to = resolve(root, r.json().optString("to"), follow = false) ?: return Response.error(403, "outside ${root.label}")
                if (f == root.dir.canonicalFile || !Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return Response.error(404, "nothing to move")
                }
                if (to.exists()) return Response.error(409, "${to.name} already exists")
                to.parentFile?.mkdirs()
                if (!f.renameTo(to)) return Response.error(500, "could not move ${f.name}")
                Response.ok()
            }
            "DELETE" to null -> {
                if (f == root.dir.canonicalFile) return Response.error(403, "can't delete ${root.label}")
                deleteTree(f.toPath())
                Response.ok()
            }
            else -> Response.error(404, "no such endpoint")
        }
    }

    /**
     * [rel] inside [root], or null if it leads outside, through ".." or a symlink. Without
     * [follow], a symlink named by [rel] is returned as is (only its folder is resolved).
     */
    private fun resolve(root: FileRoot, rel: String, follow: Boolean): File? {
        val base = root.dir.canonicalFile
        fun inside(f: File) = f == base || f.path.startsWith(base.path + "/")
        val path = File(base, rel.trimStart('/')).toPath().normalize().toFile()
        if (path == base || path.parentFile == null) return base.takeIf { inside(path.canonicalFile) }
        val f = File(path.parentFile!!.canonicalFile, path.name)
        if (!inside(f)) return null
        return if (!follow) f else f.canonicalFile.takeIf(::inside)
    }

    private fun list(root: FileRoot, dir: File): Response {
        if (!dir.isDirectory) return Response.error(404, "no folder ${dir.name}")
        val entries = dir.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { f ->
                JSONObject().put("name", f.name).put("dir", f.isDirectory)
                    .put("size", if (f.isDirectory) 0 else f.length()).put("modified", f.lastModified())
            }
        return Response.json(
            JSONObject().put("root", root.id).put("label", root.label)
                .put("free", dir.usableSpace).put("entries", jsonArray(entries))
        )
    }

    /** Sends [f] with ranges (so videos seek); ?inline=1 shows it in the browser instead of saving it. */
    fun download(r: Request, f: File): Response {
        if (f.isDirectory) return zip(f)
        if (!f.isFile) return Response.error(404, "no file ${f.name}")
        val size = f.length()
        val inline = r.query["inline"] == "1"
        val headers = mutableMapOf(
            "Content-Disposition" to (if (inline) "inline" else "attachment") + "; filename*=UTF-8''" + encode(f.name),
            "Accept-Ranges" to "bytes",
            // Files are the user's, not the dashboard's: never let one run script on this origin.
            "Content-Security-Policy" to "sandbox; default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'",
        )
        var start = 0L
        var end = size - 1
        var status = 200
        r.headers["range"]?.let { range ->
            val m = RANGE.matchEntire(range.trim()) ?: return@let
            val (a, b) = m.destructured
            when {
                a.isEmpty() && b.isNotEmpty() -> start = maxOf(0, size - b.toLong())
                a.isNotEmpty() -> {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(end, b.toLong())
                }
                else -> return@let
            }
            if (start > end || start >= size) {
                return Response(416, ByteArray(0), headers = mapOf("Content-Range" to "bytes */$size"))
            }
            status = 206
            headers["Content-Range"] = "bytes $start-$end/$size"
        }
        val count = if (size == 0L) 0 else end - start + 1
        return Response(status, ByteArray(0), mimeType(f.name), headers, stream = { out ->
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(1 shl 16)
                var left = count
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }, length = count)
    }

    /** A folder as a zip, stored uncompressed: media doesn't shrink and phones are slow. */
    private fun zip(dir: File) = Response(
        200, ByteArray(0), "application/zip",
        mapOf("Content-Disposition" to "attachment; filename*=UTF-8''" + encode(dir.name + ".zip")),
        stream = { out -> writeZip(dir.toPath(), out) },
    )

    private fun writeZip(base: Path, out: OutputStream) {
        val zip = ZipOutputStream(out).apply { setLevel(Deflater.NO_COMPRESSION) }
        val buf = ByteArray(1 shl 16)
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != base) zip.putNextEntry(ZipEntry(base.relativize(dir).toString() + "/")).also { zip.closeEntry() }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                zip.putNextEntry(ZipEntry(base.relativize(file).toString()).apply { time = attrs.lastModifiedTime().toMillis() })
                FileInputStream(file.toFile()).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        zip.finish()
    }

    /** Streams the body into [f] through a temporary file, so a broken upload leaves nothing. */
    private fun upload(r: Request, root: FileRoot, f: File): Response {
        if (f == root.dir.canonicalFile || f.isDirectory) return Response.error(409, "${f.name} is a folder")
        if (f.exists() && r.query["overwrite"] != "1") return Response.error(409, "${f.name} already exists")
        val parent = f.parentFile!!.also { it.mkdirs() }
        if (r.length > parent.usableSpace) return Response.error(507, "not enough space for ${f.name}")
        val part = File(parent, ".${f.name}.upload")
        val complete = try {
            part.outputStream().use(r::bodyTo)
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        if (!complete) {
            part.delete()
            return Response.error(400, "upload of ${f.name} was cut off")
        }
        if (!part.renameTo(f)) {
            part.delete()
            return Response.error(500, "could not save ${f.name}")
        }
        return Response.json(JSONObject().put("ok", true), 201)
    }

    // --- between phones in a cluster -------------------------------------------------------

    /** A file or folder as an [AppArchive] stream, for the phone copying it (?size=1 measures it). */
    private fun pack(r: Request, root: FileRoot): Response {
        if (r.peer == null) return Response.error(403, "Only other phones in the cluster can do this")
        val f = resolve(root, r.query["path"].orEmpty(), follow = false) ?: return Response.error(403, "outside ${root.label}")
        if (f == root.dir.canonicalFile || !Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return Response.error(404, "nothing to copy")
        val parts = listOf(AppArchive.Root("f", f))
        if (r.query["size"] == "1") return Response.json(JSONObject().put("bytes", AppArchive.size(parts)).put("dir", f.isDirectory))
        return Response(200, ByteArray(0), "application/octet-stream", stream = { AppArchive.write(parts, it) })
    }

    /**
     * Copies (or with "move", moves) a file or folder from another phone into [root] at ?path=,
     * in the background; see [Transfers]. The data goes straight between the phones.
     */
    private fun fetch(r: Request, root: FileRoot): Response {
        val body = r.json()
        val cluster = Cluster.instance ?: return Response.error(409, "The cluster isn't running")
        val src = cluster.state.peer(body.optString("from")) ?: return Response.error(404, "That phone isn't in the cluster")
        val srcRoot = body.optString("root").takeIf { Regex("[a-z0-9]{1,40}").matches(it) } ?: return Response.error(400, "invalid location")
        val srcPath = body.optString("path").trim('/').takeIf { it.isNotEmpty() } ?: return Response.error(400, "pick something to copy")
        val dest = resolve(root, r.query["path"].orEmpty(), follow = false) ?: return Response.error(403, "outside ${root.label}")
        if (dest == root.dir.canonicalFile) return Response.error(400, "pick a name for the copy")
        if (Files.exists(dest.toPath(), LinkOption.NOFOLLOW_LINKS)) return Response.error(409, "${dest.name} already exists here")
        val move = body.optBoolean("move")
        val t = Transfers.start(dest.name, src.name, move)
        Thread({
            val tmp = File(dest.parentFile, ".${dest.name}.transfer")
            try {
                val q = "?path=${URLEncoder.encode(srcPath, "UTF-8")}"
                val (status, size) = cluster.peerJson(src, "GET", "/api/files/$srcRoot/pack$q&size=1")
                if (status != 200) throw IOException(size.optString("error", "${src.name} answered $status"))
                t.total = size.optLong("bytes")
                dest.parentFile!!.mkdirs()
                if (t.total > dest.parentFile!!.usableSpace) throw IOException("not enough space here for ${dest.name}")
                cluster.peerStream(src, "/api/files/$srcRoot/pack$q").use { res ->
                    if (res.status != 200) throw IOException("${src.name} answered ${res.status}")
                    AppArchive.read(listOf(AppArchive.Root("f", tmp)), res.body) { t.done = it }
                }
                if (Files.exists(dest.toPath(), LinkOption.NOFOLLOW_LINKS) || !tmp.renameTo(dest)) throw IOException("could not save ${dest.name}")
                if (move) {
                    val (st, o) = cluster.peerJson(src, "DELETE", "/api/files/$srcRoot$q")
                    if (st != 200) throw IOException("copied, but couldn't delete it on ${src.name}: ${o.optString("error")}")
                }
                t.state = "done"
            } catch (e: Exception) {
                if (Files.exists(tmp.toPath(), LinkOption.NOFOLLOW_LINKS)) try { deleteTree(tmp.toPath()) } catch (_: IOException) {}
                t.error = e.message ?: e.javaClass.simpleName
                t.state = "failed"
            }
        }, "file-transfer").apply { isDaemon = true; start() }
        return Response.json(t.json())
    }

    private fun deleteTree(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        // walkFileTree doesn't follow symlinks, so a link to elsewhere is removed, not emptied.
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun mimeType(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private val RANGE = Regex("""bytes=(\d*)-(\d*)""")
    }
}
