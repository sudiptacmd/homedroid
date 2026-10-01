package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClusterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun identity(name: String) = ClusterIdentity.load(tmp.newFolder(name))

    @Test fun identityIsSelfSignedAndStable() {
        val dir = tmp.newFolder("id")
        val a = ClusterIdentity.load(dir)
        a.cert.verify(a.cert.publicKey)
        a.cert.checkValidity()
        assertTrue(Peer.FP.matches(a.fingerprint))
        assertEquals(a.fingerprint, ClusterIdentity.load(dir).fingerprint)
        assertNotEquals(a.fingerprint, identity("other").fingerprint)
    }

    @Test fun pairingCodeIsSymmetricAndSixDigits() {
        val a = "a".repeat(64); val b = "b".repeat(64); val c = "c".repeat(64)
        assertEquals(ClusterState.code(a, b), ClusterState.code(b, a))
        assertTrue(Regex("[0-9]{6}").matches(ClusterState.code(a, b)))
        assertNotEquals(ClusterState.code(a, b), ClusterState.code(a, c))
    }

    @Test fun mutualTlsCarriesTheClientIdentityAndPinsTheServer() {
        val server = identity("server"); val client = identity("client"); val stranger = identity("stranger")
        val http = Http(0, ClusterTls.server(server)) { r ->
            if (r.method == "POST") Response.json(JSONObject().put("peer", r.peer).put("body", String(r.body)))
            else Response.json(JSONObject().put("peer", r.peer))
        }
        http.start()
        try {
            val port = http.localPort
            val (status, o) = PeerHttp.json(client, "127.0.0.1", port, server.fingerprint, "GET", "/hello")
            assertEquals(200, status)
            assertEquals(client.fingerprint, o.getString("peer"))
            // Unpinned, for a first pairing request: the server's certificate is reported back.
            PeerHttp.request(client, "127.0.0.1", port, null, "GET", "/hello").use { assertEquals(server.fingerprint, it.fingerprint) }
            // Pinned to another phone: refused before anything is sent.
            assertThrows(java.io.IOException::class.java) {
                PeerHttp.json(client, "127.0.0.1", port, stranger.fingerprint, "GET", "/hello")
            }
            val (_, echo) = PeerHttp.json(client, "127.0.0.1", port, server.fingerprint, "POST", "/x", JSONObject().put("a", 1))
            assertEquals("{\"a\":1}", echo.getString("body"))
        } finally { http.stop() }
    }

    @Test fun plainHttpClientsCannotTalkToTheClusterPort() {
        val server = identity("server")
        val http = Http(0, ClusterTls.server(server)) { Response.ok() }
        http.start()
        try {
            java.net.Socket("127.0.0.1", http.localPort).use { s ->
                s.soTimeout = 4000
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val reply = s.getInputStream().readBytes()
                assertFalse(String(reply).contains("200 OK"))
            }
        } finally { http.stop() }
    }

    private fun peer(id: Char, fp: Char = id, address: String = "10.0.0.${id.code % 200}") =
        Peer.from(JSONObject().put("id", id.toString().repeat(16)).put("name", "Phone $id").put("model", "Model")
            .put("fingerprint", fp.toString().repeat(64)).put("address", address))!!

    @Test fun approvedRequestBecomesAMemberAndSurvivesARestart() {
        var saved = "{}"
        val state = ClusterState("{}", "f".repeat(64), persist = { saved = it })
        val code = state.request(peer('a'), "10.0.0.5").getOrThrow()
        assertEquals(ClusterState.code("f".repeat(64), "a".repeat(64)), code)
        assertEquals("pending", state.requestState("a".repeat(64)))
        assertEquals(1, state.pending().size)
        assertNotNull(state.approve("a".repeat(16)))
        assertEquals("approved", state.requestState("a".repeat(64)))
        assertEquals("a".repeat(64), state.byFingerprint("a".repeat(64))?.fingerprint)
        val again = ClusterState(saved, "f".repeat(64))
        assertEquals(state.id, again.id)
        assertEquals(listOf("a".repeat(16)), again.peers().map { it.id })
    }

    @Test fun rejectedAndExpiredRequestsDoNotJoin() {
        var now = 0L
        val state = ClusterState("{}", "f".repeat(64), clock = { now })
        state.request(peer('a'), "10.0.0.5").getOrThrow()
        assertTrue(state.reject("a".repeat(16)))
        assertEquals("rejected", state.requestState("a".repeat(64)))
        assertNull(state.approve("a".repeat(16)))
        state.request(peer('b'), "10.0.0.6").getOrThrow()
        now += ClusterState.PAIR_TIMEOUT + 1
        assertEquals("expired", state.requestState("b".repeat(64)))
        assertNull(state.approve("b".repeat(16)))
        assertTrue(state.peers().isEmpty())
    }

    @Test fun pairingRequestsAreRateLimitedPerAddressAndNeverWithSelf() {
        val state = ClusterState("{}", "f".repeat(64))
        assertTrue(state.request(peer('a', fp = 'f'), "10.0.0.5").isFailure)
        for (c in listOf('1', '2', '3', '4', '5')) assertTrue(state.request(peer(c), "10.0.0.9").isSuccess)
        assertTrue(state.request(peer('6'), "10.0.0.9").isFailure)
        assertTrue(state.request(peer('6'), "10.0.0.10").isSuccess)
        assertTrue(state.pending().size <= 4)
    }

    @Test fun membersFromAPeerSkipSelfAndConflictingIdentities() {
        val self = "f".repeat(64)
        val state = ClusterState("{}", self)
        state.add(peer('a'))
        val list = JSONArray()
            .put(peer('b').toJson())
            .put(peer('c', fp = 'f').toJson()) // claims this phone's certificate
            .put(JSONObject(peer('a', fp = 'd').toJson().toString())) // a known id with another certificate
            .put(JSONObject().put("id", state.id).put("fingerprint", "e".repeat(64)))
            .put(JSONObject().put("id", "../etc").put("fingerprint", "x"))
        val added = state.addMembers(list)
        assertEquals(listOf("b".repeat(16)), added.map { it.id })
        assertEquals("a".repeat(64), state.peer("a".repeat(16))!!.fingerprint)
        assertEquals(2, state.peers().size)
    }

    @Test fun peerAddressesCannotCarryPathsOrHeaders() {
        assertTrue(Peer.validHost("192.168.1.20"))
        assertTrue(Peer.validHost("fe80::1%wlan0"))
        assertFalse(Peer.validHost("evil.com/x"))
        assertFalse(Peer.validHost("a b"))
        assertFalse(Peer.validHost("a\r\nHost: x"))
        assertEquals("", Peer.from(peer('a').toJson().put("address", "x/y"))!!.address)
    }

    @Test fun requestsThroughALocalForwardKeepTheMembersRealAddress() {
        for (local in listOf("127.0.0.1", "127.1.2.3", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "0.0.0.0", "::", "localhost")) {
            assertTrue(local, Peer.isLocal(local))
        }
        for (remote in listOf("192.168.1.20", "10.0.2.2", "1::", "fe80::1%wlan0", "phone.lan", "cafe", "100")) {
            assertFalse(remote, Peer.isLocal(remote))
        }
        var saves = 0
        val state = ClusterState("{}", "f".repeat(64), persist = { saves++ })
        val p = peer('a').apply { address = "192.168.1.20" }
        state.add(p)
        saves = 0
        state.seen(p, "127.0.0.1")
        assertEquals("192.168.1.20", p.address)
        assertEquals(0, saves)
        state.seen(p, "192.168.1.30")
        assertEquals("192.168.1.30", p.address)
        assertEquals(1, saves)
    }

    @Test fun durableWritesReplaceTheFileAndLeaveNoTemporary() {
        val dir = tmp.newFolder("durable")
        val f = java.io.File(dir, "state.json")
        assertTrue(writeDurably(f, "one".toByteArray()))
        assertTrue(writeDurably(f, "two".toByteArray()))
        assertEquals("two", f.readText())
        assertEquals(listOf("state.json"), dir.list()!!.toList())
    }
}
