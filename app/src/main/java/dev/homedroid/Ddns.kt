package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Validation and decisions for [Ddns], free of Android so they can be tested. */
object DdnsCore {
    const val MAX_ENTRIES = 32
    const val MASK = "***"
    /** Even with no change, records are rewritten this often in case someone edited them. */
    const val REFRESH_MS = 24 * 60 * 60 * 1000L
    val IPV4_SOURCES = listOf("https://api.ipify.org", "https://ipv4.icanhazip.com", "https://checkip.amazonaws.com")
    val IPV6_SOURCES = listOf("https://api6.ipify.org", "https://ipv6.icanhazip.com")

    /** An address from a "what's my IP" service: the first line, IPv4 or IPv6 as asked, public only. */
    fun parseIp(text: String, v6: Boolean): String? {
        val ip = FirewallCore.parseIp(text.trim().lineSequence().firstOrNull().orEmpty()) ?: return null
        if (ip.size != (if (v6) 16 else 4) || FirewallCore.loopback(ip) || private(ip)) return null
        return FirewallCore.format(ip)
    }

    private val PRIVATE = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16", "0.0.0.0/8", "fc00::/7", "fe80::/10", "::/128")
        .map { FirewallCore.parseCidr(it)!! }

    fun private(ip: ByteArray) = PRIVATE.any { it.contains(ip) }

    /**
     * An entry from the dashboard form, or an error. A blank or masked token keeps [old]'s.
     * DuckDNS: {provider, domain ("myhome" or "myhome.duckdns.org", comma-separated for several), token};
     * Cloudflare: {provider, token (API token with DNS edit), zone (zone ID or name), record, proxied}.
     * Both take ipv6 (also keep an AAAA record) and enabled.
     */
    fun entry(o: JSONObject, id: String, old: JSONObject?): Result<JSONObject> {
        fun fail(m: String) = Result.failure<JSONObject>(IllegalArgumentException(m))
        val provider = o.optString("provider", old?.optString("provider").orEmpty())
        val given = o.optString("token").trim()
        val token = if (given.isEmpty() || given == MASK) old?.takeIf { it.optString("provider") == provider }?.optString("token").orEmpty() else given
        if (token.isEmpty()) return fail("Enter the token")
        if (token.length > 200 || token.any { it.isWhitespace() || it.isISOControl() }) return fail("That token doesn't look right")
        val e = JSONObject().put("id", id).put("provider", provider).put("token", token)
            .put("ipv6", o.optBoolean("ipv6")).put("enabled", o.optBoolean("enabled", true))
        when (provider) {
            "duckdns" -> {
                val names = o.optString("domain").split(',').map { it.trim().lowercase().removeSuffix(".duckdns.org") }.filter { it.isNotEmpty() }
                if (names.isEmpty() || names.size > 5 || !names.all { Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?").matches(it) }) {
                    return fail("Enter your DuckDNS name, like myhome (for myhome.duckdns.org)")
                }
                e.put("domain", names.joinToString(","))
            }
            "cloudflare" -> {
                val zone = o.optString("zone").trim().lowercase()
                if (!Regex("[0-9a-f]{32}").matches(zone) && DomainsCore.host(zone) == null) return fail("Enter the zone ID or the zone's domain, like example.com")
                val record = DomainsCore.host(o.optString("record")) ?: return fail("Enter the record's full name, like home.example.com")
                e.put("zone", zone).put("record", record).put("proxied", o.optBoolean("proxied"))
            }
            else -> return fail("Choose DuckDNS or Cloudflare")
        }
        // Results of earlier updates stay, unless what is updated changed.
        val same = old != null && listOf("provider", "domain", "zone", "record").all { old.optString(it) == e.optString(it) }
        for (k in listOf("ip", "ipv6Address", "lastUpdate", "lastCheck", "result", "error")) if (same && old!!.has(k)) e.put(k, old.get(k))
        return Result.success(e)
    }

    /** What the API shows: everything but the token. */
    fun public(e: JSONObject): JSONObject = JSONObject(e.toString()).put("token", MASK)

    /** Whether [e] needs an update for these addresses: they changed, the last try failed, or it's been a day. */
    fun due(e: JSONObject, ip4: String?, ip6: String?, now: Long): Boolean {
        if (!e.optBoolean("enabled", true) || ip4 == null && ip6 == null) return false
        if (e.optString("result") != "ok") return true
        if (ip4 != null && ip4 != e.optString("ip")) return true
        if (e.optBoolean("ipv6") && ip6 != null && ip6 != e.optString("ipv6Address")) return true
        return now - e.optLong("lastUpdate") > REFRESH_MS
    }

    fun duckUrl(e: JSONObject, ip4: String?, ip6: String?): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://www.duckdns.org/update?domains=${enc(e.getString("domain"))}&token=${enc(e.getString("token"))}" +
            "&ip=${enc(ip4.orEmpty())}" + (if (e.optBoolean("ipv6") && ip6 != null) "&ipv6=${enc(ip6)}" else "")
    }

    /** Body for creating or replacing a Cloudflare record; ttl 1 means "automatic". */
    fun cloudflareRecord(e: JSONObject, type: String, ip: String): JSONObject = JSONObject()
        .put("type", type).put("name", e.getString("record")).put("content", ip).put("ttl", 1).put("proxied", e.optBoolean("proxied"))

    /** The message of a Cloudflare API error response, or a fallback. */
    fun cloudflareError(body: String, code: Int): String = try {
        JSONObject(body).optJSONArray("errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null } ?: "Cloudflare answered $code"
}

/**
 * Dynamic DNS: keeps DuckDNS names and Cloudflare A/AAAA records pointed at the phone's
 * public address. Entries live in ddns.json; every five minutes the address is looked up and
 * entries whose address changed (or whose last update failed) are updated.
 */
class Ddns(ctx: Context) {
    private val file = File(ctx.filesDir, "ddns.json")
    private val rnd = SecureRandom()
    // Held across the network calls of a tick; the entries' own lock (this) is not, or the
    // dashboard's DDNS page would hang for as long as the address lookups time out.
    private val tickLock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ddns").apply { isDaemon = true } }

    @Synchronized private fun load(): MutableList<JSONObject> = try {
        JSONObject(file.readText()).getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    } catch (_: Exception) { mutableListOf() }

    @Synchronized private fun save(list: List<JSONObject>) =
        writeDurably(file, JSONObject().put("entries", JSONArray(list)).toString(2).toByteArray())

    fun start() { timer.scheduleWithFixedDelay({ runCatching { tick(false) } }, 30, 300, TimeUnit.SECONDS) }

    fun stop() { timer.shutdownNow() }

    fun handle(r: Request, seg: List<String>): Response = when {
        r.method == "GET" && seg.isEmpty() -> Response.json(JSONObject().put("entries", JSONArray(load().map(DdnsCore::public))))
        r.method == "POST" && seg.isEmpty() -> upsert(r.json())
        r.method == "POST" && seg.size == 2 && seg[1] == "update" -> {
            if (load().none { it.optString("id") == seg[0] }) Response.error(404, "No such entry")
            else { tick(true, seg[0]); load().first { it.optString("id") == seg[0] }.let { Response.json(DdnsCore.public(it)) } }
        }
        r.method == "DELETE" && seg.size == 1 || r.method == "POST" && seg.size == 2 && seg[1] == "delete" -> synchronized(this) {
            val list = load()
            if (!list.removeAll { it.optString("id") == seg[0] }) return Response.error(404, "No such entry")
            save(list)
            Response.ok()
        }
        else -> Response.error(404, "No such DDNS endpoint")
    }

    private fun upsert(body: JSONObject): Response {
        val e = synchronized(this) {
            val list = load()
            val old = list.firstOrNull { it.optString("id") == body.optString("id") }
            val id = old?.optString("id") ?: ByteArray(6).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
            val e = DdnsCore.entry(body, id, old).getOrElse { return Response.error(400, it.message ?: "Invalid entry") }
            val i = list.indexOf(old)
            if (i >= 0) list[i] = e else {
                if (list.size >= DdnsCore.MAX_ENTRIES) return Response.error(409, "That's the most entries this list holds")
                list.add(e)
            }
            save(list)
            e
        }
        tick(true, e.getString("id"))
        return Response.json(DdnsCore.public(load().firstOrNull { it.optString("id") == e.getString("id") } ?: e))
    }

    /** Looks up the public address and updates entries that are due (or just [only], forced). */
    private fun tick(force: Boolean, only: String? = null) = synchronized(tickLock) {
        val entries = load().filter { only == null || it.optString("id") == only }
        if (entries.isEmpty()) return@synchronized
        val ip4 = lookup(DdnsCore.IPV4_SOURCES, false)
        val ip6 = if (entries.any { it.optBoolean("ipv6") }) lookup(DdnsCore.IPV6_SOURCES, true) else null
        val now = System.currentTimeMillis()
        val results = entries.filter { force || DdnsCore.due(it, ip4, ip6, now) }.associate { e ->
            val error = if (ip4 == null && ip6 == null) "Couldn't find the phone's public address; is it online?"
            else try {
                if (e.getString("provider") == "duckdns") duck(e, ip4, ip6) else cloudflare(e, ip4, ip6)
            } catch (x: Exception) { x.message ?: x.javaClass.simpleName }
            e.getString("id") to error
        }
        // Reload so edits made while the network calls ran aren't lost.
        synchronized(this) {
        val list = load()
        for (e in list) {
            val id = e.optString("id")
            if (id !in results) { if (entries.any { it.optString("id") == id }) e.put("lastCheck", now); continue }
            val error = results[id]
            e.put("lastCheck", now).put("result", if (error == null) "ok" else "error").put("error", error ?: JSONObject.NULL)
            if (error == null) {
                e.put("lastUpdate", now)
                ip4?.let { e.put("ip", it) }
                if (e.optBoolean("ipv6")) ip6?.let { e.put("ipv6Address", it) }
                Jobs.line("DDNS: ${e.optString("domain").ifEmpty { e.optString("record") }} → ${listOfNotNull(ip4, ip6.takeIf { e.optBoolean("ipv6") }).joinToString(", ")}")
            }
        }
        save(list)
        }
    }

    /** The first answer from [sources] that is a public address of the right family. */
    private fun lookup(sources: List<String>, v6: Boolean): String? = sources.firstNotNullOfOrNull { url ->
        try { call("GET", url).second.let { DdnsCore.parseIp(it, v6) } } catch (_: Exception) { null }
    }

    private fun duck(e: JSONObject, ip4: String?, ip6: String?): String? {
        val (code, body) = call("GET", DdnsCore.duckUrl(e, ip4, ip6))
        return if (code == 200 && body.trim().startsWith("OK")) null else "DuckDNS refused the update (check the name and token)"
    }

    /** Finds the zone (if given by name), then creates or replaces the A (and AAAA) record. */
    private fun cloudflare(e: JSONObject, ip4: String?, ip6: String?): String? {
        val auth = "Bearer " + e.getString("token")
        val api = "https://api.cloudflare.com/client/v4"
        fun cf(method: String, url: String, body: JSONObject? = null): JSONObject {
            val (code, text) = call(method, url, body, auth)
            if (code !in 200..299) throw IllegalStateException(DdnsCore.cloudflareError(text, code))
            return JSONObject(text)
        }
        var zone = e.getString("zone")
        if (!Regex("[0-9a-f]{32}").matches(zone)) {
            zone = cf("GET", "$api/zones?name=${URLEncoder.encode(zone, "UTF-8")}").getJSONArray("result").optJSONObject(0)?.getString("id")
                ?: return "Cloudflare has no zone called $zone for this token"
        }
        val wanted = listOfNotNull(ip4?.let { "A" to it }, ip6?.takeIf { e.optBoolean("ipv6") }?.let { "AAAA" to it })
        for ((type, ip) in wanted) {
            val name = URLEncoder.encode(e.getString("record"), "UTF-8")
            val existing = cf("GET", "$api/zones/$zone/dns_records?type=$type&name=$name").getJSONArray("result").optJSONObject(0)
            val record = DdnsCore.cloudflareRecord(e, type, ip)
            if (existing == null) cf("POST", "$api/zones/$zone/dns_records", record)
            else if (existing.optString("content") != ip || existing.optBoolean("proxied") != e.optBoolean("proxied")) {
                cf("PUT", "$api/zones/$zone/dns_records/${existing.getString("id")}", record)
            }
        }
        return null
    }

    /** A small HTTPS request: status and body (error body on failure). */
    private fun call(method: String, url: String, body: JSONObject? = null, auth: String? = null): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "Homedroid")
            auth?.let { setRequestProperty("Authorization", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }.orEmpty()
            return code to text.take(1 shl 16)
        } finally { conn.disconnect() }
    }
}
