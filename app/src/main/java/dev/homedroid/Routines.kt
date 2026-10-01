package dev.homedroid

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Scheduled AI tasks: at set times a routine reads its sources (news feeds, web pages, the
 * weather, this phone's health, unread email), has a model summarize them, and delivers the
 * result: read aloud on a phone, kept in the Briefs inbox, pushed with ntfy or Telegram.
 *
 * Routines only read. Email is read with EXAMINE and BODY.PEEK, so nothing is marked as read,
 * and it goes to a cloud model only if the routine allows it.
 */
class Routines(context: Context, private val ai: Ai) {
    private val ctx: Context = context.applicationContext
    private val dir = File(ctx.filesDir, "ai").apply { mkdirs() }
    private val file = File(dir, "routines.json")
    private val briefsFile = File(dir, "briefs.json")
    private val prefs = ctx.getSharedPreferences("routines", Context.MODE_PRIVATE)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "routines-clock").apply { isDaemon = true } }
    // One routine at a time: a phone running a model has no room for two.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "routines").apply { isDaemon = true } }
    @Volatile private var running: String? = null

    fun start() { scheduler.scheduleWithFixedDelay(::tick, 30, 30, TimeUnit.SECONDS) }

    fun stop() { scheduler.shutdownNow(); worker.shutdownNow() }

    // --- storage --------------------------------------------------------------------------

    @Synchronized private fun all(): MutableList<JSONObject> = try {
        JSONArray(file.readText()).let { a -> (0 until a.length()).map(a::getJSONObject).toMutableList() }
    } catch (_: Exception) { mutableListOf() }

    @Synchronized private fun save(list: List<JSONObject>) {
        File(dir, "routines.json.tmp").apply { writeText(JSONArray(list).toString()) }.renameTo(file)
    }

    @Synchronized private fun update(id: String, change: (JSONObject) -> Unit) = save(all().onEach { if (it.optString("id") == id) change(it) })

    @Synchronized private fun briefs(): MutableList<JSONObject> = try {
        JSONArray(briefsFile.readText()).let { a -> (0 until a.length()).map(a::getJSONObject).toMutableList() }
    } catch (_: Exception) { mutableListOf() }

    @Synchronized private fun saveBriefs(list: List<JSONObject>) {
        File(dir, "briefs.json.tmp").apply { writeText(JSONArray(list.take(100)).toString()) }.renameTo(briefsFile)
    }

    // --- the clock ------------------------------------------------------------------------

    private fun tick() {
        if (!ai.cfg.enabled) return
        val now = ZonedDateTime.now()
        for (r in all()) {
            if (!r.optBoolean("enabled", true)) continue
            val due = r.optLong("nextRun")
            if (due == 0L || due > now.toInstant().toEpochMilli()) continue
            val schedule = try { Schedule.from(r.getJSONObject("schedule")) } catch (_: Exception) { continue }
            update(r.getString("id")) { it.put("nextRun", schedule.next(now).toInstant().toEpochMilli()) }
            // A morning brief at noon is no use: runs missed by hours (phone off) are skipped.
            if (now.toInstant().toEpochMilli() - due > 3 * 3600_000L) continue
            enqueue(r.getString("id"))
        }
    }

    private fun enqueue(id: String) = worker.execute {
        val r = all().firstOrNull { it.optString("id") == id } ?: return@execute
        running = id
        val started = System.currentTimeMillis()
        val brief = try { run(r) } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        } finally { running = null }
        brief.put("id", started.toString()).put("routine", id).put("name", r.optString("name")).put("time", started)
            .put("seconds", (System.currentTimeMillis() - started) / 1000)
        saveBriefs(listOf(brief) + briefs())
        update(id) { it.put("lastRun", started).put("lastError", brief.opt("error") ?: JSONObject.NULL) }
    }

    // --- one run ----------------------------------------------------------------------------

    private class Section(val title: String, val text: String, val email: Boolean = false)

    /** Reads the sources, summarizes long ones, writes the brief and delivers it. */
    private fun run(r: JSONObject): JSONObject {
        val model = r.optString("model")
        val target = ai.target(model)
        val sources = r.optJSONArray("sources") ?: JSONArray()
        val sections = (0 until sources.length()).map { i ->
            val s = sources.getJSONObject(i)
            try { read(s) } catch (e: Exception) { Section(label(s), "(couldn't read it: ${e.message ?: e.javaClass.simpleName})") }
        }
        if (sections.any { it.email } && !target.local && !r.optBoolean("cloudEmail")) {
            throw IOException("This routine reads email, so it only uses a model on this phone. Allow cloud models for it, or pick a local model.")
        }
        // Map: shrink long sources first, so a small model's context holds them all.
        val parts = sections.map { s ->
            if (s.text.length <= 2500) s.title to s.text
            else s.title to ai.chat(model, messages(
                "You condense source material for a personal briefing. Keep names, numbers and dates. Use only what is given.",
                "Source: ${s.title}\n\n${s.text.take(9000)}\n\nList its most important points as at most 6 short bullet points.",
            ), 400).first
        }
        val material = parts.joinToString("\n\n") { (t, x) -> "## $t\n${x.take(3000)}" }.take(12000)
        val now = ZonedDateTime.now()
        val (text, used) = ai.chat(model, messages(
            "You write short personal briefings from the material given. Use only that material and say when something is missing. " +
                "Now is ${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))}. " +
                "Write plain text: short paragraphs or bullet points, no tables.",
            "${r.optString("instruction").ifEmpty { "Brief me on what matters." }}\n\n$material",
        ), 700)
        if (text.isBlank()) throw IOException("The model returned no text")
        val brief = JSONObject().put("text", text).put("model", used.id).put("where", if (used.local) "local" else "cloud")
        used.note?.let { brief.put("note", it) }
        brief.put("delivery", deliver(r, text))
        return brief
    }

    private fun messages(system: String, user: String) = JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user))

    private fun label(s: JSONObject) = when (s.optString("type")) {
        "rss", "web" -> URL(s.optString("url")).host.removePrefix("www.")
        "weather" -> "Weather"
        "phone" -> "Phone"
        "email" -> "Email"
        else -> "Source"
    }

    private fun read(s: JSONObject): Section = when (s.optString("type")) {
        "rss" -> {
            val bytes = get(s.getString("url"))
            val items = RoutineText.feed(bytes)
            if (items.isEmpty()) throw IOException("not a news feed")
            Section(label(s), items.joinToString("\n") { "- ${it.title}${if (it.summary.isNotEmpty()) ": ${it.summary}" else ""}" })
        }
        "web" -> {
            val html = String(get(s.getString("url")))
            Section(RoutineText.title(html).ifEmpty { label(s) }, RoutineText.html(html).take(12000))
        }
        "weather" -> {
            val q = "latitude=${s.getDouble("lat")}&longitude=${s.getDouble("lon")}&timezone=auto&forecast_days=1" +
                "&current=temperature_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            Section("Weather", RoutineText.weather(s.optString("place"), JSONObject(String(get("https://api.open-meteo.com/v1/forecast?$q")))))
        }
        "phone" -> Section("This phone", phoneStatus())
        "email" -> Section("Unread email", mail().ifEmpty { "No unread email in the last ${prefs.getInt("email_hours", 12)} hours." }, email = true)
        else -> throw IOException("unknown source")
    }

    private fun get(url: String): ByteArray {
        require(url.startsWith("https://") || url.startsWith("http://")) { "only web addresses" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Homedroid/0.9 (+https://github.com/sudiptacmd/homedroid)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1 shl 14)
                while (out.size() < 2_000_000) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
        } finally { conn.disconnect() }
    }

    private fun phoneStatus(): String {
        val d = Device(ctx)
        val daemons = ServerService.supervisor?.daemons.orEmpty()
        return buildString {
            append("${d.model}, battery ${d.batteryPercent}%${if (d.charging) " (charging)" else ""}, ${d.batteryTempC} °C. ")
            append("Free storage ${d.storageFreeMb / 1024} GB of ${d.storageTotalMb / 1024} GB, free memory ${d.ramFreeMb} MB. ")
            append("Up ${d.uptimeS / 3600} hours. ")
            val bad = daemons.filter { it.state != Supervisor.State.RUNNING || it.restarts > 0 }
            append(if (bad.isEmpty()) "All ${daemons.size} services are running normally." else
                "Services needing attention: " + bad.joinToString { "${it.spec.name} (${it.state.name.lowercase()}, ${it.restarts} restarts)" } + ".")
        }
    }

    // --- email ------------------------------------------------------------------------------

    /** Unread messages from the last hours, read-only over IMAPS. */
    private fun mail(): String {
        val host = prefs.getString("email_host", "").orEmpty()
        val user = prefs.getString("email_user", "").orEmpty()
        val pass = prefs.getString("email_password", "").orEmpty()
        if (host.isEmpty() || user.isEmpty()) throw IOException("set up email in AI → Routines first")
        val since = ZonedDateTime.now().minusHours(prefs.getInt("email_hours", 12).toLong())
        return imap(host, prefs.getInt("email_port", 993), user, pass) { cmd ->
            cmd("EXAMINE INBOX")
            val search = cmd("SEARCH UNSEEN SINCE ${since.format(DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH))}")
            val ids = search.firstOrNull { it.text.startsWith("* SEARCH") }?.text?.removePrefix("* SEARCH")?.trim()
                ?.split(' ')?.filter { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty().takeLast(15).reversed()
            ids.mapNotNull { id ->
                val res = cmd("FETCH $id (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT]<0.8000>)")
                    .firstOrNull { it.text.startsWith("* $id FETCH") } ?: return@mapNotNull null
                val h = RoutineText.headers(res.literals.getOrNull(0)?.toString(Charsets.ISO_8859_1).orEmpty())
                val body = RoutineText.body(res.literals.getOrNull(1) ?: ByteArray(0), h["content-type"].orEmpty(), h["content-transfer-encoding"].orEmpty())
                "From: ${RoutineText.header(h["from"].orEmpty())}\nSubject: ${RoutineText.header(h["subject"].orEmpty())}\n" +
                    "Date: ${h["date"].orEmpty()}\n${RoutineText.clean(body).take(500)}"
            }.joinToString("\n\n")
        }
    }

    /** Logs in over TLS (checking the server's certificate and name), runs [block], logs out. */
    private fun <T> imap(host: String, port: Int, user: String, pass: String, block: ((String) -> List<RoutineText.ImapResponse>) -> T): T {
        val socket = SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket
        socket.use {
            socket.soTimeout = 20_000
            socket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) throw IOException("$host's certificate doesn't match its name")
            val input = socket.inputStream.buffered()
            val out = socket.outputStream
            RoutineText.imapResponse(input)?.takeIf { it.text.startsWith("* OK") } ?: throw IOException("$host isn't an IMAP server")
            var n = 0
            fun cmd(c: String): List<RoutineText.ImapResponse> {
                val tag = "h${++n}"
                out.write("$tag $c\r\n".toByteArray()); out.flush()
                val lines = mutableListOf<RoutineText.ImapResponse>()
                while (true) {
                    val r = RoutineText.imapResponse(input) ?: throw IOException("the mail server hung up")
                    if (r.text.startsWith("$tag ")) {
                        if (!r.text.startsWith("$tag OK")) throw IOException(r.text.removePrefix("$tag ").take(200))
                        return lines
                    }
                    lines += r
                    if (lines.size > 1000) throw IOException("too much from the mail server")
                }
            }
            try { cmd("LOGIN ${RoutineText.imapQuote(user)} ${RoutineText.imapQuote(pass)}") }
            catch (e: IOException) { throw IOException("login failed (use an app password): ${e.message}") }
            return block(::cmd).also { try { cmd("LOGOUT") } catch (_: IOException) {} }
        }
    }

    // --- delivery ---------------------------------------------------------------------------

    /** Sends [text] where the routine asks; returns what happened per channel. */
    private fun deliver(r: JSONObject, text: String): JSONObject {
        val out = JSONObject()
        val title = r.optString("name", "Homedroid brief")
        fun attempt(name: String, block: () -> Unit) {
            out.put(name, try { block(); "sent" } catch (e: Exception) { "failed: ${e.message ?: e.javaClass.simpleName}" })
        }
        val speak = r.optString("speak")
        if (speak == "self") attempt("speak") { Speaker.say(ctx, text)?.let { throw IOException(it) } }
        else if (speak.isNotEmpty()) attempt("speak") {
            val c = Cluster.instance ?: throw IOException("the cluster isn't running")
            val p = c.state.peer(speak) ?: throw IOException("that phone left the cluster")
            val (status, o) = c.peerJson(p, "POST", "/api/ai/speak", JSONObject().put("text", text))
            if (status != 200) throw IOException(o.optString("error", "${p.name} answered $status"))
        }
        if (r.optBoolean("ntfy")) attempt("ntfy") {
            val server = prefs.getString("ntfy_server", "https://ntfy.sh")!!.trimEnd('/')
            val topic = prefs.getString("ntfy_topic", "").orEmpty().ifEmpty { throw IOException("set up ntfy first") }
            post("$server/${URLEncoder.encode(topic, "UTF-8")}", text.take(4000).toByteArray(), "text/plain",
                buildMap {
                    put("Title", title.filter { it.code in 32..126 }.ifEmpty { "Homedroid" })
                    prefs.getString("ntfy_token", "")?.takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
                })
        }
        if (r.optBoolean("telegram")) attempt("telegram") {
            val token = prefs.getString("telegram_token", "").orEmpty().ifEmpty { throw IOException("set up Telegram first") }
            val body = JSONObject().put("chat_id", prefs.getString("telegram_chat", "")).put("text", "$title\n\n$text".take(4000))
            post("https://api.telegram.org/bot$token/sendMessage", body.toString().toByteArray(), "application/json")
        }
        return out
    }

    private fun post(url: String, body: ByteArray, type: String, headers: Map<String, String> = emptyMap()) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 20_000; doOutput = true
            setRequestProperty("Content-Type", type)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("HTTP $status ${AiCore.errorMessage(err).take(150)}")
            }
        } finally { conn.disconnect() }
    }

    // --- the dashboard ----------------------------------------------------------------------

    fun handle(r: Request, seg: List<String>): Response {
        val body = if (r.method == "POST") r.json() else JSONObject()
        return when {
            r.method == "POST" && seg == listOf("speak") -> {
                val text = body.optString("text").trim()
                if (text.isEmpty() || text.length > 20_000) return Response.error(400, "Nothing to say")
                Speaker.say(ctx, text)?.let { Response.error(409, it) } ?: Response.ok()
            }
            r.method == "GET" && seg == listOf("routines") -> Response.json(json())
            r.method == "POST" && seg == listOf("routines") -> saveRoutine(body)
            r.method == "POST" && seg == listOf("routines", "settings") -> saveSettings(body)
            r.method == "POST" && seg == listOf("routines", "place") -> place(body.optString("name"))
            r.method == "POST" && seg.size == 3 && seg[0] == "routines" && seg[2] == "run" -> {
                if (all().none { it.optString("id") == seg[1] }) return Response.error(404, "No such routine")
                if (!ai.cfg.enabled) return Response.error(409, "Turn on AI first")
                enqueue(seg[1]); Response.ok()
            }
            r.method == "DELETE" && seg.size == 2 && seg[0] == "routines" -> { save(all().filter { it.optString("id") != seg[1] }); Response.ok() }
            r.method == "GET" && seg == listOf("briefs") -> Response.json(JSONArray(briefs().take(50)))
            r.method == "DELETE" && seg == listOf("briefs") -> { saveBriefs(emptyList()); Response.ok() }
            r.method == "DELETE" && seg.size == 2 && seg[0] == "briefs" -> { saveBriefs(briefs().filter { it.optString("id") != seg[1] }); Response.ok() }
            else -> Response.error(404, "No such routines endpoint")
        }
    }

    private fun json() = JSONObject()
        .put("routines", JSONArray().apply { all().forEach { put(JSONObject(it.toString()).put("running", running == it.optString("id"))) } })
        .put("settings", JSONObject()
            .put("email", JSONObject().put("host", prefs.getString("email_host", "")).put("port", prefs.getInt("email_port", 993))
                .put("user", prefs.getString("email_user", "")).put("hasPassword", !prefs.getString("email_password", "").isNullOrEmpty())
                .put("hours", prefs.getInt("email_hours", 12)))
            .put("ntfy", JSONObject().put("server", prefs.getString("ntfy_server", "https://ntfy.sh")).put("topic", prefs.getString("ntfy_topic", ""))
                .put("hasToken", !prefs.getString("ntfy_token", "").isNullOrEmpty()))
            .put("telegram", JSONObject().put("chat", prefs.getString("telegram_chat", "")).put("hasToken", !prefs.getString("telegram_token", "").isNullOrEmpty())))

    private fun saveRoutine(b: JSONObject): Response {
        val name = Peer.cleanName(b.optString("name")).ifEmpty { return Response.error(400, "Give the routine a name") }
        val schedule = try { Schedule.from(b.optJSONObject("schedule") ?: JSONObject()) } catch (e: IllegalArgumentException) { return Response.error(400, e.message ?: "Invalid schedule") }
        val model = b.optString("model")
        try { AiCore.resolve(model, ai.cfg.providers, if (model.startsWith("local/")) model.removePrefix("local/") else null, true, false, "") }
        catch (_: Exception) { return Response.error(400, "Pick a model for it") }
        val sources = JSONArray()
        val given = b.optJSONArray("sources") ?: JSONArray()
        if (given.length() !in 1..12) return Response.error(400, "Add 1 to 12 sources")
        for (i in 0 until given.length()) {
            val s = given.getJSONObject(i)
            when (s.optString("type")) {
                "rss", "web" -> {
                    val url = s.optString("url").trim()
                    if (!Regex("https?://[^\\s]{3,500}").matches(url)) return Response.error(400, "Enter a web address like https://example.com/feed")
                    sources.put(JSONObject().put("type", s.getString("type")).put("url", url))
                }
                "weather" -> {
                    if (!s.has("lat") || !s.has("lon")) return Response.error(400, "Look up the weather place first")
                    sources.put(JSONObject().put("type", "weather").put("place", Peer.cleanName(s.optString("place"))).put("lat", s.getDouble("lat")).put("lon", s.getDouble("lon")))
                }
                "phone", "email" -> sources.put(JSONObject().put("type", s.getString("type")))
                else -> return Response.error(400, "Unknown source")
            }
        }
        val speak = b.optString("speak")
        if (speak.isNotEmpty() && speak != "self" && Cluster.instance?.state?.peer(speak) == null) return Response.error(400, "Pick a phone in the cluster to speak on")
        val list = all()
        val id = b.optString("id").takeIf { i -> list.any { it.optString("id") == i } } ?: System.currentTimeMillis().toString(36)
        val old = list.firstOrNull { it.optString("id") == id }
        val r = JSONObject().put("id", id).put("name", name).put("enabled", b.optBoolean("enabled", true))
            .put("schedule", schedule.toJson()).put("sources", sources).put("instruction", b.optString("instruction").take(2000))
            .put("model", model).put("cloudEmail", b.optBoolean("cloudEmail")).put("speak", speak)
            .put("ntfy", b.optBoolean("ntfy")).put("telegram", b.optBoolean("telegram"))
            .put("nextRun", schedule.next(ZonedDateTime.now()).toInstant().toEpochMilli())
            .put("lastRun", old?.optLong("lastRun") ?: 0).put("lastError", old?.opt("lastError") ?: JSONObject.NULL)
        save(list.filter { it.optString("id") != id } + r)
        return Response.json(r)
    }

    private fun saveSettings(b: JSONObject): Response {
        val e = prefs.edit()
        b.optJSONObject("email")?.let { m ->
            val host = m.optString("host").trim()
            if (host.isNotEmpty() && !Peer.validHost(host)) return Response.error(400, "Enter the IMAP server, like imap.gmail.com")
            val port = m.optInt("port", 993)
            if (port !in 1..65535) return Response.error(400, "Invalid port")
            e.putString("email_host", host).putInt("email_port", port).putString("email_user", m.optString("user").trim())
                .putInt("email_hours", m.optInt("hours", 12).coerceIn(1, 168))
            m.optString("password").takeIf { it.isNotEmpty() }?.let { e.putString("email_password", it) }
            if (m.optBoolean("clear")) e.remove("email_password").remove("email_host").remove("email_user")
        }
        b.optJSONObject("ntfy")?.let { m ->
            val server = m.optString("server", "https://ntfy.sh").trim().trimEnd('/').ifEmpty { "https://ntfy.sh" }
            if (!AiCore.validBaseUrl(server)) return Response.error(400, "Enter the ntfy server address")
            val topic = m.optString("topic").trim()
            if (topic.isNotEmpty() && !Regex("[A-Za-z0-9_-]{1,64}").matches(topic)) return Response.error(400, "Topics use letters, digits, - and _")
            e.putString("ntfy_server", server).putString("ntfy_topic", topic)
            m.optString("token").takeIf { it.isNotEmpty() }?.let { e.putString("ntfy_token", it) }
        }
        b.optJSONObject("telegram")?.let { m ->
            val chat = m.optString("chat").trim()
            if (chat.isNotEmpty() && !Regex("-?\\d{1,20}|@[A-Za-z0-9_]{4,64}").matches(chat)) return Response.error(400, "Enter the chat id, like 123456789")
            e.putString("telegram_chat", chat)
            m.optString("token").trim().takeIf { it.isNotEmpty() }?.let {
                if (!Regex("\\d{5,15}:[A-Za-z0-9_-]{20,64}").matches(it)) return Response.error(400, "That bot token doesn't look right")
                e.putString("telegram_token", it)
            }
        }
        e.commit()
        // Check the email login right away, so mistakes show now rather than at 7 am.
        if (b.optJSONObject("email")?.optString("host")?.isNotEmpty() == true) {
            try { mail() } catch (ex: Exception) { return Response.error(400, "Saved, but reading email failed: ${ex.message}") }
        }
        return Response.ok()
    }

    /** Looks up a place name for the weather (Open-Meteo's geocoder). */
    private fun place(name: String): Response {
        if (name.isBlank()) return Response.error(400, "Enter a town or city")
        val o = try { JSONObject(String(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${URLEncoder.encode(name.trim(), "UTF-8")}"))) }
        catch (e: Exception) { return Response.error(502, "Couldn't look it up: ${e.message}") }
        val p = o.optJSONArray("results")?.optJSONObject(0) ?: return Response.error(404, "No place called $name")
        return Response.json(JSONObject().put("place", listOf(p.optString("name"), p.optString("admin1"), p.optString("country")).filter { it.isNotEmpty() }.joinToString(", "))
            .put("lat", p.getDouble("latitude")).put("lon", p.getDouble("longitude")))
    }
}

/** Text-to-speech on this phone's speaker, at media volume. */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    /** Speaks [text] (markdown removed), queued in pieces the engine accepts; returns an error, or null. */
    @Synchronized fun say(ctx: Context, text: String): String? {
        if (tts == null) {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                tts = TextToSpeech(ctx.applicationContext) { ready = it == TextToSpeech.SUCCESS; latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
        }
        val t = tts
        if (t == null || !ready) { tts?.shutdown(); tts = null; return "Text-to-speech isn't available on this phone; check its speech settings" }
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val plain = text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("[*#`_>]+"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s*"), "").trim()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val chunks = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in plain.split(Regex("(?<=[.!?\\n])\\s+"))) {
            if (cur.length + sentence.length + 1 > max && cur.isNotEmpty()) { chunks += cur.toString(); cur = StringBuilder() }
            cur.append(sentence.take(max)).append(' ')
        }
        if (cur.isNotBlank()) chunks += cur.toString()
        chunks.forEachIndexed { i, c ->
            if (t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "brief-$i") != TextToSpeech.SUCCESS) return "Speaking failed"
        }
        return null
    }
}
