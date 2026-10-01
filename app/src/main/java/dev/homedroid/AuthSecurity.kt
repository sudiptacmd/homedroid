package dev.homedroid

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/** One account-wide budget plus a per-peer limit, shared by every password entry point. */
class AuthSecurity(
    saved: String = "{}",
    private val clock: () -> Long = System::currentTimeMillis,
    private val persist: (String) -> Unit = {},
) {
    data class Result(val accepted: Boolean, val retrySeconds: Long = 0)
    private val state = try { JSONObject(saved) } catch (_: Exception) { JSONObject() }
    private val peers = state.optJSONObject("peers") ?: JSONObject().also { state.put("peers", it) }
    private val sessions = state.optJSONObject("sessions") ?: JSONObject().also { state.put("sessions", it) }

    @Synchronized fun password(peer: String, verify: () -> Boolean): Result {
        val now = clock()
        prune(now)
        val local = peers.optJSONObject(peer) ?: JSONObject().put("count", 0).put("until", now + COOLDOWN)
        val global = state.optJSONObject("global") ?: JSONObject().put("count", 0).put("until", now + COOLDOWN)
        val until = maxOf(if (local.optInt("count") >= PEER_LIMIT) local.optLong("until") else 0,
            if (global.optInt("count") >= ACCOUNT_LIMIT) global.optLong("until") else 0)
        if (until > now) return Result(false, (until - now + 999) / 1000)
        if (verify()) {
            if (peers.has(peer)) { peers.remove(peer); persist(state.toString()) }
            return Result(true)
        }
        local.put("count", local.optInt("count") + 1)
        global.put("count", global.optInt("count") + 1)
        if (local.getInt("count") == PEER_LIMIT) local.put("until", now + COOLDOWN)
        if (global.getInt("count") == ACCOUNT_LIMIT) global.put("until", now + COOLDOWN)
        peers.put(peer, local)
        state.put("global", global)
        persist(state.toString())
        return Result(false, if (local.getInt("count") >= PEER_LIMIT || global.getInt("count") >= ACCOUNT_LIMIT) COOLDOWN / 1000 else 0)
    }

    @Synchronized fun session(token: String?): Boolean {
        if (token == null || !Regex("[0-9a-f]{64}").matches(token)) return false
        val key = hash(token)
        val expires = sessions.optLong(key)
        if (expires > clock() && expires <= clock() + SESSION_MS) return true
        if (sessions.has(key)) { sessions.remove(key); persist(state.toString()) }
        return false
    }

    @Synchronized fun newSession(): String {
        prune(clock())
        while (sessions.length() >= 20) sessions.remove(sessions.keys().asSequence().minBy { sessions.getLong(it) })
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        sessions.put(hash(token), clock() + SESSION_MS)
        persist(state.toString())
        return token
    }

    @Synchronized fun logout(token: String?) { token?.let { sessions.remove(hash(it)); persist(state.toString()) } }
    @Synchronized fun revokeSessions() { sessions.keys().asSequence().toList().forEach(sessions::remove); persist(state.toString()) }

    private fun prune(now: Long) {
        peers.keys().asSequence().toList().filter { peers.getJSONObject(it).optLong("until") <= now }.forEach(peers::remove)
        if ((state.optJSONObject("global")?.optLong("until") ?: 0) <= now) state.remove("global")
        sessions.keys().asSequence().toList().filter { sessions.optLong(it) <= now }.forEach(sessions::remove)
    }

    companion object {
        const val PEER_LIMIT = 5
        const val ACCOUNT_LIMIT = 50
        const val COOLDOWN = 15 * 60_000L
        const val SESSION_MS = 24 * 3600_000L
        private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun bearer(headers: Map<String, String>): String? {
            val value = headers["authorization"] ?: return null
            return if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7).takeIf { it.isNotEmpty() && it.length <= 128 } else null
        }

        /** Custom headers cannot be submitted by cross-origin forms; no CORS is enabled. */
        fun browserAllowed(method: String, headers: Map<String, String>, websocket: Boolean = false): Boolean {
            if (headers["sec-fetch-site"] == "cross-site") return false
            val origin = headers["origin"]
            if (origin != null && !sameAuthority(origin, headers["host"])) return false
            if (websocket) return origin != null || bearer(headers) != null
            return method in setOf("GET", "HEAD") || headers["x-homedroid-request"] == "1" || bearer(headers) != null
        }

        private fun sameAuthority(origin: String, host: String?): Boolean = try {
            val uri = URI(origin)
            uri.scheme in setOf("http", "https") && uri.rawAuthority?.equals(host, ignoreCase = true) == true &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty().isEmpty()
        } catch (_: Exception) { false }

        fun secureCookie(headers: Map<String, String>): Boolean =
            headers["origin"]?.let { it.startsWith("https://") && sameAuthority(it, headers["host"]) } == true
    }
}
