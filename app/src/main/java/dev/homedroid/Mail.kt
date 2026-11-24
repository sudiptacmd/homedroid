package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The Mail page's backend (/api/mail): setup wizard answers, the DNS records to add, health checks,
 * mail app settings and accounts for the Stalwart app. The pure parts are in [MailCore]; Stalwart
 * itself is configured by writing files the app's launcher applies (see apps.json) and by running
 * stalwart-cli inside Alpine against the server's local admin port.
 */
class Mail(private val cfg: Config, private val apps: Apps, private val alpine: Alpine) {
    private val dir get() = File(alpine.root, "var/lib/stalwart")
    private val setupDir get() = File(dir, "setup")
    private val cliLock = Any()
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "mail-check").apply { isDaemon = true } }

    private val app get() = apps.catalog.firstOrNull { it.id == APP }

    fun handle(r: Request, seg: List<String>): Response {
        return try {
            when {
                r.method == "GET" && seg.isEmpty() -> Response.json(status())
                r.method == "POST" && seg == listOf("setup") -> saveSetup(r.json())
                r.method == "GET" && seg == listOf("dns") -> dns()
                r.method == "POST" && seg == listOf("check") -> check()
                r.method == "GET" && seg == listOf("clients") -> clients(r.query["user"].orEmpty())
                r.method == "GET" && seg == listOf("accounts") -> accounts()
                r.method == "POST" && seg == listOf("accounts") -> createAccount(r.json())
                r.method == "POST" && seg.size == 3 && seg[0] == "accounts" && seg[2] == "password" -> resetPassword(seg[1], r.json())
                r.method == "DELETE" && seg.size == 2 && seg[0] == "accounts" -> deleteAccount(seg[1])
                r.method == "POST" && seg == listOf("certificate") -> certificate(r.json())
                else -> Response.error(404, "No such mail endpoint")
            }
        } catch (e: IllegalArgumentException) {
            Response.error(400, e.message ?: "Invalid input")
        } catch (e: IllegalStateException) {
            Response.error(409, e.message ?: "The mail server isn't ready")
        }
    }

    // --- state -----------------------------------------------------------------------------------

    private fun installed() = app?.let(apps::isInstalled) == true

    private fun daemon() = ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == APP }

    private fun savedSetup(): MailCore.Setup? = try {
        MailCore.setup(JSONObject(File(setupDir, "setup.json").readText()), null).getOrNull()
    } catch (_: Exception) { null }

    private fun adminUser() = File(dir, "admin-user").takeIf { it.exists() }?.readText()?.trim()
    private fun adminPassword() = File(dir, "admin-password").takeIf { it.exists() }?.readText()?.trim()

    private fun lan() = Device.localIps()

    private fun listening(port: Int, host: String = "127.0.0.1") = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 400); true }
    } catch (_: Exception) { false }

    private fun status(): JSONObject {
        val installed = installed()
        val setup = savedSetup()
        val d = daemon()
        val ready = installed && listening(MailCore.Ports.HTTP)
        val phase = when {
            !installed -> "not_installed"
            setup == null -> "needs_setup"
            ready -> "ready"
            d == null || d.state == Supervisor.State.STOPPED -> "stopped"
            else -> "starting"
        }
        val ports = listOf(
            Triple("SMTP: mail from other servers", MailCore.Ports.SMTP, "smtp"),
            Triple("Submission (STARTTLS): sending from mail apps", MailCore.Ports.SUBMISSION, "smtp"),
            Triple("Submission over TLS: sending from mail apps", MailCore.Ports.SUBMISSIONS, "smtp"),
            Triple("IMAP over TLS: reading mail in apps", MailCore.Ports.IMAPS, "imap"),
            Triple("Web admin, JMAP and auto-setup (plain HTTP)", MailCore.Ports.HTTP, "http"),
        ).map { (name, port, proto) ->
            JSONObject().put("name", name).put("protocol", proto).put("local", port)
                .put("public", MailCore.Ports.PUBLIC.getValue(port)).put("listening", installed && listening(port))
        }
        val ip = lan().firstOrNull()
        return JSONObject()
            .put("installed", installed).put("enabled", app?.let { !cfg.isDisabled(it.id) } ?: false)
            .put("phase", phase)
            .put("state", d?.state?.name?.lowercase() ?: "stopped")
            .put("version", File(alpine.root, "usr/local/share/stalwart.version").takeIf { it.exists() }?.readText()?.trim()?.substringBefore('/') ?: JSONObject.NULL)
            .put("setup", setup?.toJson() ?: JSONObject.NULL)
            .put("admin", adminPassword()?.let { pw ->
                JSONObject().put("user", adminUser()).put("password", pw).put("url", ip?.let { "http://$it:${MailCore.Ports.HTTP}/admin" } ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("ports", JSONArray(ports))
            .put("lan", JSONArray(lan()))
            .put("modes", modes())
            .put("limits", limits())
            .put("log", JSONArray(d?.log?.tail(30).orEmpty()))
    }

    private fun modes() = JSONArray(listOf(
        JSONObject().put("id", MailCore.MODE_DIRECT).put("title", "Directly from my home internet")
            .put("summary", "Other mail servers connect to your phone through your router. You forward ports to the phone and your internet provider must allow it.")
            .put("needs", JSONArray(listOf(
                "Router port forwarding: 25 to ${MailCore.Ports.SMTP}, 465 to ${MailCore.Ports.SUBMISSIONS}, 587 to ${MailCore.Ports.SUBMISSION}, 993 to ${MailCore.Ports.IMAPS}",
                "A public IP address (not behind carrier-grade NAT), fixed or kept up to date with dynamic DNS",
                "Port 25 open in both directions. Most home providers block it.",
            ))),
        JSONObject().put("id", MailCore.MODE_RELAY).put("title", "Send through a relay (smarthost)")
            .put("summary", "Incoming mail still needs your router, but all outgoing mail goes through a provider's SMTP server, which avoids blocked port 25 and most spam-filter trouble.")
            .put("needs", JSONArray(listOf(
                "An account with an SMTP service: server, port, user name and password",
                "The SPF include value the provider tells you (like spf.provider.com)",
                "Port forwarding for incoming mail, as for direct delivery",
            ))),
        JSONObject().put("id", MailCore.MODE_RECEIVE_ONLY).put("title", "Receive only, through a small server (VPS)")
            .put("summary", "A cheap server with a public address accepts mail on port 25 and forwards it to the phone over a private link such as Tailscale or an SSH tunnel. Nothing needs to be opened at home.")
            .put("needs", JSONArray(listOf(
                "A VPS (or any machine with a public address) that forwards its port 25 to the phone's port ${MailCore.Ports.SMTP}",
                "The VPS's public address as the mail server's address, and its name as the hostname",
                "A relay as well if you also want to send mail",
            ))),
    ))

    private fun limits() = JSONArray(listOf(
        "Most home internet providers block port 25. If they do, direct delivery cannot work: use a relay for sending and a VPS for receiving.",
        "Phones behind carrier-grade NAT (many mobile and some home connections) have no public address. Nothing can reach them from outside without a VPS or a tunnel.",
        "Reverse DNS (the PTR record for your IP) is set by your internet provider, not by you. Without one that matches your mail hostname, big providers often send your mail to spam.",
        "A home IP address is often on spam lists. Even with perfect setup, mail to Gmail, Outlook and others may land in spam. A relay is the reliable fix.",
        "Mail is only received while the phone is on, charged and connected. Other servers retry for a few days, then give up and bounce the mail.",
        "Nothing here is backed up by itself. Copy the mail data off the phone regularly (see the guide).",
        "The certificate is self-signed until you install a real one. Mail apps ask you to accept it once.",
    ).map { JSONObject().put("text", it) })

    // --- setup -----------------------------------------------------------------------------------

    private fun saveSetup(body: JSONObject): Response {
        if (!installed()) return Response.error(409, "Install the Mail server (Stalwart) from Modules first")
        val s = MailCore.setup(body, savedSetup()).getOrElse { return Response.error(400, it.message ?: "Invalid setup") }
        synchronized(cliLock) {
            setupDir.mkdirs()
            val first = !File(dir, "etc/config.json").exists()
            write(File(setupDir, "setup.json"), s.toJson(maskSecrets = false).toString())
            write(File(setupDir, "plan.ndjson"), MailCore.plan(s))
            // The first start reads this; once the server has its database it is ignored.
            write(File(setupDir, "bootstrap.json"), MailCore.bootstrap(s).toString())
            Jobs.line("Mail: settings saved for ${s.domain}" + if (first) "; the mail server is creating its database" else "; applying them")
            // On the very first setup the launcher is waiting for the files; later it needs a restart.
            if (!first) daemon()?.restart()
        }
        return Response.json(status())
    }

    private fun write(f: File, text: String) {
        f.writeText(text)
        f.setReadable(false, false); f.setReadable(true, true); f.setWritable(true, true)
    }

    // --- DNS ---------------------------------------------------------------------------------------

    private fun dkimKeys(s: MailCore.Setup): List<MailCore.DkimKey> = try {
        domainZone(s.domain)?.let(MailCore::parseDkim).orEmpty()
    } catch (_: Exception) { emptyList() }

    private fun domainZone(domain: String): String? {
        if (!listening(MailCore.Ports.HTTP)) return null
        val ids = jsonLines(cli(listOf("query", "Domain", "--json", "--fields", "name")))
        val id = ids.firstOrNull { it.optString("name") == domain }?.optString("id") ?: return null
        return jsonLines(cli(listOf("get", "Domain", id, "--json"))).firstOrNull()?.optString("dnsZoneFile")
    }

    private fun dns(): Response {
        val s = savedSetup() ?: return Response.error(409, "Answer the setup questions first")
        val keys = dkimKeys(s)
        val records = MailCore.dnsRecords(s, keys)
        return Response.json(JSONObject()
            .put("domain", s.domain).put("hostname", s.hostname)
            .put("dkimReady", keys.isNotEmpty())
            .put("records", JSONArray(records.map { it.toJson() }))
            .put("zone", records.filter { !it.pending && it.group != "optional" }.joinToString("\n") { it.zoneLine() })
            .put("groups", JSONArray(listOf(
                JSONObject().put("id", "essential").put("title", "Needed to receive mail"),
                JSONObject().put("id", "authentication").put("title", "Needed so your mail isn't treated as spam"),
                JSONObject().put("id", "optional").put("title", "Optional"),
            ))))
    }

    // --- health checks -----------------------------------------------------------------------------

    private class Check(val id: String, val group: String, val title: String, val status: String, val detail: String, val fix: String = "") {
        fun json() = JSONObject().put("id", id).put("group", group).put("title", title).put("status", status).put("detail", detail).put("fix", fix)
    }

    private fun check(): Response {
        val s = savedSetup() ?: return Response.error(409, "Answer the setup questions first")
        val out = mutableListOf<Check>()
        val d = daemon()
        val running = listening(MailCore.Ports.HTTP)
        out += if (running) Check("server", "server", "Mail server is running", "ok", "Stalwart answers on port ${MailCore.Ports.HTTP}.")
        else Check("server", "server", "Mail server is running", "fail",
            if (d == null) "The server isn't running." else "Stalwart isn't answering yet (${d.state.name.lowercase()}). The first start takes a minute or two.",
            "Make sure the server is started in the Homedroid app and the Mail server module is on. Look at its log for errors.")
        for ((port, name) in listOf(MailCore.Ports.SMTP to "SMTP", MailCore.Ports.SUBMISSION to "Submission", MailCore.Ports.SUBMISSIONS to "Submission over TLS", MailCore.Ports.IMAPS to "IMAP over TLS")) {
            val up = listening(port)
            out += Check("port-$port", "ports", "$name on port $port (public ${MailCore.Ports.PUBLIC.getValue(port)})", if (up) "ok" else "fail",
                if (up) "Listening on the phone." else "Nothing is listening.", if (up) "" else "Wait for the server to finish starting, or check its log.")
        }
        val tasks = mutableListOf<Callable<List<Check>>>()
        tasks += Callable { tlsCheck(s) }
        tasks += Callable { networkChecks(s) }
        tasks += Callable { dnsChecks(s) }
        for (f in pool.invokeAll(tasks, 40, TimeUnit.SECONDS)) {
            try { out += f.get() } catch (_: Exception) { }
        }
        val counts = out.groupingBy { it.status }.eachCount()
        return Response.json(JSONObject()
            .put("checks", JSONArray(out.map { it.json() }))
            .put("summary", JSONObject().put("ok", counts["ok"] ?: 0).put("warn", counts["warn"] ?: 0).put("fail", counts["fail"] ?: 0))
            .put("note", "These checks run from the phone. Whether the internet can reach it can only be tested from outside: send a message to this domain from another provider's mailbox."))
    }

    // Trusting every certificate is deliberate: only the certificate is read, to show its details; no data is sent.
    @android.annotation.SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun tlsCheck(s: MailCore.Setup): List<Check> {
        val title = "TLS certificate"
        if (!listening(MailCore.Ports.IMAPS)) return listOf(Check("tls", "tls", title, "fail", "The server isn't answering, so the certificate can't be read."))
        return try {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
            // Only the certificate is read; nothing is sent over this connection.
            val cert = (ctx.socketFactory.createSocket("127.0.0.1", MailCore.Ports.IMAPS) as SSLSocket).use { sock ->
                sock.soTimeout = 4000
                val params = sock.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(s.hostname))
                sock.sslParameters = params
                sock.startHandshake()
                sock.session.peerCertificates.first() as X509Certificate
            }
            val self = cert.subjectX500Principal == cert.issuerX500Principal
            val expired = cert.notAfter.time < System.currentTimeMillis()
            val names = cert.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1) as? String }
            val matches = names.any { n -> n.equals(s.hostname, true) || n.startsWith("*.") && s.hostname.endsWith(n.drop(1)) }
            val until = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cert.notAfter)
            listOf(when {
                expired -> Check("tls", "tls", title, "fail", "The certificate expired on $until.", "Install a new certificate with the Mail page's certificate form.")
                self -> Check("tls", "tls", title, "warn", "Self-signed. Mail apps will ask you to trust it, and other mail servers can't verify it (most accept that).",
                    "For a trusted certificate, get one for ${s.hostname} elsewhere (for example with a DNS challenge) and install it from the certificate form.")
                !matches -> Check("tls", "tls", title, "warn", "The certificate isn't issued for ${s.hostname} (it covers ${names.joinToString().ifEmpty { "other names" }}).", "Install one for ${s.hostname}.")
                else -> Check("tls", "tls", title, "ok", "Issued for ${s.hostname}, valid until $until.")
            })
        } catch (e: Exception) {
            listOf(Check("tls", "tls", title, "fail", "Couldn't read the certificate: ${e.message}"))
        }
    }

    private fun networkChecks(s: MailCore.Setup): List<Check> {
        val out = mutableListOf<Check>()
        // Outbound port 25: needed to send directly to other servers.
        if (s.mode == MailCore.MODE_DIRECT) {
            val open = try { Socket().use { it.connect(InetSocketAddress("gmail-smtp-in.l.google.com", 25), 5000); true } } catch (_: Exception) { false }
            out += if (open) Check("out25", "network", "Sending directly (outbound port 25)", "ok", "This connection can reach other mail servers on port 25.")
            else Check("out25", "network", "Sending directly (outbound port 25)", "fail", "Port 25 is blocked or unreachable from here. Most home providers do this.",
                "Use \"Send through a relay\" in the setup. Receiving mail can still work.")
        }
        s.relay?.let { r ->
            val up = try { Socket().use { it.connect(InetSocketAddress(r.host, r.port), 5000); true } } catch (_: Exception) { false }
            out += if (up) Check("relay", "network", "Relay ${r.host}:${r.port}", "ok", "The relay's server answers.")
            else Check("relay", "network", "Relay ${r.host}:${r.port}", "fail", "Can't connect to the relay.", "Check the server name and port. Some networks block port 587; try 2525 or 465 if your provider offers it.")
        }
        val ip = publicIp()
        if (ip == null) {
            out += Check("public-ip", "network", "Public address", "warn", "Couldn't find out your public IP address (no internet?).")
            return out
        }
        out += Check("public-ip", "network", "Public address", "info", "The internet sees this phone's connection as $ip.",
            "If your router's own WAN address differs from $ip (or starts with 100.64 to 100.127), your provider shares addresses between customers (CGNAT) and incoming mail can't reach you.")
        val hostIps = dohAnswers(s.hostname, "A").orEmpty().map { it.trim() }.filter(MailCore::isIpv4)
        out += when {
            hostIps.isEmpty() && s.mode != MailCore.MODE_RECEIVE_ONLY -> Check("host-ip", "network", "${s.hostname} points to this connection", "fail", "${s.hostname} has no address in DNS yet.", "Add the address record from the DNS list.")
            s.mode == MailCore.MODE_RECEIVE_ONLY -> Check("host-ip", "network", "${s.hostname} address", "info", "In receive-only mode ${s.hostname} should lead to your VPS: ${hostIps.joinToString().ifEmpty { "not found yet" }}.")
            ip in hostIps -> Check("host-ip", "network", "${s.hostname} points to this connection", "ok", "${s.hostname} leads to $ip.")
            else -> Check("host-ip", "network", "${s.hostname} points to this connection", "fail", "${s.hostname} leads to ${hostIps.joinToString()}, but this connection is $ip.", "Update the address record, or your dynamic DNS service, to $ip.")
        }
        if (s.mode == MailCore.MODE_DIRECT) {
            val ptr = dohAnswers(ip.split('.').reversed().joinToString(".") + ".in-addr.arpa", "PTR").orEmpty().map { it.trim().removeSuffix(".").lowercase() }
            out += when {
                ptr.isEmpty() -> Check("ptr", "network", "Reverse DNS (PTR) of $ip", "warn", "No reverse DNS is set. Big mail providers may reject or junk your mail.", "Ask your internet provider to set the PTR record of $ip to ${s.hostname}. Only they can.")
                s.hostname in ptr -> Check("ptr", "network", "Reverse DNS (PTR) of $ip", "ok", "$ip is named ${s.hostname}.")
                else -> Check("ptr", "network", "Reverse DNS (PTR) of $ip", "warn", "$ip is named ${ptr.joinToString()}, not ${s.hostname}.", "Ask your internet provider to set it to ${s.hostname}.")
            }
        }
        return out
    }

    private fun dnsChecks(s: MailCore.Setup): List<Check> {
        val records = MailCore.dnsRecords(s, dkimKeys(s))
        val futures = records.map { rec ->
            rec to pool.submit<List<String>?> { dohAnswers(rec.name, rec.type) }
        }
        return futures.map { (rec, f) ->
            val group = "dns"
            val title = "${rec.type} ${rec.name}"
            val optional = rec.group == "optional"
            if (rec.pending) return@map Check("dns-${rec.id}", group, title, if (optional) "info" else "warn", "Not ready yet: ${rec.note}")
            val answers = try { f.get(15, TimeUnit.SECONDS) } catch (_: Exception) { null }
                ?: return@map Check("dns-${rec.id}", group, title, "warn", "Couldn't look this up (no internet, or the DNS service didn't answer).")
            when (val state = MailCore.compare(rec, answers)) {
                "ok" -> Check("dns-${rec.id}", group, title, "ok", "Found, and it matches.")
                "missing" -> Check("dns-${rec.id}", group, title, if (optional) "info" else "fail", MailCore.hintFor(rec, state), if (optional) "" else "Add the record from the DNS list.")
                else -> Check("dns-${rec.id}", group, title, "warn", MailCore.hintFor(rec, state), "Compare it with the suggested record.")
            }
        }
    }

    private fun publicIp(): String? {
        for (url in DdnsCore.IPV4_SOURCES) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000
                val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                DdnsCore.parseIp(text, v6 = false)?.let { return it }
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * Answers for [name] as public DNS sees them, through DNS over HTTPS; null if no service answered,
     * empty if the name or record doesn't exist.
     */
    private fun dohAnswers(name: String, type: String): List<String>? {
        val code = mapOf("A" to 1, "NS" to 2, "CNAME" to 5, "PTR" to 12, "MX" to 15, "TXT" to 16, "AAAA" to 28, "SRV" to 33).getValue(type)
        for (base in listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve")) {
            try {
                val c = URL("$base?name=${URLEncoder.encode(name, "UTF-8")}&type=$type").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.setRequestProperty("Accept", "application/dns-json")
                if (c.responseCode != 200) continue
                val j = JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                val answers = j.optJSONArray("Answer") ?: return emptyList()
                return (0 until answers.length()).map { answers.getJSONObject(it) }.filter { it.optInt("type") == code }.map { it.getString("data") }
            } catch (_: Exception) { }
        }
        return null
    }

    // --- mail app settings -------------------------------------------------------------------------------

    private fun clients(user: String): Response {
        val s = savedSetup() ?: return Response.error(409, "Answer the setup questions first")
        val name = if ('@' in user) user.substringBefore('@') else user
        val email = (MailCore.accountName(name) ?: "you") + "@" + s.domain
        return Response.json(MailCore.clientSettings(s, email, lan().firstOrNull()))
    }

    // --- accounts ----------------------------------------------------------------------------------------------

    /** Runs stalwart-cli against the local server as the admin; the output lines. */
    private fun cli(args: List<String>): List<String> {
        val pw = adminPassword() ?: throw IllegalStateException("The mail server hasn't finished its first start yet")
        val user = adminUser().orEmpty()
        if (!listening(MailCore.Ports.HTTP)) throw IllegalStateException("The mail server isn't running")
        val out = mutableListOf<String>()
        val script = "exec stalwart-cli --no-color " + args.joinToString(" ", transform = MailCore::shellQuote)
        val rc = synchronized(cliLock) {
            alpine.run(script, { out += it }, mapOf("STALWART_URL" to "http://127.0.0.1:${MailCore.Ports.HTTP}", "STALWART_USER" to user, "STALWART_PASSWORD" to pw))
        }
        if (rc != 0) throw IllegalStateException(out.lastOrNull { it.isNotBlank() }?.removePrefix("error: ") ?: "The mail server refused the request")
        return out
    }

    private fun jsonLines(lines: List<String>) = lines.filter { it.startsWith("{") }.mapNotNull { try { JSONObject(it) } catch (_: Exception) { null } }

    private fun accountRows(): List<JSONObject> = jsonLines(cli(listOf("query", "Account", "--json", "--fields", "name,emailAddress")))

    private fun accounts(): Response {
        if (!listening(MailCore.Ports.HTTP)) return Response.error(409, "The mail server isn't running")
        val admin = adminUser()
        val rows = accountRows().map {
            JSONObject().put("id", it.optString("id")).put("name", it.optString("name")).put("email", it.optString("emailAddress"))
                .put("admin", it.optString("emailAddress") == admin)
        }
        return Response.json(JSONObject().put("accounts", JSONArray(rows)))
    }

    private fun createAccount(body: JSONObject): Response {
        val s = savedSetup() ?: return Response.error(409, "Answer the setup questions first")
        val name = MailCore.accountName(body.optString("name")) ?: return Response.error(400, "Use letters, digits, dots, dashes or underscores for the name, like alice")
        val given = body.optString("password")
        if (given.isNotEmpty() && !MailCore.validPassword(given)) return Response.error(400, "The password needs 8 to 128 characters")
        val password = given.ifEmpty { MailCore.generatePassword() }
        val domainId = jsonLines(cli(listOf("query", "Domain", "--json", "--fields", "name"))).firstOrNull { it.optString("name") == s.domain }?.optString("id")
            ?: return Response.error(409, "The domain ${s.domain} isn't in the mail server yet; wait for it to finish starting")
        val account = JSONObject().put("@type", "User").put("name", name).put("domainId", domainId)
            .put("credentials", JSONObject().put("0", JSONObject().put("@type", "Password").put("secret", password)))
        val out = cli(listOf("create", "Account", "--json", account.toString()))
        val id = out.firstNotNullOfOrNull { Regex("Created Account (\\S+)").find(it)?.groupValues?.get(1) }
        return Response.json(JSONObject().put("id", id ?: JSONObject.NULL).put("email", "$name@${s.domain}").put("password", password)
            .put("generated", given.isEmpty()))
    }

    private fun resetPassword(id: String, body: JSONObject): Response {
        if (!Regex("[A-Za-z0-9]{1,32}").matches(id)) return Response.error(400, "Invalid account")
        val given = body.optString("password")
        if (given.isNotEmpty() && !MailCore.validPassword(given)) return Response.error(400, "The password needs 8 to 128 characters")
        val password = given.ifEmpty { MailCore.generatePassword() }
        cli(listOf("update", "Account", id, "--json", JSONObject().put("credentials", JSONObject().put("0", JSONObject().put("@type", "Password").put("secret", password))).toString()))
        return Response.json(JSONObject().put("password", password).put("generated", given.isEmpty()))
    }

    private fun deleteAccount(id: String): Response {
        if (!Regex("[A-Za-z0-9]{1,32}").matches(id)) return Response.error(400, "Invalid account")
        val row = accountRows().firstOrNull { it.optString("id") == id } ?: return Response.error(404, "No such account")
        if (row.optString("emailAddress") == adminUser()) return Response.error(409, "The admin account can't be deleted here")
        cli(listOf("delete", "Account", "--ids", id))
        return Response.ok()
    }

    // --- certificate -----------------------------------------------------------------------------------------------

    private fun certificate(body: JSONObject): Response {
        val (cert, key) = MailCore.certificate(body).getOrElse { return Response.error(400, it.message ?: "Invalid certificate") }
        val f = File(setupDir, "certificate.json")
        try {
            setupDir.mkdirs()
            write(f, JSONObject().put("certificate", JSONObject().put("@type", "Text").put("value", cert))
                .put("privateKey", JSONObject().put("@type", "Text").put("secret", key)).toString())
            val before = jsonLines(cli(listOf("query", "Certificate", "--json"))).map { it.optString("id") }
            val out = cli(listOf("create", "Certificate", "--file", "/var/lib/stalwart/setup/certificate.json"))
            val id = out.firstNotNullOfOrNull { Regex("Created Certificate (\\S+)").find(it)?.groupValues?.get(1) }
                ?: return Response.error(502, "The mail server didn't report the new certificate")
            cli(listOf("update", "SystemSettings", "--json", JSONObject().put("defaultCertificateId", id).toString()))
            if (before.isNotEmpty()) runCatching { cli(listOf("delete", "Certificate", "--ids", before.joinToString(","))) }
        } finally { f.delete() }
        // Listeners pick the certificate up on a restart.
        daemon()?.restart()
        return Response.ok()
    }

    companion object {
        private const val APP = "stalwart"
    }
}
