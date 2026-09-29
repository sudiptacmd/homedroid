package dev.homedroid

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The dashboard's web terminal: a WebSocket from the browser, relayed to sshd's terminal
 * port on loopback (native/sshd/terminal.go), which runs the shell on a PTY.
 *
 * Browser to phone: binary messages are keystrokes, text messages are JSON {cols, rows}
 * resizes. Phone to browser: binary messages with terminal output.
 */
object Terminal {
    const val PORT = 8023

    /** Proves to sshd that a terminal connection comes from this app, not another one on the phone. */
    val token: String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** Upgrades [r] to a WebSocket running a shell, or explains why not. */
    fun open(r: Request, sshEnabled: Boolean): Response {
        val key = r.headers["sec-websocket-key"]
        if (r.headers["upgrade"]?.lowercase() != "websocket" || key == null) return Response.error(400, "expected a WebSocket")
        // Cookies ride along on cross-site WebSockets too, so insist on this origin.
        val origin = r.headers["origin"]?.substringAfter("://")
        if (origin != null && origin != r.headers["host"]) return Response.error(403, "wrong origin")
        if (!sshEnabled) return Response.error(409, "turn on SSH & SFTP to use the terminal")
        val cols = r.query["cols"]?.toIntOrNull() ?: 80
        val rows = r.query["rows"]?.toIntOrNull() ?: 24
        val alpine = r.query["alpine"] == "1"
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
        return Response(
            101, ByteArray(0), "",
            mapOf("Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Accept" to accept),
            upgrade = { input, output -> relay(input, output, cols, rows, alpine, r.remote) },
        )
    }

    private fun relay(input: InputStream, output: OutputStream, cols: Int, rows: Int, alpine: Boolean, ip: String) {
        val ws = WebSocket(input, output)
        val shell = try {
            Socket().apply { connect(InetSocketAddress("127.0.0.1", PORT), 3000) }
        } catch (e: IOException) {
            ws.send("SSH & SFTP isn't running, so the terminal can't start.\r\n".toByteArray())
            ws.close()
            return
        }
        shell.use {
            val toShell = DataOutputStream(shell.getOutputStream().buffered())
            val hello = JSONObject().put("token", token).put("cols", cols).put("rows", rows).put("alpine", alpine).put("ip", ip)
            toShell.write((hello.toString() + "\n").toByteArray())
            toShell.flush()
            // Shell output to the browser, on its own thread.
            val pump = Thread({
                val buf = ByteArray(16 * 1024)
                val fromShell = shell.getInputStream()
                try {
                    while (true) {
                        val n = fromShell.read(buf)
                        if (n < 0) break
                        ws.send(buf.copyOf(n))
                    }
                } catch (_: IOException) {
                }
                ws.close()
            }, "terminal-out").apply { isDaemon = true; start() }
            // Browser input to the shell, here.
            try {
                while (true) {
                    val msg = ws.receive() ?: break
                    if (msg.text) {
                        val o = JSONObject(String(msg.data))
                        toShell.writeByte(1)
                        toShell.writeShort(4)
                        toShell.writeShort(o.optInt("cols", cols).coerceIn(1, 1000))
                        toShell.writeShort(o.optInt("rows", rows).coerceIn(1, 1000))
                    } else {
                        var off = 0
                        while (off < msg.data.size) {
                            val n = minOf(msg.data.size - off, 0xffff)
                            toShell.writeByte(0)
                            toShell.writeShort(n)
                            toShell.write(msg.data, off, n)
                            off += n
                        }
                    }
                    toShell.flush()
                }
            } catch (_: Exception) {
            }
            shell.close()
            pump.join(2000)
        }
    }
}

/** Just enough of RFC 6455 for the terminal: unfragmented-or-continued data frames, ping, close. */
class WebSocket(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = output
    private var closed = false

    class Message(val text: Boolean, val data: ByteArray)

    /** The next data message, or null once the browser closed the socket. */
    fun receive(): Message? {
        var text = false
        var payload = ByteArray(0)
        while (true) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val op = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            if (len > MAX_MESSAGE) throw IOException("message too large")
            val mask = ByteArray(4).also { if (b1 and 0x80 != 0) input.readFully(it) }
            val data = ByteArray(len.toInt()).also(input::readFully)
            if (b1 and 0x80 != 0) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                0x8 -> { close(); return null }
                0x9 -> { frame(0xA, data); continue }
                0xA -> continue
                0x1, 0x2 -> { text = op == 0x1; payload = data }
                0x0 -> payload += data
            }
            if (fin) return Message(text, payload)
        }
    }

    fun send(data: ByteArray) = frame(0x2, data)

    fun close() {
        try {
            frame(0x8, ByteArray(0))
        } catch (_: IOException) {
        }
        closed = true
    }

    @Synchronized
    private fun frame(op: Int, data: ByteArray) {
        if (closed) return
        val head = when {
            data.size < 126 -> byteArrayOf((0x80 or op).toByte(), data.size.toByte())
            data.size < 65536 -> byteArrayOf((0x80 or op).toByte(), 126, (data.size shr 8).toByte(), data.size.toByte())
            else -> byteArrayOf((0x80 or op).toByte(), 127) + ByteArray(8) { i -> (data.size.toLong() shr (56 - 8 * i)).toByte() }
        }
        output.write(head)
        output.write(data)
        output.flush()
    }

    companion object {
        private const val MAX_MESSAGE = 1L shl 20
    }
}
