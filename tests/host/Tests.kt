package dev.homedroid

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0
private val results = mutableListOf<String>()
private fun test(area: String, name: String, block: () -> Unit) {
    try { block(); passed++; results += "PASS [$area] $name" }
    catch (e: Throwable) { failed++; results += "FAIL [$area] $name: ${cause(e)}" }
    println(results.last())
}
private fun cause(e: Throwable): Throwable = if (e is java.lang.reflect.InvocationTargetException) cause(e.targetException) else e
private fun eq(expected: Any?, actual: Any?) { check(expected == actual) { "expected <$expected>, got <$actual>" } }
private fun rejects(block: () -> Unit) {
    var rejected = false
    try { block() } catch (e: Throwable) {
        val c = cause(e)
        if (c is IOException || c is IllegalArgumentException || c is org.json.JSONException) rejected = true else throw c
    }
    check(rejected) { "invalid input was accepted" }
}
private fun call(obj: Any, name: String, vararg args: Any?): Any? {
    val m = obj.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
    m.isAccessible = true
    return m.invoke(obj, *args)
}
private fun field(obj: Any, name: String): Any? = obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)
private fun temp() = Files.createTempDirectory("homedroid-test-").toFile()
private fun sandbox(block: (File) -> Unit) { val dir = temp(); try { block(dir) } finally { dir.deleteRecursively() } }
private data class Entry(val name: String, val data: String = "", val type: Char = '0', val link: String = "")
private fun tar(vararg entries: Entry): ByteArray {
    val out = ByteArrayOutputStream()
    for (e in entries) {
        val h = ByteArray(512)
        fun put(offset: Int, text: String) { text.toByteArray().copyInto(h, offset) }
        put(0, e.name); put(100, "0000755\u0000"); put(124, e.data.toByteArray().size.toString(8).padStart(11, '0') + "\u0000")
        h[156] = e.type.code.toByte(); put(157, e.link); put(257, "ustar\u0000")
        for (i in 148..155) h[i] = 32
        put(148, h.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
        out.write(h); out.write(e.data.toByteArray()); out.write(ByteArray((512 - e.data.toByteArray().size % 512) % 512))
    }
    out.write(ByteArray(1024)); return out.toByteArray()
}
private fun extract(t: Tar, vararg entries: Entry) = t.extract(tar(*entries).inputStream())
private fun hash(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private data class Reply(val code: Int = 200, val bytes: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())
private data class Seen(val url: String, val auth: String?)
private val seen = mutableListOf<Seen>()
private var responder: (URL, String?) -> Reply = { url, _ -> error("unexpected URL: $url") }
private fun installNetworkDouble() {
    URL.setURLStreamHandlerFactory { protocol ->
        if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                private val reply by lazy {
                    val auth = getRequestProperty("Authorization")
                    seen += Seen(url.toString(), auth); responder(url, auth)
                }
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = reply.code
                override fun getInputStream(): InputStream { check(reply.code == 200); return reply.bytes.inputStream() }
                override fun getHeaderField(name: String) = reply.headers[name]
            }
        }
    }
}
private fun ref(s: String) = call(Oci("amd64"), "parse", s)!!
private fun open(ref: Any): String = (call(Oci("amd64"), "open", ref, "manifests/latest", "application/json") as InputStream).use { it.reader().readText() }
private fun registryTests() {
    for ((input, expected) in listOf(
        "alpine" to listOf("registry-1.docker.io", "library/alpine", "latest"),
        "alpine:3.20" to listOf("registry-1.docker.io", "library/alpine", "3.20"),
        "org/app:v1" to listOf("registry-1.docker.io", "org/app", "v1"),
        "docker.io/alpine" to listOf("registry-1.docker.io", "library/alpine", "latest"),
        "ghcr.io/org/app:v1" to listOf("ghcr.io", "org/app", "v1"),
        "localhost:5000/team/app" to listOf("localhost:5000", "team/app", "latest"),
        ("ghcr.io/org/app@sha256:" + "a".repeat(64)) to listOf("ghcr.io", "org/app", "sha256:" + "a".repeat(64)))) {
        test("OCI references", input) { val r = ref(input); eq(expected, listOf("registry", "repo", "reference").map { field(r, it) }) }
    }
    test("OCI references", "tag plus digest strips tag from repository") {
        eq("org/app", field(ref("ghcr.io/org/app:v1@sha256:" + "a".repeat(64)), "repo"))
    }
    for (s in listOf("", "alpine:", "ghcr.io/", "alpine@sha256:nope")) test("OCI references", "reject malformed '$s'") { rejects { ref(s) } }
    test("registry auth", "anonymous 200") { responder = { _, _ -> Reply(bytes = "ok".toByteArray()) }; eq("ok", open(ref("example.test/app"))) }
    for (key in listOf("token", "access_token")) test("registry auth", "401 exchange with $key and cached token") {
        seen.clear()
        responder = { url, auth -> when {
            url.host == "auth.test" -> Reply(bytes = "{\"$key\":\"secret\"}".toByteArray())
            auth == null -> Reply(401, headers = mapOf("WWW-Authenticate" to "Bearer realm=\"https://auth.test/token\",service=\"registry\""))
            else -> { eq("Bearer secret", auth); Reply(bytes = "ok".toByteArray()) }
        } }
        val r = ref("example.test/team/app"); eq("ok", open(r)); eq("ok", open(r))
        eq(1, seen.count { it.url.startsWith("https://auth.test/") })
        check(seen.any { "scope=repository:team/app:pull" in it.url })
    }
    test("registry auth", "redirect strips registry bearer token") {
        val r = ref("example.test/app"); r.javaClass.getDeclaredField("token").apply { isAccessible = true }.set(r, "secret")
        responder = { url, auth -> if (url.host == "example.test") { eq("Bearer secret", auth); Reply(307, headers = mapOf("Location" to "https://storage.test/blob")) } else { eq(null, auth); Reply(bytes = "blob".toByteArray()) } }
        eq("blob", open(r))
    }
    test("registry auth", "reject 401 without challenge") { responder = { _, _ -> Reply(401) }; rejects { open(ref("example.test/app")) } }
    test("registry auth", "reject repeated 401") {
        responder = { u, _ -> if (u.host == "auth.test") Reply(bytes = "{\"token\":\"bad\"}".toByteArray()) else Reply(401, headers = mapOf("WWW-Authenticate" to "Bearer realm=\"https://auth.test/token\"")) }
        rejects { open(ref("example.test/app")) }
    }
    test("registry auth", "redirect loop is bounded") { seen.clear(); responder = { _, _ -> Reply(302, headers = mapOf("Location" to "/again")) }; rejects { open(ref("example.test/app")) }; check(seen.size in 1..12) { "redirect loop exceeded the request budget: ${seen.size}" } }
    for (status in listOf(429, 503)) test("registry auth", "retry HTTP $status before succeeding") {
        var attempts = 0
        responder = { _, _ ->
            attempts++
            if (attempts == 1) Reply(status, headers = mapOf("Retry-After" to "1"))
            else Reply(bytes = "ok".toByteArray())
        }
        eq("ok", open(ref("example.test/app")))
        eq(2, attempts)
    }
    test("registry auth", "token realm preserves existing query parameters") {
        responder = { u, auth -> when {
            u.host == "auth.test" -> { check(u.query.startsWith("existing=1&scope=")) { "malformed token query: ${u.query}" }; Reply(bytes = "{\"token\":\"ok\"}".toByteArray()) }
            auth == null -> Reply(401, headers = mapOf("WWW-Authenticate" to "Bearer realm=\"https://auth.test/token?existing=1\""))
            else -> Reply()
        } }; open(ref("example.test/app"))
    }
}
private fun digestTests() {
    fun fixture(blob: ByteArray, digest: String, gzip: Boolean, badConfig: Boolean = false, badManifest: Boolean = false): String {
        val config = "{\"config\":{\"Cmd\":[\"sh\"]}}".toByteArray()
        val cd = if (badConfig) "sha256:" + "0".repeat(64) else hash(config)
        val manifest = """{"config":{"digest":"$cd"},"layers":[{"digest":"$digest","mediaType":"application/vnd.oci.image.layer.v1.tar${if(gzip) "+gzip" else ""}"}]}""".toByteArray()
        responder = { url, _ -> when {
            "/manifests/" in url.path -> Reply(bytes = manifest)
            url.path.endsWith(cd) -> Reply(bytes = config)
            else -> Reply(bytes = blob)
        } }
        return "example.test/app" + if (badManifest) "@sha256:" + "0".repeat(64) else ""
    }
    for (gzip in listOf(false, true)) test("digests", "valid ${if(gzip) "gzip" else "tar"} layer installs") { sandbox { dir ->
        val raw = tar(Entry("hello", "world")); val blob = if (gzip) ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray() else raw
        Oci("amd64").pull(fixture(blob, hash(blob), gzip), File(dir,"image")) {}
        eq("world", File(dir,"image/hello").readText())
    } }
    test("digests", "corrupt layer rejected and old destination preserved") { sandbox { dir ->
        val dest = File(dir,"image").apply { mkdirs() }; File(dest,"old").writeText("keep")
        rejects { Oci("amd64").pull(fixture(tar(Entry("new", "bad")), "sha256:" + "0".repeat(64), false), dest) {} }
        eq("keep", File(dest,"old").readText()); check(!File(dest,"new").exists())
    } }
    test("digests", "digest covers bytes after tar end marker") { sandbox { dir ->
        val blob = tar(Entry("hello", "world")); rejects { Oci("amd64").pull(fixture(blob + byteArrayOf(42), hash(blob), false), File(dir,"image")) {} }
    } }
    for (manifest in listOf(false,true)) test("digests", "reject corrupt ${if(manifest) "digest-pinned manifest" else "config blob"}") { sandbox { dir ->
        val blob = tar(Entry("hello", "world")); rejects { Oci("amd64").pull(fixture(blob, hash(blob), false, !manifest, manifest), File(dir,"image")) {} }
    } }
}
private fun tarTests() {
    for (name in listOf("../escaped", "a/../../escaped")) test("tar traversal", "reject $name") { sandbox { dir ->
        val root = File(dir,"root"); extract(Tar(root), Entry(name,"bad"), Entry("safe","ok")); check(!File(dir,"escaped").exists()); eq("ok", File(root,"safe").readText())
    } }
    test("tar traversal", "absolute archive names remain inside root") { sandbox { dir -> extract(Tar(dir), Entry("/inside","ok")); eq("ok", File(dir,"inside").readText()) } }
    for (target in listOf("../../outside", "/outside")) test("tar traversal", "symlink target $target is rooted") { sandbox { dir ->
        val root = File(dir,"root"); val outside = File(dir,"outside").apply { mkdirs() }; File(outside,"sentinel").writeText("keep")
        extract(Tar(root), Entry("link",type='2',link=target), Entry("link/sentinel","inside"))
        eq("keep",File(outside,"sentinel").readText()); eq("inside",File(root,"outside/sentinel").readText())
    } }
    test("tar traversal", "hard link traversal cannot copy outside file") { sandbox { dir ->
        File(dir,"secret").writeText("secret"); val root = File(dir,"root"); extract(Tar(root),Entry("stolen",type='1',link="../secret")); check(!File(root,"stolen").exists())
    } }
    test("tar traversal", "symlink cycle fails with bounded hops") { sandbox { dir -> rejects { extract(Tar(dir),Entry("a",type='2',link="b"),Entry("b",type='2',link="a"),Entry("a/payload","x")) } } }
    test("tar traversal", "truncated file data rejected") { sandbox { dir -> rejects { Tar(dir).extract(tar(Entry("x","abc")).copyOf(513).inputStream()) } } }
    test("whiteouts", "ordinary whiteout removes lower-layer file") { sandbox { dir ->
        val t=Tar(dir,true); extract(t,Entry("old","x")); extract(t,Entry(".wh.old")); check(!File(dir,"old").exists()); check(!File(dir,".wh.old").exists())
    } }
    test("whiteouts", "opaque directory removes lower-layer children using same extractor") { sandbox { dir ->
        val t=Tar(dir,true); extract(t,Entry("d/old","old")); extract(t,Entry("d/.wh..wh..opq"),Entry("d/new","new")); check(!File(dir,"d/old").exists()) { "lower-layer d/old survived opacity" }; eq("new",File(dir,"d/new").readText())
    } }
    test("whiteouts", "opaque marker preserves current-layer additions") { sandbox { dir ->
        File(dir,"d").mkdirs(); File(dir,"d/old").writeText("old"); extract(Tar(dir,true),Entry("d/new","new"),Entry("d/.wh..wh..opq")); check(!File(dir,"d/old").exists()); eq("new",File(dir,"d/new").readText())
    } }
    test("whiteouts", "whiteout cannot remove same-layer replacement") { sandbox { dir ->
        File(dir,"old").writeText("lower"); extract(Tar(dir,true),Entry("old","new"),Entry(".wh.old")); check(File(dir,"old").exists()) { "same-layer replacement was deleted" }; eq("new",File(dir,"old").readText())
    } }
    test("whiteouts", "non-OCI mode preserves whiteout as regular file") { sandbox { dir -> extract(Tar(dir),Entry(".wh.old")); check(File(dir,".wh.old").exists()) } }
}
private fun configTests() {
    test("configuration", "settings defaults and updates persist across instances") { sandbox { dir ->
        val ctx=Context(dir); val c=Config(ctx); check(c.autostart && c.sshEnabled && c.webEnabled); eq("",c.tunnelToken)
        c.autostart=false; c.sshEnabled=false; c.webEnabled=false; c.tunnelToken="abc"; c.setDisabled("app",true)
        val again=Config(ctx); check(!again.autostart && !again.sshEnabled && !again.webEnabled); eq("abc",again.tunnelToken); check(again.isDisabled("app")); check(!again.isDisabled("other"))
    } }
    test("configuration", "generated dashboard password is stable and correctly formatted") { sandbox { dir ->
        val ctx=Context(dir); val p=Config(ctx).dashboardPassword; check(Regex("[a-z2-9]{4}(-[a-z2-9]{4}){2}").matches(p)); eq(p,Config(ctx).dashboardPassword)
    } }
    test("configuration", "real app catalog parses and has unique IDs and valid image service keys") { sandbox { dir ->
        val ctx=Context(dir); val apps=Apps(ctx,Paths(ctx)).catalog; check(apps.isNotEmpty()); eq(apps.size, apps.map { it.id }.toSet().size)
        for(a in apps) for(s in a.services) if(s.image != null) check(s.image in a.images) { "${a.id}/${s.name}: unknown image ${s.image}" }
    } }
    test("configuration", "malformed app catalog is rejected") { sandbox { dir ->
        val ctx=Context(dir); val a=Apps(ctx,Paths(ctx)); rejects { call(a,"parse","[{}]") }; rejects { call(a,"parse","invalid") }
    } }
    test("configuration", "image config defaults and env values containing equals") { sandbox { dir ->
        eq(null,ImageConfig.read(dir)); File(dir,Oci.CONFIG_FILE).writeText("""{"Env":["A=x=y","EMPTY=","BARE"],"Entrypoint":["/bin/sh"],"Cmd":["-c","true"]}""")
        val c=ImageConfig.read(dir)!!; eq("/",c.workdir); eq(mapOf("A" to "x=y","EMPTY" to "","BARE" to ""),c.env); eq(listOf("/bin/sh"),c.entrypoint); eq(listOf("-c","true"),c.cmd)
    } }
    test("configuration", "malformed image config rejected") { sandbox { dir -> File(dir,Oci.CONFIG_FILE).writeText("invalid"); rejects { ImageConfig.read(dir) } } }
    test("configuration", "shared storage setting and clearing") { sandbox { dir ->
        val ctx=Context(dir); val a=Apps(ctx,Paths(ctx)).catalog.first(); val c=Config(ctx); eq(null,c.storageDir(a)); c.setStorageDir(a,"/data/test"); eq("/data/test",Config(ctx).storageDir(a)); c.setStorageDir(a,null); eq(null,c.storageDir(a))
    } }
}
private fun deployInput(name: String="Test App", port: Int=3000) = JSONObject().put("name",name).put("repo","https://example.test/app.git").put("port",port)
private fun deployTests() {
    test("deployment", "defaults, persistence, and removal") { sandbox { dir ->
        val ctx=Context(dir); val ds=Deploys(ctx,Paths(ctx)); val d=ds.create(deployInput()); eq("test-app",d.id); eq("main",d.branch); eq("auto",d.runtime); check(!d.autoDeploy); eq("deploy-test-app",d.service)
        eq(d.toStored().toString(),Deploys(ctx,Paths(ctx)).get(d.id)!!.toStored().toString()); ds.remove(d.id); eq(0,ds.all().size)
    } }
    for ((key,value) in listOf("name" to "!!!","repo" to "file:///tmp/repo","port" to 1023,"port" to 65536,"port" to 8800,"runtime" to "ruby","env" to JSONObject().put("BAD-NAME","x"))) {
        test("deployment", "reject $key=$value") { sandbox { dir -> val ctx=Context(dir); rejects { Deploys(ctx,Paths(ctx)).create(deployInput().put(key,value)) } } }
    }
    test("deployment", "duplicate ID and port rejected") { sandbox { dir ->
        val ctx=Context(dir); val ds=Deploys(ctx,Paths(ctx)); ds.create(deployInput()); rejects { ds.create(deployInput("test app",3001)) }; rejects { ds.create(deployInput("different",3000)) }
    } }
    test("deployment", "public JSON redacts repository credentials and environment") { sandbox { dir ->
        val ctx=Context(dir); val d=Deploys(ctx,Paths(ctx)).create(deployInput().put("repo","https://user:secret@example.test/app.git").put("env",JSONObject().put("TOKEN","secret")))
        check("secret" !in d.toJson(null).toString()); eq("***",d.toJson(null).getJSONObject("env").getString("TOKEN")); eq("secret",d.toStored().getJSONObject("env").getString("TOKEN"))
    } }
    test("deployment", "build environment reserves repository, branch, and build values") { sandbox { dir ->
        val ctx=Context(dir); val ds=Deploys(ctx,Paths(ctx)); val d=ds.create(deployInput().put("env",JSONObject().put("REPO","bad").put("EXTRA","ok")))
        eq(d.repo,ds.buildEnv(d)["REPO"]); eq("ok",ds.buildEnv(d)["EXTRA"])
    } }
    test("deployment", "unbuilt app has no supervisor spec; static build has requested port") { sandbox { dir ->
        val ctx=Context(dir); val paths=Paths(ctx); val ds=Deploys(ctx,paths); val d=ds.create(deployInput()); val alpine=Alpine(ctx,paths); eq(null,ds.spec(d,alpine))
        val meta=File(alpine.root,"srv/.homedroid").apply { mkdirs() }; File(meta,"${d.id}.commit").writeText("abc"); File(meta,"${d.id}.runtime").writeText("static"); File(meta,"${d.id}.root").writeText("dist")
        val spec=ds.spec(d,alpine)!!; eq(d.service,spec.name); check(spec.command.contains(":3000")); check(spec.command.contains(File(alpine.root,"srv/${d.id}/dist").path))
    } }
}
private fun request(path: String="/api/services", method: String="GET", headers: Map<String,String> = emptyMap(), body: String="") = Request(method,path,emptyMap(),headers,body.toByteArray(),"127.0.0.1")
private fun handle(d: Dashboard,r: Request) = call(d,"handle",r) as Response
private fun login(d: Dashboard,p: String) = handle(d,request("/api/login","POST",body=JSONObject().put("password",p).toString()))
private fun authTests() {
    test("HTTP auth/session", "public HTML and protected API") { sandbox { dir ->
        val d=Dashboard(Context(dir)); val r=handle(d,request("/")); eq(200,r.status); eq("DENY",r.headers["X-Frame-Options"]); eq(401,handle(d,request()).status); eq(401,handle(d,request(headers=mapOf("authorization" to "Bearer wrong"))).status)
    } }
    test("HTTP auth/session", "correct bearer password accepted") { sandbox { dir ->
        val ctx=Context(dir); eq(200,handle(Dashboard(ctx),request(headers=mapOf("authorization" to "Bearer ${Config(ctx).dashboardPassword}"))).status)
    } }
    test("HTTP auth/session", "bare password without Bearer scheme rejected") { sandbox { dir ->
        val ctx=Context(dir); eq(401,handle(Dashboard(ctx),request(headers=mapOf("authorization" to Config(ctx).dashboardPassword))).status)
    } }
    test("HTTP auth/session", "incorrect login rejected") { sandbox { dir -> eq(401,login(Dashboard(Context(dir)),"wrong").status) } }
    test("HTTP auth/session", "login cookie, authenticated access, logout invalidation") { sandbox { dir ->
        val ctx=Context(dir); val d=Dashboard(ctx); val response=login(d,Config(ctx).dashboardPassword); eq(200,response.status)
        val set=response.headers.getValue("Set-Cookie"); check("HttpOnly" in set && "SameSite=Strict" in set && "Path=/" in set)
        val cookie=set.substringBefore(';'); check(Regex("homedroid_session=[0-9a-f]{64}").matches(cookie))
        val headers=mapOf("cookie" to cookie); eq(200,handle(d,request(headers=headers)).status)
        val logout=handle(d,request("/api/logout","POST",headers)); eq(200,logout.status); check("Max-Age=0" in logout.headers.getValue("Set-Cookie")); eq(401,handle(d,request(headers=headers)).status)
    } }
    test("HTTP auth/session", "session limit evicts oldest and tokens are unique") { sandbox { dir ->
        val ctx=Context(dir); val d=Dashboard(ctx); val tokens=(1..21).map { login(d,Config(ctx).dashboardPassword).headers.getValue("Set-Cookie").substringBefore(';') }; eq(21,tokens.toSet().size)
        eq(401,handle(d,request(headers=mapOf("cookie" to tokens.first()))).status); eq(200,handle(d,request(headers=mapOf("cookie" to tokens.last()))).status)
    } }
    test("HTTP auth/session", "sessions do not survive dashboard recreation") { sandbox { dir ->
        val ctx=Context(dir); val cookie=login(Dashboard(ctx),Config(ctx).dashboardPassword).headers.getValue("Set-Cookie").substringBefore(';')
        eq(401,handle(Dashboard(ctx),request(headers=mapOf("cookie" to cookie))).status)
    } }
    test("HTTP auth/session", "cookie parsing preserves equals and exact names") {
        eq("abc==",request(headers=mapOf("cookie" to "other=x; homedroid_session=abc==; third=y")).cookie("homedroid_session")); eq(null,request(headers=mapOf("cookie" to "not_homedroid_session=abc")).cookie("homedroid_session"))
    }
    test("HTTP auth/session", "real socket login and session authentication") { sandbox { dir ->
        val ctx=Context(dir); val d=Dashboard(ctx); val http=Http(0) { handle(d,it) }; http.start()
        try {
            val port=(field(http,"socket") as ServerSocket).localPort
            fun send(method: String,path: String,body: String="",cookie: String=""): String = Socket("127.0.0.1",port).use { s ->
                s.soTimeout=5000
                s.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()); s.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            check(send("GET","/api/services").startsWith("HTTP/1.1 401"))
            val response=send("POST","/api/login",JSONObject().put("password",Config(ctx).dashboardPassword).toString()); check(response.startsWith("HTTP/1.1 200")); check("Cache-Control: no-store" in response)
            val cookie=response.lineSequence().first { it.startsWith("Set-Cookie: ") }.removePrefix("Set-Cookie: ").substringBefore(';')
            check(send("GET","/api/services",cookie=cookie).startsWith("HTTP/1.1 200")); check(send("POST","/api/logout",cookie=cookie).startsWith("HTTP/1.1 200")); check(send("GET","/api/services",cookie=cookie).startsWith("HTTP/1.1 401"))
        } finally { http.stop() }
    } }
}
private fun waitUntil(timeout: Long=5000, condition: () -> Boolean) {
    val end=System.nanoTime()+timeout*1_000_000
    while(!condition()) { check(System.nanoTime()<end) { "condition not reached within ${timeout}ms" }; Thread.sleep(10) }
}
private fun supervisorTests() {
    // On this host /system/bin/setsid is absent, so these exercise the real spawn-failure loop.
    check(!File("/system/bin/setsid").exists()) { "supervisor fixture requires absent Android setsid" }
    test("supervisor", "spawn failures back off 1, 2, 4 seconds and stop interrupts sleep") { sandbox { dir ->
        val ctx=Context(dir); val p=Paths(ctx); p.home.mkdirs(); val sv=Supervisor(p,listOf(Spec("test",listOf("unused")))); val d=sv.daemons.single(); sv.start()
        try { waitUntil(4500) { d.log.tail(100).any { "restarting in 4s" in it } }; eq(3,d.restarts); eq(Supervisor.State.BACKOFF,d.state)
            val delays=d.log.tail(100).filter { "restarting in" in it }; eq(listOf(1,2,4),delays.map { Regex("in (\\d+)s").find(it)!!.groupValues[1].toInt() })
        } finally { sv.stop() }; eq(Supervisor.State.STOPPED,d.state)
    } }
    test("supervisor", "stable elapsed interval resets backoff to 1 second") { sandbox { dir ->
        SystemClock.step=60001
        val ctx=Context(dir); val p=Paths(ctx); p.home.mkdirs(); val sv=Supervisor(p,listOf(Spec("test",listOf("unused")))); val d=sv.daemons.single(); sv.start()
        try { waitUntil(3000) { d.restarts>=3 }; check(d.log.tail(100).filter { "restarting in" in it }.all { "restarting in 1s" in it }) }
        finally { sv.stop(); SystemClock.step=0 }
    } }
    test("supervisor", "restart during backoff wakes daemon promptly") { sandbox { dir ->
        val ctx=Context(dir); val p=Paths(ctx); p.home.mkdirs(); val sv=Supervisor(p,listOf(Spec("test",listOf("unused")))); val d=sv.daemons.single(); sv.start()
        try { waitUntil(4500) { d.log.tail(100).any { "restarting in 4s" in it } }; val before=d.log.tail(100).count { "Cannot run program" in it }; d.restart()
            waitUntil(600) { d.log.tail(100).count { "Cannot run program" in it }>before }
        } finally { sv.stop() }
    } }
    test("supervisor", "backoff reaches 60 second cap") { sandbox { dir ->
        val ctx=Context(dir); val p=Paths(ctx); p.home.mkdirs(); val sv=Supervisor(p,listOf(Spec("test",listOf("unused")))); val d=sv.daemons.single(); sv.start()
        try {
            waitUntil(125000) { d.restarts>=8 }
            val delays=d.log.tail(100).filter { "restarting in" in it }.map { Regex("in (\\d+)s").find(it)!!.groupValues[1].toInt() }
            eq(listOf(1,2,4,8,16,32,60,60),delays.take(8))
        } finally { sv.stop() }
    } }
}
fun main() {
    installNetworkDouble()
    registryTests(); digestTests(); tarTests(); configTests(); deployTests(); authTests(); supervisorTests()
    val summary="TOTAL: $passed passed, $failed failed, ${passed+failed} tests"
    println(summary)
    File("tests/host/results.txt").writeText(results.joinToString("\n")+"\n"+summary+"\n")
    if(failed>0) exitProcess(1)
}
