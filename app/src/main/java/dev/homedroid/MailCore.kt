package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure logic for the Mail app (Stalwart): validating the setup wizard's answers, generating
 * Stalwart's settings, the DNS records the user has to add, client settings and the comparison
 * of DNS answers with what is expected. Free of Android so it can be tested; [Mail] runs it.
 */
object MailCore {
    /** Where Stalwart listens on the phone: Android apps can't bind ports below 1024. */
    object Ports {
        const val SMTP = 2525
        const val SUBMISSION = 2587
        const val SUBMISSIONS = 4650
        const val IMAPS = 9930
        const val HTTP = 8825

        /** The port the rest of the world expects for each, to forward on the router. */
        val PUBLIC = mapOf(SMTP to 25, SUBMISSION to 587, SUBMISSIONS to 465, IMAPS to 993, HTTP to 80)
    }

    const val MODE_DIRECT = "direct"
    const val MODE_RELAY = "relay"
    const val MODE_RECEIVE_ONLY = "receive_only"
    val MODES = listOf(MODE_DIRECT, MODE_RELAY, MODE_RECEIVE_ONLY)
    val DMARC_POLICIES = listOf("none", "quarantine", "reject")
    const val MASK = "***"

    class Relay(val host: String, val port: Int, val username: String, val password: String, val implicitTls: Boolean)

    class Setup(
        val domain: String,
        val hostname: String,
        val mode: String,
        /** Public IPv4/IPv6 address, or the DDNS name, that [hostname] should lead to; may be empty. */
        val address: String,
        val relay: Relay?,
        /** SPF include: values of the relay provider, e.g. "spf.mailgun.org". */
        val spfInclude: List<String>,
        val dmarcPolicy: String,
        /** Mailbox for DMARC and TLS reports, or empty for none. */
        val reportEmail: String,
    ) {
        fun toJson(maskSecrets: Boolean = true): JSONObject = JSONObject()
            .put("domain", domain).put("hostname", hostname).put("mode", mode).put("address", address)
            .put("spfInclude", JSONArray(spfInclude)).put("dmarcPolicy", dmarcPolicy).put("reportEmail", reportEmail)
            .put("relay", relay?.let {
                JSONObject().put("host", it.host).put("port", it.port).put("username", it.username)
                    .put("password", if (maskSecrets && it.password.isNotEmpty()) MASK else it.password)
                    .put("implicitTls", it.implicitTls)
            } ?: JSONObject.NULL)
    }

    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** A lowercase DNS name with at least two labels, or null. */
    fun dnsName(raw: String): String? {
        val s = raw.trim().lowercase().removeSuffix(".")
        if (s.length > 253 || '.' !in s) return null
        val labels = s.split('.')
        if (!labels.all { LABEL.matches(it) } || labels.last().all { it.isDigit() }) return null
        return s
    }

    fun isIpv4(s: String) = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""").matches(s)

    fun isIpv6(s: String) = ':' in s && s.length <= 45 && Regex("[0-9a-fA-F:.]+").matches(s) &&
        s.count { it == ':' } in 2..7 && !s.contains(":::")

    fun validEmail(s: String) = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}").matches(s) && '.' in s.substringAfter('@')

    /**
     * The wizard's answers: {domain, hostname, mode, address?, spfInclude? (list or text), dmarcPolicy?, reportEmail?,
     * relay?: {host, port, username, password, implicitTls}}. A blank or masked relay password keeps [old]'s.
     */
    fun setup(o: JSONObject, old: Setup?): Result<Setup> {
        fun fail(m: String) = Result.failure<Setup>(IllegalArgumentException(m))
        val domain = dnsName(o.optString("domain")) ?: return fail("Enter your domain, like example.com")
        val hostname = dnsName(o.optString("hostname").ifBlank { "mail.$domain" })
            ?: return fail("Enter the mail server's hostname, like mail.$domain")
        val mode = o.optString("mode", MODE_DIRECT)
        if (mode !in MODES) return fail("Choose how mail reaches the phone")
        val address = o.optString("address").trim().lowercase().removeSuffix(".")
        if (address.isNotEmpty() && !isIpv4(address) && !isIpv6(address) && dnsName(address) == null) {
            return fail("The address should be your public IP (like 203.0.113.7) or a dynamic DNS name (like myhome.duckdns.org)")
        }
        val includes = when (val v = o.opt("spfInclude")) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is String -> v.split(',', ' ', '\n')
            else -> emptyList()
        }.map { it.trim().lowercase().removePrefix("include:") }.filter { it.isNotEmpty() }
        if (includes.size > 5 || includes.any { dnsName(it) == null }) return fail("SPF include should be a domain like spf.example.net")
        val dmarc = o.optString("dmarcPolicy", "none")
        if (dmarc !in DMARC_POLICIES) return fail("DMARC policy must be none, quarantine or reject")
        val report = o.optString("reportEmail").trim()
        if (report.isNotEmpty() && !validEmail(report)) return fail("The report address should look like postmaster@$domain")
        var relay: Relay? = null
        val r = o.optJSONObject("relay")
        if (r != null && r.optString("host").isNotBlank()) {
            val host = dnsName(r.optString("host").trim()) ?: r.optString("host").trim().takeIf { isIpv4(it) }
                ?: return fail("Enter the relay's server name, like smtp.example.net")
            val port = r.optInt("port", 587)
            if (port !in 1..65535) return fail("The relay port should be between 1 and 65535")
            val given = r.optString("password")
            val password = if (given.isEmpty() || given == MASK) old?.relay?.password.orEmpty() else given
            val user = r.optString("username").trim()
            if (user.any { it.isISOControl() } || password.any { it.isISOControl() } || user.length > 256 || password.length > 512) {
                return fail("The relay user name or password has characters that aren't allowed")
            }
            relay = Relay(host, port, user, password, r.optBoolean("implicitTls", port == 465))
        }
        if (mode == MODE_RELAY && relay == null) return fail("Enter the relay's server name, port, user and password")
        return Result.success(Setup(domain, hostname, mode, address, relay, includes, dmarc, report))
    }

    // --- Stalwart's settings -------------------------------------------------------------------

    /** The setup wizard's answers for stalwart-cli: where the data lives and the first domain. */
    fun bootstrap(s: Setup, dbPath: String = "/var/lib/stalwart/data/stalwart.db"): JSONObject = JSONObject()
        .put("defaultHostname", s.hostname).put("defaultDomain", s.domain)
        // No certificate requests: ACME needs port 80 or 443, which an app can't open.
        .put("requestTlsCertificate", false)
        // The plan creates the keys with just the RSA algorithm.
        .put("generateDkimKeys", false)
        // A small pool keeps memory low on a phone.
        .put("dataStore", JSONObject().put("@type", "Sqlite").put("path", dbPath).put("poolWorkers", 2).put("poolMaxConnections", 4))
        .put("tracer", JSONObject().put("@type", "Stdout").put("enable", true).put("ansi", false).put("level", "info"))

    private fun expr(cond: String?, then: String, otherwise: String): JSONObject {
        val m = JSONObject()
        if (cond != null) m.put("0", JSONObject().put("if", cond).put("then", then))
        return JSONObject().put("match", m).put("else", otherwise)
    }

    private fun op(type: String, obj: String, value: JSONObject, matchOn: String? = null): String {
        val o = JSONObject().put("@type", type).put("object", obj)
        if (matchOn != null) o.put("matchOn", JSONArray().put(matchOn))
        return o.put("value", value).toString()
    }

    private fun listener(name: String, port: Int, protocol: String, implicitTls: Boolean, tls: Boolean = true) = JSONObject()
        .put("name", name).put("bind", JSONObject().put("0.0.0.0:$port", true)).put("protocol", protocol)
        .put("useTls", tls).put("implicitTls", implicitTls)

    /**
     * Stalwart's settings as a plan for `stalwart-cli apply` (one JSON object per line). Applying it
     * again gives the same result. Its listeners replace Stalwart's defaults (25, 465, 993…), and the
     * rules that treat port 25 as "mail from other servers" are pointed at [Ports.SMTP].
     */
    fun plan(s: Setup): String {
        val p = Ports.SMTP
        val lines = mutableListOf<String>()
        lines += op("reconcile", "NetworkListener", JSONObject()
            .put("l-smtp", listener("smtp", p, "smtp", false))
            .put("l-submission", listener("submission", Ports.SUBMISSION, "smtp", false))
            .put("l-submissions", listener("submissions", Ports.SUBMISSIONS, "smtp", true))
            .put("l-imaps", listener("imaps", Ports.IMAPS, "imap", true))
            // Admin, JMAP and autoconfiguration, over plain HTTP; see the guide for the LAN-only advice.
            .put("l-http", listener("http", Ports.HTTP, "http", false, tls = false)), "name")
        val day = 24L * 60 * 60 * 1000
        lines += op("upsert", "Domain", JSONObject().put("dom-0", JSONObject()
            .put("name", s.domain)
            .put("certificateManagement", JSONObject().put("@type", "Manual"))
            // One RSA key, and no rotation for ten years: rotating would need new DNS records.
            .put("dkimManagement", JSONObject().put("@type", "Automatic")
                .put("algorithms", JSONObject().put("Dkim1RsaSha256", true))
                .put("selectorTemplate", "v{version}-{algorithm}-{date-%Y%m%d}")
                .put("rotateAfter", 3650 * day).put("retireAfter", 7 * day).put("deleteAfter", 30 * day))
            .put("dnsManagement", JSONObject().put("@type", "Manual"))
            .put("subAddressing", JSONObject().put("@type", "Enabled"))
            .put("reportAddressUri", "mailto:postmaster")), "name")
        lines += op("update", "SystemSettings", JSONObject().put("defaultHostname", s.hostname))
        s.relay?.let { r ->
            val route = JSONObject().put("@type", "Relay").put("name", "relay").put("host", r.host).put("port", r.port)
                .put("protocol", "smtp").put("implicitTls", r.implicitTls).put("allowInvalidCerts", false)
                .put("description", "Set by Homedroid's mail setup")
            if (r.username.isNotEmpty()) {
                route.put("authUsername", r.username).put("authSecret", JSONObject().put("@type", "Value").put("secret", r.password))
            }
            lines += op("upsert", "MtaRoute", JSONObject().put("r-relay", route), "name")
        }
        val outbound = if (s.relay != null) "relay" else "mx"
        lines += op("update", "MtaOutboundStrategy", JSONObject().put("route", expr("is_local_domain(rcpt_domain)", "'local'", "'$outbound'")))
        lines += op("update", "MtaStageAuth", JSONObject()
            .put("require", expr(null, "", "local_port != $p"))
            .put("saslMechanisms", expr("local_port != $p && is_tls", "[plain, login, oauthbearer, xoauth2]", "false")))
        val onSmtp = expr("local_port == $p", "true", "false")
        lines += op("update", "MtaStageData", JSONObject().apply {
            for (k in listOf("addAuthResultsHeader", "addDateHeader", "addMessageIdHeader", "addReceivedHeader", "addReceivedSpfHeader", "addReturnPathHeader")) put(k, onSmtp)
        })
        lines += op("update", "MtaStageEhlo", JSONObject().put("rejectNonFqdn", onSmtp))
        val verify = expr("local_port == $p", "relaxed", "disable")
        lines += op("update", "SenderAuth", JSONObject().apply {
            for (k in listOf("spfEhloVerify", "spfFromVerify", "dmarcVerify", "reverseIpVerify")) put(k, verify)
        })
        return lines.joinToString("\n", postfix = "\n")
    }

    // --- DNS records -----------------------------------------------------------------------------

    class DkimKey(val selector: String, val name: String, val value: String)

    /**
     * Strings of at most [max] bytes: a DNS TXT record holds 255 per string, and a 2048-bit DKIM key
     * is longer. Zone files write them side by side; most DNS panels join them for you.
     */
    fun splitTxt(value: String, max: Int = 255): List<String> = if (value.isEmpty()) listOf("") else value.chunked(max)

    /** A TXT value as a zone file writes it: one quoted string, or several in parentheses when long. */
    fun zoneTxt(value: String): String {
        val parts = splitTxt(value).map { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        return if (parts.size == 1) parts[0] else parts.joinToString(" ", "(", ")")
    }

    /** DKIM TXT records from a Stalwart zone file (names containing _domainkey), with parentheses and split strings joined. */
    fun parseDkim(zone: String): List<DkimKey> {
        val records = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        for (line in zone.lineSequence()) {
            if (depth == 0 && line.isBlank()) continue
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(line.trim())
            depth += line.count { it == '(' } - line.count { it == ')' }
            if (depth <= 0) { depth = 0; records += cur.toString(); cur.setLength(0) }
        }
        if (cur.isNotEmpty()) records += cur.toString()
        val head = Regex("""^(\S+)\s+(?:\d+\s+)?IN\s+TXT\s+(.*)$""")
        return records.mapNotNull { rec ->
            val m = head.matchEntire(rec) ?: return@mapNotNull null
            val name = m.groupValues[1].removeSuffix(".").lowercase()
            if ("._domainkey." !in name) return@mapNotNull null
            val value = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(m.groupValues[2]).joinToString("") { it.groupValues[1].replace(Regex("""\\(.)"""), "$1") }
            DkimKey(name.substringBefore("._domainkey."), name, value)
        }
    }

    class DnsRecord(
        val id: String,
        val type: String,
        /** Fully qualified, no trailing dot. */
        val name: String,
        /** Relative to the domain ("@" for the domain itself), for DNS panels that ask for that. */
        val host: String,
        val value: String,
        val priority: Int? = null,
        /** "essential", "authentication", "optional". */
        val group: String,
        val title: String,
        val note: String,
        /** Needs something only the user knows (their IP) or something not generated yet. */
        val pending: Boolean = false,
        val extra: String? = null,
    ) {
        /** The record as a line of a zone file. */
        fun zoneLine(): String {
            val v = when (type) {
                "TXT" -> zoneTxt(value)
                "MX", "SRV" -> "${priority ?: 0} ${value}"
                else -> value
            }
            return "$name. IN $type $v"
        }

        fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("host", host)
            .put("value", value).put("priority", priority ?: JSONObject.NULL).put("group", group).put("title", title)
            .put("note", note).put("pending", pending).put("zone", zoneLine())
            .put("chunks", if (type == "TXT") JSONArray(splitTxt(value)) else JSONObject.NULL)
            .put("extra", extra ?: JSONObject.NULL)
    }

    private fun relative(name: String, domain: String) = if (name == domain) "@" else name.removeSuffix(".$domain").takeIf { it != name } ?: name

    /** The SPF value: who may send mail as the domain. */
    fun spf(s: Setup): String {
        val sends = s.mode != MODE_RECEIVE_ONLY || s.relay != null
        if (!sends) return "v=spf1 -all"
        val parts = mutableListOf("v=spf1")
        if (s.mode == MODE_DIRECT) {
            parts += "mx"
            if (isIpv4(s.address)) parts += "ip4:${s.address}" else if (isIpv6(s.address)) parts += "ip6:${s.address}"
        }
        parts += s.spfInclude.map { "include:$it" }
        // ~all (soft fail) while testing; -all once mail is known to flow, see the guide.
        parts += "~all"
        return parts.joinToString(" ")
    }

    fun dmarc(s: Setup): String {
        val v = StringBuilder("v=DMARC1; p=${s.dmarcPolicy}")
        if (s.reportEmail.isNotEmpty()) v.append("; rua=mailto:${s.reportEmail}")
        return v.toString()
    }

    /** Everything to add at the DNS host, with the DKIM keys Stalwart made (empty until the server has run). */
    fun dnsRecords(s: Setup, dkim: List<DkimKey>): List<DnsRecord> {
        val d = s.domain
        val h = s.hostname
        val out = mutableListOf<DnsRecord>()
        // Where the hostname points. A hostname outside the domain (a dynamic DNS name) is kept up to date elsewhere.
        val inDomain = h == d || h.endsWith(".$d")
        if (inDomain) when {
            s.address.isEmpty() -> out += DnsRecord("address", "A", h, relative(h, d), "<your public IP address>", group = "essential",
                title = "Address of the mail server", pending = true,
                note = "Type your home's public IP address, or fill in the address in the setup. If it changes now and then, use a dynamic DNS name instead.")
            isIpv4(s.address) -> out += DnsRecord("address", "A", h, relative(h, d), s.address, group = "essential",
                title = "Address of the mail server", note = "Your public IPv4 address. Must stay the same, or be updated by dynamic DNS.")
            isIpv6(s.address) -> out += DnsRecord("address", "AAAA", h, relative(h, d), s.address, group = "essential",
                title = "Address of the mail server", note = "Your public IPv6 address.")
            s.address != h -> out += DnsRecord("address", "CNAME", h, relative(h, d), "${s.address}.", group = "essential",
                title = "Address of the mail server", note = "Points the mail hostname at your dynamic DNS name, which keeps your changing IP up to date.")
        }
        out += DnsRecord("mx", "MX", d, "@", "$h.", priority = 10, group = "essential", title = "Where mail for $d is delivered",
            note = "Tells other mail servers to deliver mail for @$d to $h.")
        out += DnsRecord("spf", "TXT", d, "@", spf(s), group = "authentication", title = "SPF: who may send as $d",
            note = when {
                s.mode == MODE_RELAY && s.spfInclude.isEmpty() -> "Your relay provider will tell you the include: value to put here. Without it, mail may be rejected."
                else -> "Starts as soft fail (~all). Change to -all once mail flows and you've checked the results."
            })
        if (s.mode == MODE_DIRECT) out += DnsRecord("spf-helo", "TXT", h, relative(h, d), "v=spf1 a -all", group = "authentication",
            title = "SPF for the server's own name", note = "Some servers check the name this phone introduces itself with.")
        if (dkim.isEmpty()) out += DnsRecord("dkim", "TXT", "<selector>._domainkey.$d", "<selector>._domainkey", "v=DKIM1; k=rsa; h=sha256; p=<key made by the server>",
            group = "authentication", title = "DKIM: signature key", pending = true,
            note = "Created when the mail server first starts. Start it, then reload this list.")
        for (k in dkim) {
            val rel = k.name.removeSuffix(".$d")
            out += DnsRecord("dkim-${k.selector}", "TXT", k.name, rel, k.value, group = "authentication", title = "DKIM: signature key",
                note = "Long keys are split into 255-character strings in a zone file; most DNS panels want the whole value in one box.")
        }
        out += DnsRecord("dmarc", "TXT", "_dmarc.$d", "_dmarc", dmarc(s), group = "authentication", title = "DMARC: what to do with mail that fails",
            note = if (s.dmarcPolicy == "none") "Starts by only watching (p=none). Move to quarantine, then reject, once your mail passes."
            else "Receivers will ${if (s.dmarcPolicy == "reject") "reject" else "put in spam"} mail from your domain that fails SPF and DKIM.")
        // Optional records.
        out += DnsRecord("autoconfig", "CNAME", "autoconfig.$d", "autoconfig", "$h.", group = "optional", title = "Mail app auto-setup (Thunderbird)",
            note = "Only works if port 80 reaches the phone (forward 80 to ${Ports.HTTP}). Skip it otherwise; set up mail apps by hand.")
        out += DnsRecord("autodiscover", "CNAME", "autodiscover.$d", "autodiscover", "$h.", group = "optional", title = "Mail app auto-setup (Outlook)",
            note = "Outlook needs HTTPS with a trusted certificate. Skip unless you installed one.")
        out += DnsRecord("srv-imaps", "SRV", "_imaps._tcp.$d", "_imaps._tcp", "1 993 $h.", priority = 0, group = "optional",
            title = "Finds the IMAP server", note = "Lets mail apps find the server from the address alone. 993 is the public port; forward it to ${Ports.IMAPS}.")
        out += DnsRecord("srv-submissions", "SRV", "_submissions._tcp.$d", "_submissions._tcp", "1 465 $h.", priority = 0, group = "optional",
            title = "Finds the sending server", note = "Public port 465, forwarded to ${Ports.SUBMISSIONS}.")
        out += DnsRecord("tlsrpt", "TXT", "_smtp._tls.$d", "_smtp._tls", "v=TLSRPTv1; rua=mailto:${s.reportEmail.ifEmpty { "postmaster@$d" }}", group = "optional",
            title = "TLS reports", pending = s.reportEmail.isEmpty(), note = "Other servers email you if they can't deliver securely. Needs a mailbox that exists.")
        val stsId = "1"
        out += DnsRecord("mta-sts", "TXT", "_mta-sts.$d", "_mta-sts", "v=STSv1; id=$stsId", group = "optional", title = "MTA-STS: require encrypted delivery",
            note = "Only useful with a trusted HTTPS certificate on mta-sts.$d (port 443), which a phone usually can't get. Skip it unless you have that.",
            extra = mtaSts(s))
        return out
    }

    /** The mta-sts.txt policy file to host at https://mta-sts.<domain>/.well-known/mta-sts.txt. */
    fun mtaSts(s: Setup) = "version: STSv1\nmode: testing\nmx: ${s.hostname}\nmax_age: 86400\n"

    // --- Comparing with real DNS -----------------------------------------------------------------

    /** TXT answers as one string each: quotes removed, split strings joined (a DNS-over-HTTPS answer shows them as "a" "b"). */
    fun txtAnswer(raw: String): String {
        val quoted = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(raw).map { it.groupValues[1] }.toList()
        return if (quoted.isEmpty()) raw.trim() else quoted.joinToString("")
    }

    private fun squash(s: String) = s.filter { !it.isWhitespace() }.lowercase()

    /** One of "ok", "different" or "missing" for [rec] given the [answers] (record data as DNS returns it) of its name and type. */
    fun compare(rec: DnsRecord, answers: List<String>): String {
        if (answers.isEmpty()) return "missing"
        fun host(x: String) = x.trim().lowercase().removeSuffix(".")
        return when (rec.type) {
            "A", "AAAA", "CNAME" -> if (answers.any { host(it) == host(rec.value) }) "ok" else "different"
            "MX" -> if (answers.any { a ->
                    val p = a.trim().split(Regex("\\s+"))
                    p.size == 2 && host(p[1]) == host(rec.value) }) "ok" else "different"
            "SRV" -> rec.value.split(' ').let { w ->
                if (answers.any { a -> a.trim().split(Regex("\\s+")).let { p -> p.size == 4 && p[2] == w[1] && host(p[3]) == host(w[2]) } }) "ok" else "different"
            }
            else -> {
                val want = squash(rec.value)
                val have = answers.map { squash(txtAnswer(it)) }
                // Other TXT records share the name (site verification, say): look only at ones of the same kind.
                val kind = rec.value.trim().takeWhile { it != ';' && !it.isWhitespace() }.lowercase()
                val same = have.filter { it.startsWith(kind) }
                if (same.isEmpty()) "missing" else if (want in same) "ok" else "different"
            }
        }
    }

    /** The most likely cause of a failed check, in plain words. */
    fun hintFor(rec: DnsRecord, state: String): String = when {
        state == "ok" -> ""
        state == "missing" -> "Not found yet. DNS changes can take from a few minutes to a few hours to show up."
        rec.type == "TXT" && rec.id == "spf" -> "There is an SPF record but it differs from the suggested one. That's fine if you changed it on purpose; there must be only one SPF record."
        else -> "Found, but with a different value. Compare it with the suggestion."
    }

    // --- Mail app settings ---------------------------------------------------------------------------

    /**
     * What to type into a mail app for [email]: [lanHost] (the phone's address at home) with the real ports,
     * and the public hostname with the standard ports once they're forwarded on the router.
     */
    fun clientSettings(s: Setup, email: String, lanHost: String?): JSONObject {
        fun server(host: String, imap: Int, smtpTls: Int, smtpStart: Int) = JSONObject()
            .put("incoming", JSONObject().put("protocol", "IMAP").put("server", host).put("port", imap).put("security", "SSL/TLS").put("authentication", "Normal password").put("username", email))
            .put("outgoing", JSONObject().put("protocol", "SMTP").put("server", host).put("port", smtpTls).put("security", "SSL/TLS").put("authentication", "Normal password").put("username", email)
                .put("alternative", JSONObject().put("port", smtpStart).put("security", "STARTTLS")))
        return JSONObject()
            .put("email", email)
            .put("away", server(s.hostname, Ports.PUBLIC.getValue(Ports.IMAPS), Ports.PUBLIC.getValue(Ports.SUBMISSIONS), Ports.PUBLIC.getValue(Ports.SUBMISSION))
                .put("when", "From anywhere, once the router forwards 993, 465 and 587 to the phone."))
            .put("home", (lanHost?.let { server(it, Ports.IMAPS, Ports.SUBMISSIONS, Ports.SUBMISSION) } ?: JSONObject())
                .put("when", "On your home Wi-Fi, straight to the phone. No router setup needed."))
            .put("webAdmin", lanHost?.let { "http://$it:${Ports.HTTP}/admin" } ?: JSONObject.NULL)
            .put("jmap", lanHost?.let { "http://$it:${Ports.HTTP}/jmap/session" } ?: JSONObject.NULL)
            .put("certificate", "The mail server starts with a self-signed certificate. Your mail app will warn about it once; accept it, or install your own certificate (see the guide).")
            .put("steps", JSONArray(listOf(
                "Add an account of type IMAP (not Gmail or Exchange).",
                "Incoming: the server and port above, security SSL/TLS, user name $email.",
                "Outgoing: the same user name and password, security SSL/TLS (or STARTTLS on the alternative port).",
                "Accept the certificate warning if you see one.",
            )))
    }

    // --- Accounts ---------------------------------------------------------------------------------------

    /** A mailbox name (the part before @), or null. */
    fun accountName(raw: String): String? = raw.trim().lowercase().takeIf { Regex("[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?").matches(it) }

    fun validPassword(p: String) = p.length in 8..128 && p.none { it.isISOControl() }

    fun generatePassword(rnd: java.util.Random = java.security.SecureRandom()): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    /** Quotes [s] for a POSIX shell. */
    fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun pem(s: String, kind: String) = Regex("-----BEGIN $kind-----[\\s\\S]+?-----END $kind-----").find(s)?.value

    /** PEM certificate chain and private key from the form, or an error message. */
    fun certificate(o: JSONObject): Result<Pair<String, String>> {
        val cert = o.optString("certificate").trim()
        val key = o.optString("privateKey").trim()
        if (pem(cert, "CERTIFICATE") == null) return Result.failure(IllegalArgumentException("Paste the certificate (the text starting with -----BEGIN CERTIFICATE-----)"))
        val keyOk = listOf("PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY").any { pem(key, it) != null }
        if (!keyOk) return Result.failure(IllegalArgumentException("Paste the private key (the text starting with -----BEGIN PRIVATE KEY-----)"))
        if (cert.length > 64 * 1024 || key.length > 16 * 1024) return Result.failure(IllegalArgumentException("That's too long to be a certificate"))
        return Result.success(cert to key)
    }
}
