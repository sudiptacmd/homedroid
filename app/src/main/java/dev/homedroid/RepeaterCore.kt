package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress

/**
 * Pure logic of the Wi-Fi repeater: the group's name and passphrase, the Wi-Fi QR code, the proxy
 * auto-configuration file, request parsing for the HTTP and SOCKS5 proxies, and which destinations
 * clients may reach. Free of Android so it can be tested; [WifiRepeater] and [ProxyServer] use it.
 */
object RepeaterCore {
    /** What Android's Wi-Fi Direct group owner uses unless the phone's maker changed it. */
    const val DEFAULT_ADDRESS = "192.168.49.1"
    const val HTTP_PORT = 8282
    const val PAC_PORT = 8283
    const val SOCKS_PORT = 8284
    const val DEFAULT_NAME = "DIRECT-hd-Homedroid"
    const val MAX_NAME_BYTES = 32

    // --- the Wi-Fi group ---------------------------------------------------------------------------------

    /**
     * A valid Wi-Fi Direct group name. Android requires "DIRECT-xy-" in front, where xy are two letters or
     * digits; what the user typed becomes the rest. Empty gives the default.
     */
    fun networkName(raw: String): String {
        val given = raw.trim()
        if (Regex("DIRECT-[A-Za-z0-9]{2}-.{1,21}").matches(given) && given.toByteArray().size <= MAX_NAME_BYTES && given.all { it.code in 32..126 }) return given
        val tail = given.removePrefix("DIRECT-").filter { it.code in 32..126 }.trim()
            .replace(Regex("^[A-Za-z0-9]{2}-(?=.)"), "")
        val clean = tail.ifEmpty { "Homedroid" }.take(MAX_NAME_BYTES - "DIRECT-hd-".length).trim().ifEmpty { "Homedroid" }
        return "DIRECT-hd-$clean"
    }

    /** WPA2 passphrases are 8 to 63 printable ASCII characters. */
    fun validPassphrase(p: String) = p.length in 8..63 && p.all { it.code in 32..126 }

    fun newPassphrase(rnd: java.util.Random = java.security.SecureRandom()): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    /** WifiP2pConfig's operating band constants: 0 automatic, 1 2.4 GHz, 2 5 GHz; null for anything else. */
    fun bandCode(s: String): Int? = when (s.trim().lowercase()) {
        "", "auto" -> 0
        "2.4", "2.4ghz", "2g", "2" -> 1
        "5", "5ghz", "5g" -> 2
        else -> null
    }

    fun bandName(code: Int) = when (code) { 1 -> "2.4"; 2 -> "5"; else -> "auto" }

    /** Escapes the characters that are special in a Wi-Fi QR payload (\ ; , : and "). */
    fun escapeWifi(s: String) = buildString { for (c in s) { if (c in "\\;,:\"") append('\\'); append(c) } }

    /** What phones scan to join the network: WIFI:T:WPA;S:name;P:passphrase;; */
    fun wifiQr(ssid: String, passphrase: String) = "WIFI:T:WPA;S:${escapeWifi(ssid)};P:${escapeWifi(passphrase)};;"

    // --- proxy auto-configuration -----------------------------------------------------------------------------

    /** The PAC file clients fetch from http://<address>:8283/proxy.pac. Local addresses go direct. */
    fun pac(address: String = DEFAULT_ADDRESS, httpPort: Int = HTTP_PORT, socksPort: Int = SOCKS_PORT): String = """
        |function FindProxyForURL(url, host) {
        |  if (isPlainHostName(host) || host === "localhost" || shExpMatch(host, "*.local") ||
        |      isInNet(host, "127.0.0.0", "255.0.0.0") || isInNet(host, "$address", "255.255.255.255")) {
        |    return "DIRECT";
        |  }
        |  return "PROXY $address:$httpPort; SOCKS5 $address:$socksPort; SOCKS $address:$socksPort";
        |}
        |""".trimMargin()

    fun pacUrl(address: String = DEFAULT_ADDRESS) = "http://$address:$PAC_PORT/proxy.pac"

    // --- who may be reached ------------------------------------------------------------------------------------------

    private fun u(b: Byte) = b.toInt() and 0xFF

    /** Addresses that belong to the phone or the networks around it: loopback, private ranges, link-local, unspecified, multicast. */
    fun isLocalAddress(addr: ByteArray): Boolean = when (addr.size) {
        4 -> {
            val a = u(addr[0]); val b = u(addr[1])
            a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 192 && b == 0 && u(addr[2]) == 0) || (a == 198 && b in 18..19)
        }
        16 -> {
            val mapped = (0 until 10).all { addr[it].toInt() == 0 } && u(addr[10]) == 0xFF && u(addr[11]) == 0xFF
            val v4 = (0 until 15).all { addr[it].toInt() == 0 }
            when {
                mapped -> isLocalAddress(addr.copyOfRange(12, 16))
                v4 -> true // :: and ::1
                else -> (u(addr[0]) and 0xFE) == 0xFC || (u(addr[0]) == 0xFE && (u(addr[1]) and 0xC0) == 0x80) || u(addr[0]) == 0xFF
            }
        }
        else -> true
    }

    /**
     * Whether a client may connect to [addr]. By default only the internet: the phone's own services (the
     * dashboard, SSH) and the home network stay out of reach, since everyone with the Wi-Fi passphrase can use the proxy.
     */
    fun allowed(addr: InetAddress, allowLan: Boolean, own: Set<InetAddress> = emptySet()): Boolean {
        if (addr in own || addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) return false
        return allowLan || !isLocalAddress(addr.address)
    }

    // --- HTTP proxy ----------------------------------------------------------------------------------------------------------

    class HttpRequest(
        val method: String,
        val host: String,
        val port: Int,
        /** CONNECT: tunnel raw bytes. Otherwise the request is forwarded as [target] with [version]. */
        val connect: Boolean,
        val target: String,
        val version: String,
    )

    /** "host", "host:port" or "[v6]:port" split into host and port, or null. */
    fun hostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val a = authority.trim()
        if (a.isEmpty() || a.contains('@') || a.contains('/')) return null
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0) return null
            val port = if (end + 1 == a.length) defaultPort else a.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return if (port in 1..65535 && end > 1 && a.substring(end + 1).let { it.isEmpty() || it.startsWith(":") }) a.substring(1, end) to port else null
        }
        val i = a.lastIndexOf(':')
        if (i >= 0 && a.indexOf(':') != i) return null // an unbracketed IPv6 address
        val host = if (i >= 0) a.substring(0, i) else a
        val port = if (i >= 0) a.substring(i + 1).toIntOrNull() ?: return null else defaultPort
        if (host.isEmpty() || port !in 1..65535 || host.any { it.isWhitespace() || it.isISOControl() }) return null
        return host to port
    }

    /** A proxy request line: "CONNECT host:443 HTTP/1.1" or "GET http://host/path HTTP/1.1". */
    fun parseRequestLine(line: String): Result<HttpRequest> {
        fun fail(m: String) = Result.failure<HttpRequest>(IllegalArgumentException(m))
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size != 3 || !Regex("[A-Z]+").matches(parts[0]) || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return fail("Bad request line")
        val (method, uri, version) = parts
        if (method == "CONNECT") {
            val (h, p) = hostPort(uri, 443) ?: return fail("Bad CONNECT target")
            return Result.success(HttpRequest(method, h, p, true, "", version))
        }
        val m = Regex("^http://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE).find(uri)
            ?: return fail(if (uri.startsWith("/")) "This is a proxy: set your device's proxy to this address instead of opening it as a website" else "Only http:// and CONNECT requests can be proxied")
        val (h, p) = hostPort(m.groupValues[1], 80) ?: return fail("Bad host in request")
        val path = m.groupValues[2].let { if (it.isEmpty() || it.startsWith("?")) "/$it" else it }
        return Result.success(HttpRequest(method, h, p, false, path, version))
    }

    private val HOP_BY_HOP = setOf("proxy-connection", "proxy-authorization", "proxy-authenticate", "connection", "keep-alive", "te", "trailer", "upgrade")

    /**
     * Headers to send to the origin server for a forwarded request: the proxy's own and hop-by-hop headers
     * are dropped, Host is set, and the connection is closed after the response.
     */
    fun forwardHeaders(headerLines: List<String>, host: String, port: Int): List<String> {
        val kept = headerLines.filter { l ->
            val name = l.substringBefore(':').trim().lowercase()
            name.isNotEmpty() && ':' in l && name !in HOP_BY_HOP && name != "host"
        }
        val authority = (if (':' in host) "[$host]" else host) + if (port != 80) ":$port" else ""
        return listOf("Host: $authority") + kept + "Connection: close"
    }

    // --- SOCKS5 -------------------------------------------------------------------------------------------------------------

    object Socks5 {
        const val CONNECT = 1
        const val UDP_ASSOCIATE = 3
        const val SUCCEEDED = 0
        const val GENERAL_FAILURE = 1
        const val NOT_ALLOWED = 2
        const val NETWORK_UNREACHABLE = 3
        const val HOST_UNREACHABLE = 4
        const val CONNECTION_REFUSED = 5
        const val COMMAND_NOT_SUPPORTED = 7
        const val ADDRESS_NOT_SUPPORTED = 8

        class Request(val command: Int, val host: String, val port: Int)

        private fun InputStream.byte(): Int = read().also { if (it < 0) throw EOFException() }

        private fun InputStream.bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = read(b, got, n - got)
                if (r < 0) throw EOFException()
                got += r
            }
            return b
        }

        /** The authentication methods the client offers (after the version byte 5). */
        fun readGreeting(i: InputStream): List<Int> {
            if (i.byte() != 5) throw IOException("Not SOCKS5")
            val n = i.byte()
            if (n == 0) throw IOException("No authentication methods offered")
            return i.bytes(n).map { it.toInt() and 0xFF }
        }

        /** Reply to the greeting: "no authentication" (the Wi-Fi passphrase is the access control), or refusal. */
        fun methodReply(methods: List<Int>) = byteArrayOf(5, if (0 in methods) 0 else 0xFF.toByte())

        fun readRequest(i: InputStream): Request {
            if (i.byte() != 5) throw IOException("Not SOCKS5")
            val cmd = i.byte()
            i.byte() // reserved
            val host = when (val atyp = i.byte()) {
                1 -> i.bytes(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
                3 -> {
                    val n = i.byte()
                    if (n == 0) throw IOException("Empty host name")
                    String(i.bytes(n), Charsets.ISO_8859_1)
                }
                4 -> InetAddress.getByAddress(i.bytes(16)).hostAddress.orEmpty()
                else -> throw UnsupportedAddressType(atyp)
            }
            val port = (i.byte() shl 8) or i.byte()
            return Request(cmd, host, port)
        }

        class UnsupportedAddressType(val atyp: Int) : IOException("Address type $atyp not supported")

        /** A reply with the given result code and bound address (IPv4 or IPv6 bytes). */
        fun reply(code: Int, addr: ByteArray = byteArrayOf(0, 0, 0, 0), port: Int = 0): ByteArray {
            val a = if (addr.size == 4 || addr.size == 16) addr else byteArrayOf(0, 0, 0, 0)
            return byteArrayOf(5, code.toByte(), 0, if (a.size == 4) 1 else 4) + a + byteArrayOf((port shr 8).toByte(), port.toByte())
        }

        /** A datagram of a UDP association: header (RSV, FRAG, ATYP, address, port) and the payload. */
        class Udp(val frag: Int, val host: String, val port: Int, val payload: ByteArray)

        fun parseUdp(data: ByteArray, length: Int = data.size): Udp? {
            if (length < 10 || data[0].toInt() != 0 || data[1].toInt() != 0) return null
            val frag = u(data[2])
            var p = 4
            val host = when (u(data[3])) {
                1 -> { if (length < p + 4 + 2) return null; (0 until 4).joinToString(".") { u(data[p + it]).toString() }.also { p += 4 } }
                3 -> {
                    val n = u(data[p]); p++
                    if (n == 0 || length < p + n + 2) return null
                    String(data, p, n, Charsets.ISO_8859_1).also { p += n }
                }
                4 -> { if (length < p + 16 + 2) return null; InetAddress.getByAddress(data.copyOfRange(p, p + 16)).hostAddress.orEmpty().also { p += 16 } }
                else -> return null
            }
            val port = (u(data[p]) shl 8) or u(data[p + 1])
            p += 2
            return Udp(frag, host, port, data.copyOfRange(p, length))
        }

        /** The header for a datagram coming back from [from] (IPv4 or IPv6 bytes), followed by [payload]. */
        fun buildUdp(from: ByteArray, port: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
            val head = byteArrayOf(0, 0, 0, if (from.size == 4) 1 else 4) + from + byteArrayOf((port shr 8).toByte(), port.toByte())
            return head + payload.copyOf(length)
        }
    }

    // --- help for the user ------------------------------------------------------------------------------------------------------

    /** Step-by-step setup for each kind of client, as [{os, steps[]}]. */
    fun instructions(address: String, httpPort: Int = HTTP_PORT, socksPort: Int = SOCKS_PORT, ssid: String, passphrase: String): JSONArray {
        val pac = pacUrl(address)
        fun os(name: String, vararg steps: String) = JSONObject().put("os", name).put("steps", JSONArray(steps.toList()))
        val join = "Join the Wi-Fi network \"$ssid\" with the passphrase shown here (or scan its QR code)."
        return JSONArray(listOf(
            os("Android", join,
                "Wi-Fi settings: tap the network (or the gear next to it) > Advanced > Proxy > Manual.",
                "Proxy hostname $address, port $httpPort. Save. (Or choose Proxy auto-config and enter $pac.)",
                "If Android says \"no internet\", choose to stay connected."),
            os("iPhone and iPad", join,
                "Settings > Wi-Fi > the (i) next to the network > Configure Proxy > Manual.",
                "Server $address, port $httpPort, authentication off. (Or choose Automatic and enter $pac.)",
                "Tap Save. If iOS offers to leave a network without internet, stay."),
            os("Windows 11", join,
                "Settings > Network & internet > Proxy.",
                "Under Manual proxy setup, Set up: on, address $address, port $httpPort, Save.",
                "Or turn on \"Use setup script\", Script address $pac, and Save."),
            os("macOS", join,
                "System Settings > Network > Wi-Fi > Details > Proxies.",
                "Turn on Web Proxy (HTTP) and Secure Web Proxy (HTTPS): server $address, port $httpPort. Or turn on Automatic Proxy Configuration with URL $pac.",
                "Click OK, then Apply."),
            os("Linux (GNOME)", join,
                "Settings > Network > Network Proxy > Manual: HTTP and HTTPS proxy $address port $httpPort; Socks host $address port $socksPort.",
                "Or Automatic with Configuration URL $pac."),
            os("Command line and apps with their own setting",
                "export http_proxy=http://$address:$httpPort https_proxy=http://$address:$httpPort",
                "curl --socks5-hostname $address:$socksPort https://example.com",
                "Firefox: Settings > Network Settings > Manual proxy, or SOCKS Host $address port $socksPort with SOCKS v5 and \"Proxy DNS when using SOCKS v5\" on."),
        ))
    }
}
