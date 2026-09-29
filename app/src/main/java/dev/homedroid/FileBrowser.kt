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
        val root = roots().firstOrNull { it.id == seg[0] } ?: return Response.error(404, "no such location")
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

    private fun download(r: Request, f: File): Response {
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
