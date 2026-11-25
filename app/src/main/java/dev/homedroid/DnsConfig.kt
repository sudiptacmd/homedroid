package dev.homedroid

/**
 * resolv.conf for Alpine and app images.
 *
 * Android reports the network's DNS servers, but not all of them work from inside proot: an
 * IPv6 link-local server (fe80::…) is useless once its interface scope is dropped, and some
 * networks hand out nothing usable at all. musl asks every listed server at once (up to three)
 * and takes the first answer, so keeping a public resolver in the list costs nothing when the
 * network's own servers work and saves installs when they don't.
 */
object DnsConfig {
    /** musl reads at most this many nameserver lines. */
    private const val MAX = 3
    private val FALLBACK_V4 = listOf("1.1.1.1", "8.8.8.8")
    private const val FALLBACK_V6 = "2606:4700:4700::1111"

    /**
     * [system] are the network's servers (without %scope), [custom] the user's own choice,
     * which replaces them; [ipv4] is false on IPv6-only networks (some mobile carriers).
     */
    fun servers(system: List<String>, custom: List<String> = emptyList(), ipv4: Boolean = true): List<String> {
        if (custom.isNotEmpty()) return custom.filter(::usable).distinct().take(MAX)
        val own = system.filter(::usable).distinct().take(MAX - 1)
        val fallback = if (ipv4) FALLBACK_V4 else listOf(FALLBACK_V6) + FALLBACK_V4
        return (own + fallback.filterNot { it in own }).take(maxOf(MAX, own.size + 1)).take(MAX)
    }

    fun resolvConf(servers: List<String>): String =
        "# Written by Homedroid on every start; set your own servers in Settings.\n" +
            servers.joinToString("") { "nameserver $it\n" } +
            "options timeout:2 attempts:3\n"

    /** Parses a comma- or space-separated list from settings; null if any entry isn't an IP address. */
    fun parseCustom(text: String): List<String>? {
        val items = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return items.takeIf { it.size <= MAX && it.all(::usable) }
    }

    /** A literal IPv4 or global IPv6 address that works without an interface scope. */
    fun usable(address: String): Boolean {
        val a = address.trim().lowercase()
        if (IPV4.matches(a)) {
            val parts = a.split('.').map { it.toInt() }
            return parts.all { it in 0..255 } && parts[0] != 0 && !(parts[0] == 169 && parts[1] == 254)
        }
        if (':' !in a || '%' in a || !IPV6.matches(a)) return false
        return !(a.startsWith("fe8") || a.startsWith("fe9") || a.startsWith("fea") || a.startsWith("feb")) &&
            a != "::" && a != "::1"
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6 = Regex("""[0-9a-f:]{2,39}(\d{1,3}(\.\d{1,3}){3})?""")
}
