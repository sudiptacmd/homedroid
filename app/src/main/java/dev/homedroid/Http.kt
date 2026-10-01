package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** Request body, read on demand so uploads can stream it with [bodyTo] instead. */
    private val input: InputStream,
    val length: Long,
    val remote: String,
    /** On the cluster port: the SHA-256 fingerprint of the certificate the other phone presented. */
    val peer: String? = null,
) {
    private var consumed = false

    val body: ByteArray by lazy {
        if (length !in 0..MAX_BODY.toLong()) throw IOException("body too large")
        consume()
        ByteArray(length.toInt()).also { b ->
            var read = 0
            while (read < b.size) {
                val n = input.read(b, read, b.size - read)
                if (n < 0) throw IOException("truncated body")
                read += n
            }
        }
    }

    /** Copies the body to [out] as it arrives; returns false if the client hung up early. */
    fun bodyTo(out: OutputStream): Boolean {
        if (length < 0) throw IOException("invalid body length")
        consume()
        val buf = ByteArray(1 shl 16)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return false
            out.write(buf, 0, n)
            left -= n
        }
        return true
    }

    private fun consume() {
        check(!consumed) { "body already read" }
        consumed = true
    }

    fun json() = JSONObject(if (body.isEmpty()) "{}" else String(body))

    fun cookie(name: String): String? = headers["cookie"]?.split(';')
        ?.map { it.trim().split('=', limit = 2) }
        ?.firstOrNull { it.size == 2 && it[0] == name }?.get(1)
}

class Response(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
    /** Streams the body instead of [body]: [length] bytes, or until the connection closes if null. */
    val stream: ((OutputStream) -> Unit)? = null,
    val length: Long? = null,
    /** For status 101: takes over the connection (a WebSocket) once the headers are sent. */
    val upgrade: ((InputStream, OutputStream) -> Unit)? = null,
    /** Rechecked while a terminal is open, including while the connection is idle. */
    val upgradeAuthorized: (() -> Boolean)? = null,
) {
    companion object {
        fun json(o: Any, status: Int = 200, headers: Map<String, String> = emptyMap()) =
            Response(status, o.toString().toByteArray(), headers = headers)

        fun error(status: Int, message: String) = json(JSONObject().put("error", message), status)

        fun ok() = json(JSONObject().put("ok", true))
    }
}

/**
 * Minimal HTTP/1.1 server for the dashboard: one short-lived thread per connection, one
 * request per connection. Enough for a handful of users on a LAN, with no dependencies.
 */
class Http(
    private val port: Int,
    /** With TLS (the cluster port), clients must present a certificate; see [ClusterTls]. */
    private val tls: javax.net.ssl.SSLContext? = null,
    threads: Int = 16,
    private val handler: (Request) -> Response,
) {
    private val pool = ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val leases = Executors.newSingleThreadScheduledExecutor()
    private var socket: ServerSocket? = null

    /** The port actually bound, e.g. when started on port 0. */
    val localPort get() = socket?.localPort ?: port

    fun start() {
        val server = (tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()).apply {
            reuseAddress = true
            (this as? javax.net.ssl.SSLServerSocket)?.let(ClusterTls::configureServer)
            bind(InetSocketAddress(port))
        }
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                clients.add(client)
                try {
                    pool.execute { try { serve(client) } finally { clients.remove(client) } }
                } catch (_: RejectedExecutionException) {
                    clients.remove(client)
                    client.close()
                }
            }
        }, "http-$port").apply { isDaemon = true; start() }
    }

    fun stop() {
        socket?.close()
        pool.shutdownNow()
        leases.shutdownNow()
        clients.forEach { try { it.close() } catch (_: IOException) {} }
        clients.clear()
    }

    private fun serve(client: Socket) = client.use {
        client.soTimeout = 8_000
        val peer = (client as? javax.net.ssl.SSLSocket)?.let {
            try { it.startHandshake() } catch (_: IOException) { return@use }
            ClusterTls.clientFingerprint(it)
        }
        val input = BufferedInputStream(client.getInputStream())
        val response = try {
            val request = read(input, client.inetAddress.hostAddress.orEmpty(), peer)
                ?: return@use
            client.soTimeout = 30_000
            try {
                handler(request)
            } catch (_: org.json.JSONException) {
                Response.error(400, "invalid JSON")
            } catch (_: IllegalArgumentException) {
                Response.error(400, "invalid request")
            } catch (_: Exception) {
                Response.error(500, "request failed")
            }
        } catch (_: Exception) {
            Response.error(400, "bad request")
        }
        // An uncaught exception on any thread would take down the whole app.
        try {
            if (response.upgrade != null) {
                val out = client.getOutputStream()
                val head = StringBuilder("HTTP/1.1 101 Switching Protocols\r\n")
                for ((k, v) in response.headers) head.append(k).append(": ").append(v).append("\r\n")
                out.write(head.append("\r\n").toString().toByteArray())
                out.flush()
                client.soTimeout = 0 // a terminal can sit idle for hours
                val lease = response.upgradeAuthorized?.let { valid ->
                    leases.scheduleWithFixedDelay({ if (!runCatching(valid).getOrDefault(false)) runCatching { client.close() } }, 1, 1, TimeUnit.SECONDS)
                }
                try { response.upgrade.invoke(input, out) } finally { lease?.cancel(false) }
            } else {
                write(client, response)
            }
        } catch (_: Exception) {
            // The client went away mid-download.
        }
    }

    internal fun read(input: InputStream, remote: String, peer: String? = null): Request? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        val requestLine = readLine(input, deadline) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !Regex("[A-Z]+").matches(parts[0]) || !parts[1].startsWith('/') ||
            parts[2] !in setOf("HTTP/1.1", "HTTP/1.0")) throw IOException("bad request line")
        val headers = HashMap<String, String>()
        var bytes = requestLine.length
        while (true) {
            val line = readLine(input, deadline) ?: throw IOException("truncated headers")
            bytes += line.length + 2
            if (bytes > 32768 || headers.size >= 64) throw IOException("too many headers")
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) throw IOException("invalid header")
            val name = line.substring(0, i).lowercase()
            if (!Regex("[a-z0-9!#$%&'*+.^_`|~-]+").matches(name) || headers.containsKey(name) ||
                line.substring(i + 1).any { it.code < 32 && it != '\t' || it.code == 127 }) throw IOException("invalid header")
            headers[name] = line.substring(i + 1).trim()
        }
        if (headers["host"].isNullOrEmpty() || headers.containsKey("transfer-encoding")) throw IOException("unsupported framing")
        val length = headers["content-length"]?.let {
            if (!Regex("[0-9]+").matches(it)) throw IOException("invalid length")
            it.toLongOrNull() ?: throw IOException("invalid length")
        } ?: 0
        val target = parts[1]
        val path = decode(target.substringBefore('?'))
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        return Request(parts[0], path, query, headers, input, length, remote, peer)
    }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val sb = StringBuilder()
        while (true) {
            if (System.nanoTime() > deadline) throw IOException("headers timed out")
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("line too long")
            sb.append(c.toChar())
        }
    }

    private fun write(client: Socket, r: Response) {
        val out = client.getOutputStream()
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            .append("Content-Type: ").append(r.contentType).append("\r\n")
        val length = if (r.stream != null) r.length else r.body.size.toLong()
        if (length != null) head.append("Content-Length: ").append(length).append("\r\n")
        head.append("Connection: close\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
        if ("Cache-Control" !in r.headers) head.append("Cache-Control: no-store\r\n")
        for ((k, v) in r.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        val buffered = BufferedOutputStream(out, 1 shl 16)
        buffered.write(head.toString().toByteArray())
        if (r.stream != null) r.stream.invoke(buffered) else buffered.write(r.body)
        buffered.flush()
    }

    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        206 -> "Partial Content"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        416 -> "Range Not Satisfiable"
        429 -> "Too Many Requests"
        502 -> "Bad Gateway"
        504 -> "Gateway Timeout"
        507 -> "Insufficient Storage"
        else -> if (status >= 500) "Server Error" else "Status"
    }

}

/** Largest body read into memory; bigger ones must be streamed with [Request.bodyTo]. */
private const val MAX_BODY = 1 shl 20

fun jsonArray(items: Iterable<Any>) = JSONArray().apply { items.forEach { put(it) } }
