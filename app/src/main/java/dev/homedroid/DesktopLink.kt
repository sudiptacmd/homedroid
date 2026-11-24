package dev.homedroid

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared by the native deck and dashboard. Only hashes ever reach disk. */
class DesktopLink private constructor(private val ctx: Context) {
    companion object {
        // The singleton holds only applicationContext, never an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: DesktopLink? = null
        fun closeIfInitialized() { instance?.closeStreams() }
        fun get(ctx: Context): DesktopLink = instance ?: synchronized(this) {
            instance ?: DesktopLink(ctx.applicationContext).also { instance = it }
        }
    }
    private data class Computer(val id: String, val name: String, val os: String, val version: String, val hash: String, var seen: Long)
    private data class Stream(val queue: ArrayBlockingQueue<JSONObject> = ArrayBlockingQueue(32), @Volatile var active: Boolean = true)
    private val file = AtomicFile(File(ctx.filesDir, "desktop.json"))
    private val computers = LinkedHashMap<String, Computer>()
    private val streams = HashMap<String, Stream>()
    private val pairing = DesktopCore.Pairing()
    private val acknowledgements = DesktopCore.Acknowledgements()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "desktop-acks").apply { isDaemon = true } }
    private val phoneId = ctx.getSharedPreferences("desktop", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("phoneId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("phoneId", it).apply() }
    }
    init {
        if (file.baseFile.exists()) {
            // A corrupt store must not be silently overwritten or revive credentials from elsewhere.
            val root = JSONObject(file.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
            val list = root.getJSONArray("computers")
            require(list.length() <= 8)
            for (i in 0 until list.length()) {
                val c = list.getJSONObject(i)
                computers[c.getString("id")] = Computer(c.getString("id"), c.getString("name"), c.getString("os"),
                    c.optString("version"), c.getString("tokenHash"), c.optLong("lastSeen"))
            }
        }
        timer.scheduleWithFixedDelay({ acknowledgements.expire() }, 250, 250, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun issueCode(): JSONObject = pairing.issue()
    @Synchronized private fun save() {
        val list = JSONArray()
        computers.values.forEach { list.put(publicJson(it).put("tokenHash", it.hash).put("version", it.version).also { o -> o.remove("online") }) }
        val out = file.startWrite()
        try { out.write(JSONObject().put("version", 1).put("computers", list).toString().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    private fun publicJson(c: Computer) = JSONObject().put("id", c.id).put("name", c.name).put("os", c.os)
        .put("online", streams[c.id]?.active == true).put("lastSeen", c.seen)
    @Synchronized fun list(): JSONArray = JSONArray().apply { computers.values.forEach { put(publicJson(it)) } }
    @Synchronized fun remove(id: String): Boolean {
        val c = computers.remove(id) ?: return false
        try { save() } catch (e: Exception) { computers[id] = c; throw e }
        streams[id]?.active = false
        acknowledgements.disconnect(id)
        DesktopWebcamService.instance?.revoke(id)
        return true
    }
    @Synchronized fun closeStreams() {
        streams.forEach { (id, stream) -> stream.active = false; acknowledgements.disconnect(id) }
        DesktopWebcamService.instance?.stopStream()
    }
    @Synchronized private fun authenticate(r: Request): String? {
        val token = r.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return null
        if (!Regex("[0-9a-fA-F]{64}").matches(token)) return null
        var match: Computer? = null
        // Do not short circuit hash comparisons depending on the matching slot.
        computers.values.forEach { if (DesktopCore.matches(token, it.hash)) match = it }
        return match?.also { it.seen = System.currentTimeMillis() }?.id
    }
    @Synchronized private fun paired(id: String) = computers.containsKey(id)
    fun handle(r: Request): Response {
        if (r.path == "/desktop/v1/pair") {
            if (r.method != "POST") return Response.error(405, "Use POST")
            val body = r.json()
            val name = body.getString("name"); val os = body.getString("os"); val version = body.getString("version")
            require(name.isNotBlank() && name.length <= 80 && os in setOf("windows", "linux", "macos") && version.length <= 40)
            return synchronized(this) {
                val status = pairing.consume(r.client, body.optString("code"))
                if (status != 200) return@synchronized Response.error(status, if (status == 429) "Too many attempts; wait one minute" else "That code is wrong or expired")
                if (computers.size >= 8) return@synchronized Response.error(409, "Eight computers are paired; remove one first")
                val token = DesktopCore.token(); val id = "c-${UUID.randomUUID()}"
                computers[id] = Computer(id, name, os, version, DesktopCore.hash(token), System.currentTimeMillis())
                try { save() } catch (e: Exception) { computers.remove(id); throw e }
                Response.json(JSONObject().put("token", token).put("computerId", id).put("phone", Build.MODEL).put("phoneId", phoneId))
            }
        }
        val id = authenticate(r) ?: return Response.error(401, "Pair again")
        return when (r.method to r.path) {
            "GET" to "/desktop/v1/hello" -> Response.json(JSONObject().put("phone", Build.MODEL).put("computerId", id)
                .put("cameras", DesktopWebcamService.cameras(ctx)).put("features", JSONArray(listOf("webcam", "macros"))))
            "GET" to "/desktop/v1/events" -> events(id)
            "POST" to "/desktop/v1/ack" -> {
                val a = r.json(); val ok = a.get("ok"); require(ok is Boolean)
                val error = if (ok) null else a.optString("error", "Action failed").take(512)
                if (acknowledgements.ack(a.getString("id"), id, error)) Response.ok() else Response.error(404, "Unknown or expired action")
            }
            "GET" to "/desktop/v1/webcam" -> DesktopWebcamService.instance?.stream(id, r.query) { paired(id) }
                ?: Response.error(409, "Enable Desktop webcam on the phone's MacroPads screen first")
            "POST" to "/desktop/v1/webcam/control" -> DesktopWebcamService.instance?.control(id, r.json())
                ?: Response.error(409, "No webcam stream is running")
            else -> Response.error(404, "No such desktop endpoint")
        }
    }
    @Synchronized private fun events(id: String): Response {
        if (streams.containsKey(id)) return Response.error(409, "This computer already has an events stream")
        if (!paired(id)) return Response.error(401, "Pair again")
        val stream = Stream(); streams[id] = stream
        fun valid() = stream.active && paired(id)
        return Response(200, byteArrayOf(), "application/x-ndjson", chunked = true, streamAuthorized = ::valid,
            stream = { out ->
                out.write(DesktopCore.ndjson(JSONObject().put("type", "hello").put("computerId", id)))
                while (valid()) {
                    val next = stream.queue.poll(15, TimeUnit.SECONDS) ?: JSONObject().put("type", "ping")
                    if (!valid()) break
                    if (next.optString("type") == "action" && !acknowledgements.isPending(next.getString("id"), id)) continue
                    out.write(DesktopCore.ndjson(next)); out.flush()
                    synchronized(this) { computers[id]?.seen = System.currentTimeMillis() }
                }
            }, onClose = {
                synchronized(this) {
                    stream.active = false
                    if (streams[id] === stream) streams.remove(id)
                    acknowledgements.disconnect(id)
                    runCatching { save() }.onFailure { Jobs.line("Could not save desktop last-seen time: ${it.message}") }
                }
            })
    }
    /** A broadcast succeeds only when every selected computer acknowledges successfully. */
    @Synchronized fun send(action: JSONObject, target: String?): CompletableFuture<String?> {
        val ids = if (target != null) listOf(target) else streams.filterValues { it.active }.keys.toList()
        if (ids.isEmpty() || ids.any { streams[it]?.active != true }) return CompletableFuture.completedFuture("Computer is offline; open Homedroid Desktop")
        if (!acknowledgements.hasCapacity(ids.size)) return CompletableFuture.completedFuture("Too many pending actions; try again shortly")
        val futures = ids.map { computer ->
            val stream = streams.getValue(computer); val id = "a-${UUID.randomUUID()}"
            val future = acknowledgements.add(id, computer)
            val wire = JSONObject(action.toString()).apply { remove("computer") }
            if (!stream.queue.offer(JSONObject().put("type", "action").put("id", id).put("action", wire)))
                acknowledgements.ack(id, computer, "Computer action queue is full")
            future
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.firstNotNullOfOrNull { it.getNow(null) } }
    }
    fun api(r: Request): Response = when {
        r.path == "/api/desktop/pair" && r.method == "POST" -> Response.json(issueCode())
        r.path == "/api/desktop/computers" && r.method == "GET" -> Response.json(list())
        r.path.startsWith("/api/desktop/computers/") && r.method == "DELETE" ->
            if (remove(r.path.removePrefix("/api/desktop/computers/"))) Response.ok() else Response.error(404, "Unknown computer")
        else -> Response.error(404, "No such desktop endpoint")
    }
}
