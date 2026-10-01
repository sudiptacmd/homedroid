package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkCoreTest {
    @Test fun hostsAndPortsAreValidated() {
        for (s in listOf("example.com", "printer.local", "192.168.1.10", "::1", "2001:db8::123", "fe80::1%wlan0", "::ffff:192.168.1.1")) assertEquals(s, NetworkCore.host(s))
        for (s in listOf("", "-oX", "host;id", "a b", "999.1.2.3", "192.168.1", "a..b", "a/b", "::gg", "1:2:3", "x".repeat(254)))
            assertThrows(IllegalArgumentException::class.java) { NetworkCore.host(s) }
        assertEquals(1053, NetworkCore.port("1053"))
        for (s in listOf("0", "65536", "-1", "53abc", "1;id", "")) assertThrows(IllegalArgumentException::class.java) { NetworkCore.port(s) }
        assertEquals("22,80,8000-8080", NetworkCore.ports("22,80,8000-8080"))
        for (s in listOf("80-22", "1-2-3", "80,", "80 --script", "0-65535", "80,,443"))
            assertThrows(IllegalArgumentException::class.java) { NetworkCore.ports(s) }
    }

    @Test fun subnetsNeverExceedOne24() {
        val ips = NetworkCore.subnet("192.168.5.123/24")
        assertEquals(254, ips.size)
        assertEquals("192.168.5.1", ips.first())
        assertEquals("192.168.5.254", ips.last())
        assertEquals(listOf("10.0.0.128", "10.0.0.129"), NetworkCore.subnet("10.0.0.129/31"))
        assertEquals(listOf("10.0.0.7"), NetworkCore.subnet("10.0.0.7/32"))
        assertEquals(126, NetworkCore.subnet("10.0.0.200/25").size)
        for (s in listOf("10.0.0.0/23", "10.0.0.0/0", "10.0.0.0/33", "10.0.0.0/-24", "300.0.0.0/24", "::/64", "10.0.0.1/24/24"))
            assertThrows(IllegalArgumentException::class.java) { NetworkCore.subnet(s) }
    }

    @Test fun commandsAreUnprivilegedAndArgumentsStaySeparate() {
        val scan = NetworkCore.command("scan", JSONObject().put("host", "192.168.1.0/24"))
        assertTrue(scan.containsAll(listOf("--unprivileged", "-sT", "-Pn", "-n", "--disable-arp-ping", "--top-ports", "100")))
        assertFalse(scan.any { it in setOf("-A", "-O", "-sS", "-sU", "/bin/sh") })
        assertEquals("192.168.1.0/24", scan.last())
        assertTrue(NetworkCore.command("scan", JSONObject().put("host", "::1")).contains("-6"))
        val dig = NetworkCore.command("dns", JSONObject().put("host", "example.com").put("server", "127.0.0.1").put("port", "1053"))
        assertTrue(dig.containsAll(listOf("@127.0.0.1", "-p", "1053", "example.com", "A")))
        val url = "https://example.com/?a=1&b=$(id)"
        assertEquals(url, NetworkCore.command("http", JSONObject().put("url", url)).last())
        for (kind in listOf("ping", "traceroute", "dns", "scan", "whois", "iperf", "tcp"))
            assertThrows(IllegalArgumentException::class.java) { NetworkCore.command(kind, JSONObject().put("host", "--help")) }
        assertThrows(IllegalArgumentException::class.java) { NetworkCore.command("dns", JSONObject().put("host", "example.com").put("type", "AXFR")) }
        assertThrows(IllegalArgumentException::class.java) { NetworkCore.command("scan", JSONObject().put("host", "10.0.0.0/16")) }
    }

    @Test fun urlsOnlyAllowHttpWithoutCredentials() {
        assertEquals("http://[::1]:8080/", NetworkCore.url("http://[::1]:8080/"))
        for (s in listOf("file:///etc/passwd", "ftp://example.com", "https://user:pass@example.com", "http://example.com:0", "http://example.com:65536", "http://x\n--help", "--help", "http://"))
            assertThrows(IllegalArgumentException::class.java) { NetworkCore.url(s) }
    }

    @Test fun ssdpResponsesAreBoundedAndCaseInsensitive() {
        val headers = NetworkCore.ssdp("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.4:80/description.xml\r\nServer: printer\r\nST: upnp:rootdevice\r\n\r\n")
        assertEquals("printer", headers["server"])
        assertEquals("http://192.168.1.4:80/description.xml", headers["location"])
        assertTrue(NetworkCore.ssdp("NOTIFY * HTTP/1.1\r\nSERVER: x").isEmpty())
        assertEquals(512, NetworkCore.ssdp("HTTP/1.1 200 OK\nSERVER: " + "x".repeat(1000))["server"]!!.length)
    }

    @Test fun toolOutputIsParsedWithoutInventingReachability() {
        val scan = NetworkCore.nmap("Nmap scan report for 192.168.1.2\nPORT STATE SERVICE\n22/tcp open ssh\n80/tcp closed http\nNmap scan report for 192.168.1.3\n443/tcp filtered https\n")
        assertEquals(listOf(22), scan[0].second)
        assertTrue(scan[1].second.isEmpty())
        assertEquals(listOf("example.com. 60 IN A 1.2.3.4"), NetworkCore.dig(";; ANSWER SECTION:\nexample.com. 60 IN A 1.2.3.4\n\n"))
        val curl = NetworkCore.curl("DNS=0.012\nConnect=0.030\nTLS=0.080\nFirstByte=0.110\nTotal=0.120\nStatus=200\nDownload=1250000\nExpire date: Oct 10 00:00:00 2026 GMT\n")
        assertEquals(0.012, curl.getDouble("DNS"), 0.0001)
        assertEquals(200, curl.getInt("Status"))
        assertTrue(curl.getString("certExpiry").contains("2026"))
        assertFalse(NetworkCore.curl("Total=NaN").has("Total"))
        val iperf = NetworkCore.iperf("""{"end":{"sum_sent":{"bits_per_second":12000000},"sum_received":{"bits_per_second":10000000}}}""")
        assertEquals(12.0, iperf.getDouble("sentMbps"), 0.001)
        assertEquals(10.0, iperf.getDouble("receivedMbps"), 0.001)
        assertEquals("Connection refused", NetworkCore.iperf("""{"error":"Connection refused"}""").getString("error"))
        assertTrue(NetworkCore.pingDenied("ping: socket: Operation not permitted"))
        assertFalse(NetworkCore.pingDenied("100% packet loss"))
    }
}
