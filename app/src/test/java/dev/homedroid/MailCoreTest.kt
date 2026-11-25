package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MailCoreTest {
    private fun setup(mode: String = "direct", address: String = "203.0.113.7", extra: JSONObject.() -> Unit = {}): MailCore.Setup =
        MailCore.setup(JSONObject().put("domain", "Example.COM").put("hostname", "mail.example.com").put("mode", mode).put("address", address).apply(extra), null).getOrThrow()

    private fun relayBody() = JSONObject().put("host", "smtp.relay.example.net").put("port", 587).put("username", "u").put("password", "p@ss'word")

    private val key2048 = "v=DKIM1; k=rsa; h=sha256; p=" + "A".repeat(392)

    @Test fun setupAnswersAreValidatedAndNormalized() {
        val s = setup()
        assertEquals("example.com", s.domain)
        assertEquals("mail.example.com", s.hostname)
        assertEquals("none", s.dmarcPolicy)
        assertTrue(MailCore.setup(JSONObject().put("domain", "localhost"), null).isFailure)
        assertTrue(MailCore.setup(JSONObject().put("domain", "example.com").put("mode", "teleport"), null).isFailure)
        assertTrue(MailCore.setup(JSONObject().put("domain", "example.com").put("address", "not an address"), null).isFailure)
        assertTrue("relay mode needs a relay", MailCore.setup(JSONObject().put("domain", "example.com").put("mode", "relay"), null).isFailure)
        assertEquals("mail.example.com", MailCore.setup(JSONObject().put("domain", "example.com"), null).getOrThrow().hostname)
    }

    @Test fun aMaskedRelayPasswordKeepsTheOldOne() {
        val old = setup("relay") { put("relay", relayBody()) }
        val next = MailCore.setup(JSONObject().put("domain", "example.com").put("mode", "relay")
            .put("relay", relayBody().put("password", MailCore.MASK)), old).getOrThrow()
        assertEquals("p@ss'word", next.relay!!.password)
        assertEquals(MailCore.MASK, next.toJson().getJSONObject("relay").getString("password"))
        assertEquals("p@ss'word", next.toJson(maskSecrets = false).getJSONObject("relay").getString("password"))
    }

    @Test fun spfIncludesAreCleanedAndLimited() {
        val s = setup("relay") { put("relay", relayBody()); put("spfInclude", "include:spf.relay.example.net, spf.other.example.org") }
        assertEquals(listOf("spf.relay.example.net", "spf.other.example.org"), s.spfInclude)
        assertTrue(MailCore.setup(JSONObject().put("domain", "example.com").put("spfInclude", "bad host!"), null).isFailure)
    }

    @Test fun planMovesEveryListenerAndTheRulesOffPort25() {
        val lines = MailCore.plan(setup()).trim().lines().map { JSONObject(it) }
        val listeners = lines.first { it.getString("object") == "NetworkListener" }
        assertEquals("reconcile", listeners.getString("@type"))
        val binds = listeners.getJSONObject("value").keys().asSequence().map { listeners.getJSONObject("value").getJSONObject(it).getJSONObject("bind").keys().next() }.toSet()
        assertEquals(setOf("0.0.0.0:2525", "0.0.0.0:2587", "0.0.0.0:4650", "0.0.0.0:9930", "0.0.0.0:8825"), binds)
        for (b in binds) assertTrue("no port below 1024: $b", b.substringAfter(':').toInt() >= 1024)
        val auth = lines.first { it.getString("object") == "MtaStageAuth" }.getJSONObject("value")
        assertEquals("local_port != 2525", auth.getJSONObject("require").getString("else"))
        assertTrue(auth.getJSONObject("saslMechanisms").getJSONObject("match").getJSONObject("0").getString("if").contains("local_port != 2525"))
        val verify = lines.first { it.getString("object") == "SenderAuth" }.getJSONObject("value")
        assertEquals("local_port == 2525", verify.getJSONObject("spfFromVerify").getJSONObject("match").getJSONObject("0").getString("if"))
        assertFalse("no 25 left in the plan", MailCore.plan(setup()).contains("local_port == 25\""))
    }

    @Test fun planUsesOneRsaDkimKeyThatDoesNotRotate() {
        val d = MailCore.plan(setup()).lines().map { if (it.isBlank()) JSONObject() else JSONObject(it) }
            .first { it.optString("object") == "Domain" }.getJSONObject("value").getJSONObject("dom-0")
        assertEquals("example.com", d.getString("name"))
        val dkim = d.getJSONObject("dkimManagement")
        assertEquals("Automatic", dkim.getString("@type"))
        assertEquals(1, dkim.getJSONObject("algorithms").length())
        assertTrue(dkim.getLong("rotateAfter") >= 3650L * 24 * 3600 * 1000)
    }

    @Test fun relayModeAddsARouteAndSendsEverythingThroughIt() {
        val lines = MailCore.plan(setup("relay") { put("relay", relayBody()) }).trim().lines().map { JSONObject(it) }
        val route = lines.first { it.getString("object") == "MtaRoute" }.getJSONObject("value").getJSONObject("r-relay")
        assertEquals("Relay", route.getString("@type"))
        assertEquals("smtp.relay.example.net", route.getString("host"))
        assertEquals("p@ss'word", route.getJSONObject("authSecret").getString("secret"))
        val strategy = lines.first { it.getString("object") == "MtaOutboundStrategy" }.getJSONObject("value").getJSONObject("route")
        assertEquals("'relay'", strategy.getString("else"))
        assertEquals("'local'", strategy.getJSONObject("match").getJSONObject("0").getString("then"))
        val direct = MailCore.plan(setup()).trim().lines().map { JSONObject(it) }
        assertTrue(direct.none { it.getString("object") == "MtaRoute" })
        assertEquals("'mx'", direct.first { it.getString("object") == "MtaOutboundStrategy" }.getJSONObject("value").getJSONObject("route").getString("else"))
    }

    @Test fun bootstrapSkipsAcmeAndKeepsMemoryLow() {
        val b = MailCore.bootstrap(setup())
        assertFalse(b.getBoolean("requestTlsCertificate"))
        assertFalse(b.getBoolean("generateDkimKeys"))
        assertEquals("Sqlite", b.getJSONObject("dataStore").getString("@type"))
        assertEquals("example.com", b.getString("defaultDomain"))
    }

    @Test fun longDkimKeysSplitIntoStringsOf255() {
        val parts = MailCore.splitTxt(key2048)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 255 })
        assertEquals(key2048, parts.joinToString(""))
        assertEquals(listOf("short"), MailCore.splitTxt("short"))
        assertEquals(listOf(""), MailCore.splitTxt(""))
        val zone = MailCore.zoneTxt(key2048)
        assertTrue(zone.startsWith("(\"") && zone.endsWith("\")"))
        assertEquals("\"v=spf1 mx -all\"", MailCore.zoneTxt("v=spf1 mx -all"))
        assertEquals("\"say \\\"hi\\\"\"", MailCore.zoneTxt("say \"hi\""))
    }

    @Test fun dkimKeysAreReadFromStalwartsZoneFile() {
        val zone = """
            v1-rsa-20261124._domainkey.example.com. IN TXT (
                "v=DKIM1; k=rsa; h=sha256; p=MIIBIjANBg"
                "kqhkiG9w0BAQEFAAOCAQ8A"
            )
            mail.example.com. IN TXT "v=spf1 a -all"
            example.com. IN MX 10 mail.example.com.
            v1-ed25519-20261124._domainkey.example.com. IN TXT "v=DKIM1; k=ed25519; h=sha256; p=abc="
        """.trimIndent()
        val keys = MailCore.parseDkim(zone)
        assertEquals(2, keys.size)
        assertEquals("v1-rsa-20261124", keys[0].selector)
        assertEquals("v1-rsa-20261124._domainkey.example.com", keys[0].name)
        assertEquals("v=DKIM1; k=rsa; h=sha256; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A", keys[0].value)
        assertEquals("v=DKIM1; k=ed25519; h=sha256; p=abc=", keys[1].value)
        assertTrue(MailCore.parseDkim("").isEmpty())
    }

    @Test fun spfFollowsTheModeAndAddress() {
        assertEquals("v=spf1 mx ip4:203.0.113.7 ~all", MailCore.spf(setup()))
        assertEquals("v=spf1 mx ip6:2001:db8::1 ~all", MailCore.spf(setup(address = "2001:db8::1")))
        assertEquals("v=spf1 include:spf.relay.example.net ~all", MailCore.spf(setup("relay") { put("relay", relayBody()); put("spfInclude", "spf.relay.example.net") }))
        assertEquals("nobody sends, so nobody may", "v=spf1 -all", MailCore.spf(setup("receive_only")))
        assertEquals("v=spf1 include:spf.r.example ~all", MailCore.spf(setup("receive_only") { put("relay", relayBody()); put("spfInclude", "spf.r.example") }))
    }

    @Test fun dmarcCarriesThePolicyAndReportAddress() {
        assertEquals("v=DMARC1; p=none", MailCore.dmarc(setup()))
        assertEquals("v=DMARC1; p=quarantine; rua=mailto:dmarc@example.com", MailCore.dmarc(setup { put("dmarcPolicy", "quarantine"); put("reportEmail", "dmarc@example.com") }))
        assertTrue(MailCore.setup(JSONObject().put("domain", "example.com").put("dmarcPolicy", "maybe"), null).isFailure)
    }

    @Test fun dnsRecordsCoverWhatReceivingNeeds() {
        val dkim = listOf(MailCore.DkimKey("v1-rsa-20261124", "v1-rsa-20261124._domainkey.example.com", key2048))
        val recs = MailCore.dnsRecords(setup(), dkim).associateBy { it.id }
        assertEquals("203.0.113.7", recs.getValue("address").value)
        assertEquals("A", recs.getValue("address").type)
        assertEquals("mail", recs.getValue("address").host)
        assertEquals("mail.example.com.", recs.getValue("mx").value)
        assertEquals(10, recs.getValue("mx").priority)
        assertEquals("example.com. IN MX 10 mail.example.com.", recs.getValue("mx").zoneLine())
        assertEquals("_dmarc.example.com", recs.getValue("dmarc").name)
        assertEquals("v1-rsa-20261124._domainkey", recs.getValue("dkim-v1-rsa-20261124").host)
        assertTrue(recs.getValue("dkim-v1-rsa-20261124").zoneLine().contains("(\""))
        assertEquals(listOf("example.com"), listOf(recs.getValue("spf").name))
        assertEquals("optional", recs.getValue("srv-imaps").group)
        assertEquals("1 993 mail.example.com.", recs.getValue("srv-imaps").value)
        assertEquals("version: STSv1\nmode: testing\nmx: mail.example.com\nmax_age: 86400\n", recs.getValue("mta-sts").extra)
        // JSON for the dashboard carries the chunks and the zone line.
        val j = recs.getValue("dkim-v1-rsa-20261124").toJson()
        assertTrue(j.getJSONArray("chunks").length() > 1)
        assertEquals(JSONObject.NULL, recs.getValue("mx").toJson().get("chunks"))
    }

    @Test fun dnsRecordsHandleDdnsNamesAndMissingKeys() {
        val ddns = MailCore.dnsRecords(setup(address = "myhome.duckdns.org"), emptyList()).associateBy { it.id }
        assertEquals("CNAME", ddns.getValue("address").type)
        assertEquals("myhome.duckdns.org.", ddns.getValue("address").value)
        assertTrue("no key yet", ddns.getValue("dkim").pending)
        val blank = MailCore.dnsRecords(setup(address = ""), emptyList()).associateBy { it.id }
        assertTrue(blank.getValue("address").pending)
        // A hostname that is itself a dynamic DNS name needs no record at the domain's host.
        val outside = MailCore.setup(JSONObject().put("domain", "example.com").put("hostname", "myhome.duckdns.org").put("address", "myhome.duckdns.org"), null).getOrThrow()
        assertFalse(MailCore.dnsRecords(outside, emptyList()).any { it.id == "address" })
        assertEquals("myhome.duckdns.org.", MailCore.dnsRecords(outside, emptyList()).first { it.id == "mx" }.value)
        // A relay needs no helo SPF record.
        assertNull(MailCore.dnsRecords(setup("relay") { put("relay", relayBody()) }, emptyList()).firstOrNull { it.id == "spf-helo" })
    }

    @Test fun dnsAnswersAreComparedLeniently() {
        val s = setup()
        val dkim = MailCore.DkimKey("sel", "sel._domainkey.example.com", key2048)
        val recs = MailCore.dnsRecords(s, listOf(dkim)).associateBy { it.id }
        // DNS over HTTPS shows long TXT values as several quoted strings.
        val quoted = MailCore.splitTxt(key2048).joinToString(" ") { "\"$it\"" }
        assertEquals("ok", MailCore.compare(recs.getValue("dkim-sel"), listOf(quoted)))
        assertEquals("ok", MailCore.compare(recs.getValue("dkim-sel"), listOf("\"other\"", key2048)))
        assertEquals("missing", MailCore.compare(recs.getValue("dkim-sel"), listOf("\"google-site-verification=x\"")))
        assertEquals("different", MailCore.compare(recs.getValue("dkim-sel"), listOf("\"v=DKIM1; k=rsa; p=zzz\"")))
        assertEquals("ok", MailCore.compare(recs.getValue("spf"), listOf("\"google-site-verification=x\"", "\"V=SPF1  mx ip4:203.0.113.7 ~all\"")))
        assertEquals("different", MailCore.compare(recs.getValue("spf"), listOf("\"v=spf1 -all\"")))
        assertEquals("missing", MailCore.compare(recs.getValue("spf"), emptyList()))
        assertEquals("ok", MailCore.compare(recs.getValue("mx"), listOf("10 Mail.Example.com.")))
        assertEquals("different", MailCore.compare(recs.getValue("mx"), listOf("10 mx.other.net.")))
        assertEquals("ok", MailCore.compare(recs.getValue("address"), listOf("203.0.113.7")))
        assertEquals("different", MailCore.compare(recs.getValue("address"), listOf("198.51.100.1")))
        assertEquals("ok", MailCore.compare(recs.getValue("srv-imaps"), listOf("0 1 993 mail.example.com.")))
        assertEquals("different", MailCore.compare(recs.getValue("srv-imaps"), listOf("0 1 143 mail.example.com.")))
        assertEquals("ok", MailCore.compare(recs.getValue("autoconfig"), listOf("mail.example.com.")))
        assertEquals("a", MailCore.txtAnswer("\"a\""))
        assertEquals("ab", MailCore.txtAnswer("\"a\" \"b\""))
        assertEquals("raw", MailCore.txtAnswer("raw"))
    }

    @Test fun clientSettingsListLanAndPublicPorts() {
        val j = MailCore.clientSettings(setup(), "alice@example.com", "192.168.1.20")
        assertEquals("alice@example.com", j.getJSONObject("home").getJSONObject("incoming").getString("username"))
        assertEquals(9930, j.getJSONObject("home").getJSONObject("incoming").getInt("port"))
        assertEquals("192.168.1.20", j.getJSONObject("home").getJSONObject("incoming").getString("server"))
        assertEquals(993, j.getJSONObject("away").getJSONObject("incoming").getInt("port"))
        assertEquals("mail.example.com", j.getJSONObject("away").getJSONObject("outgoing").getString("server"))
        assertEquals(465, j.getJSONObject("away").getJSONObject("outgoing").getInt("port"))
        assertEquals(587, j.getJSONObject("away").getJSONObject("outgoing").getJSONObject("alternative").getInt("port"))
        assertEquals("http://192.168.1.20:8825/admin", j.getString("webAdmin"))
        assertEquals(JSONObject.NULL, MailCore.clientSettings(setup(), "a@example.com", null).get("webAdmin"))
    }

    @Test fun accountNamesPasswordsAndShellQuoting() {
        assertEquals("alice.b", MailCore.accountName(" Alice.B "))
        assertNull(MailCore.accountName("a b"))
        assertNull(MailCore.accountName("-x"))
        assertNull(MailCore.accountName("a@b.c"))
        assertTrue(MailCore.validPassword("12345678"))
        assertFalse(MailCore.validPassword("short"))
        assertEquals(19, MailCore.generatePassword().length)
        assertEquals("'it'\\''s'", MailCore.shellQuote("it's"))
        assertEquals("'a b;rm -rf /'", MailCore.shellQuote("a b;rm -rf /"))
    }

    @Test fun certificatesMustLookLikePem() {
        val cert = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        val key = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"
        assertTrue(MailCore.certificate(JSONObject().put("certificate", cert).put("privateKey", key)).isSuccess)
        assertTrue(MailCore.certificate(JSONObject().put("certificate", cert).put("privateKey", "hello")).isFailure)
        assertTrue(MailCore.certificate(JSONObject().put("certificate", "hello").put("privateKey", key)).isFailure)
        assertTrue(MailCore.certificate(JSONObject().put("certificate", cert).put("privateKey", key.replace("PRIVATE KEY", "RSA PRIVATE KEY"))).isSuccess)
    }

    @Test fun dnsNamesAndAddresses() {
        assertEquals("mail.example.com", MailCore.dnsName(" Mail.Example.COM. "))
        assertNull(MailCore.dnsName("example"))
        assertNull(MailCore.dnsName("-bad.example.com"))
        assertNull(MailCore.dnsName("1.2.3.4"))
        assertTrue(MailCore.isIpv4("203.0.113.7"))
        assertFalse(MailCore.isIpv4("256.1.1.1"))
        assertTrue(MailCore.isIpv6("2001:db8::1"))
        assertFalse(MailCore.isIpv6("hello"))
        assertTrue(MailCore.validEmail("a@b.example"))
        assertFalse(MailCore.validEmail("a@b"))
    }
}
