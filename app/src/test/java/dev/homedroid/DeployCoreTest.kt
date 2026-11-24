package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeployCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- runtimes -------------------------------------------------------------------------

    @Test fun detectsRuntimesInPriorityOrder() {
        fun detect(vararg files: String) = Runtimes.detect { it in files }
        assertEquals("deno", detect("deno.json", "package.json"))
        assertEquals("deno", detect("deno.jsonc"))
        assertEquals("bun", detect("bun.lockb", "package.json"))
        assertEquals("bun", detect("bun.lock"))
        assertEquals("php", detect("composer.json", "package.json"))
        assertEquals("node", detect("package.json", "go.mod"))
        assertEquals("go", detect("go.mod"))
        assertEquals("python", detect("pyproject.toml"))
        assertEquals("php", detect("public/index.php"))
        assertEquals("php", detect("index.php"))
        assertEquals("static", detect("index.html"))
    }

    @Test fun shellDetectionFollowsTheSameRules() {
        val script = Runtimes.detectScript()
        val lines = script.lines()
        assertTrue(lines.first().startsWith("if [ -f deno.json ] || [ -f deno.jsonc ]; then rt=deno"))
        assertEquals("else rt=static; fi", lines.last())
        assertEquals(Runtimes.RULES.size + 1, lines.size)
        Runtimes.RULES.forEach { (rt, files) -> files.forEach { assertTrue(script.contains("[ -f $it ]")) }; assertTrue(script.contains("rt=$rt")) }
    }

    @Test fun everyRuntimeIsInstallableAndStartable() {
        val install = Runtimes.installScript()
        for (r in Runtimes.ALL.filter { it.id != "static" }) {
            assertTrue(r.id, install.contains("${r.id})"))
            assertTrue(r.id, Runtimes.defaultStart(r.id).isNotEmpty())
        }
        assertEquals(listOf("auto", "node", "python", "go", "php", "bun", "deno", "static"), Runtimes.CHOICES)
        assertTrue(install.contains("apk add --no-cache go"))
        assertTrue(install.contains("apk add --no-cache deno"))
        // Bun's binary is checked against the release's checksums before it's installed.
        assertTrue(install.contains("sha256sum -c"))
    }

    // --- hooks ----------------------------------------------------------------------------

    @Test fun tokensAreComparedByHash() {
        val token = DeployHooksCore.newSecret("hd_")
        assertEquals(67, token.length)
        val hash = DeployHooksCore.hash(token)
        assertFalse(hash.contains(token))
        assertTrue(DeployHooksCore.tokenMatches(token, hash))
        assertFalse(DeployHooksCore.tokenMatches(token + "x", hash))
        assertFalse(DeployHooksCore.tokenMatches(null, hash))
        assertFalse(DeployHooksCore.tokenMatches("", ""))
        assertFalse("no token issued: nothing matches", DeployHooksCore.tokenMatches(token, ""))
        assertNotEquals(token, DeployHooksCore.newSecret("hd_"))
    }

    @Test fun githubSignatureMatchesTheirExample() {
        // From GitHub's "Validating webhook deliveries" documentation.
        val secret = "It's a Secret to Everybody"
        val body = "Hello, World!".toByteArray()
        val sig = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        assertTrue(DeployHooksCore.githubSignatureValid(secret, body, sig))
        assertTrue(DeployHooksCore.githubSignatureValid(secret, body, sig.uppercase().replace("SHA256=", "sha256=")))
        assertFalse(DeployHooksCore.githubSignatureValid(secret, "Hello, World?".toByteArray(), sig))
        assertFalse(DeployHooksCore.githubSignatureValid("other", body, sig))
        assertFalse(DeployHooksCore.githubSignatureValid("", body, sig))
        assertFalse(DeployHooksCore.githubSignatureValid(secret, body, null))
        assertFalse(DeployHooksCore.githubSignatureValid(secret, body, sig.removePrefix("sha256=")))
    }

    @Test fun pushBranchIgnoresTags() {
        assertEquals("main", DeployHooksCore.pushBranch(JSONObject().put("ref", "refs/heads/main")))
        assertEquals("feature/x", DeployHooksCore.pushBranch(JSONObject().put("ref", "refs/heads/feature/x")))
        assertNull(DeployHooksCore.pushBranch(JSONObject().put("ref", "refs/tags/v1")))
        assertNull(DeployHooksCore.pushBranch(JSONObject()))
    }

    // --- pipelines ------------------------------------------------------------------------

    @Test fun parsesPipelinesInBothShapes() {
        val steps = Pipelines.parse("""{"steps":[{"name":"Test","run":"npm test"},{"run":"npm run lint"}]}""")
        assertEquals(listOf("Test", "Step 2"), steps.map { it.name })
        assertEquals("npm run lint", steps[1].run)
        assertEquals(1, Pipelines.parse(JSONArray().put(JSONObject().put("name", "a").put("run", "true"))).size)
        assertTrue(Pipelines.parse(null).isEmpty())
        assertTrue(Pipelines.parse(JSONObject.NULL).isEmpty())
        assertTrue(Pipelines.parse("  ").isEmpty())
        assertEquals("x", Pipelines.parse("""[{"name":"x\u0007","run":"true"}]""")[0].name)
    }

    @Test fun rejectsInvalidPipelines() {
        for (bad in listOf("""[{"name":"no run"}]""", """{"stages":[]}""", "not json", """[1]""",
            JSONArray().apply { repeat(Pipelines.MAX_STEPS + 1) { put(JSONObject().put("run", "true")) } }.toString())) {
            try { Pipelines.parse(bad); fail(bad) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun pipelineRunsKeepTheNewestAndHideLogsInTheList() {
        var now = 1_000_000L
        val runs = PipelineRuns(tmp.newFolder("runs"), keep = 3, clock = { now++ })
        val steps = listOf(PipelineStep("Test", "true"))
        val ids = (1..5).map { runs.create("manual", steps).getString("id") }
        val list = runs.list()
        assertEquals(ids.takeLast(3).reversed(), list.map { it.getString("id") })
        assertNull(runs.get(ids[0]))
        val run = runs.get(ids[4])!!
        assertEquals("queued", run.getString("status"))
        run.getJSONArray("steps").getJSONObject(0).put("log", "ok\n").put("status", "passed")
        runs.save(run.put("status", "passed"))
        assertEquals("ok\n", runs.get(ids[4])!!.getJSONArray("steps").getJSONObject(0).getString("log"))
        assertFalse(runs.list()[0].getJSONArray("steps").getJSONObject(0).has("log"))
        assertNull(runs.get("../../etc/passwd"))
        assertTrue(PipelineRuns.clip("x".repeat(PipelineRuns.MAX_STEP_LOG + 10)).length <= PipelineRuns.MAX_STEP_LOG + 2)
    }

    // --- balancing ------------------------------------------------------------------------

    @Test fun balanceSettingsAreValidated() {
        val b = Balance.parse(JSONObject().put("port", 9000))!!
        assertEquals("round_robin", b.policy)
        assertEquals("/", b.healthUri)
        assertEquals(10, b.interval)
        assertNull(Balance.parse(null))
        assertEquals("first", Balance.parse(JSONObject().put("port", 9000).put("policy", "first"))!!.policy)
        for (bad in listOf(
            JSONObject().put("port", 80),
            JSONObject().put("port", 9000).put("policy", "weighted"),
            JSONObject().put("port", 9000).put("healthUri", "/x }\n:80 {"),
            JSONObject().put("port", 9000).put("healthUri", "health"),
            JSONObject().put("port", 9000).put("interval", 1),
        )) try { Balance.parse(bad); fail(bad.toString()) } catch (_: IllegalArgumentException) {}
    }

    @Test fun upstreamsStartWithThisPhoneAndSkipOddHosts() {
        val list = Balancer.upstreams(3000, listOf("192.168.1.20" to 3000, "fe80::1" to 3000, "a b" to 3000, "fe80::2%wlan0" to 3000, "192.168.1.20" to 3000, "10.0.0.2" to 0))
        assertEquals(listOf("127.0.0.1:3000", "192.168.1.20:3000", "[fe80::1]:3000"), list)
    }

    @Test fun snippetIsACaddySiteBlockPerDeployment() {
        val text = Balancer.snippet(listOf(
            Balancer.Site("web", Balance(9000, "first", "/health", 15), listOf("127.0.0.1:3000", "192.168.1.20:3000")),
            Balancer.Site("none", Balance(9001, "round_robin", "/", 10), emptyList()),
        ))
        assertTrue(text.startsWith("# Managed by Homedroid"))
        assertTrue(text.contains(":9000 {\n\treverse_proxy 127.0.0.1:3000 192.168.1.20:3000 {\n\t\tlb_policy first\n"))
        assertTrue(text.contains("\t\thealth_uri /health\n\t\thealth_interval 15s\n"))
        assertFalse(text.contains(":9001"))
        assertEquals(text.count { it == '{' }, text.count { it == '}' })
        assertEquals("# Managed by Homedroid: load-balanced deployments. Changes here are overwritten.\n", Balancer.snippet(emptyList()))
    }

    @Test fun importIsAddedOnceAtTheEnd() {
        val user = "{\n\tauto_https off\n}\n\n:8080 {\n\tfile_server\n}\n"
        val once = Balancer.withImport(user)!!
        assertTrue(once.startsWith(user.trimEnd()))
        assertTrue(once.endsWith("\n" + Balancer.IMPORT + "\n"))
        assertNull(Balancer.withImport(once))
        // Someone else (the domains feature) may have added the same line already.
        assertNull(Balancer.withImport("$user\nimport Caddyfile.d/*.caddy\n# mine\n"))
        assertEquals("import Caddyfile.d/*.caddy", Balancer.IMPORT)
    }

    // --- uploads --------------------------------------------------------------------------

    @Test fun entryPathsStayInside() {
        assertEquals("a/b.txt", UploadArchive.entryPath("./a//b.txt"))
        assertEquals("", UploadArchive.entryPath("./"))
        assertNull(UploadArchive.entryPath("../x"))
        assertNull(UploadArchive.entryPath("a/../../x"))
        assertNull(UploadArchive.entryPath("a/.."))
        assertNull(UploadArchive.entryPath("/etc/passwd"))
        assertNull(UploadArchive.entryPath("a\\..\\b"))
        assertNull(UploadArchive.entryPath("a\u0000b"))
    }

    private fun zip(vararg entries: Pair<String, String?>): File = tmp.newFile().apply {
        ZipOutputStream(outputStream()).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                text?.let { z.write(it.toByteArray()) }
                z.closeEntry()
            }
        }
    }

    @Test fun unzipsAndHoistsASingleTopFolder() {
        val dest = File(tmp.root, "out")
        val bytes = UploadArchive.extract(zip("repo-main/" to null, "repo-main/package.json" to "{}", "repo-main/src/a.js" to "x",
            "__MACOSX/._a" to "junk"), dest, 1 shl 20)
        assertEquals(7L, bytes)
        assertEquals("{}", File(dest, "package.json").readText())
        assertEquals("x", File(dest, "src/a.js").readText())
        assertFalse(File(dest, "__MACOSX").exists())
        assertFalse(File(tmp.root, "out.hoist").exists())
    }

    @Test fun refusesZipSlip() {
        try {
            UploadArchive.extract(zip("ok.txt" to "1", "../evil.txt" to "2"), File(tmp.root, "out"), 1 shl 20)
            fail()
        } catch (_: IOException) {}
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun capsTheUnpackedSize() {
        try {
            UploadArchive.extract(zip("big" to "x".repeat(5000)), File(tmp.root, "out"), 4096)
            fail()
        } catch (e: IOException) { assertTrue(e.message!!.contains("larger")) }
    }

    @Test fun rejectsOtherFiles() {
        val f = tmp.newFile().apply { writeText("just text") }
        try { UploadArchive.extract(f, File(tmp.root, "out"), 1 shl 20); fail() } catch (_: IOException) {}
    }

    private fun tarHeader(name: String, size: Int, type: Char, mode: Int = 420, link: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode) + "\u0000")
        put(124, "%011o".format(size) + "\u0000")
        put(148, "        ")
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar\u000000")
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, "%06o".format(sum) + "\u0000 ")
        return h
    }

    private fun tgz(build: ByteArrayOutputStream.() -> Unit): File {
        val raw = ByteArrayOutputStream().apply(build).apply { write(ByteArray(1024)) }.toByteArray()
        return tmp.newFile().apply { GZIPOutputStream(outputStream()).use { it.write(raw) } }
    }

    private fun ByteArrayOutputStream.entry(name: String, text: String, type: Char = '0', mode: Int = 420, link: String = "") {
        val data = text.toByteArray()
        write(tarHeader(name, if (type == '0') data.size else 0, type, mode, link))
        if (type == '0') { write(data); write(ByteArray((512 - data.size % 512) % 512)) }
    }

    @Test fun untarsFilesButNeverLinks() {
        val dest = File(tmp.root, "out")
        UploadArchive.extract(tgz {
            entry("app/", "", '5', 493)
            entry("app/run.sh", "#!/bin/sh\n", mode = 493)
            entry("app/link", "", '2', link = "/data/data")
            entry("app/hard", "", '1', link = "app/run.sh")
            entry("other.txt", "two roots: no hoisting")
        }, dest, 1 shl 20)
        assertTrue(File(dest, "app/run.sh").canExecute())
        assertFalse(Files.exists(File(dest, "app/link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(dest, "app/hard").exists())
        assertEquals("two roots: no hoisting", File(dest, "other.txt").readText())
    }

    @Test fun refusesTarEscapes() {
        try {
            UploadArchive.extract(tgz { entry("a/../../evil", "x") }, File(tmp.root, "out"), 1 shl 20)
            fail()
        } catch (_: IOException) {}
        assertFalse(File(tmp.root, "evil").exists())
    }
}
