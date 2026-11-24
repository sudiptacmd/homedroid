package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DdnsCoreTest {
    private val duck = JSONObject().put("provider", "duckdns").put("domain", "MyHome.duckdns.org, other").put("token", "tok-123")
    private val cf = JSONObject().put("provider", "cloudflare").put("token", "cf-token").put("zone", "Example.com")
        .put("record", "home.example.com").put("proxied", true).put("ipv6", true)

    @Test fun parsesPublicAddresses() {
        assertEquals("203.0.113.9", DdnsCore.parseIp("203.0.113.9\n", false))
        assertEquals("2001:db8::5", DdnsCore.parseIp(" 2001:0db8::5 \n", true))
        assertNull(DdnsCore.parseIp("2001:db8::5", false))
        assertNull(DdnsCore.parseIp("203.0.113.9", true))
        for (bad in listOf("", "<html>error</html>", "192.168.1.5", "10.0.0.1", "100.64.1.1", "127.0.0.1", "fe80::1", "fd00::1", "::1"))
            assertNull(bad, DdnsCore.parseIp(bad, ':' in bad))
    }

    @Test fun entriesAreValidatedAndTokensKept() {
        val d = DdnsCore.entry(duck, "id1", null).getOrThrow()
        assertEquals("myhome,other", d.getString("domain"))
        assertTrue(d.getBoolean("enabled"))
        val c = DdnsCore.entry(cf, "id2", null).getOrThrow()
        assertEquals("example.com", c.getString("zone"))
        assertTrue(c.getBoolean("proxied"))
        // A masked or empty token keeps the stored one; switching provider needs a new one.
        val old = JSONObject(d.toString()).put("result", "ok").put("ip", "1.2.3.4")
        val kept = DdnsCore.entry(JSONObject(duck.toString()).put("token", DdnsCore.MASK), "id1", old).getOrThrow()
        assertEquals("tok-123", kept.getString("token"))
        assertEquals("1.2.3.4", kept.getString("ip"))
        assertTrue(DdnsCore.entry(JSONObject(cf.toString()).put("token", ""), "id1", old).isFailure)
        // Changing the name forgets the old result.
        assertFalse(DdnsCore.entry(JSONObject(duck.toString()).put("domain", "new"), "id1", old).getOrThrow().has("ip"))
        for (bad in listOf(
            JSONObject(duck.toString()).put("domain", "bad_name"), JSONObject(duck.toString()).put("token", "a b"),
            JSONObject(cf.toString()).put("record", "nodot"), JSONObject(cf.toString()).put("zone", "zz"),
            JSONObject(duck.toString()).put("provider", "noip"),
        )) assertTrue(bad.toString(), DdnsCore.entry(bad, "x", null).isFailure)
        assertTrue(DdnsCore.entry(JSONObject(cf.toString()).put("zone", "0123456789abcdef0123456789abcdef"), "x", null).isSuccess)
    }

    @Test fun tokensAreNeverShown() {
        val p = DdnsCore.public(DdnsCore.entry(cf, "id", null).getOrThrow())
        assertEquals(DdnsCore.MASK, p.getString("token"))
        assertFalse(p.toString().contains("cf-token"))
    }

    @Test fun updatesOnlyWhenNeeded() {
        val now = 10 * DdnsCore.REFRESH_MS
        val e = DdnsCore.entry(cf, "id", null).getOrThrow()
        assertTrue("never updated", DdnsCore.due(e, "1.2.3.4", null, now))
        e.put("result", "ok").put("ip", "1.2.3.4").put("ipv6Address", "2001:db8::1").put("lastUpdate", now - 1000)
        assertFalse(DdnsCore.due(e, "1.2.3.4", "2001:db8::1", now))
        assertFalse("no IPv6 found this time", DdnsCore.due(e, "1.2.3.4", null, now))
        assertTrue(DdnsCore.due(e, "1.2.3.5", "2001:db8::1", now))
        assertTrue(DdnsCore.due(e, "1.2.3.4", "2001:db8::2", now))
        assertFalse("no address at all", DdnsCore.due(e, null, null, now))
        assertTrue("daily refresh", DdnsCore.due(e, "1.2.3.4", "2001:db8::1", now + DdnsCore.REFRESH_MS))
        assertTrue("retry after failure", DdnsCore.due(JSONObject(e.toString()).put("result", "error"), "1.2.3.4", "2001:db8::1", now))
        assertFalse("disabled", DdnsCore.due(JSONObject(e.toString()).put("enabled", false).put("result", "error"), "9.9.9.9", null, now))
    }

    @Test fun buildsProviderRequests() {
        val d = DdnsCore.entry(duck.put("ipv6", true), "id", null).getOrThrow()
        assertEquals("https://www.duckdns.org/update?domains=myhome%2Cother&token=tok-123&ip=1.2.3.4&ipv6=2001%3Adb8%3A%3A1",
            DdnsCore.duckUrl(d, "1.2.3.4", "2001:db8::1"))
        assertEquals("https://www.duckdns.org/update?domains=myhome%2Cother&token=tok-123&ip=", DdnsCore.duckUrl(d, null, null))
        val rec = DdnsCore.cloudflareRecord(DdnsCore.entry(cf, "id", null).getOrThrow(), "AAAA", "2001:db8::1")
        assertEquals("AAAA", rec.getString("type"))
        assertEquals("home.example.com", rec.getString("name"))
        assertTrue(rec.getBoolean("proxied"))
        assertEquals(1, rec.getInt("ttl"))
        assertEquals("Invalid API token", DdnsCore.cloudflareError("""{"success":false,"errors":[{"code":1000,"message":"Invalid API token"}]}""", 403))
        assertEquals("Cloudflare answered 500", DdnsCore.cloudflareError("oops", 500))
    }
}
