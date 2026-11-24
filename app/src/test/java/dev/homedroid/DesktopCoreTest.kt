package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopCoreTest {
    @Test fun pairingExpiresAtFiveMinutesAndIsSingleUse() {
        var now = 1000L
        val pairing = DesktopCore.Pairing({ now }, { "012345" })
        assertEquals("012345", pairing.issue().getString("code"))
        now += 299999
        assertEquals(200, pairing.consume("a", "012345"))
        assertEquals(403, pairing.consume("a", "012345"))
        pairing.issue(); now += 300000
        assertEquals(403, pairing.consume("b", "012345"))
    }
    @Test fun newCodeReplacesOldAndWrongCodesAreRateLimitedPerAddress() {
        var now = 0L; var code = "111111"
        val pairing = DesktopCore.Pairing({ now }, { code })
        pairing.issue(); code = "222222"; pairing.issue()
        repeat(5) { assertEquals(403, pairing.consume("a", "111111")) }
        assertEquals(429, pairing.consume("a", "222222"))
        assertEquals(403, pairing.consume("b", "111111"))
        now = 60000
        assertEquals(200, pairing.consume("a", "222222"))
    }
    @Test fun addressBucketsAreBoundedAndExpire() {
        var now = 0L
        val pairing = DesktopCore.Pairing({ now }, { "123456" })
        pairing.issue()
        repeat(1024) { assertEquals(403, pairing.consume("address-$it", "bad")) }
        assertEquals(429, pairing.consume("new", "123456"))
        now = 60000
        assertEquals(200, pairing.consume("new", "123456"))
    }
    @Test fun tokensAreRandomAndOnlyMatchingHashesAuthenticate() {
        val token = DesktopCore.token()
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(token, DesktopCore.token())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DesktopCore.hash("abc"))
        assertTrue(DesktopCore.matches(token, DesktopCore.hash(token)))
        assertFalse(DesktopCore.matches(token + "x", DesktopCore.hash(token)))
        assertFalse(DesktopCore.matches(token, ""))
    }
    @Test fun ndjsonEscapesNewlinesAndPreservesUnicode() {
        val value = JSONObject().put("type", "action").put("text", "Hello\nবাংলা\r\n")
        val wire = DesktopCore.ndjson(value).toString(Charsets.UTF_8)
        assertTrue(wire.endsWith("\n"))
        assertEquals(1, wire.count { it == '\n' })
        assertEquals(value.getString("text"), JSONObject(wire).getString("text"))
    }
    @Test fun multipartHasExactCrLfHeadersAndBinaryPayload() {
        val jpeg = byteArrayOf(-1, -40, 0, 13, 10, -1, -39)
        val expected = "--hdframe\r\nContent-Type: image/jpeg\r\nContent-Length: 7\r\nX-Frame: 42\r\nX-Time: 12345\r\n\r\n".toByteArray() + jpeg + byteArrayOf(13, 10)
        assertArrayEquals(expected, DesktopCore.multipart(jpeg, 42, 12345))
    }
    @Test fun acknowledgementsRequireRecipientAndExpireAtTenSeconds() {
        var now = 0L
        val acks = DesktopCore.Acknowledgements { now }
        val good = acks.add("a-1", "c-1")
        assertFalse(acks.ack("a-1", "c-2", null)); assertFalse(good.isDone)
        assertTrue(acks.ack("a-1", "c-1", null)); assertNull(good.get())
        assertFalse(acks.ack("a-1", "c-1", null))
        val expired = acks.add("a-2", "c-1")
        now = 9999; acks.expire(); assertFalse(expired.isDone)
        now = 10000; acks.expire(); assertTrue(expired.get()!!.contains("10 seconds"))
        val late = acks.add("a-3", "c-1"); now += 10000
        assertTrue(acks.ack("a-3", "c-1", null)); assertNotNull(late.get())
    }
    @Test fun expiredQueuedActionsCannotBeDispatchedAndCapacityIsBounded() {
        var now = 0L
        val acks = DesktopCore.Acknowledgements { now }
        repeat(256) { acks.add("a-$it", "c-1") }
        assertFalse(acks.hasCapacity(1)); assertTrue(acks.isPending("a-0", "c-1"))
        assertFalse(acks.isPending("a-0", "c-2"))
        now = 10000
        assertFalse(acks.isPending("a-0", "c-1"))
        acks.expire(); assertTrue(acks.hasCapacity(256))
    }
    @Test fun failuresAndDisconnectsCompleteOnlyAffectedActions() {
        val acks = DesktopCore.Acknowledgements()
        val one = acks.add("a-1", "c-1"); val two = acks.add("a-2", "c-2")
        acks.disconnect("c-1"); assertEquals("Computer disconnected", one.get()); assertFalse(two.isDone)
        assertTrue(acks.ack("a-2", "c-2", "No such app")); assertEquals("No such app", two.get())
    }
}
