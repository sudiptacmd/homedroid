package dev.homedroid

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture

/** Android-free wire format, credentials and pairing policy. */
object DesktopCore {
    private val random = SecureRandom()
    fun token(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun matches(token: String, hash: String): Boolean = MessageDigest.isEqual(
        hash(token).toByteArray(Charsets.US_ASCII), hash.toByteArray(Charsets.US_ASCII))
    fun ndjson(value: JSONObject): ByteArray = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
    fun multipart(jpeg: ByteArray, sequence: Long, timestamp: Long): ByteArray =
        ("--hdframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n" +
            "X-Frame: $sequence\r\nX-Time: $timestamp\r\n\r\n").toByteArray(Charsets.US_ASCII) + jpeg + byteArrayOf(13, 10)

    class Pairing(private val now: () -> Long = System::currentTimeMillis, private val generate: () -> String = {
        random.nextInt(1_000_000).toString().padStart(6, '0')
    }) {
        private var code: String? = null
        private var expires = 0L
        private val failures = LinkedHashMap<String, MutableList<Long>>()
        @Synchronized fun issue(): JSONObject {
            code = generate(); expires = now() + 300_000
            return JSONObject().put("code", code).put("expires", expires)
        }
        /** 200 consumes the code, 403 is a bad code, 429 is a full rate-limit window. */
        @Synchronized fun consume(address: String, attempt: String): Int {
            val time = now()
            failures.entries.removeAll { (_, times) -> times.removeAll { time - it >= 60_000 }; times.isEmpty() }
            val list = failures[address]
            if ((list?.size ?: 0) >= 5 || list == null && failures.size >= 1024) return 429
            if (code != null && time < expires && MessageDigest.isEqual(code!!.toByteArray(), attempt.toByteArray())) {
                code = null
                return 200
            }
            failures.getOrPut(address) { mutableListOf() }.add(time)
            return 403
        }
    }

    /** Only the receiving computer may acknowledge; deadlines never extend on reconnect. */
    class Acknowledgements(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
        private data class Pending(val computer: String, val deadline: Long, val result: CompletableFuture<String?>)
        private val pending = HashMap<String, Pending>()
        @Synchronized fun hasCapacity(count: Int): Boolean = pending.size + count <= 256
        @Synchronized fun isPending(id: String, computer: String): Boolean =
            pending[id]?.let { it.computer == computer && now() < it.deadline } == true
        @Synchronized fun add(id: String, computer: String): CompletableFuture<String?> {
            require(pending.size < 256) { "Too many pending actions" }
            require(id !in pending)
            return CompletableFuture<String?>().also { pending[id] = Pending(computer, now() + 10_000, it) }
        }
        @Synchronized fun ack(id: String, computer: String, error: String?): Boolean {
            val p = pending[id] ?: return false
            if (p.computer != computer) return false
            pending.remove(id)
            p.result.complete(if (now() >= p.deadline) "Computer did not respond within 10 seconds" else error)
            return true
        }
        @Synchronized fun expire() {
            val expired = pending.filterValues { it.deadline <= now() }.keys.toList()
            expired.forEach { pending.remove(it)?.result?.complete("Computer did not respond within 10 seconds") }
        }
        @Synchronized fun disconnect(computer: String) {
            pending.filterValues { it.computer == computer }.keys.toList().forEach {
                pending.remove(it)?.result?.complete("Computer disconnected")
            }
        }
    }
}
