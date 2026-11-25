package dev.homedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class TerminalPortTest {
    private fun busy(port: Int) = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }

    @Test fun picksAFixedLowPortNotAnEphemeralOne() {
        val p = Terminal.freePort(18023, avoid = emptySet())
        assertTrue("$p should be from 18023 up", p in 18023 until 18223)
    }

    @Test fun skipsPortsInUseAndPortsToAvoid() {
        busy(18300).use {
            val p = Terminal.freePort(18300, avoid = setOf(18301))
            assertNotEquals(18300, p)
            assertNotEquals(18301, p)
            assertEquals(18302, p)
        }
    }
}
