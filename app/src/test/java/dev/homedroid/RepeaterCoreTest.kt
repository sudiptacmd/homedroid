package dev.homedroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class RepeaterCoreTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun networkNamesGetTheDirectPrefix() {
        assertEquals("DIRECT-hd-Homedroid", RepeaterCore.networkName(""))
        assertEquals("DIRECT-hd-Kitchen", RepeaterCore.networkName("Kitchen"))
        assertEquals("DIRECT-xy-Kitchen", RepeaterCore.networkName("DIRECT-xy-Kitchen"))
        assertEquals("DIRECT-hd-Kitchen", RepeaterCore.networkName("DIRECT-Kitchen"))
        assertTrue(RepeaterCore.networkName("x".repeat(100)).toByteArray().size <= 32)
        assertTrue(RepeaterCore.networkName("Küche ☕").all { it.code in 32..126 })
        assertEquals("DIRECT-hd-Homedroid", RepeaterCore.networkName("DIRECT-"))
    }

    @Test fun passphrasesAreWpa2Sized() {
        assertTrue(RepeaterCore.validPassphrase("12345678"))
        assertFalse(RepeaterCore.validPassphrase("1234567"))
        assertFalse(RepeaterCore.validPassphrase("x".repeat(64)))
        assertFalse(RepeaterCore.validPassphrase("tab\tinside"))
        assertFalse(RepeaterCore.validPassphrase("ünïcode-pass"))
        repeat(20) { assertTrue(RepeaterCore.validPassphrase(RepeaterCore.newPassphrase())) }
    }

    @Test fun bandsMapToAndroidsConstants() {
        assertEquals(0, RepeaterCore.bandCode("auto"))
        assertEquals(0, RepeaterCore.bandCode(""))
        assertEquals(1, RepeaterCore.bandCode("2.4"))
        assertEquals(2, RepeaterCore.bandCode("5"))
        assertNull(RepeaterCore.bandCode("6"))
        assertEquals("5", RepeaterCore.bandName(2))
    }

    @Test fun wifiQrEscapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:DIRECT-hd-Home;P:abcd-1234;;", RepeaterCore.wifiQr("DIRECT-hd-Home", "abcd-1234"))
        assertEquals("WIFI:T:WPA;S:a\\;b\\,c\\:d\\\\e\\\"f;P:p\\;w;;", RepeaterCore.wifiQr("a;b,c:d\\e\"f", "p;w"))
    }

    @Test fun pacSendsEverythingElseToTheProxies() {
        val pac = RepeaterCore.pac("192.168.49.1", 8282, 8284)
        assertTrue(pac.contains("function FindProxyForURL(url, host)"))
        assertTrue(pac.contains("return \"PROXY 192.168.49.1:8282; SOCKS5 192.168.49.1:8284; SOCKS 192.168.49.1:8284\";"))
        assertTrue(pac.contains("isPlainHostName(host)"))
        assertEquals("http://192.168.49.1:8283/proxy.pac", RepeaterCore.pacUrl())
    }

    @Test fun connectRequestsAreParsed() {
        val r = RepeaterCore.parseRequestLine("CONNECT example.com:443 HTTP/1.1").getOrThrow()
        assertTrue(r.connect); assertEquals("example.com", r.host); assertEquals(443, r.port)
        val v6 = RepeaterCore.parseRequestLine("CONNECT [2001:db8::1]:8443 HTTP/1.1").getOrThrow()
        assertEquals("2001:db8::1", v6.host); assertEquals(8443, v6.port)
        assertEquals(443, RepeaterCore.parseRequestLine("CONNECT example.com HTTP/1.1").getOrThrow().port)
        assertTrue(RepeaterCore.parseRequestLine("CONNECT example.com:99999 HTTP/1.1").isFailure)
        assertTrue(RepeaterCore.parseRequestLine("CONNECT user@example.com:443 HTTP/1.1").isFailure)
        assertTrue(RepeaterCore.parseRequestLine("CONNECT 2001:db8::1:443 HTTP/1.1").isFailure)
    }

    @Test fun absoluteFormRequestsAreParsed() {
        val r = RepeaterCore.parseRequestLine("GET http://example.com:8080/a/b?c=d HTTP/1.1").getOrThrow()
        assertFalse(r.connect)
        assertEquals("example.com", r.host); assertEquals(8080, r.port)
        assertEquals("/a/b?c=d", r.target)
        assertEquals("/", RepeaterCore.parseRequestLine("GET http://example.com HTTP/1.0").getOrThrow().target)
        assertEquals("/?q=1", RepeaterCore.parseRequestLine("GET http://example.com?q=1 HTTP/1.1").getOrThrow().target)
        assertEquals(80, RepeaterCore.parseRequestLine("POST HTTP://Example.com/x HTTP/1.1").getOrThrow().port)
        assertTrue(RepeaterCore.parseRequestLine("GET /proxy.pac HTTP/1.1").exceptionOrNull()!!.message!!.contains("proxy"))
        assertTrue(RepeaterCore.parseRequestLine("GET https://example.com/ HTTP/1.1").isFailure)
        assertTrue(RepeaterCore.parseRequestLine("GET http://example.com/").isFailure)
        assertTrue(RepeaterCore.parseRequestLine("get http://example.com/ HTTP/1.1").isFailure)
        assertTrue(RepeaterCore.parseRequestLine("GET http://example.com/ HTTP/2").isFailure)
    }

    @Test fun forwardedHeadersDropProxyAndHopByHopOnes() {
        val h = RepeaterCore.forwardHeaders(listOf("Host: proxy.local", "Proxy-Connection: keep-alive", "Proxy-Authorization: Basic x",
            "Accept: */*", "Connection: keep-alive", "Keep-Alive: 5", "User-Agent: t", "garbage"), "example.com", 8080)
        assertEquals(listOf("Host: example.com:8080", "Accept: */*", "User-Agent: t", "Connection: close"), h)
        assertEquals("Host: example.com", RepeaterCore.forwardHeaders(emptyList(), "example.com", 80)[0])
        assertEquals("Host: [2001:db8::1]", RepeaterCore.forwardHeaders(emptyList(), "2001:db8::1", 80)[0])
    }

    private fun stream(vararg b: Int): InputStream = ByteArrayInputStream(ByteArray(b.size) { b[it].toByte() })

    @Test fun socksGreetingPicksNoAuthentication() {
        val methods = RepeaterCore.Socks5.readGreeting(stream(5, 2, 0x02, 0x00))
        assertEquals(listOf(2, 0), methods)
        assertArrayEquals(byteArrayOf(5, 0), RepeaterCore.Socks5.methodReply(methods))
        assertArrayEquals(byteArrayOf(5, 0xFF.toByte()), RepeaterCore.Socks5.methodReply(listOf(2)))
        assertThrows(java.io.IOException::class.java) { RepeaterCore.Socks5.readGreeting(stream(4, 1, 0)) }
        assertThrows(java.io.IOException::class.java) { RepeaterCore.Socks5.readGreeting(stream(5, 0)) }
        assertThrows(java.io.IOException::class.java) { RepeaterCore.Socks5.readGreeting(stream(5, 3, 0)) }
    }

    @Test fun socksRequestsHandleAllAddressTypes() {
        val v4 = RepeaterCore.Socks5.readRequest(stream(5, 1, 0, 1, 93, 184, 216, 34, 0x01, 0xBB))
        assertEquals(1, v4.command); assertEquals("93.184.216.34", v4.host); assertEquals(443, v4.port)
        val name = "example.com".toByteArray().map { it.toInt() }.toIntArray()
        val dom = RepeaterCore.Socks5.readRequest(stream(5, 1, 0, 3, name.size, *name, 0x00, 0x50))
        assertEquals("example.com", dom.host); assertEquals(80, dom.port)
        val v6 = RepeaterCore.Socks5.readRequest(stream(5, 3, 0, 4, 0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0x1F, 0x90))
        assertEquals(3, v6.command); assertEquals(8080, v6.port)
        assertTrue(v6.host.startsWith("2001:db8"))
        assertThrows(RepeaterCore.Socks5.UnsupportedAddressType::class.java) { RepeaterCore.Socks5.readRequest(stream(5, 1, 0, 9, 1, 2)) }
        assertThrows(java.io.EOFException::class.java) { RepeaterCore.Socks5.readRequest(stream(5, 1, 0, 1, 1, 2)) }
    }

    @Test fun socksRepliesAndUdpHeaders() {
        assertArrayEquals(byteArrayOf(5, 0, 0, 1, 10, 0, 0, 1, 0x1F, 0x90.toByte()), RepeaterCore.Socks5.reply(0, byteArrayOf(10, 0, 0, 1), 8080))
        assertArrayEquals(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0), RepeaterCore.Socks5.reply(RepeaterCore.Socks5.CONNECTION_REFUSED))
        val dgram = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53, 1, 2, 3)
        val u = RepeaterCore.Socks5.parseUdp(dgram)!!
        assertEquals(0, u.frag); assertEquals("8.8.8.8", u.host); assertEquals(53, u.port)
        assertArrayEquals(byteArrayOf(1, 2, 3), u.payload)
        val back = RepeaterCore.Socks5.buildUdp(byteArrayOf(8, 8, 8, 8), 53, byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53, 9, 9), back)
        assertEquals("example.com", RepeaterCore.Socks5.parseUdp(byteArrayOf(0, 0, 0, 3, 11, *"example.com".toByteArray(), 1, 0, 7))!!.host)
        assertNull(RepeaterCore.Socks5.parseUdp(byteArrayOf(1, 0, 0, 1, 8, 8, 8, 8, 0, 53)))
        assertNull(RepeaterCore.Socks5.parseUdp(byteArrayOf(0, 0, 0, 1, 8, 8)))
    }

    @Test fun onlyTheInternetIsReachableByDefault() {
        for (a in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.20", "192.168.49.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:192.168.1.1")) {
            assertFalse(a, RepeaterCore.allowed(ip(a), allowLan = false))
        }
        for (a in listOf("93.184.216.34", "8.8.8.8", "172.32.0.1", "2606:4700::1111", "::ffff:8.8.8.8")) {
            assertTrue(a, RepeaterCore.allowed(ip(a), allowLan = false))
        }
        assertTrue(RepeaterCore.allowed(ip("192.168.1.50"), allowLan = true))
        assertFalse("never the phone itself", RepeaterCore.allowed(ip("127.0.0.1"), allowLan = true))
        assertFalse(RepeaterCore.allowed(ip("192.168.1.20"), allowLan = true, own = setOf(ip("192.168.1.20"))))
    }

    @Test fun hostAndPortSplitting() {
        assertEquals("a.b" to 80, RepeaterCore.hostPort("a.b", 80))
        assertEquals("a.b" to 8080, RepeaterCore.hostPort("a.b:8080", 80))
        assertEquals("::1" to 9, RepeaterCore.hostPort("[::1]:9", 80))
        assertNull(RepeaterCore.hostPort("", 80))
        assertNull(RepeaterCore.hostPort("a.b:0", 80))
        assertNull(RepeaterCore.hostPort("a.b:x", 80))
        assertNull(RepeaterCore.hostPort("a b", 80))
    }

    @Test fun instructionsNameTheAddressAndPorts() {
        val all = RepeaterCore.instructions("192.168.49.1", ssid = "DIRECT-hd-Home", passphrase = "pass").toString()
        for (os in listOf("Android", "iPhone and iPad", "Windows 11", "macOS", "Linux")) assertTrue(os, all.contains(os))
        assertTrue(all.contains("192.168.49.1"))
        assertTrue(all.contains("8282"))
        assertTrue(all.contains("http://192.168.49.1:8283/proxy.pac"))
    }

    // --- the proxies themselves, on loopback ----------------------------------------------------------------------------

    private fun server(policy: (InetAddress) -> Boolean = { true }) = ProxyServer(ip("127.0.0.1"), 0, 0, 0, pac = { "PAC" }, policy = policy).also { it.start() }

    private fun Socket.readHttpHead(): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val c = getInputStream().read(); if (c < 0) break; sb.append(c.toChar()) }
        return sb.toString()
    }

    /** An echo server: replies with what it receives, upper-cased. */
    private fun echo(): ServerSocket = ServerSocket(0, 5, ip("127.0.0.1")).also { s ->
        Thread { while (!s.isClosed) { try { val c = s.accept(); Thread { try { val buf = ByteArray(100); val n = c.getInputStream().read(buf); if (n > 0) c.getOutputStream().write(String(buf, 0, n).uppercase().toByteArray()); c.close() } catch (_: Exception) {} }.start() } catch (_: Exception) {} } }.apply { isDaemon = true }.start()
    }

    @Test fun httpConnectTunnelsBytesAndCountsThem() {
        val origin = echo(); val proxy = server()
        try {
            Socket(ip("127.0.0.1"), proxy.localHttpPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("CONNECT 127.0.0.1:${origin.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertTrue(c.readHttpHead().startsWith("HTTP/1.1 200"))
                c.getOutputStream().write("hello".toByteArray())
                val buf = ByteArray(5); var got = 0
                while (got < 5) { val n = c.getInputStream().read(buf, got, 5 - got); if (n < 0) break; got += n }
                assertEquals("HELLO", String(buf, 0, got))
            }
            Thread.sleep(200)
            assertEquals(5, proxy.bytesUp.get()); assertEquals(5, proxy.bytesDown.get())
            assertTrue(proxy.totalConnections.get() >= 1)
        } finally { proxy.stop(); origin.close() }
    }

    @Test fun httpAbsoluteFormIsForwardedWithCleanHeaders() {
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val origin = ServerSocket(0, 5, ip("127.0.0.1"))
        Thread { try { origin.accept().use { c ->
            val sb = StringBuilder(); while (!sb.endsWith("\r\n\r\n")) sb.append(c.getInputStream().read().toChar())
            seen.add(sb.toString()); c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
        } } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        val proxy = server()
        try {
            Socket(ip("127.0.0.1"), proxy.localHttpPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("GET http://127.0.0.1:${origin.localPort}/path?q=1 HTTP/1.1\r\nHost: wrong\r\nProxy-Connection: keep-alive\r\nAccept: */*\r\n\r\n".toByteArray())
                val reply = c.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK") && reply.endsWith("ok"))
            }
            val head = seen.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertTrue(head, head.startsWith("GET /path?q=1 HTTP/1.1\r\nHost: 127.0.0.1:${origin.localPort}\r\n"))
            assertFalse(head.contains("Proxy-Connection")); assertTrue(head.contains("Connection: close"))
        } finally { proxy.stop(); origin.close() }
    }

    @Test fun blockedDestinationsGetForbidden() {
        val proxy = server(policy = { RepeaterCore.allowed(it, false) })
        try {
            Socket(ip("127.0.0.1"), proxy.localHttpPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("CONNECT 127.0.0.1:8800 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(c.readHttpHead().startsWith("HTTP/1.1 403"))
            }
            Socket(ip("127.0.0.1"), proxy.localSocksPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write(byteArrayOf(5, 1, 0)); val m = ByteArray(2); c.getInputStream().readNBytes(m, 0, 2)
                c.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0x22, 0x60))
                val r = ByteArray(10); c.getInputStream().readNBytes(r, 0, 10)
                assertEquals(RepeaterCore.Socks5.NOT_ALLOWED, r[1].toInt())
            }
        } finally { proxy.stop() }
    }

    @Test fun socks5ConnectRelaysBytes() {
        val origin = echo(); val proxy = server()
        try {
            Socket(ip("127.0.0.1"), proxy.localSocksPort).use { c ->
                c.soTimeout = 5000
                val o = c.getOutputStream(); val i = c.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); val m = ByteArray(2); i.readNBytes(m, 0, 2)
                assertArrayEquals(byteArrayOf(5, 0), m)
                val port = origin.localPort
                o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
                val r = ByteArray(10); i.readNBytes(r, 0, 10)
                assertEquals(0, r[1].toInt())
                o.write("socks".toByteArray())
                val buf = ByteArray(5); i.readNBytes(buf, 0, 5)
                assertEquals("SOCKS", String(buf))
            }
        } finally { proxy.stop(); origin.close() }
    }

    @Test fun socks5UdpAssociateRelaysDatagrams() {
        val udpEcho = DatagramSocket(0, ip("127.0.0.1"))
        Thread { try { val b = ByteArray(100); val p = DatagramPacket(b, b.size); udpEcho.receive(p); udpEcho.send(DatagramPacket(b, p.length, p.address, p.port)) } catch (_: Exception) {} }.apply { isDaemon = true }.start()
        val proxy = server()
        try {
            Socket(ip("127.0.0.1"), proxy.localSocksPort).use { c ->
                c.soTimeout = 5000
                val o = c.getOutputStream(); val i = c.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); i.readNBytes(ByteArray(2), 0, 2)
                o.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                val r = ByteArray(10); i.readNBytes(r, 0, 10)
                assertEquals(0, r[1].toInt())
                val relayPort = ((r[8].toInt() and 0xFF) shl 8) or (r[9].toInt() and 0xFF)
                DatagramSocket().use { u ->
                    u.soTimeout = 5000
                    val payload = "ping".toByteArray()
                    val port = udpEcho.localPort
                    val data = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()) + payload
                    u.send(DatagramPacket(data, data.size, ip("127.0.0.1"), relayPort))
                    val buf = ByteArray(200); val p = DatagramPacket(buf, buf.size); u.receive(p)
                    val back = RepeaterCore.Socks5.parseUdp(buf, p.length)!!
                    assertEquals("127.0.0.1", back.host); assertEquals(port, back.port)
                    assertEquals("ping", String(back.payload))
                }
            }
        } finally { proxy.stop(); udpEcho.close() }
    }

    @Test fun pacIsServedOnItsOwnPort() {
        val proxy = server()
        try {
            Socket(ip("127.0.0.1"), proxy.localPacPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val text = c.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(text.contains("application/x-ns-proxy-autoconfig")); assertTrue(text.endsWith("PAC"))
            }
            Socket(ip("127.0.0.1"), proxy.localPacPort).use { c ->
                c.soTimeout = 5000
                c.getOutputStream().write("GET /nope HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(c.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 404"))
            }
        } finally { proxy.stop() }
    }
}
