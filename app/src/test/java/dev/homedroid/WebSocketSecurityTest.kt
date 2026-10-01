package dev.homedroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WebSocketSecurityTest {
    private fun frame(fin: Boolean, op: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 128 else 0) or op)
        if (data.size < 126) out.write(128 or data.size) else {
            out.write(128 or 127)
            repeat(8) { out.write((data.size.toLong() shr (56 - 8 * it)).toInt() and 255) }
        }
        out.write(ByteArray(4)); out.write(data)
        return out.toByteArray()
    }
    @Test fun maskedFragmentedMessagesAreAccepted() {
        val socket = WebSocket(ByteArrayInputStream(frame(false, 2, byteArrayOf(1)) + frame(true, 0, byteArrayOf(2))), ByteArrayOutputStream())
        assertArrayEquals(byteArrayOf(1, 2), socket.receive()!!.data)
    }
    @Test fun fragmentedMessagesCannotExceedCombinedLimit() {
        reject(frame(false, 2, ByteArray(600_000)) + frame(true, 0, ByteArray(600_000)))
    }
    @Test fun malformedControlAndClientFramesAreRejected() {
        reject(byteArrayOf(0x82.toByte(), 1, 0)) // unmasked client
        reject(frame(false, 9, byteArrayOf()))
        reject(frame(true, 9, ByteArray(126)))
        reject(frame(true, 0, byteArrayOf()))
        reject(frame(true, 3, byteArrayOf()))
    }
    private fun reject(bytes: ByteArray) {
        try { WebSocket(ByteArrayInputStream(bytes), ByteArrayOutputStream()).receive(); fail("Malformed frame accepted") }
        catch (_: IOException) {}
    }
}
