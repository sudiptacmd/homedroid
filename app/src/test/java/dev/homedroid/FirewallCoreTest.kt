package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FirewallCoreTest {
    private fun ip(s: String) = FirewallCore.parseIp(s)!!
    private fun rules(vararg r: Triple<String, String, String>) = FirewallCore.rules(JSONObject().put("rules", JSONArray(r.map {
        JSONObject().put("action", it.first).put("cidr", it.second).put("scope", it.third)
    }))).getOrThrow()

    @Test fun parsesIpv4AndIpv6() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 10), ip("192.168.1.10"))
        assertEquals(16, ip("2001:db8::1").size)
        assertEquals("2001:db8::1", FirewallCore.format(ip("2001:0db8:0000:0000:0000:0000:0000:0001")))
        assertEquals("::1", FirewallCore.format(ip("::1")))
        assertEquals("::", FirewallCore.format(ip("::")))
        assertEquals("fe80::", FirewallCore.format(ip("fe80::")))
        assertEquals("1:0:2::3", FirewallCore.format(ip("1:0:2:0:0:0:0:3")))
        assertEquals("fe80::1", FirewallCore.format(ip("fe80::1%wlan0")))
        assertEquals("::1", FirewallCore.format(ip("[::1]")))
        // IPv4-mapped addresses (what Java reports for v4 clients on a dual-stack socket) become IPv4.
        assertEquals("10.1.2.3", FirewallCore.format(ip("::ffff:10.1.2.3")))
        assertEquals("64:ff9b::a01:203", FirewallCore.format(ip("64:ff9b::10.1.2.3")))
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.-1", "a.b.c.d", ":::", "1::2::3",
            "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "12345::", "g::1", ":1", "1:", "::1.2.3", "1.2.3.4::", "example.com"))
            assertNull(bad, FirewallCore.parseIp(bad))
    }

    @Test fun cidrsMatch() {
        val net = FirewallCore.parseCidr("192.168.1.77/24")!!
        assertEquals("192.168.1.0/24", net.toString())
        assertTrue(net.contains(ip("192.168.1.1")))
        assertTrue(net.contains(ip("192.168.1.255")))
        assertFalse(net.contains(ip("192.168.2.1")))
        assertFalse(net.contains(ip("::1")))
        assertTrue(FirewallCore.parseCidr("10.0.0.0/8")!!.contains(ip("10.255.0.1")))
        assertTrue(FirewallCore.parseCidr("0.0.0.0/0")!!.contains(ip("8.8.8.8")))
        assertTrue(FirewallCore.parseCidr("172.16.0.0/12")!!.contains(ip("172.31.255.255")))
        assertFalse(FirewallCore.parseCidr("172.16.0.0/12")!!.contains(ip("172.32.0.0")))
        val v6 = FirewallCore.parseCidr("2001:db8:abcd::/48")!!
        assertTrue(v6.contains(ip("2001:db8:abcd:12::5")))
        assertFalse(v6.contains(ip("2001:db8:abce::5")))
        assertEquals("2001:db8:8000::/33", FirewallCore.parseCidr("2001:db8:ffff::/33")!!.toString())
        assertEquals("2001:db8::/32", FirewallCore.parseCidr("2001:db8:ffff::1/32")!!.toString())
        assertEquals("1.2.3.4", FirewallCore.parseCidr("1.2.3.4")!!.toString())
        assertEquals("10.0.0.0/8", FirewallCore.parseCidr("::ffff:10.0.0.0/104")!!.toString())
        for (bad in listOf("1.2.3.4/33", "::/129", "1.2.3.4/", "1.2.3.4/-1", "1.2.3.4/a", "/24", "x/8"))
            assertNull(bad, FirewallCore.parseCidr(bad))
    }

    @Test fun defaultAllowsEverything() {
        assertTrue(FirewallCore.allows(emptyList(), "dashboard", ip("8.8.8.8")))
        assertTrue(FirewallCore.allows(emptyList(), "web", null))
    }

    @Test fun denyWinsThenAllowListApplies() {
        val r = rules(Triple("allow", "192.168.1.0/24", "dashboard"), Triple("deny", "192.168.1.66", "all"))
        assertTrue(FirewallCore.allows(r, "dashboard", ip("192.168.1.5")))
        assertFalse(FirewallCore.allows(r, "dashboard", ip("192.168.1.66")))
        assertFalse(FirewallCore.allows(r, "dashboard", ip("10.0.0.5")))
        assertFalse(FirewallCore.allows(r, "dashboard", null))
        // The allow rule is dashboard-only, so the web is open except for the deny.
        assertTrue(FirewallCore.allows(r, "web", ip("10.0.0.5")))
        assertFalse(FirewallCore.allows(r, "web", ip("192.168.1.66")))
    }

    @Test fun loopbackIsNeverBlocked() {
        val r = rules(Triple("deny", "0.0.0.0/0", "all"), Triple("deny", "::/0", "all"), Triple("allow", "10.9.9.9", "all"))
        assertTrue(FirewallCore.allows(r, "dashboard", ip("127.0.0.1")))
        assertTrue(FirewallCore.allows(r, "dashboard", ip("127.5.5.5")))
        assertTrue(FirewallCore.allows(r, "dashboard", ip("::1")))
        assertTrue(FirewallCore.allows(r, "dashboard", ip("::ffff:127.0.0.1")))
        assertFalse(FirewallCore.allows(r, "dashboard", ip("10.9.9.9")))
    }

    @Test fun tunnelClientsAreJudgedByTheirRealAddress() {
        val cf = mapOf("cf-connecting-ip" to "203.0.113.9")
        assertEquals("203.0.113.9", FirewallCore.format(FirewallCore.clientIp("127.0.0.1", cf)!!))
        // A LAN client can't pretend to be someone else.
        assertEquals("192.168.1.4", FirewallCore.format(FirewallCore.clientIp("192.168.1.4", cf)!!))
        assertEquals("127.0.0.1", FirewallCore.format(FirewallCore.clientIp("127.0.0.1", mapOf("cf-connecting-ip" to "junk"))!!))
        assertNull(FirewallCore.clientIp("nonsense", emptyMap()))
    }

    @Test fun invalidRuleSetsAreRejected() {
        fun bad(o: JSONObject) = assertTrue(FirewallCore.rules(JSONObject().put("rules", JSONArray().put(o))).isFailure)
        bad(JSONObject().put("action", "maybe").put("cidr", "1.2.3.4"))
        bad(JSONObject().put("action", "deny").put("cidr", "1.2.3.400"))
        bad(JSONObject().put("action", "deny").put("cidr", "1.2.3.4").put("scope", "ssh"))
        val many = JSONArray().apply { repeat(FirewallCore.MAX_RULES + 1) { put(JSONObject().put("action", "deny").put("cidr", "1.2.3.4")) } }
        assertTrue(FirewallCore.rules(JSONObject().put("rules", many)).isFailure)
        assertEquals("all", rules(Triple("deny", "1.2.3.4", ""))[0].scope)
        assertTrue(FirewallCore.rules(JSONObject()).getOrThrow().isEmpty())
    }

    @Test fun webRulesBecomeCaddyMatchers() {
        assertNull(FirewallCore.caddyRules(rules(Triple("allow", "10.0.0.0/8", "dashboard"))))
        val c = FirewallCore.caddyRules(rules(Triple("deny", "203.0.113.0/24", "web"), Triple("deny", "2001:db8::/32", "all"), Triple("allow", "198.51.100.0/24", "web")))!!
        assertTrue(c.contains("client_ip 203.0.113.0/24 2001:db8::/32\n"))
        assertTrue(c.contains("not client_ip 127.0.0.0/8 ::1"))
        assertTrue(c.contains("respond @homedroid_denied \"Forbidden\" 403"))
        assertTrue(c.contains("@homedroid_unlisted not client_ip 198.51.100.0/24 127.0.0.0/8 ::1\n"))
        assertTrue(c.contains("respond @homedroid_unlisted \"Forbidden\" 403"))
        val denyOnly = FirewallCore.caddyRules(rules(Triple("deny", "203.0.113.7", "web")))!!
        assertFalse(denyOnly.contains("unlisted"))
    }
}
