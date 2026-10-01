package dev.homedroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission

/**
 * An app's data as one stream, for moving it to another phone in the cluster: its settings and
 * database (folders in Alpine and in appdata/) and, if asked, its library.
 *
 * Each part is a [Root]: a name in the stream and a folder on this phone. Symlinks are kept as
 * links (proot stores hard links as symlinks with absolute targets, which are the same on every
 * phone), never followed. Reading refuses any entry outside the roots it was given.
 */
object AppArchive {
    class Root(val name: String, val dir: File, val skip: File? = null)

    private val MAGIC = "HDMOVE1\n".toByteArray()

    /** Bytes of regular files under [roots]. */
    fun size(roots: List<Root>): Long {
        var total = 0L
        walk(roots) { _, _, attrs -> if (attrs.isRegularFile) total += attrs.size() }
        return total
    }

    fun write(roots: List<Root>, out: OutputStream, progress: (Long) -> Unit = {}) {
        val data = DataOutputStream(out.buffered(1 shl 16))
        data.write(MAGIC)
        val buf = ByteArray(1 shl 16)
        var sent = 0L
        walk(roots) { name, path, attrs ->
            when {
                attrs.isSymbolicLink -> { data.writeByte('L'.code); data.writeUTF(name); data.writeUTF(Files.readSymbolicLink(path).toString()) }
                attrs.isDirectory -> { data.writeByte('D'.code); data.writeUTF(name); data.writeInt(mode(path)); data.writeLong(attrs.lastModifiedTime().toMillis()) }
                attrs.isRegularFile -> {
                    data.writeByte('F'.code); data.writeUTF(name); data.writeInt(mode(path)); data.writeLong(attrs.lastModifiedTime().toMillis())
                    val size = attrs.size()
                    data.writeLong(size)
                    Files.newInputStream(path).use { input ->
                        var left = size
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            // A file that shrank while copying is padded; the app is stopped, so this is rare.
                            if (n < 0) { buf.fill(0); while (left > 0) { val k = minOf(buf.size.toLong(), left).toInt(); data.write(buf, 0, k); left -= k }; break }
                            data.write(buf, 0, n)
                            left -= n
                            sent += n
                        }
                    }
                    progress(sent)
                }
            }
        }
        data.writeByte('E'.code)
        data.flush()
    }

    /**
     * Unpacks a stream from [write] into [roots], matched by name. Entries for other names, or
     * that would land outside their root (through "..", or a link in the way), fail the whole read.
     * Returns the bytes of file content written.
     */
    fun read(roots: List<Root>, input: InputStream, progress: (Long) -> Unit = {}): Long {
        val data = DataInputStream(input.buffered(1 shl 16))
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("not an app archive")
        val byName = roots.associateBy { it.name }
        val dirTimes = mutableListOf<Pair<File, Long>>()
        val buf = ByteArray(1 shl 16)
        var written = 0L
        while (true) {
            val type = data.readUnsignedByte().toChar()
            if (type == 'E') break
            val name = data.readUTF()
            val target = resolve(byName, name)
            when (type) {
                'D' -> {
                    val mode = data.readInt(); val time = data.readLong()
                    if (!target.isDirectory && !target.mkdirs()) throw IOException("could not create ${target.name}")
                    setMode(target, mode)
                    dirTimes += target to time
                }
                'F' -> {
                    val mode = data.readInt(); val time = data.readLong(); val size = data.readLong()
                    if (size < 0) throw IOException("bad size")
                    target.parentFile!!.mkdirs()
                    Files.deleteIfExists(target.toPath())
                    target.outputStream().use { out ->
                        var left = size
                        while (left > 0) {
                            val n = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("archive cut off")
                            out.write(buf, 0, n)
                            left -= n
                            written += n
                        }
                    }
                    setMode(target, mode)
                    target.setLastModified(time)
                    progress(written)
                }
                'L' -> {
                    val link = data.readUTF()
                    target.parentFile!!.mkdirs()
                    Files.deleteIfExists(target.toPath())
                    Files.createSymbolicLink(target.toPath(), File(link).toPath())
                }
                else -> throw IOException("bad archive entry")
            }
        }
        // Folders last: writing their contents changed their times.
        for ((dir, time) in dirTimes.asReversed()) dir.setLastModified(time)
        return written
    }

    /** Calls [visit] with each entry's stream name, root first, never following links. */
    private fun walk(roots: List<Root>, visit: (String, Path, BasicFileAttributes) -> Unit) {
        for (root in roots) {
            val base = root.dir.toPath()
            // A root may also be a single file (copying one file between phones).
            if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) continue
            val skip = root.skip?.canonicalFile?.toPath()
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (skip != null && dir != base && dir.toFile().canonicalFile.toPath() == skip) return FileVisitResult.SKIP_SUBTREE
                    visit(entry(root, base, dir), dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    visit(entry(root, base, file), file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
        }
    }

    private fun entry(root: Root, base: Path, p: Path): String {
        val rel = base.relativize(p).joinToString("/")
        return if (rel.isEmpty()) root.name else "${root.name}/$rel"
    }

    /** Where entry [name] goes; only inside its root, and never through a symlink. */
    private fun resolve(roots: Map<String, Root>, name: String): File {
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) throw IOException("bad path in archive")
        val root = roots[parts[0]] ?: throw IOException("unexpected data in archive: ${parts[0]}")
        var f = root.dir
        for (part in parts.drop(1)) {
            if (Files.isSymbolicLink(f.toPath())) throw IOException("archive writes through a link")
            f = File(f, part)
        }
        return f
    }

    private fun mode(p: Path): Int = try {
        Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS).sumOf { 1 shl (8 - it.ordinal) }
    } catch (_: Exception) { -1 }

    private fun setMode(f: File, mode: Int) {
        if (mode < 0) return
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermission.values().filter { mode and (1 shl (8 - it.ordinal)) != 0 }.toSet())
        } catch (_: Exception) {}
    }
}
