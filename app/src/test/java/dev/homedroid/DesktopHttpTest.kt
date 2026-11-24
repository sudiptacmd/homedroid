package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Wire-level checks exercise the real server without Android classes or an emulator. */
class DesktopHttpTest {
    @Test fun eventsUseChunkedFramingAndAlwaysReleaseTheirSlot() {
        val closed = CountDownLatch(1)
        val payload = DesktopCore.ndjson(JSONObject().put("type", "hello").put("computerId", "c-1"))
        val http = Http(0) {
            Response(200, byteArrayOf(), "application/x-ndjson", chunked = true,
                stream = { it.write(payload) }, onClose = { closed.countDown() })
        }
        http.start()
        try {
            Socket("127.0.0.1", http.localPort).use { socket ->
                socket.soTimeout = 4000
                socket.getOutputStream().write("GET /desktop/v1/events HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                val headers = response.substringBefore("\r\n\r\n")
                assertTrue(headers.contains("Transfer-Encoding: chunked"))
                assertTrue(headers.contains("Content-Type: application/x-ndjson"))
                assertFalse(headers.contains("Content-Length:"))
                assertEquals(payload.size.toString(16) + "\r\n" + payload.toString(Charsets.UTF_8) + "\r\n0\r\n\r\n", response.substringAfter("\r\n\r\n"))
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
        } finally { http.stop() }
    }
    @Test fun revocationClosesSocketEvenWhenStreamIsIdle() {
        val valid = AtomicBoolean(true)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val http = Http(0) {
            Response(200, byteArrayOf(), "application/x-ndjson", chunked = true, streamAuthorized = valid::get,
                stream = { release.await(5, TimeUnit.SECONDS) }, onClose = { closed.countDown() })
        }
        http.start()
        try {
            Socket("127.0.0.1", http.localPort).use { socket ->
                socket.soTimeout = 4000
                socket.getOutputStream().write("GET /desktop/v1/events HTTP/1.1\r\nHost: phone\r\n\r\n".toByteArray())
                val reader = socket.getInputStream().bufferedReader()
                assertTrue(reader.readLine().contains("200"))
                while (reader.readLine().isNotEmpty()) {}
                valid.set(false)
                assertEquals(-1, reader.read())
                release.countDown()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
        } finally { release.countDown(); http.stop() }
    }
}
