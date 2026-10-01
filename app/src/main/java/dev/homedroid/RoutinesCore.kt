package dev.homedroid

import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** When a routine runs: daily or on weekdays at a time, or every few hours. */
class Schedule(val type: String, val time: LocalTime, val hours: Int) {
    /** The first run strictly after [after]. */
    fun next(after: ZonedDateTime): ZonedDateTime {
        if (type == "interval") return after.plusHours(hours.toLong()).withSecond(0).withNano(0)
        var t = after.toLocalDate().atTime(time).atZone(after.zone)
        while (!t.isAfter(after) || type == "weekdays" && t.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
            t = t.toLocalDate().plusDays(1).atTime(time).atZone(after.zone)
        }
        return t
    }

    fun toJson(): JSONObject = JSONObject().put("type", type).put("time", "%02d:%02d".format(time.hour, time.minute)).put("hours", hours)

    companion object {
        fun from(o: JSONObject): Schedule {
            val type = o.optString("type", "daily")
            require(type in setOf("daily", "weekdays", "interval")) { "Pick when it runs" }
            val m = Regex("([01]?\\d|2[0-3]):([0-5]\\d)").matchEntire(o.optString("time", "07:00"))
            require(m != null) { "Enter a time like 07:00" }
            val hours = o.optInt("hours", 3)
            require(type != "interval" || hours in 1..24) { "Run every 1 to 24 hours" }
            return Schedule(type, LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()), hours)
        }
    }
}

/** An item from an RSS or Atom feed. */
class FeedItem(val title: String, val link: String, val summary: String, val date: String)

/** Readers for what routines collect, kept free of Android so they can be tested. */
object RoutineText {
    /** Items of an RSS 2.0 or Atom feed, newest first as the feed lists them; empty if it isn't one. */
    fun feed(xml: ByteArray, limit: Int = 12): List<FeedItem> {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                // No DOCTYPEs: no external entities, no entity expansion bombs.
                try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            }
            if (String(xml, 0, minOf(xml.size, 2048), Charsets.ISO_8859_1).contains("<!DOCTYPE", ignoreCase = true)) return emptyList()
            f.newDocumentBuilder().parse(xml.inputStream())
        } catch (_: Exception) { return emptyList() }
        fun Element.text(tag: String) = getElementsByTagName(tag).let { if (it.length > 0) it.item(0).textContent.orEmpty().trim() else "" }
        val items = doc.getElementsByTagName("item").takeIf { it.length > 0 } ?: doc.getElementsByTagName("entry")
        return (0 until minOf(items.length, limit)).map { i ->
            val e = items.item(i) as Element
            val link = e.text("link").ifEmpty {
                (e.getElementsByTagName("link").item(0) as? Element)?.getAttribute("href").orEmpty()
            }
            FeedItem(
                clean(e.text("title")), link,
                clean(html(e.text("description").ifEmpty { e.text("summary").ifEmpty { e.text("content") } })).take(400),
                e.text("pubDate").ifEmpty { e.text("updated").ifEmpty { e.text("published") } },
            )
        }
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "#39" to "'",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“")

    /** The readable text of an HTML page or fragment: main content if marked, no scripts or menus. */
    fun html(s: String): String {
        var h = s
        Regex("(?is)<(article|main)\\b[^>]*>(.*?)</\\1>").find(h)?.let { h = it.groupValues[2] }
        h = h.replace(Regex("(?is)<(script|style|noscript|svg|nav|header|footer|form|aside|template)\\b.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)\\b[^>]*>"), "\n")
            .replace(Regex("<[^>]*>"), " ")
        h = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(h) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
                else -> ENTITIES[e] ?: m.value
            }
        }
        return h.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** The page's title, if it has one. */
    fun title(html: String) = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { clean(html(it)) }.orEmpty()

    private val WEATHER = mapOf(0 to "clear sky", 1 to "mainly clear", 2 to "partly cloudy", 3 to "overcast", 45 to "fog", 48 to "freezing fog",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 56 to "freezing drizzle", 57 to "freezing drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "freezing rain", 67 to "freezing rain", 71 to "light snow", 73 to "snow",
        75 to "heavy snow", 77 to "snow grains", 80 to "rain showers", 81 to "rain showers", 82 to "violent rain showers", 85 to "snow showers",
        86 to "heavy snow showers", 95 to "thunderstorm", 96 to "thunderstorm with hail", 99 to "thunderstorm with heavy hail")

    /** An Open-Meteo forecast as one paragraph. */
    fun weather(place: String, o: JSONObject): String {
        val now = o.optJSONObject("current") ?: JSONObject()
        val day = o.optJSONObject("daily") ?: JSONObject()
        fun first(k: String) = day.optJSONArray(k)?.opt(0)
        val code = now.optInt("weather_code", -1)
        return buildString {
            append("Weather in $place now: ${WEATHER[code] ?: "unknown conditions"}, ${now.optDouble("temperature_2m")} °C, wind ${now.optDouble("wind_speed_10m")} km/h. ")
            append("Today: ${WEATHER[(first("weather_code") as? Number)?.toInt() ?: -1] ?: "mixed"}, ")
            append("${first("temperature_2m_min")} to ${first("temperature_2m_max")} °C, ")
            append("chance of rain ${first("precipitation_probability_max")}%.")
        }
    }

    // --- email --------------------------------------------------------------------------------

    /** Decodes RFC 2047 encoded words (=?UTF-8?B?…?=) in a header value. */
    fun header(value: String): String = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=(\\s+(?==\\?))?").replace(value) { m ->
        val cs = charset(m.groupValues[1])
        val data = m.groupValues[3]
        try {
            if (m.groupValues[2].equals("B", true)) String(Base64.getMimeDecoder().decode(data), cs)
            else String(qp(data.replace('_', ' ').toByteArray(Charsets.ISO_8859_1)), cs)
        } catch (_: Exception) { m.value }
    }.let(::clean)

    fun charset(name: String?): Charset = try { Charset.forName(name?.trim('"', ' ') ?: "UTF-8") } catch (_: Exception) { Charsets.UTF_8 }

    /** Quoted-printable bytes to bytes; lenient about broken escapes. */
    fun qp(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            if (c == '='.code && i + 1 < b.size && (b[i + 1].toInt() == '\r'.code || b[i + 1].toInt() == '\n'.code)) {
                i += if (b[i + 1].toInt() == '\r'.code && i + 2 < b.size && b[i + 2].toInt() == '\n'.code) 3 else 2
                continue
            }
            if (c == '='.code && i + 2 < b.size) {
                val hex = String(b, i + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                if (hex != null) { out.write(hex); i += 3; continue }
            }
            out.write(c); i++
        }
        return out.toByteArray()
    }

    /** Header fields of a raw header block, unfolded, names lowercased. */
    fun headers(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var last: String? = null
        for (line in raw.split("\r\n", "\n")) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') { last?.let { out[it] = out[it] + " " + line.trim() }; continue }
            val i = line.indexOf(':')
            if (i <= 0) continue
            last = line.substring(0, i).trim().lowercase()
            out[last] = line.substring(i + 1).trim()
        }
        return out
    }

    private fun param(contentType: String, name: String) =
        Regex("(?i)\\b$name\\s*=\\s*(\"([^\"]*)\"|([^;\\s]+))").find(contentType)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    /**
     * Readable text of a message body (possibly cut short) given its Content-Type and
     * Content-Transfer-Encoding: the first text/plain part, else text/html as text.
     */
    fun body(raw: ByteArray, contentType: String, encoding: String, depth: Int = 0): String {
        val type = contentType.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        if (type.startsWith("multipart/") && depth < 4) {
            val boundary = param(contentType, "boundary") ?: return ""
            val text = String(raw, Charsets.ISO_8859_1)
            val parts = text.split("--$boundary").drop(1).filterNot { it.startsWith("--") }
            val decoded = parts.mapNotNull { p ->
                val split = Regex("\r?\n\r?\n").find(p) ?: return@mapNotNull null
                val h = headers(p.substring(0, split.range.first))
                val ct = h["content-type"] ?: "text/plain"
                if (h["content-disposition"]?.startsWith("attachment", true) == true) return@mapNotNull null
                ct to body(p.substring(split.range.last + 1).toByteArray(Charsets.ISO_8859_1), ct, h["content-transfer-encoding"].orEmpty(), depth + 1)
            }
            return (decoded.firstOrNull { it.first.startsWith("text/plain", true) && it.second.isNotBlank() }
                ?: decoded.firstOrNull { it.second.isNotBlank() })?.second.orEmpty()
        }
        if (!type.startsWith("text/")) return ""
        val bytes = when (encoding.trim().lowercase()) {
            "base64" -> try { Base64.getMimeDecoder().decode(String(raw, Charsets.ISO_8859_1).replace(Regex("[^A-Za-z0-9+/=]"), "").let { it.substring(0, it.length / 4 * 4) }) } catch (_: Exception) { ByteArray(0) }
            "quoted-printable" -> qp(raw)
            else -> raw
        }
        val s = String(bytes, charset(param(contentType, "charset")))
        return if (type == "text/html") html(s) else s.lines().filterNot { it.startsWith(">") }.joinToString("\n").trim()
    }

    /** One IMAP response: its text with each {n} literal left as the marker, and the literals' bytes in order. */
    class ImapResponse(val text: String, val literals: List<ByteArray>)

    /** Reads one IMAP response, however many lines its literals span; null at the end of the stream. */
    fun imapResponse(input: InputStream, max: Int = 1 shl 20): ImapResponse? {
        val text = StringBuilder()
        val literals = mutableListOf<ByteArray>()
        var size = 0
        while (true) {
            val line = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (text.isEmpty() && line.size() == 0) null else ImapResponse(text.append(line.toString("ISO-8859-1")).toString(), literals)
                if (c == '\n'.code) break
                line.write(c)
                if (++size > max) throw IOException("IMAP response too large")
            }
            val l = line.toString("ISO-8859-1").removeSuffix("\r")
            text.append(l)
            val m = Regex("\\{(\\d{1,9})}$").find(l) ?: return ImapResponse(text.toString(), literals)
            val n = m.groupValues[1].toInt()
            size += n
            if (size > max) throw IOException("IMAP response too large")
            val buf = ByteArray(n)
            var read = 0
            while (read < n) { val k = input.read(buf, read, n - read); if (k < 0) throw IOException("IMAP literal cut off"); read += k }
            literals += buf
        }
    }

    /** Quotes a string for an IMAP command. */
    fun imapQuote(s: String): String {
        require(s.none { it == '\r' || it == '\n' || it == '\u0000' }) { "invalid characters" }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
