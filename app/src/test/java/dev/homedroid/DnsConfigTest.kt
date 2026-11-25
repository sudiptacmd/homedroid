package dev.homedroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsConfigTest {
    @Test
    fun networkServersComeFirstWithAPublicFallback() {
        assertEquals(listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"), DnsConfig.servers(listOf("192.168.1.1")))
        assertEquals(
            listOf("192.168.1.1", "192.168.1.2", "1.1.1.1"),
            DnsConfig.servers(listOf("192.168.1.1", "192.168.1.2", "192.168.1.3")),
        )
    }

    @Test
    fun linkLocalAndBrokenServersAreDropped() {
        // What Samsung phones on IPv6 Wi-Fi report: the router's link-local address only.
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), DnsConfig.servers(listOf("fe80::1", "169.254.1.1", "0.0.0.0")))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), DnsConfig.servers(emptyList()))
        assertFalse(DnsConfig.usable("fe80::1%wlan0"))
        assertFalse(DnsConfig.usable("::1"))
        assertFalse(DnsConfig.usable("300.1.1.1"))
        assertTrue(DnsConfig.usable("2001:4860:4860::8888"))
    }

    @Test
    fun ipv6OnlyNetworksGetAnIpv6Fallback() {
        assertEquals(
            listOf("2001:db8::53", "2606:4700:4700::1111", "1.1.1.1"),
            DnsConfig.servers(listOf("2001:db8::53"), ipv4 = false),
        )
    }

    @Test
    fun customServersReplaceTheNetworks() {
        assertEquals(listOf("9.9.9.9"), DnsConfig.servers(listOf("192.168.1.1"), listOf("9.9.9.9")))
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), DnsConfig.parseCustom("9.9.9.9, 149.112.112.112"))
        assertEquals(emptyList<String>(), DnsConfig.parseCustom(" "))
        assertNull(DnsConfig.parseCustom("dns.google"))
        assertNull(DnsConfig.parseCustom("1.1.1.1 8.8.8.8 9.9.9.9 8.8.4.4"))
    }

    @Test
    fun resolvConfListsServersAndShortTimeouts() {
        val conf = DnsConfig.resolvConf(listOf("192.168.1.1", "1.1.1.1"))
        assertTrue(conf.contains("nameserver 192.168.1.1\nnameserver 1.1.1.1\n"))
        assertTrue(conf.contains("options timeout:2 attempts:3"))
    }
}
