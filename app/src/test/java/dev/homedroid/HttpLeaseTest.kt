package dev.homedroid

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class HttpLeaseTest {
    @Test fun idleUpgradeClosesWhenAuthorizationIsRevoked() {
        val valid = AtomicBoolean(true)
        val port = ServerSocket(0).use { it.localPort }
        val http = Http(port) {
            Response(101, byteArrayOf(), headers = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
                upgrade = { input, _ -> while (input.read() >= 0) {} }, upgradeAuthorized = valid::get)
        }
        http.start()
        try {
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 4000
                client.getOutputStream().write("GET /api/terminal HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
                val input = client.getInputStream().bufferedReader()
                assertTrue(input.readLine().contains("101"))
                while (input.readLine().isNotEmpty()) {}
                valid.set(false)
                assertEquals(-1, input.read())
            }
        } finally { http.stop() }
    }
}
