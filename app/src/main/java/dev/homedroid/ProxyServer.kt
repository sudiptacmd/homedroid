package dev.homedroid

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Wi-Fi repeater's proxies, plain Java sockets with no dependencies: an HTTP proxy (CONNECT for HTTPS,
 * absolute-form for plain HTTP), a SOCKS5 proxy (CONNECT and UDP ASSOCIATE) and a tiny server for the
 * proxy auto-configuration file. All three listen on [bind], the Wi-Fi group's address, so the home Wi-Fi
 * can't use them. [policy] decides which resolved destinations clients may reach.
 */
class ProxyServer(
    private val bind: InetAddress,
    private val httpPort: Int = RepeaterCore.HTTP_PORT,
    private val socksPort: Int = RepeaterCore.SOCKS_PORT,
    private val pacPort: Int = RepeaterCore.PAC_PORT,
    private val pac: () -> String,
    private val policy: (InetAddress) -> Boolean,
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
) {
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val connections = AtomicInteger()
    val totalConnections = AtomicLong()

    private val servers = mutableListOf<ServerSocket>()
    private val live = ConcurrentHashMap.newKeySet<java.io.Closeable>()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "repeater").apply { isDaemon = true } })
    @Volatile private var stopped = false

    val localHttpPort get() = servers.getOrNull(0)?.localPort ?: httpPort
    val localSocksPort get() = servers.getOrNull(1)?.localPort ?: socksPort
    val localPacPort get() = servers.getOrNull(2)?.localPort ?: pacPort

    @Synchronized
    fun start() {
        try {
            for ((port, handler) in listOf<Pair<Int, (Socket) -> Unit>>(httpPort to ::http, socksPort to ::socks, pacPort to ::pacPage)) {
                val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port)) }
                servers += server
                Thread({ accept(server, handler) }, "repeater-accept-$port").apply { isDaemon = true; start() }
            }
        } catch (e: IOException) {
            stop()
            throw e
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        live.forEach { runCatching { it.close() } }
        live.clear()
        pool.shutdownNow()
    }

    private fun accept(server: ServerSocket, handler: (Socket) -> Unit) {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: IOException) { return }
            if (connections.get() >= MAX_CONNECTIONS) { runCatching { client.close() }; continue }
            connections.incrementAndGet()
            totalConnections.incrementAndGet()
            live += client
            try {
                pool.execute {
                    try { client.soTimeout = HEADER_TIMEOUT_MS; client.tcpNoDelay = true; handler(client) }
                    catch (_: Exception) { }
                    finally { runCatching { client.close() }; live -= client; connections.decrementAndGet() }
                }
            } catch (_: Exception) {
                runCatching { client.close() }; live -= client; connections.decrementAndGet()
            }
        }
    }

    // --- connecting out --------------------------------------------------------------------------------------

    private class Blocked : IOException("blocked")

    private fun connect(host: String, port: Int): Socket {
        val all = resolve(host)
        if (all.isEmpty()) throw UnknownHostException(host)
        val allowed = all.filter(policy)
        if (allowed.isEmpty()) throw Blocked()
        // IPv4 first: the phone's Wi-Fi often has no working IPv6.
        var last: IOException? = null
        for (a in allowed.sortedBy { it.address.size }.take(3)) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.soTimeout = IDLE_TIMEOUT_MS
                live += s
                return s
            } catch (e: IOException) { runCatching { s.close() }; last = e }
        }
        throw last ?: IOException("could not connect")
    }

    /** Copies both ways until one side closes; counts the bytes. */
    private fun relay(client: Socket, clientIn: InputStream, target: Socket) {
        val back = pool.submit {
            try { copy(target.getInputStream(), client.getOutputStream(), bytesDown) } catch (_: IOException) { }
            runCatching { client.shutdownOutput() }
        }
        try { copy(clientIn, target.getOutputStream(), bytesUp) } catch (_: IOException) { }
        runCatching { target.shutdownOutput() }
        try { back.get() } catch (_: Exception) { }
        runCatching { target.close() }
        live -= target
    }

    private fun copy(from: InputStream, to: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try { from.read(buf) } catch (_: SocketTimeoutException) { return }
            if (n < 0) return
            to.write(buf, 0, n)
            to.flush()
            counter.addAndGet(n.toLong())
        }
    }

    // --- HTTP -------------------------------------------------------------------------------------------------

    private fun readLine(i: InputStream, limit: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= limit) throw IOException("line too long")
            sb.append(c.toChar())
        }
    }

    private fun respond(out: OutputStream, status: Int, reason: String, message: String) {
        val body = message.toByteArray()
        out.write(("HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
        out.flush()
    }

    private fun http(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val line = readLine(input) ?: return
        val headers = mutableListOf<String>()
        var total = line.length
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            total += h.length
            if (headers.size >= 100 || total > 65536) { respond(out, 431, "Request Header Fields Too Large", "Too many headers"); return }
            headers += h
        }
        val req = RepeaterCore.parseRequestLine(line).getOrElse { respond(out, 400, "Bad Request", it.message.orEmpty()); return }
        val target = try { connect(req.host, req.port) } catch (e: Blocked) {
            respond(out, 403, "Forbidden", "The Wi-Fi repeater only reaches the internet, not this phone or its network."); return
        } catch (e: UnknownHostException) {
            respond(out, 502, "Bad Gateway", "Can't find ${req.host}"); return
        } catch (e: IOException) {
            respond(out, 502, "Bad Gateway", "Can't connect to ${req.host}:${req.port}"); return
        }
        client.soTimeout = IDLE_TIMEOUT_MS
        try {
            if (req.connect) {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
            } else {
                val head = buildString {
                    append(req.method).append(' ').append(req.target).append(' ').append(req.version).append("\r\n")
                    for (h in RepeaterCore.forwardHeaders(headers, req.host, req.port)) append(h).append("\r\n")
                    append("\r\n")
                }
                target.getOutputStream().write(head.toByteArray())
                bytesUp.addAndGet(head.length.toLong())
            }
            relay(client, input, target)
        } finally { runCatching { target.close() }; live -= target }
    }

    // --- SOCKS5 ----------------------------------------------------------------------------------------------------

    private fun socks(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        try {
            out.write(RepeaterCore.Socks5.methodReply(RepeaterCore.Socks5.readGreeting(input)))
            out.flush()
        } catch (_: IOException) { return }
        val req = try { RepeaterCore.Socks5.readRequest(input) } catch (e: RepeaterCore.Socks5.UnsupportedAddressType) {
            out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.ADDRESS_NOT_SUPPORTED)); return
        } catch (_: IOException) { return }
        when (req.command) {
            RepeaterCore.Socks5.CONNECT -> {
                val target = try { connect(req.host, req.port) } catch (_: Blocked) {
                    out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.NOT_ALLOWED)); return
                } catch (_: UnknownHostException) {
                    out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.HOST_UNREACHABLE)); return
                } catch (_: IOException) {
                    out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.CONNECTION_REFUSED)); return
                }
                try {
                    out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.SUCCEEDED, target.localAddress.address, target.localPort))
                    out.flush()
                    client.soTimeout = IDLE_TIMEOUT_MS
                    relay(client, input, target)
                } finally { runCatching { target.close() }; live -= target }
            }
            RepeaterCore.Socks5.UDP_ASSOCIATE -> udpAssociate(client, input, out)
            else -> out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.COMMAND_NOT_SUPPORTED))
        }
    }

    /**
     * UDP through SOCKS5: the client sends datagrams, each wrapped in a SOCKS5 header, to a relay port on the
     * group address; replies are wrapped and returned. It lasts as long as the client's TCP connection.
     */
    private fun udpAssociate(client: Socket, input: InputStream, out: OutputStream) {
        val inbound = DatagramSocket(InetSocketAddress(bind, 0))
        val outbound = DatagramSocket()
        live += inbound; live += outbound
        try {
            out.write(RepeaterCore.Socks5.reply(RepeaterCore.Socks5.SUCCEEDED, bind.address, inbound.localPort))
            out.flush()
            val peerIp = client.inetAddress
            val clientAddr = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>()
            val reader = pool.submit {
                val buf = ByteArray(65535)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    while (!inbound.isClosed) {
                        pkt.length = buf.size
                        inbound.receive(pkt)
                        if (pkt.address != peerIp) continue
                        clientAddr.set(InetSocketAddress(pkt.address, pkt.port))
                        val u = RepeaterCore.Socks5.parseUdp(buf, pkt.length) ?: continue
                        if (u.frag != 0) continue // fragmentation is optional and rarely used
                        val dest = try { resolve(u.host).filter(policy).firstOrNull() } catch (_: IOException) { null } ?: continue
                        outbound.send(DatagramPacket(u.payload, u.payload.size, dest, u.port))
                        bytesUp.addAndGet(u.payload.size.toLong())
                    }
                } catch (_: IOException) { }
            }
            val replies = pool.submit {
                val buf = ByteArray(65535)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    while (!outbound.isClosed) {
                        pkt.length = buf.size
                        outbound.receive(pkt)
                        val to = clientAddr.get() ?: continue
                        val data = RepeaterCore.Socks5.buildUdp(pkt.address.address, pkt.port, buf.copyOf(pkt.length))
                        inbound.send(DatagramPacket(data, data.size, to))
                        bytesDown.addAndGet(pkt.length.toLong())
                    }
                } catch (_: IOException) { }
            }
            // The association ends when the client closes (or drops) its control connection.
            client.soTimeout = 0
            try { while (input.read() >= 0) { /* ignore */ } } catch (_: IOException) { }
            reader.cancel(true); replies.cancel(true)
        } finally {
            inbound.close(); outbound.close(); live -= inbound; live -= outbound
        }
    }

    // --- proxy auto-configuration ------------------------------------------------------------------------------------

    private fun pacPage(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val line = readLine(input) ?: return
        while (true) { if (readLine(input).isNullOrEmpty()) break }
        val parts = line.split(' ')
        val path = parts.getOrNull(1)?.substringBefore('?')
        if (parts.size != 3 || parts[0] !in setOf("GET", "HEAD")) { respond(out, 405, "Method Not Allowed", "GET only"); return }
        val (type, body) = when (path) {
            "/proxy.pac", "/wpad.dat" -> "application/x-ns-proxy-autoconfig" to pac()
            "/" -> "text/plain; charset=utf-8" to "Homedroid Wi-Fi repeater.\nProxy auto-configuration: /proxy.pac\nHTTP proxy: ${bind.hostAddress}:$localHttpPort\nSOCKS5 proxy: ${bind.hostAddress}:$localSocksPort\n"
            else -> { respond(out, 404, "Not Found", "Not found"); return }
        }
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        if (parts[0] == "GET") out.write(bytes)
        out.flush()
    }

    companion object {
        const val MAX_CONNECTIONS = 256
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val IDLE_TIMEOUT_MS = 5 * 60_000
    }
}
