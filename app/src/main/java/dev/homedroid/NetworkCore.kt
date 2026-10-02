package dev.homedroid

import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Validation and command/output formats shared by the network jobs. */
object NetworkCore {
    val tools = setOf("ping", "traceroute", "dns", "scan", "http", "whois", "iperf", "speed", "tcp")
    val discoveryPorts = listOf(22, 80, 443, 445, 554, 8080, 8443, 9100)

    fun ipv4(s: String): Boolean = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(s) &&
        s.split('.').all { it.toInt() in 0..255 }

    fun host(s: String): String {
        require(s.length in 1..253 && !s.startsWith('-')) { "Enter a hostname, IPv4 or IPv6 address" }
        val valid = if (':' in s) {
            Regex("[0-9a-fA-F:.]+(%[a-zA-Z0-9_.-]{1,32})?").matches(s) && runCatching {
                val literal = s.substringBefore('%')
                InetAddress.getByName(literal) is Inet6Address ||
                    literal.lowercase().startsWith("::ffff:") && ipv4(literal.substringAfterLast(':'))
            }.getOrDefault(false)
        } else if (Regex("[0-9.]+").matches(s)) ipv4(s)
        else s.removeSuffix(".").split('.').all { Regex("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?").matches(it) }
        require(valid) { "Enter a valid hostname, IPv4 or IPv6 address" }
        return s
    }

    fun port(s: String): Int {
        require(Regex("[0-9]{1,5}").matches(s) && s.toInt() in 1..65535) { "Port must be between 1 and 65535" }
        return s.toInt()
    }

    fun ports(s: String): String {
        require(s.length in 1..256) { "Enter ports or ranges, like 22,80,8000-8080" }
        for (part in s.split(',')) {
            val range = part.split('-')
            require(range.size in 1..2) { "Use comma-separated ports or ranges" }
            val first = port(range[0])
            if (range.size == 2) require(port(range[1]) >= first) { "Port range must be in ascending order" }
        }
        return s
    }

    fun subnet(s: String): List<String> {
        val parts = s.split('/')
        require(parts.size == 2 && ipv4(parts[0]) && Regex("[0-9]{2}").matches(parts[1]) && parts[1].toInt() in 24..32) {
            "Scan one IPv4 subnet of /24 or smaller"
        }
        val bits = parts[1].toInt()
        val ip = parts[0].split('.').fold(0L) { n, v -> (n shl 8) or v.toLong() }
        val count = 1 shl (32 - bits)
        val base = ip and (0xffffffffL shl (32 - bits))
        val offsets = if (bits < 31) 1 until count - 1 else 0 until count
        return offsets.map { offset -> (3 downTo 0).joinToString(".") { ((base + offset) shr (it * 8) and 255).toString() } }
    }

    fun target(s: String): String {
        if ('/' in s) subnet(s) else host(s)
        return s
    }

    fun url(s: String): String {
        require(s.length in 1..2048 && s.none { it.isWhitespace() || it.code < 32 }) { "Enter an HTTP or HTTPS URL (up to 2048 characters)" }
        val uri = runCatching { URI(s) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null) {
            "Enter an HTTP or HTTPS URL without credentials"
        }
        host(uri!!.host.removePrefix("[").removeSuffix("]"))
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid URL port" }
        return s
    }

    val curlFormat = "\nDNS=%{time_namelookup}\nConnect=%{time_connect}\nTLS=%{time_appconnect}\nFirstByte=%{time_starttransfer}\nTotal=%{time_total}\nStatus=%{response_code}\nDownload=%{speed_download}\nUpload=%{speed_upload}\n"
    private val curl = listOf("curl", "--silent", "--show-error", "--connect-timeout", "10", "--max-time", "50", "--proto", "=http,https", "--output", "/dev/null", "--write-out", curlFormat)

    fun command(kind: String, b: JSONObject): List<String> {
        require(kind in tools) { "Unknown network tool" }
        val h = if (kind in setOf("ping", "traceroute", "dns", "whois", "iperf", "tcp")) host(b.optString("host").trim()) else ""
        return when (kind) {
            "ping" -> listOf("ping", "-c", "5", "-W", "2", h)
            "traceroute" -> listOf("traceroute", "-n", "-m", "20", "-w", "1", "-q", "1", h)
            "dns" -> {
                val type = b.optString("type", "A")
                require(type in setOf("A", "AAAA", "MX", "TXT", "NS", "SOA", "PTR", "SRV", "CNAME")) { "Unknown DNS record type" }
                val server = b.optString("server").trim()
                listOf("dig", "+time=3", "+tries=1") + (if (server.isEmpty()) emptyList() else listOf("@${host(server)}")) +
                    listOf("-p", port(b.optString("port", "53")).toString(), h, type)
            }
            "scan" -> listOf("nmap", "--unprivileged", "-sT", "-Pn", "-n", "--disable-arp-ping", "--host-timeout", "15s", "--max-retries", "1", "--max-parallelism", "16") +
                (if (b.optString("ports").isBlank()) listOf("--top-ports", "100") else listOf("-p", ports(b.optString("ports")))) +
                (if (':' in b.optString("host")) listOf("-6") else emptyList()) + listOf(target(b.optString("host").trim()))
            // Only the HTTP check needs the certificate chain; its expiry date is read from it.
            "http" -> curl + listOf("--write-out", curlFormat + "%{certs}\n", "--head", url(b.optString("url").trim()))
            "whois" -> listOf("whois", h)
            "iperf" -> listOf("iperf3", "-c", h, "-p", port(b.optString("port", "5201")).toString(), "-t", "10", "-J")
            "speed" -> curl + listOf("https://speed.cloudflare.com/__down?bytes=10000000")
            else -> { port(b.optString("port", "443")); emptyList() }
        }
    }

    fun speedUpload() = curl + listOf("--data-binary", "@-", "https://speed.cloudflare.com/__up")

    fun ssdp(text: String): Map<String, String> {
        if (!text.startsWith("HTTP/1.1 200")) return emptyMap()
        return text.take(8192).lineSequence().drop(1).mapNotNull {
            val p = it.indexOf(':')
            if (p <= 0) null else it.substring(0, p).trim().lowercase() to it.substring(p + 1).trim().take(512)
        }.toMap()
    }

    fun nmap(text: String): List<Pair<String, List<Int>>> {
        val result = mutableListOf<Pair<String, List<Int>>>()
        var ip = ""
        var ports = mutableListOf<Int>()
        for (line in text.lineSequence()) {
            if (line.startsWith("Nmap scan report for ")) {
                if (ip.isNotEmpty()) result.add(ip to ports.toList())
                ip = line.substringAfter("for ").trim(); ports = mutableListOf()
            }
            Regex("^(\\d+)/tcp\\s+open\\s").find(line)?.let { ports.add(it.groupValues[1].toInt()) }
        }
        if (ip.isNotEmpty()) result.add(ip to ports.toList())
        return result
    }

    fun dig(text: String) = text.lineSequence().filter { it.isNotBlank() && !it.startsWith(';') }.take(100).toList()

    fun curl(text: String): JSONObject = JSONObject().apply {
        for (line in text.lineSequence()) {
            val pair = line.split('=', limit = 2)
            if (pair.size == 2 && pair[0] in setOf("DNS", "Connect", "TLS", "FirstByte", "Total", "Status", "Download", "Upload"))
                pair[1].toDoubleOrNull()?.takeIf { it.isFinite() }?.let { put(pair[0], it) }
            if (line.startsWith("Expire date:")) put("certExpiry", line.substringAfter(':').trim())
        }
    }

    fun iperf(text: String): JSONObject {
        val j = JSONObject(text)
        return JSONObject().put("sentMbps", j.optJSONObject("end")?.optJSONObject("sum_sent")?.optDouble("bits_per_second", 0.0)?.div(1e6) ?: 0.0)
            .put("receivedMbps", j.optJSONObject("end")?.optJSONObject("sum_received")?.optDouble("bits_per_second", 0.0)?.div(1e6) ?: 0.0)
            .apply { if (j.has("error")) put("error", j.getString("error")) }
    }

    fun pingDenied(text: String) = listOf("operation not permitted", "permission denied", "socket: permission", "cannot create socket", "can't create raw socket").any { it in text.lowercase() }
}
