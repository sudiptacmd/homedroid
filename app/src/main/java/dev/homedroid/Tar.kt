package dev.homedroid

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Extracts a tar stream into a root directory the way a container runtime would, within
 * what an Android app may do:
 *  - ownership is not restored (apps can't chown); proot fakes it at run time,
 *  - hard links become copies (Android forbids hard links in app storage),
 *  - paths resolve inside [root], so absolute symlinks in the archive (/lib -> /usr/lib)
 *    point into the rootfs instead of the phone's own filesystem,
 *  - with [oci], layer whiteouts (.wh.name, .wh..wh..opq) delete what lower layers added.
 */
class Tar(private val root: File, private val oci: Boolean = false) {
    private val rootPath = root.canonicalPath
    private val added = HashSet<String>()

    fun extract(input: InputStream) {
        root.mkdirs()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            if (size < 0) throw IOException("invalid tar size")
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> { longName = String(readData(input, size)).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(readData(input, size)).trimEnd('\u0000'); continue }
                'x' -> { pax = parsePax(readData(input, size)); continue }
                'g' -> { skip(input, size); continue }
            }
            val prefix = string(header, 345, 155)
            val shortName = string(header, 0, 100)
            val name = pax["path"] ?: longName ?: if (prefix.isEmpty()) shortName else "$prefix/$shortName"
            val link = pax["linkpath"] ?: longLink ?: string(header, 157, 100)
            val mode = octal(header, 100, 8).toInt() and 0x1ff
            longName = null
            longLink = null
            pax = emptyMap()

            val rel = normalize(name)
            if (rel == null) {
                skip(input, size)
                continue
            }
            val base = rel.substringAfterLast('/')
            if (oci && base.startsWith(WHITEOUT)) {
                skip(input, size)
                val dir = rel.substringBeforeLast('/', "")
                if (base == OPAQUE) clearDir(dir) else delete(join(dir, base.removePrefix(WHITEOUT)))
                continue
            }
            val target = resolve(rel, followLast = false)
            added += rel
            when (type) {
                '5' -> {
                    if (!isDir(target)) {
                        deleteHost(target)
                        target.mkdirs()
                    }
                    chmod(target, mode or 0x1c0) // keep it writable for later layers
                }
                '2' -> {
                    deleteHost(target)
                    target.parentFile?.mkdirs()
                    try {
                        Os.symlink(link, target.path)
                    } catch (e: ErrnoException) {
                        throw IOException("symlink $rel: ${e.message}")
                    }
                }
                '1' -> {
                    skip(input, size)
                    val source = normalize(link)?.let { resolve(it, followLast = true) }
                    deleteHost(target)
                    target.parentFile?.mkdirs()
                    if (source != null && source.isFile) source.copyTo(target, overwrite = true)
                    chmod(target, mode)
                }
                '0', '\u0000', '7' -> {
                    deleteHost(target)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> copy(input, out, size) }
                    skipPadding(input, size)
                    chmod(target, mode or 0x180)
                }
                else -> skip(input, size) // devices and fifos can't be created by apps
            }
        }
    }

    /** Resolves an archive path to a host file, following symlinks as if [root] were "/". */
    private fun resolve(rel: String, followLast: Boolean): File {
        var current = rootPath
        val pending = ArrayDeque(rel.split('/').filter { it.isNotEmpty() })
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == "..") {
                current = if (current == rootPath) rootPath else current.substringBeforeLast('/')
                continue
            }
            val next = "$current/$part"
            val last = pending.isEmpty()
            val stat = try {
                Os.lstat(next)
            } catch (_: ErrnoException) {
                null
            }
            if (stat != null && OsConstants.S_ISLNK(stat.st_mode) && (!last || followLast)) {
                if (++hops > 40) throw IOException("too many symlinks at $rel")
                val dest = Os.readlink(next)
                if (dest.startsWith("/")) current = rootPath
                dest.split('/').filter { it.isNotEmpty() && it != "." }.asReversed().forEach(pending::addFirst)
            } else {
                current = next
            }
        }
        return File(current)
    }

    private fun clearDir(dir: String) {
        val host = resolve(dir, followLast = true)
        host.list()?.forEach { child ->
            val rel = join(dir, child)
            if (rel !in added) deleteHost(File(host, child))
        }
    }

    private fun delete(rel: String) = deleteHost(resolve(rel, followLast = false))

    /** Deletes a file or tree without following symlinks. */
    private fun deleteHost(f: File) {
        val st = try {
            Os.lstat(f.path)
        } catch (_: ErrnoException) {
            return
        }
        if (OsConstants.S_ISDIR(st.st_mode)) {
            chmod(f, 0x1ff)
            f.list()?.forEach { deleteHost(File(f, it)) }
        }
        f.delete()
    }

    private fun isDir(f: File) = try {
        OsConstants.S_ISDIR(Os.lstat(f.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    private fun chmod(f: File, mode: Int) {
        try {
            Os.chmod(f.path, mode)
        } catch (_: ErrnoException) {
        }
    }

    companion object {
        private const val BLOCK = 512
        private const val WHITEOUT = ".wh."
        private const val OPAQUE = ".wh..wh..opq"

        /** Archive path without leading "./" or "/", or null if it would climb out of the root. */
        private fun normalize(path: String): String? {
            val parts = ArrayList<String>()
            for (p in path.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                    else -> parts += p
                }
            }
            return parts.joinToString("/")
        }

        private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

        private fun string(b: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && b[end] != 0.toByte()) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }

        private fun octal(b: ByteArray, off: Int, len: Int): Long {
            // GNU base-256 encoding for large values.
            if (b[off].toInt() and 0x80 != 0) {
                var v = 0L
                for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
                return v
            }
            val s = string(b, off, len).trim()
            return if (s.isEmpty()) 0 else s.toLong(8)
        }

        private fun parsePax(data: ByteArray): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            while (i < data.size) {
                val sp = (i until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
                val len = String(data, i, sp - i).toIntOrNull() ?: break
                if (len <= sp - i + 2 || len > data.size - i) throw IOException("invalid pax record")
                val record = String(data, sp + 1, len - (sp - i) - 2, Charsets.UTF_8)
                val eq = record.indexOf('=')
                if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
                i += len
            }
            return out
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) {
                    if (n == 0) return false
                    throw EOFException("truncated tar")
                }
                n += r
            }
            return true
        }

        private fun readData(input: InputStream, size: Long): ByteArray {
            if (size !in 0..(1L shl 20)) throw IOException("tar metadata too large")
            val data = ByteArray(size.toInt())
            if (!readFully(input, data) && size > 0) throw EOFException("truncated tar")
            skipPadding(input, size)
            return data
        }

        private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw EOFException("truncated tar")
                out.write(buf, 0, r)
                left -= r
            }
        }

        private fun skip(input: InputStream, size: Long) {
            copy(input, NullOutput, size)
            skipPadding(input, size)
        }

        private fun skipPadding(input: InputStream, size: Long) {
            val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
            if (pad > 0) readFully(input, ByteArray(pad))
        }

        private object NullOutput : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
    }
}
