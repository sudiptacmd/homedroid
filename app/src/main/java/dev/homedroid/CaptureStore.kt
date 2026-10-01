package dev.homedroid

import java.io.File

/** Only this library's completed captures may be evicted. Active .partial files are protected. */
class CaptureStore(private val dir: File, private val quota: () -> Long) {
    companion object {
        private val lock = Any()
        private val capture = Regex("[0-9]+-[0-9a-f-]+\\.(jpg|mp4|wav)")
        private const val FREE_RESERVE = 64L * 1024 * 1024
    }
    fun used(): Long = synchronized(lock) { files().sumOf { it.length() } }
    fun activeBytes(): Long = synchronized(lock) { files().filter { it.name.endsWith(".partial") }.sumOf { it.length() } }
    private fun files() = dir.listFiles().orEmpty().filter { it.isFile && (capture.matches(it.name) || capture.matches(it.name.removeSuffix(".partial"))) }
    /** Make room before writing; never deletes an active recording or a file outside the library. */
    fun reserve(bytes: Long): Boolean = synchronized(lock) {
        val files = files()
        var used = files.sumOf { it.length() }
        for (file in files.filter { capture.matches(it.name) }.sortedBy { it.name }) {
            if (used + bytes <= quota() && dir.usableSpace >= FREE_RESERVE + bytes) break
            val size = file.length()
            if (file.delete()) used -= size
        }
        used + bytes <= quota() && dir.usableSpace >= FREE_RESERVE + bytes
    }
}
