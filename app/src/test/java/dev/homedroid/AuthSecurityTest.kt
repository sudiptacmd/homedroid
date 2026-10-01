package dev.homedroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Executors

class AuthSecurityTest {
    @Test fun lockoutIsSharedPersistentAndDoesNotExtendOnRetries() {
        var now = 1000L
        var saved = "{}"
        var auth = AuthSecurity(clock = { now }, persist = { saved = it })
        repeat(4) { assertEquals(0L, auth.password("peer") { false }.retrySeconds) }
        assertEquals(900L, auth.password("peer") { false }.retrySeconds)
        auth = AuthSecurity(saved, clock = { now })
        now += 1000
        assertEquals(899L, auth.password("peer") { fail("Blocked requests must not verify passwords"); true }.retrySeconds)
        now += AuthSecurity.COOLDOWN
        assertTrue(auth.password("peer") { true }.accepted)
    }

    @Test fun rotatingAddressesCannotBypassAccountBudget() {
        val auth = AuthSecurity()
        repeat(49) { assertEquals(0L, auth.password("peer$it") { false }.retrySeconds) }
        assertEquals(900L, auth.password("peer49") { false }.retrySeconds)
        assertTrue(auth.password("new-peer") { true }.retrySeconds > 0)
    }

    @Test fun parallelFailuresStopAfterFiveVerifications() {
        val auth = AuthSecurity()
        val executor = Executors.newFixedThreadPool(12)
        var checked = 0
        try {
            val tasks = (1..24).map { executor.submit<AuthSecurity.Result> { auth.password("peer") { checked++; false } } }
            tasks.forEach { it.get() }
            assertEquals(5, checked)
        } finally { executor.shutdownNow() }
    }

    @Test fun validSessionSurvivesLockoutButExpiresAndCanBeRevoked() {
        var now = 1000L
        var saved = "{}"
        val auth = AuthSecurity(clock = { now }, persist = { saved = it })
        val token = auth.newSession()
        assertFalse(saved.contains(token))
        repeat(5) { auth.password("peer") { false } }
        assertTrue(auth.session(token))
        assertTrue(AuthSecurity(saved, clock = { now }).session(token))
        now += AuthSecurity.SESSION_MS
        assertFalse(auth.session(token))
        val other = auth.newSession()
        auth.revokeSessions()
        assertFalse(auth.session(other))
    }

    @Test fun logoutAndSessionCountAreBounded() {
        var now = 1000L
        val auth = AuthSecurity(clock = { now++ })
        val first = auth.newSession()
        repeat(20) { auth.newSession() }
        assertFalse(auth.session(first))
        val last = auth.newSession()
        auth.logout(last)
        assertFalse(auth.session(last))
        assertFalse(auth.session("bad-cookie"))
    }

    @Test fun browserWritesNeedHeaderAndWebsocketsNeedOrigin() {
        val headers = mapOf("host" to "phone:8800", "origin" to "http://phone:8800", "x-homedroid-request" to "1")
        assertTrue(AuthSecurity.browserAllowed("POST", headers))
        assertFalse(AuthSecurity.browserAllowed("POST", headers - "x-homedroid-request"))
        assertFalse(AuthSecurity.browserAllowed("GET", headers + ("origin" to "http://phone:8080")))
        assertFalse(AuthSecurity.browserAllowed("GET", headers + ("origin" to "null")))
        assertFalse(AuthSecurity.browserAllowed("GET", headers + ("sec-fetch-site" to "cross-site")))
        assertFalse(AuthSecurity.browserAllowed("GET", headers - "origin", websocket = true))
        assertTrue(AuthSecurity.browserAllowed("GET", headers, websocket = true))
        assertTrue(AuthSecurity.browserAllowed("POST", mapOf("authorization" to "Bearer secret")))
        assertNull(AuthSecurity.bearer(mapOf("authorization" to "Basic secret")))
        assertFalse(AuthSecurity.secureCookie(headers))
        assertTrue(AuthSecurity.secureCookie(headers + ("origin" to "https://phone:8800")))
    }

    @Test fun ambiguousHttpFramingIsRejected() {
        val bad = listOf("Content-Length: -1", "Content-Length: nope", "Content-Length: 1\r\nContent-Length: 1",
            "Transfer-Encoding: chunked", "Content-Length: 1\r\nTransfer-Encoding: chunked", "Host: other", "Bad Header: value")
        bad.forEach { extra ->
            try {
                parse("GET / HTTP/1.1\r\nHost: phone\r\n$extra\r\n\r\n")
                fail("Accepted ambiguous headers: $extra")
            } catch (_: IOException) {}
        }
        assertEquals(2L, parse("POST /api/login HTTP/1.1\r\nHost: phone\r\nContent-Length: 2\r\n\r\n{}")!!.length)
        try { parse("GET / HTTP/1.1\r\nHost: phone\r\n" + (1..65).joinToString("\r\n") { "X-$it: a" } + "\r\n\r\n"); fail() }
        catch (_: IOException) {}
    }
    private fun parse(raw: String) = Http(0) { Response.ok() }.read(ByteArrayInputStream(raw.toByteArray()), "peer")
}
