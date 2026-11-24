package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An address block; [addr] is 4 bytes for IPv4 and 16 for IPv6, with the host bits cleared. */
class Cidr(val addr: ByteArray, val prefix: Int) {
    fun contains(ip: ByteArray): Boolean {
        if (ip.size != addr.size) return false
        for (bit in 0 until prefix) {
            val mask = 0x80 ushr (bit % 8)
            if (ip[bit / 8].toInt() and mask != addr[bit / 8].toInt() and mask) return false
        }
        return true
    }

    override fun toString() = FirewallCore.format(addr) + if (prefix == addr.size * 8) "" else "/$prefix"
}

/** Address parsing and rule evaluation for [Firewall] and the Caddy config, free of Android so it can be tested. */
object FirewallCore {
    val SCOPES = setOf("dashboard", "web", "all")
    const val MAX_RULES = 200
    private val LOOPBACK = listOf(parseCidr("127.0.0.0/8")!!, parseCidr("::1")!!)

    /** An IPv4 or IPv6 literal (zone and brackets allowed); IPv4-mapped IPv6 comes back as IPv4. */
    fun parseIp(raw: String): ByteArray? {
        val s = raw.trim().removePrefix("[").substringBefore(']').substringBefore('%')
        if (':' !in s) return parseV4(s)
        val v6 = parseV6(s) ?: return null
        val mapped = (0 until 10).all { v6[it] == 0.toByte() } && v6[10] == 0xff.toByte() && v6[11] == 0xff.toByte()
        return if (mapped) v6.copyOfRange(12, 16) else v6
    }

    private fun parseV4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        return ByteArray(4) { i ->
            val p = parts[i]
            // Leading zeros are ambiguous (some tools read them as octal), so refuse them.
            if (!Regex("0|[1-9][0-9]{0,2}").matches(p) || p.toInt() > 255) return null
            p.toInt().toByte()
        }
    }

    private fun parseV6(s: String): ByteArray? {
        val halves = s.split("::")
        if (halves.size > 2) return null
        // Each side is a list of 16-bit groups; an IPv4 tail counts as two.
        fun groups(part: String, last: Boolean): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val items = part.split(':')
            return items.flatMapIndexed { i, g ->
                if (last && i == items.lastIndex && '.' in g) {
                    val v4 = parseV4(g) ?: return null
                    listOf((v4[0].toInt() and 0xff) shl 8 or (v4[1].toInt() and 0xff), (v4[2].toInt() and 0xff) shl 8 or (v4[3].toInt() and 0xff))
                } else {
                    if (!Regex("[0-9a-fA-F]{1,4}").matches(g)) return null
                    listOf(g.toInt(16))
                }
            }
        }
        val head = groups(halves[0], halves.size == 1) ?: return null
        val tail = if (halves.size == 2) groups(halves[1], true) ?: return null else emptyList()
        val all = if (halves.size == 2) {
            if (head.size + tail.size > 7) return null
            head + List(8 - head.size - tail.size) { 0 } + tail
        } else head
        if (all.size != 8) return null
        return ByteArray(16) { i -> (all[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
    }

    /** "10.0.0.0/8", "2001:db8::/32" or a single address; host bits are cleared. */
    fun parseCidr(raw: String): Cidr? {
        val s = raw.trim()
        val ip = parseIp(s.substringBefore('/')) ?: return null
        val bits = ip.size * 8
        var prefix = bits
        if ('/' in s) {
            val p = s.substringAfter('/')
            if (!Regex("[0-9]{1,3}").matches(p)) return null
            prefix = p.toInt()
            // "::ffff:10.0.0.0/104" was parsed as IPv4, so its prefix shrinks by the 96 mapped bits.
            if (ip.size == 4 && ':' in s.substringBefore('/')) prefix -= 96
            if (prefix !in 0..bits) return null
        }
        val masked = ip.copyOf()
        for (bit in prefix until bits) masked[bit / 8] = (masked[bit / 8].toInt() and (0x80 ushr (bit % 8)).inv()).toByte()
        return Cidr(masked, prefix)
    }

    /** Dotted IPv4, or IPv6 with the longest run of zero groups shortened to "::". */
    fun format(ip: ByteArray): String {
        if (ip.size == 4) return ip.joinToString(".") { (it.toInt() and 0xff).toString() }
        val g = (0 until 8).map { (ip[it * 2].toInt() and 0xff) shl 8 or (ip[it * 2 + 1].toInt() and 0xff) }
        var best = -1; var bestLen = 1
        var i = 0
        while (i < 8) {
            if (g[i] != 0) { i++; continue }
            val start = i
            while (i < 8 && g[i] == 0) i++
            if (i - start > bestLen) { best = start; bestLen = i - start }
        }
        if (best < 0) return g.joinToString(":") { it.toString(16) }
        return g.subList(0, best).joinToString(":") { it.toString(16) } + "::" +
            g.subList(best + bestLen, 8).joinToString(":") { it.toString(16) }
    }

    fun loopback(ip: ByteArray) = LOOPBACK.any { it.contains(ip) }

    class Rule(val action: String, val cidr: Cidr, val scope: String, val note: String) {
        fun applies(s: String) = scope == "all" || scope == s
        fun toJson(): JSONObject = JSONObject().put("action", action).put("cidr", cidr.toString()).put("scope", scope).put("note", note)
    }

    /** The rule set from {"rules": [{action, cidr, scope, note}]}, or an error message. */
    fun rules(o: JSONObject): Result<List<Rule>> {
        val arr = o.optJSONArray("rules") ?: JSONArray()
        if (arr.length() > MAX_RULES) return Result.failure(IllegalArgumentException("That's more than $MAX_RULES rules"))
        val out = mutableListOf<Rule>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: return Result.failure(IllegalArgumentException("Rule ${i + 1} is not an object"))
            val action = r.optString("action")
            if (action !in setOf("allow", "deny")) return Result.failure(IllegalArgumentException("Rule ${i + 1}: action must be allow or deny"))
            val cidr = parseCidr(r.optString("cidr"))
                ?: return Result.failure(IllegalArgumentException("Rule ${i + 1}: \"${r.optString("cidr").take(60)}\" isn't an IP address or range like 192.168.1.0/24"))
            val scope = r.optString("scope", "all").ifEmpty { "all" }
            if (scope !in SCOPES) return Result.failure(IllegalArgumentException("Rule ${i + 1}: scope must be dashboard, web or all"))
            out += Rule(action, cidr, scope, r.optString("note").filter { !it.isISOControl() }.trim().take(80))
        }
        return Result.success(out)
    }

    /**
     * Deny wins; then, if the scope has any allow rules, only those addresses get in. Loopback
     * is always allowed, so the phone itself (and the tunnel, which connects from it) can't be
     * locked out; an unreadable address only gets in when nothing is allow-listed.
     */
    fun allows(rules: List<Rule>, scope: String, ip: ByteArray?): Boolean {
        val scoped = rules.filter { it.applies(scope) }
        if (ip == null) return scoped.none { it.action == "allow" }
        if (loopback(ip)) return true
        if (scoped.any { it.action == "deny" && it.cidr.contains(ip) }) return false
        val allows = scoped.filter { it.action == "allow" }
        return allows.isEmpty() || allows.any { it.cidr.contains(ip) }
    }

    /**
     * The address the dashboard judges: the socket's, unless that is loopback and the request
     * came through the Cloudflare tunnel, which names the real client in Cf-Connecting-Ip.
     * Only a local process can set that header on a loopback connection.
     */
    fun clientIp(remote: String, headers: Map<String, String>): ByteArray? {
        val ip = parseIp(remote) ?: return null
        if (!loopback(ip)) return ip
        return headers["cf-connecting-ip"]?.let(::parseIp) ?: ip
    }

    /**
     * Caddy matchers for the "web" rules (a snippet body), or null with no web rules. Behind the
     * tunnel `client_ip` is the visitor (see trusted_proxies in [DomainsCore.ensureCaddyfile]).
     */
    fun caddyRules(rules: List<Rule>): String? {
        val web = rules.filter { it.applies("web") }
        val deny = web.filter { it.action == "deny" }.map { it.cidr.toString() }.distinct()
        val allow = web.filter { it.action == "allow" }.map { it.cidr.toString() }.distinct()
        if (deny.isEmpty() && allow.isEmpty()) return null
        return buildString {
            if (deny.isNotEmpty()) {
                append("\t@homedroid_denied {\n\t\tclient_ip ").append(deny.joinToString(" ")).append("\n")
                append("\t\tnot client_ip 127.0.0.0/8 ::1\n\t}\n")
                append("\trespond @homedroid_denied \"Forbidden\" 403\n")
            }
            if (allow.isNotEmpty()) {
                append("\t@homedroid_unlisted not client_ip ").append((allow + listOf("127.0.0.0/8", "::1")).joinToString(" ")).append("\n")
                append("\trespond @homedroid_unlisted \"Forbidden\" 403\n")
            }
        }
    }
}

/**
 * IP allow and deny rules (firewall.json). "dashboard" rules are enforced by the dashboard's
 * own HTTP server; "web" rules go into the Homedroid-managed Caddy config via [onWebChange].
 */
class Firewall(ctx: Context, private val onWebChange: () -> String?) {
    private val file = File(ctx.filesDir, "firewall.json")
    @Volatile var rules: List<FirewallCore.Rule> = load()
        private set

    private fun load(): List<FirewallCore.Rule> = try {
        FirewallCore.rules(JSONObject(file.readText())).getOrDefault(emptyList())
    } catch (_: Exception) { emptyList() }

    fun allows(r: Request) = FirewallCore.allows(rules, "dashboard", FirewallCore.clientIp(r.remote, r.headers))

    fun json(r: Request): JSONObject = JSONObject()
        .put("rules", JSONArray(rules.map { it.toJson() }))
        .put("you", FirewallCore.clientIp(r.remote, r.headers)?.let(FirewallCore::format) ?: JSONObject.NULL)

    fun handle(r: Request, seg: List<String>): Response = when {
        r.method == "GET" && (seg.isEmpty() || seg == listOf("rules")) -> Response.json(json(r))
        r.method == "POST" && (seg.isEmpty() || seg == listOf("rules")) -> save(r)
        else -> Response.error(404, "No such firewall endpoint")
    }

    @Synchronized private fun save(r: Request): Response {
        val next = FirewallCore.rules(r.json()).getOrElse { return Response.error(400, it.message ?: "Invalid rules") }
        val you = FirewallCore.clientIp(r.remote, r.headers)
        if (!FirewallCore.allows(next, "dashboard", you)) {
            return Response.error(409, "These rules would block your own address (${you?.let(FirewallCore::format) ?: r.remote}) from the dashboard")
        }
        val webChanged = FirewallCore.caddyRules(next) != FirewallCore.caddyRules(rules)
        val previous = rules
        if (!writeDurably(file, JSONObject().put("rules", JSONArray(next.map { it.toJson() })).toString(2).toByteArray())) {
            return Response.error(500, "Couldn't save the rules")
        }
        rules = next
        if (webChanged) onWebChange()?.let { error ->
            rules = previous
            writeDurably(file, JSONObject().put("rules", JSONArray(previous.map { it.toJson() })).toString(2).toByteArray())
            return Response.error(409, "Caddy rejected the web rules: $error")
        }
        return Response.json(json(r))
    }
}
