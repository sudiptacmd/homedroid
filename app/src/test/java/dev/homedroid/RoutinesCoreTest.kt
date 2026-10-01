package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutinesCoreTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), zone)

    @Test fun dailyAndWeekdaySchedules() {
        val daily = Schedule("daily", LocalTime.of(7, 0), 0)
        assertEquals(at("2026-10-02T07:00"), daily.next(at("2026-10-01T07:00")))   // exactly at the time: the next day
        assertEquals(at("2026-10-01T07:00"), daily.next(at("2026-10-01T06:59")))
        val weekdays = Schedule("weekdays", LocalTime.of(7, 0), 0)
        // Friday 2 October 2026, after 7: the next weekday is Monday.
        assertEquals(at("2026-10-05T07:00"), weekdays.next(at("2026-10-02T08:00")))
        assertEquals(at("2026-10-05T07:00"), weekdays.next(at("2026-10-03T06:00")))
        assertEquals(at("2026-10-01T11:30"), Schedule("interval", LocalTime.MIDNIGHT, 3).next(at("2026-10-01T08:30:42")))
    }

    @Test fun schedulesAreValidated() {
        assertEquals("07:05", Schedule.from(JSONObject().put("type", "daily").put("time", "7:05")).toJson().getString("time"))
        for (bad in listOf(JSONObject().put("type", "hourly"), JSONObject().put("time", "25:00"), JSONObject().put("type", "interval").put("hours", 0))) {
            assertThrows(IllegalArgumentException::class.java) { Schedule.from(bad) }
        }
    }

    @Test fun rssAndAtomFeedsAreRead() {
        val rss = """<?xml version="1.0"?><rss><channel><title>News</title>
            <item><title>Rain &amp; wind</title><link>https://n.example/1</link><description>&lt;p&gt;Storm &lt;b&gt;coming&lt;/b&gt;&lt;/p&gt;</description><pubDate>Thu, 01 Oct 2026</pubDate></item>
            <item><title>Second</title><link>https://n.example/2</link></item></channel></rss>"""
        val items = RoutineText.feed(rss.toByteArray())
        assertEquals(2, items.size)
        assertEquals("Rain & wind", items[0].title)
        assertEquals("Storm coming", items[0].summary)
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Hello</title><link href="https://a.example/x"/><summary>Short</summary><updated>2026-10-01</updated></entry></feed>"""
        val a = RoutineText.feed(atom.toByteArray())
        assertEquals("https://a.example/x", a.single().link)
        assertEquals("Short", a.single().summary)
    }

    @Test fun feedsWithDoctypesOrGarbageGiveNothing() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title></item></channel></rss>"""
        assertTrue(RoutineText.feed(xxe.toByteArray()).isEmpty())
        assertTrue(RoutineText.feed("<html><body>not a feed".toByteArray()).isEmpty())
    }

    @Test fun pagesBecomeReadableText() {
        val html = """<html><head><title>My &amp; Page</title><style>p{}</style><script>alert(1)</script></head>
            <body><nav>Menu</nav><article><h1>Big news</h1><p>First&nbsp;line.</p><p>Caf&#233; &#x2764;</p></article><footer>©</footer></body></html>"""
        assertEquals("My & Page", RoutineText.title(html))
        assertEquals("Big news\nFirst line.\nCafé ❤", RoutineText.html(html))
    }

    @Test fun mailHeadersAndBodiesDecode() {
        assertEquals("Grüße from Ana", RoutineText.header("=?UTF-8?B?R3LDvMOfZQ==?= =?utf-8?Q?_from_Ana?="))
        assertEquals("Plain", RoutineText.header("Plain"))
        val h = RoutineText.headers("From: Ana <a@x>\r\nSubject: Long\r\n subject\r\nContent-Type: multipart/alternative;\r\n boundary=\"b1\"\r\n")
        assertEquals("Long subject", h["subject"])
        val body = "--b1\r\nContent-Type: text/html\r\n\r\n<p>html</p>\r\n--b1\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nCaf=C3=A9 at 9=\r\n am\r\n> quoted reply\r\n--b1--\r\n"
        assertEquals("Café at 9 am", RoutineText.body(body.toByteArray(), h["content-type"]!!, ""))
        val b64 = java.util.Base64.getMimeEncoder().encodeToString("Hello there".toByteArray())
        assertEquals("Hello there", RoutineText.body(b64.toByteArray(), "text/plain", "base64"))
        // A body cut off mid-way by the 8 KB fetch still decodes what arrived.
        assertEquals("Hello th", RoutineText.body(b64.dropLast(4).toByteArray(), "text/plain", "base64").take(8))
        assertEquals("only html", RoutineText.body("<b>only</b> html".toByteArray(), "text/html", ""))
    }

    @Test fun imapLiteralsAreSeparatedFromTheText() {
        val wire = "* 3 FETCH (BODY[HEADER.FIELDS (FROM)] {11}\r\nFrom: a@x\r\n BODY[TEXT]<0> {5}\r\nhello)\r\nh4 OK done\r\n"
        val input = wire.toByteArray().inputStream()
        val r = RoutineText.imapResponse(input)!!
        assertTrue(r.text.startsWith("* 3 FETCH"))
        assertEquals(listOf("From: a@x\r\n", "hello"), r.literals.map { String(it) })
        assertEquals("h4 OK done", RoutineText.imapResponse(input)!!.text)
        assertNull(RoutineText.imapResponse(input))
    }

    @Test fun imapQuotingRefusesLineBreaks() {
        assertEquals("\"a\\\"b\\\\c\"", RoutineText.imapQuote("a\"b\\c"))
        assertThrows(IllegalArgumentException::class.java) { RoutineText.imapQuote("pw\r\nh9 DELETE INBOX") }
    }

    @Test fun weatherReadsAsASentence() {
        val o = JSONObject("""{"current":{"temperature_2m":24.5,"weather_code":61,"wind_speed_10m":12.0},
            "daily":{"weather_code":[63],"temperature_2m_min":[22.1],"temperature_2m_max":[29.8],"precipitation_probability_max":[80]}}""")
        val s = RoutineText.weather("Dhaka", o)
        assertTrue(s, s.contains("light rain") && s.contains("22.1 to 29.8") && s.contains("80%"))
    }
}
