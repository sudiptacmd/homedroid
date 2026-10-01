package dev.homedroid

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AppArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(name: String): File = tmp.newFolder(name).apply {
        File(this, "config/system.xml").apply { parentFile.mkdirs(); writeText("<config/>") }
        File(this, "db/data.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        File(this, "empty").mkdirs()
        File(this, ".secret").writeText("hunter2")
    }

    @Test fun roundTripKeepsFilesFoldersAndSkipsTheLibraryUnlessAsked() {
        val data = tree("src-data")
        val appdata = tree("src-appdata")
        val library = File(appdata, "storage").apply { mkdirs(); File(this, "photo.jpg").writeText("jpeg") }
        val out = ByteArrayOutputStream()
        val roots = listOf(AppArchive.Root("data0", data, library), AppArchive.Root("appdata", appdata, library))
        assertEquals(2 * (9 + 200_000 + 7L), AppArchive.size(roots))
        AppArchive.write(roots, out)

        val dData = tmp.newFolder("dst-data"); val dApp = tmp.newFolder("dst-appdata")
        val written = AppArchive.read(listOf(AppArchive.Root("data0", dData), AppArchive.Root("appdata", dApp)), ByteArrayInputStream(out.toByteArray()))
        assertEquals(AppArchive.size(roots), written)
        for ((a, b) in listOf(data to dData, appdata to dApp)) {
            assertEquals("<config/>", File(b, "config/system.xml").readText())
            assertArrayEquals(File(a, "db/data.bin").readBytes(), File(b, "db/data.bin").readBytes())
            assertTrue(File(b, "empty").isDirectory)
            assertEquals("hunter2", File(b, ".secret").readText())
        }
        assertFalse("library skipped", File(dApp, "storage").exists())
    }

    @Test fun libraryIsItsOwnPart() {
        val lib = tmp.newFolder("lib").apply { File(this, "Movies/a.mkv").apply { parentFile.mkdirs(); writeText("film") } }
        val out = ByteArrayOutputStream()
        AppArchive.write(listOf(AppArchive.Root("library", lib)), out)
        val dst = tmp.newFolder("dst").apply { File(this, "keep.txt").writeText("mine") }
        AppArchive.read(listOf(AppArchive.Root("library", dst)), ByteArrayInputStream(out.toByteArray()))
        assertEquals("film", File(dst, "Movies/a.mkv").readText())
        assertEquals("a library is merged, not replaced", "mine", File(dst, "keep.txt").readText())
    }

    private fun hostile(vararg entries: Pair<Char, String>): ByteArray = ByteArrayOutputStream().also { b ->
        DataOutputStream(b).apply {
            write("HDMOVE1\n".toByteArray())
            for ((type, name) in entries) {
                writeByte(type.code); writeUTF(name)
                when (type) {
                    'F' -> { writeInt(-1); writeLong(0); writeLong(1); writeByte('x'.code) }
                    'D' -> { writeInt(-1); writeLong(0) }
                    'L' -> writeUTF("/")
                }
            }
            writeByte('E'.code)
        }
    }.toByteArray()

    @Test fun refusesPathsOutsideTheirRoot() {
        val dst = tmp.newFolder("dst")
        val roots = listOf(AppArchive.Root("appdata", dst))
        for (bad in listOf("appdata/../escape", "appdata//x", "appdata/./x", "other/x", "/appdata/x", "appdata/a\u0000b")) {
            assertThrows(bad, IOException::class.java) { AppArchive.read(roots, ByteArrayInputStream(hostile('F' to bad))) }
        }
        assertFalse(File(dst.parentFile, "escape").exists())
        assertThrows(IOException::class.java) { AppArchive.read(roots, ByteArrayInputStream("nope".toByteArray())) }
    }

    @Test fun refusesToWriteThroughALinkItCreated() {
        val dst = tmp.newFolder("dst")
        val probe = File(dst, "probe")
        val links = try { Files.createSymbolicLink(probe.toPath(), dst.toPath()); probe.delete(); true } catch (_: Exception) { false }
        assumeTrue("symlinks need privileges on this machine", links)
        val roots = listOf(AppArchive.Root("appdata", dst))
        assertThrows(IOException::class.java) {
            AppArchive.read(roots, ByteArrayInputStream(hostile('L' to "appdata/link", 'F' to "appdata/link/etc/passwd")))
        }
    }
}
