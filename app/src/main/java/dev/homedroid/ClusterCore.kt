package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * A phone's identity in a cluster: an EC P-256 key and a self-signed certificate, made on
 * first use. Phones know each other by the SHA-256 fingerprint of that certificate, learned
 * during pairing, so no certificate authority is involved.
 */
class ClusterIdentity private constructor(val key: PrivateKey, val cert: X509Certificate) {
    val fingerprint: String = fingerprint(cert)

    companion object {
        fun load(dir: File): ClusterIdentity {
            dir.mkdirs()
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            if (!keyFile.exists() || !certFile.exists()) {
                val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                val der = selfSigned(pair.public.encoded, pair.private)
                // Written to temporary files first, so a crash never leaves a key without its certificate.
                File(dir, "cert.der.tmp").apply { writeBytes(der) }.renameTo(certFile)
                File(dir, "key.pk8.tmp").apply { writeBytes(pair.private.encoded) }.renameTo(keyFile)
            }
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            return ClusterIdentity(key, parse(certFile.readBytes()))
        }

        fun parse(der: ByteArray) =
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

        fun fingerprint(cert: X509Certificate): String = sha256(cert.encoded)

        /** A minimal X.509 v1 certificate, valid 2020–2099, signed by its own key. */
        internal fun selfSigned(publicKey: ByteArray, key: PrivateKey): ByteArray {
            val algorithm = Der.seq(Der.oid(1, 2, 840, 10045, 4, 3, 2)) // ecdsa-with-SHA256
            val name = Der.seq(Der.set(Der.seq(Der.oid(2, 5, 4, 3), Der.utf8("Homedroid cluster node"))))
            val serial = BigInteger(1, ByteArray(16).also(SecureRandom()::nextBytes))
            val tbs = Der.seq(
                Der.tlv(0x02, serial.toByteArray()),
                algorithm,
                name,
                Der.seq(Der.tlv(0x17, "200101000000Z".toByteArray()), Der.tlv(0x18, "20991231235959Z".toByteArray())),
                name,
                publicKey, // already a DER SubjectPublicKeyInfo
            )
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(tbs); sign() }
            return Der.seq(tbs, algorithm, Der.tlv(0x03, byteArrayOf(0) + signature))
        }
    }
}

/** Just enough DER to write a certificate. */
internal object Der {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    fun seq(vararg items: ByteArray) = tlv(0x30, items.reduce(ByteArray::plus))
    fun set(vararg items: ByteArray) = tlv(0x31, items.reduce(ByteArray::plus))
    fun utf8(s: String) = tlv(0x0c, s.toByteArray())

    fun oid(vararg parts: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            var v = p
            val chunk = ArrayDeque<Int>()
            do { chunk.addFirst((v and 0x7f).toInt()); v = v shr 7 } while (v > 0)
            chunk.forEachIndexed { i, b -> out.write(if (i < chunk.size - 1) b or 0x80 else b) }
        }
        return tlv(0x06, out.toByteArray())
    }
}

fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

/**
 * Mutual TLS between phones. Both sides always present their certificate. The server accepts
 * any client certificate at the TLS layer (a stranger may ask to pair) and checks who it is per
 * request; a client pins the server's fingerprint, except on the very first pairing request,
 * where it records it instead and the user compares the resulting code on both phones.
 */
object ClusterTls {
    private val PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")

    private class KeyManager(private val id: ClusterIdentity) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(id.cert)
        override fun getPrivateKey(alias: String?) = id.key
    }

    // Deliberately not the system's CA trust: phones pin each other's self-signed certificates
    // (servers) or check the client's fingerprint on every request (Cluster.handle).
    @android.annotation.SuppressLint("CustomX509TrustManager")
    private class Trust(private val pin: String?, private val seen: (String) -> Unit) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
            if (chain.isEmpty()) throw CertificateException("no client certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            if (chain.isEmpty()) throw CertificateException("no server certificate")
            val fp = ClusterIdentity.fingerprint(chain[0])
            if (pin != null && fp != pin) throw CertificateException("this is not the phone that was paired")
            seen(fp)
        }

        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    fun server(id: ClusterIdentity): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(KeyManager(id)), arrayOf(Trust(null) {}), SecureRandom()) }

    fun configureServer(socket: javax.net.ssl.SSLServerSocket) {
        socket.needClientAuth = true
        socket.enabledProtocols = PROTOCOLS.filter { it in socket.supportedProtocols }.toTypedArray()
    }

    /** The fingerprint of the certificate the client presented, once the handshake is done. */
    fun clientFingerprint(socket: SSLSocket): String? = try {
        (socket.session.peerCertificates.firstOrNull() as? X509Certificate)?.let(ClusterIdentity::fingerprint)
    } catch (_: Exception) { null }

    fun connect(id: ClusterIdentity, host: String, port: Int, pin: String?, timeoutMs: Int = 4000): Pair<SSLSocket, String> {
        var server: String? = null
        val ctx = SSLContext.getInstance("TLS").apply { init(arrayOf(KeyManager(id)), arrayOf(Trust(pin) { server = it }), SecureRandom()) }
        val raw = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs); soTimeout = timeoutMs * 2 }
        val socket = try {
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                enabledProtocols = PROTOCOLS.filter { it in supportedProtocols }.toTypedArray()
                useClientMode = true
                startHandshake()
            }
        } catch (e: Exception) {
            raw.close()
            throw e
        }
        return socket to (server ?: throw IOException("no server certificate"))
    }

    private const val ALIAS = "node"
}

/** A response from another phone. The body streams; close it (or the connection) when done. */
class PeerResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    val socket: SSLSocket,
    /** The server's certificate fingerprint. */
    val fingerprint: String,
) : java.io.Closeable {
    fun text(): String = body.use { it.readBytes().toString(Charsets.UTF_8) }
    fun json(): JSONObject = JSONObject(text().ifEmpty { "{}" })
    override fun close() = try { socket.close() } catch (_: IOException) {}
}

/** HTTP/1.1 over the cluster's TLS: one request per connection, like the server. */
object PeerHttp {
    fun request(
        id: ClusterIdentity, host: String, port: Int, pin: String?,
        method: String, path: String,
        headers: Map<String, String> = emptyMap(),
        length: Long = 0, body: ((OutputStream) -> Unit)? = null,
        timeoutMs: Int = 4000, readTimeoutMs: Int = 15_000,
    ): PeerResponse {
        val (socket, fp) = ClusterTls.connect(id, host, port, pin, timeoutMs)
        try {
            socket.soTimeout = readTimeoutMs
            val out = socket.outputStream.buffered()
            val head = StringBuilder("$method $path HTTP/1.1\r\nHost: ${if (':' in host) "[$host]" else host}:$port\r\n")
            for ((k, v) in headers) {
                require(k.none { it == '\r' || it == '\n' || it == ':' } && v.none { it == '\r' || it == '\n' }) { "bad header" }
                head.append(k).append(": ").append(v).append("\r\n")
            }
            if (length > 0 || method in setOf("POST", "PUT")) head.append("Content-Length: ").append(length).append("\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            body?.invoke(out)
            out.flush()
            val input = BufferedInputStream(socket.inputStream)
            val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad response from the phone")
            val h = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("truncated response")
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                if (h.size > 64) throw IOException("too many headers")
            }
            val size = h["content-length"]?.toLongOrNull()
            val stream = if (size != null && status != 101) Limited(input, size) else input
            return PeerResponse(status, h, stream, socket, fp)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    fun json(
        id: ClusterIdentity, host: String, port: Int, pin: String?, method: String, path: String,
        body: JSONObject? = null, timeoutMs: Int = 4000, readTimeoutMs: Int = 15_000,
    ): Pair<Int, JSONObject> {
        val bytes = body?.toString()?.toByteArray()
        request(
            id, host, port, pin, method, path,
            if (bytes != null) mapOf("Content-Type" to "application/json") else emptyMap(),
            bytes?.size?.toLong() ?: 0, bytes?.let { b -> { it.write(b) } }, timeoutMs, readTimeoutMs,
        ).use { r ->
            val text = r.text()
            val json = try { JSONObject(text.ifEmpty { "{}" }) } catch (_: Exception) { JSONObject().put("error", "invalid response") }
            return r.status to json
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("line too long")
            sb.append(c.toChar())
        }
    }

    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}

/** Another phone in the cluster, trusted by its certificate [fingerprint]. */
class Peer(
    val id: String,
    @Volatile var name: String,
    @Volatile var model: String,
    val fingerprint: String,
    @Volatile var address: String,
    @Volatile var port: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("model", model)
        .put("fingerprint", fingerprint).put("address", address).put("port", port)

    companion object {
        fun from(o: JSONObject): Peer? {
            val id = o.optString("id")
            val fp = o.optString("fingerprint")
            if (!ID.matches(id) || !FP.matches(fp)) return null
            return Peer(id, cleanName(o.optString("name")).ifEmpty { "Phone" }, cleanName(o.optString("model")), fp,
                o.optString("address").takeIf(::validHost) ?: "", o.optInt("port", ClusterState.PORT).takeIf { it in 1..65535 } ?: ClusterState.PORT)
        }

        val ID = Regex("[0-9a-f]{16}")
        val FP = Regex("[0-9a-f]{64}")
        fun cleanName(s: String) = s.filter { !it.isISOControl() }.trim().take(40)

        /** An IPv4/IPv6 literal or a plain hostname: nothing that could smuggle a path or header. */
        fun validHost(s: String) = s.length in 1..253 && Regex("[0-9A-Za-z.:%-]+").matches(s)
    }
}

/**
 * Who is in the cluster, and pairing requests waiting for the user. Pure state, saved through
 * [persist], so the rules can be tested off the phone.
 *
 * Pairing: phone A asks phone B; B shows a 6-digit code made from both certificates and waits
 * for the user to approve it on B. Each phone's code only matches if nobody sits in between.
 * Once approved, members vouch for each other: a phone the user added anywhere is trusted
 * everywhere.
 */
class ClusterState(
    saved: String,
    private val selfFingerprint: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persist: (String) -> Unit = {},
) {
    class Pending(val peer: Peer, val code: String, val created: Long) {
        @Volatile var state = "pending" // pending, approved, rejected
    }

    val id: String
    @Volatile var name: String; private set
    private val peers = LinkedHashMap<String, Peer>()
    private val pending = LinkedHashMap<String, Pending>() // by fingerprint
    private val attempts = HashMap<String, MutableList<Long>>() // by address

    init {
        val o = try { JSONObject(saved) } catch (_: Exception) { JSONObject() }
        id = o.optString("id").takeIf(Peer.ID::matches) ?: ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        name = Peer.cleanName(o.optString("name"))
        o.optJSONArray("peers")?.let { a -> for (i in 0 until a.length()) Peer.from(a.getJSONObject(i))?.let { peers[it.id] = it } }
        if (!o.has("id")) save()
    }

    @Synchronized fun peers(): List<Peer> = peers.values.toList()
    @Synchronized fun peer(id: String) = peers[id]
    @Synchronized fun byFingerprint(fp: String?) = fp?.let { f -> peers.values.firstOrNull { it.fingerprint == f } }

    @Synchronized fun rename(n: String) {
        name = Peer.cleanName(n)
        save()
    }

    /** A pairing request from [peer]; returns the code to compare, or an error message. */
    @Synchronized fun request(peer: Peer, address: String): Result<String> {
        val now = clock()
        expire(now)
        if (peer.fingerprint == selfFingerprint || peer.id == id) return Result.failure(IllegalArgumentException("A phone can't pair with itself"))
        val recent = attempts.getOrPut(address) { mutableListOf() }.apply { removeAll { now - it > RATE_WINDOW } }
        if (recent.size >= RATE_LIMIT && pending[peer.fingerprint] == null) {
            return Result.failure(IllegalStateException("Too many pairing requests; try again in a few minutes"))
        }
        pending[peer.fingerprint]?.let { if (it.state == "pending") return Result.success(it.code) }
        recent.add(now)
        while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
        val code = code(selfFingerprint, peer.fingerprint)
        pending[peer.fingerprint] = Pending(peer, code, now)
        return Result.success(code)
    }

    @Synchronized fun pending(): List<Pending> { expire(clock()); return pending.values.filter { it.state == "pending" } }

    /** The state of [fingerprint]'s request: pending, approved, rejected or expired. */
    @Synchronized fun requestState(fingerprint: String): String {
        expire(clock())
        if (byFingerprint(fingerprint) != null) return "approved"
        return pending[fingerprint]?.state ?: "expired"
    }

    /** Approves a request by the requesting phone's id; returns it, now a member. */
    @Synchronized fun approve(peerId: String): Peer? {
        expire(clock())
        val p = pending.values.firstOrNull { it.peer.id == peerId && it.state == "pending" } ?: return null
        p.state = "approved"
        add(p.peer)
        save()
        return p.peer
    }

    @Synchronized fun reject(peerId: String): Boolean {
        val p = pending.values.firstOrNull { it.peer.id == peerId && it.state == "pending" } ?: return false
        p.state = "rejected"
        return true
    }

    /** Adds members a trusted phone told us about; returns the ones that were new. */
    @Synchronized fun addMembers(members: JSONArray?): List<Peer> {
        val added = mutableListOf<Peer>()
        for (i in 0 until (members?.length() ?: 0)) {
            val p = Peer.from(members!!.optJSONObject(i) ?: continue) ?: continue
            if (p.id == id || p.fingerprint == selfFingerprint) continue
            val known = peers[p.id]
            if (known == null) { if (add(p)) added += p }
            else if (known.fingerprint == p.fingerprint) {
                if (known.address.isEmpty() && p.address.isNotEmpty()) known.address = p.address
            }
        }
        if (added.isNotEmpty()) save()
        return added
    }

    /** Adds [p] unless its id or certificate is already taken by another member. */
    @Synchronized fun add(p: Peer): Boolean {
        if (peers.values.any { it.fingerprint == p.fingerprint && it.id != p.id }) return false
        val known = peers[p.id]
        if (known != null && known.fingerprint != p.fingerprint) return false
        peers[p.id] = p
        save()
        return true
    }

    @Synchronized fun remove(peerId: String): Boolean = (peers.remove(peerId) != null).also { if (it) save() }

    @Synchronized fun clear() { peers.clear(); save() }

    /** Records where a member was last heard from; only saved when it changed. */
    @Synchronized fun seen(p: Peer, address: String, info: JSONObject? = null) {
        var changed = false
        if (Peer.validHost(address) && address != p.address) { p.address = address; changed = true }
        info?.optString("name")?.let(Peer::cleanName)?.takeIf { it.isNotEmpty() && it != p.name }?.let { p.name = it; changed = true }
        info?.optString("model")?.let(Peer::cleanName)?.takeIf { it.isNotEmpty() && it != p.model }?.let { p.model = it; changed = true }
        if (changed) save()
    }

    private fun expire(now: Long) {
        pending.entries.removeAll { now - it.value.created > PAIR_TIMEOUT }
    }

    @Synchronized private fun save() = persist(JSONObject().put("id", id).put("name", name)
        .put("peers", JSONArray().apply { peers.values.forEach { put(it.toJson()) } }).toString())

    companion object {
        const val PORT = 8801
        /** Bumped when phones on different versions could no longer understand each other. */
        const val PROTOCOL = 1
        const val PAIR_TIMEOUT = 3 * 60_000L
        private const val MAX_PENDING = 4
        private const val RATE_LIMIT = 5
        private const val RATE_WINDOW = 10 * 60_000L

        /** The 6-digit code both phones show, from both certificate fingerprints. */
        fun code(a: String, b: String): String {
            val (x, y) = listOf(a, b).sorted()
            val h = MessageDigest.getInstance("SHA-256").digest("homedroid-pair-v1|$x|$y".toByteArray())
            val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
            return "%06d".format(n % 1_000_000)
        }
    }
}
