package dev.homedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineMeminfoTest {
    private fun parse(text: String) = text.lines().filter { it.isNotBlank() }.associate { line ->
        val (key, value) = line.split(':', limit = 2)
        assertTrue("unit in $line", value.trim().endsWith(" kB"))
        key to value.trim().removeSuffix(" kB").toLong()
    }

    @Test fun reportsTotalAndAvailableInKibibytes() {
        val m = parse(Alpine.meminfoText(total = 4L shl 30, available = 1536L shl 20))
        assertEquals(4L shl 20, m["MemTotal"])
        assertEquals(1536L shl 10, m["MemAvailable"])
        assertEquals(m["MemAvailable"], m["MemFree"])
        assertEquals(0L, m["SwapTotal"])
    }

    @Test fun availableNeverExceedsTotalOrGoesNegative() {
        assertEquals(1024L, parse(Alpine.meminfoText(1L shl 20, 5L shl 20))["MemAvailable"])
        assertEquals(0L, parse(Alpine.meminfoText(1L shl 20, -1))["MemAvailable"])
    }

    @Test fun startsWithMemTotalLikeTheKernel() {
        assertTrue(Alpine.meminfoText(1L shl 30, 1L shl 29).startsWith("MemTotal:"))
    }
}
