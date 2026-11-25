package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DomainsCoreTest {
    private val default = """
        {
        	# Ports < 1024 need root and public TLS is terminated by the Cloudflare tunnel.
        	auto_https off
        }

        :8080 {
        	root * {${'$'}HOME}/www
        	encode gzip
        	file_server
        }
    """.trimIndent() + "\n"

    @Test fun hostsAndPathsAreValidated() {
        assertEquals("blog.example.com", DomainsCore.host(" Blog.Example.COM. "))
        for (bad in listOf("", "localhost", "*.example.com", "-a.example.com", "a_b.example.com", "1.2.3.4", "a..b", "a b.com", "x".repeat(64) + ".com"))
            assertNull(bad, DomainsCore.host(bad))
        assertEquals("/api", DomainsCore.routePath("/api/"))
        assertEquals("/api/v1", DomainsCore.routePath("/api/v1"))
        for (bad in listOf("", "/", "api", "/a/../b", "/a b", "/a{x}", "/a\"", "/a*", "/a//b"))
            assertNull(bad, DomainsCore.routePath(bad))
    }

    @Test fun targetsAreNormalized() {
        assertEquals(3000, DomainsCore.target(JSONObject().put("type", "port").put("port", 3000)).getOrThrow().getInt("port"))
        assertEquals("blog", DomainsCore.target(JSONObject().put("type", "static").put("path", "/blog/")).getOrThrow().getString("path"))
        assertEquals("", DomainsCore.target(JSONObject().put("type", "static").put("path", "")).getOrThrow().getString("path"))
        assertEquals("jellyfin", DomainsCore.target(JSONObject().put("type", "app").put("id", "jellyfin")).getOrThrow().getString("id"))
        for (bad in listOf(
            JSONObject().put("type", "port").put("port", 8080), JSONObject().put("type", "port").put("port", 70000),
            JSONObject().put("type", "static").put("path", "../etc"), JSONObject().put("type", "deploy").put("id", "A B"),
            JSONObject().put("type", "ftp"),
        )) assertTrue(bad.toString(), DomainsCore.target(bad).isFailure)
        assertTrue(DomainsCore.target(null).isFailure)
    }

    @Test fun domainsKeepVerificationOnlyForTheSameName() {
        val form = JSONObject().put("host", "a.example.com").put("target", JSONObject().put("type", "port").put("port", 3000))
            .put("headers", JSONObject().put("X-Frame-Options", "DENY").put("-Server", ""))
            .put("routes", JSONArray().put(JSONObject().put("path", "/api").put("strip", true).put("target", JSONObject().put("type", "port").put("port", 4000))))
        val old = DomainsCore.domain(form, "id1", "tok", null).getOrThrow().put("verified", true).put("verifiedAt", 5L)
        assertFalse(DomainsCore.domain(form, "id1", "tok", null).getOrThrow().getBoolean("verified"))
        val edited = DomainsCore.domain(JSONObject(form.toString()).put("headers", JSONObject()), "id1", "tok", old).getOrThrow()
        assertTrue(edited.getBoolean("verified"))
        assertEquals(5L, edited.getLong("verifiedAt"))
        val renamed = DomainsCore.domain(JSONObject(form.toString()).put("host", "b.example.com"), "id1", "tok", old).getOrThrow()
        assertFalse(renamed.getBoolean("verified"))
        fun bad(change: JSONObject.() -> Unit) = assertTrue(DomainsCore.domain(JSONObject(form.toString()).apply(change), "id1", "tok", null).isFailure)
        bad { put("headers", JSONObject().put("X-A", "a\"b")) }
        bad { put("headers", JSONObject().put("X-A", "a\nb")) }
        bad { put("headers", JSONObject().put("Bad Name", "x")) }
        bad { put("routes", JSONArray().put(JSONObject().put("path", "/a").put("target", JSONObject().put("type", "port").put("port", 1)))
            .put(JSONObject().put("path", "/a/").put("target", JSONObject().put("type", "port").put("port", 2)))) }
        bad { put("host", "nope") }
    }

    @Test fun longestPrefixWins() {
        val routes = listOf("/api", "/api/v2", "/static")
        assertEquals("/api/v2", DomainsCore.matchRoute(routes, "/api/v2/users"))
        assertEquals("/api/v2", DomainsCore.matchRoute(routes, "/api/v2"))
        assertEquals("/api", DomainsCore.matchRoute(routes, "/api/v1"))
        assertNull(DomainsCore.matchRoute(routes, "/apix"))
        assertNull(DomainsCore.matchRoute(routes, "/"))
    }

    @Test fun generatesManagedSites() {
        val site = Site(
            "blog.example.com", "abc123", linkedMapOf("X-Frame-Options" to "DENY", "-Server" to ""),
            listOf(Triple("/api", true, Upstream.Proxy(4000)), Triple("/api/v2", false, Upstream.Proxy(4002)), Triple("/old", false, Upstream.Missing("gone"))),
            Upstream.Static("/data/files/home/www/blog"),
        )
        val c = DomainsCore.caddyConfig(listOf(site), null, "/data/files/logs/caddy-access.log")
        assertTrue(c.contains("(homedroid-log) {"))
        assertTrue(c.contains("output file \"/data/files/logs/caddy-access.log\" {"))
        assertTrue(c.contains("roll_size 5MiB"))
        assertTrue(c.contains("format json"))
        assertFalse(c.contains("homedroid-firewall"))
        assertTrue(c.contains("(homedroid-site) {\n\timport homedroid-log\n}"))
        assertTrue(c.contains("http://blog.example.com:8080 {\n\timport homedroid-log\n\troute {\n"))
        assertTrue(c.contains("handle /.well-known/homedroid-verify/abc123 {\n\t\t\trespond \"abc123\" 200"))
        assertTrue(c.contains("\t\t\tdefer\n\t\t\tX-Frame-Options \"DENY\"\n\t\t\t-Server\n"))
        // Longest prefix first, each matching the prefix itself and below it.
        val v2 = c.indexOf("@route0 path /api/v2 /api/v2/*")
        val api = c.indexOf("path /api /api/*")
        assertTrue(v2 in 0 until api)
        assertTrue(c.contains("uri strip_prefix /api\n\t\t\treverse_proxy 127.0.0.1:4000"))
        assertTrue(c.contains("respond \"gone\" 502"))
        assertTrue(c.contains("handle {\n\t\t\troot * \"/data/files/home/www/blog\"\n\t\t\tfile_server\n\t\t}"))
        // The verification handler comes before everything else.
        assertTrue(c.indexOf("homedroid-verify") < c.indexOf("header {"))
        assertEquals(c.count { it == '{' }, c.count { it == '}' })

        val fw = DomainsCore.caddyConfig(listOf(site), "\trespond @x 403\n", "/l")
        assertTrue(fw.contains("(homedroid-firewall) {\n\trespond @x 403\n}"))
        assertTrue(fw.contains("(homedroid-site) {\n\timport homedroid-log\n\troute {\n\t\timport homedroid-firewall\n\t}\n}"))
        val inSite = fw.indexOf("\t\timport homedroid-firewall", fw.indexOf("http://"))
        assertTrue(fw.indexOf("homedroid-verify/abc123") < inSite)
        assertTrue(inSite < fw.indexOf("header {"))
        assertEquals(fw.count { it == '{' }, fw.count { it == '}' })
    }

    @Test fun caddyfileImportIsAddedOnce() {
        val once = DomainsCore.ensureCaddyfile(default)
        assertEquals(once, DomainsCore.ensureCaddyfile(once))
        assertEquals(1, Regex("""import Caddyfile\.d/\*\.caddy""").findAll(once).count())
        assertTrue(once.contains("auto_https off"))
        assertTrue(once.contains("trusted_proxies static 127.0.0.1/8 ::1"))
        // The import sits after the global options and before the site, which uses its snippet.
        val lines = once.lines()
        val closeGlobal = lines.indexOf("}")
        val import = lines.indexOf(DomainsCore.IMPORT)
        val site = lines.indexOfFirst { it.startsWith(":8080") }
        assertTrue(closeGlobal < import && import < site)
        assertEquals("\timport homedroid-site", lines[site + 1])
        assertTrue(once.contains("root * {\$HOME}/www\n\tencode gzip\n\tfile_server"))
        assertTrue(once.endsWith("}\n"))
    }

    @Test fun userEditsAreRespected() {
        val custom = """
            # my server
            {
            	admin localhost:2019
            	servers {
            		protocols h1 h2
            	}
            }

            :8080 {
            	log
            	reverse_proxy localhost:3000 # "app" {
            }

            example.org:8080 {
            	respond "hi"
            }
        """.trimIndent() + "\n"
        val out = DomainsCore.ensureCaddyfile(custom)
        assertFalse("existing servers block is left alone", out.contains("trusted_proxies"))
        assertFalse("a site that logs isn't given a second log", out.contains("import homedroid-site"))
        assertTrue(out.startsWith("# my server\n{\n\tadmin localhost:2019"))
        assertTrue(out.contains("}\n\n# Sites managed by Homedroid (Domains in the dashboard).\n${DomainsCore.IMPORT}\n\n:8080 {"))
        assertTrue(out.contains("example.org:8080 {\n\trespond \"hi\"\n}"))
        assertEquals(out, DomainsCore.ensureCaddyfile(out))

        // No global options: one is added at the top, then the import.
        val bare = ":8080 {\n\tfile_server\n}\n"
        val b = DomainsCore.ensureCaddyfile(bare)
        assertTrue(b.startsWith("{\n"))
        assertTrue(b.indexOf(DomainsCore.IMPORT) < b.indexOf(":8080 {"))
        assertTrue(b.contains(":8080 {\n\timport homedroid-site\n\tfile_server\n}"))
        assertEquals(b, DomainsCore.ensureCaddyfile(b))

        // An import the user already wrote (or moved) isn't added again.
        val own = "{\n}\n:8080 {\n}\nimport ./Caddyfile.d/*.caddy\n"
        assertEquals(1, DomainsCore.ensureCaddyfile(own).split("Caddyfile.d").size - 1)

        // A global block that never closes is too broken to edit.
        val broken = "{\n\tauto_https off\n:8080 {\n}\n"
        assertEquals(broken, DomainsCore.ensureCaddyfile(broken))
    }

    @Test fun parsesCaddyAccessLogs() {
        val line = """{"level":"info","ts":1700000000.5,"logger":"http.log.access","msg":"handled request","request":{"remote_ip":"127.0.0.1","remote_port":"5555","client_ip":"203.0.113.9","proto":"HTTP/1.1","method":"GET","host":"blog.example.com:8080","uri":"/a?b=1","headers":{}},"bytes_read":0,"user_id":"","duration":0.0012346,"size":512,"status":200,"resp_headers":{}}"""
        val e = DomainsCore.parseLog(line)!!
        assertEquals(1700000000500L, e.getLong("time"))
        assertEquals("blog.example.com", e.getString("host"))
        assertEquals("GET", e.getString("method"))
        assertEquals("/a?b=1", e.getString("uri"))
        assertEquals(200, e.getInt("status"))
        assertEquals(1.235, e.getDouble("duration"), 0.0001)
        assertEquals(512L, e.getLong("size"))
        assertEquals("203.0.113.9", e.getString("remote"))
        assertNull(DomainsCore.parseLog("not json"))
        assertNull(DomainsCore.parseLog("""{"level":"info","msg":"serving initial configuration"}"""))
        val noClient = DomainsCore.parseLog(line.replace(""""client_ip":"203.0.113.9",""", ""))!!
        assertEquals("127.0.0.1", noClient.getString("remote"))
    }

    @Test fun filtersLogsNewestFirst() {
        fun l(host: String, status: Int, ts: Int) =
            """{"ts":$ts,"request":{"remote_ip":"1.1.1.1","method":"GET","host":"$host","uri":"/"},"duration":0.1,"size":1,"status":$status}"""
        val lines = listOf(l("a.com", 200, 1), l("b.com", 404, 2), "garbage", l("a.com", 502, 3), l("a.com", 301, 4), l("A.com", 404, 5))
        assertEquals(listOf(5000L, 4000L, 3000L, 2000L, 1000L), DomainsCore.filterLogs(lines, null, null, 100).map { it.getLong("time") })
        assertEquals(listOf(5000L, 4000L), DomainsCore.filterLogs(lines, null, null, 2).map { it.getLong("time") })
        assertEquals(listOf(5000L, 4000L, 3000L, 1000L), DomainsCore.filterLogs(lines, "a.com", "", 100).map { it.getLong("time") })
        assertEquals(listOf(5000L, 2000L), DomainsCore.filterLogs(lines, null, "4xx", 100).map { it.getLong("time") })
        assertEquals(listOf(3000L), DomainsCore.filterLogs(lines, "a.com", "5", 100).map { it.getLong("time") })
        assertEquals(listOf(5000L, 2000L), DomainsCore.filterLogs(lines, "", "404", 100).map { it.getLong("time") })
    }
}
