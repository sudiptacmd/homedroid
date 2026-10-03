package android.system

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class ErrnoException(message: String) : Exception(message)
class Stat(val st_mode: Int)
object OsConstants {
    const val SIGTERM = 15
    const val SIGKILL = 9
    fun S_ISLNK(mode: Int) = mode and 0xf000 == 0xa000
    fun S_ISDIR(mode: Int) = mode and 0xf000 == 0x4000
}
object Os {
    private fun <T> syscall(block: () -> T): T = try { block() } catch (e: Exception) { throw ErrnoException(e.toString()) }
    fun lstat(path: String) = syscall { Stat((Files.getAttribute(Path.of(path), "unix:mode", NOFOLLOW_LINKS) as Number).toInt()) }
    fun symlink(target: String, path: String) = syscall { Files.createSymbolicLink(Path.of(path), Path.of(target)); Unit }
    fun readlink(path: String) = syscall { Files.readSymbolicLink(Path.of(path)).toString() }
    fun chmod(path: String, mode: Int) = syscall { Files.setAttribute(Path.of(path), "unix:mode", mode); Unit }
    fun kill(pid: Int, signal: Int) { throw ErrnoException("process group signals require Android integration tests") }
}
