package dev.homedroid

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetModelTest {
    @Test fun phonesShowBatteryAndTemperatureOrOffline() {
        assertEquals(WidgetModel.Row("S24+", "81% · 32 °C", WidgetModel.OK), WidgetModel.phone("S24+", true, 81, 31.8, false))
        assertEquals("12%⚡ · 30 °C", WidgetModel.phone("A", true, 12, 30.0, true).detail)
        assertEquals(WidgetModel.WARN, WidgetModel.phone("A", true, 12, 30.0, false).color) // low and not charging
        assertEquals(WidgetModel.WARN, WidgetModel.phone("A", true, 80, 46.0, true).color)  // hot
        assertEquals(WidgetModel.Row("Moto", "offline", WidgetModel.BAD), WidgetModel.phone("Moto", false, -1, null, false))
    }

    @Test fun crashingServicesComeFirst() {
        val rows = WidgetModel.services(listOf(Triple("Web hosting", "running", 0), Triple("Jellyfin", "backoff", 4),
            Triple("AI model", "starting", 0), Triple("SSH & SFTP", "running", 2)))
        assertEquals(listOf("Jellyfin", "AI model", "SSH & SFTP", "Web hosting"), rows.map { it.name })
        assertEquals("crashing", rows[0].detail)
        assertEquals("running · 2 restarts", rows[2].detail)
    }

    @Test fun processNamesBecomeModuleNames() {
        val app = AppDef("immich", "Immich", "", 2283, "", "", "", emptyList(), emptyList(), emptyMap(), null, null, null, null, null,
            emptyMap(), listOf(ServiceDef("server", null, null, emptyList(), emptyMap(), emptyList(), false)), emptyList())
        val single = AppDef("jellyfin", "Jellyfin", "", 8096, "", "", "", emptyList(), emptyList(), emptyMap(), null, null, null, null, null,
            emptyMap(), emptyList(), emptyList())
        val deploy = Deploy("blog", "My blog", "https://x", "main", 3000, "auto", "", "", emptyMap(), false)
        assertEquals("SSH & SFTP", WidgetModel.label("sshd", emptyList(), emptyList()))
        assertEquals("AI model", WidgetModel.label("ai-llama", emptyList(), emptyList()))
        assertEquals("Jellyfin", WidgetModel.label("jellyfin", listOf(single, app), emptyList()))
        assertEquals("Immich · server", WidgetModel.label("immich-server", listOf(single, app), emptyList()))
        assertEquals("My blog", WidgetModel.label("deploy-blog", emptyList(), listOf(deploy)))
        assertEquals("mystery", WidgetModel.label("mystery", emptyList(), emptyList()))
    }

    @Test fun statusPill() {
        val ok = WidgetModel.Row("Web", "running", WidgetModel.OK)
        val bad = WidgetModel.Row("Jellyfin", "crashing", WidgetModel.BAD)
        assertEquals(WidgetModel.Status("Online", WidgetModel.OK), WidgetModel.status(true, true, listOf(ok)))
        assertEquals(WidgetModel.Status("Online", WidgetModel.OK), WidgetModel.status(true, true, emptyList()))
        assertEquals(WidgetModel.Status("Needs attention", WidgetModel.BAD), WidgetModel.status(true, true, listOf(ok, bad)))
        assertEquals("Starting", WidgetModel.status(false, true, emptyList()).label)
        assertEquals(WidgetModel.Status("Stopped", WidgetModel.OFF), WidgetModel.status(false, false, listOf(bad)))
    }

    @Test fun summaryLine() {
        val rows = WidgetModel.services(listOf(Triple("A", "running", 0), Triple("B", "running", 3), Triple("C", "backoff", 1)))
        assertEquals("2 of 3 services running", WidgetModel.summary(true, rows))
        assertEquals("All 2 services running", WidgetModel.summary(true, rows.filter { it.detail.startsWith("running") }))
        assertEquals("All 1 service running", WidgetModel.summary(true, listOf(WidgetModel.Row("A", "running", WidgetModel.OK))))
        assertEquals("No services turned on", WidgetModel.summary(true, emptyList()))
        assertEquals("Server stopped", WidgetModel.summary(false, rows))
    }

    @Test fun metricFormatting() {
        assertEquals("84%", WidgetModel.percent(84))
        assertEquals("—", WidgetModel.percent(-1))
        assertEquals("13%", WidgetModel.percent(12.6))
        assertEquals("—", WidgetModel.percent(null as Double?))
        assertEquals("—", WidgetModel.percent(Double.NaN))
        assertEquals("31°", WidgetModel.degrees(31.4))
        assertEquals("—", WidgetModel.degrees(0.0))
        assertEquals("—", WidgetModel.degrees(null))
        assertEquals(75, WidgetModel.ramUsed(2048, 8192))
        assertEquals(-1, WidgetModel.ramUsed(0, 0))
        assertEquals("Charging", WidgetModel.batteryLabel(50, true))
        assertEquals("Full", WidgetModel.batteryLabel(100, true))
        assertEquals("Battery", WidgetModel.batteryLabel(100, false))
    }

    @Test fun clockLine() {
        val online = WidgetModel.Status("Online", WidgetModel.OK)
        assertEquals("Online · 84% charging", WidgetModel.clockLine(online, 84, true))
        assertEquals("Online · 84%", WidgetModel.clockLine(online, 84, false))
        assertEquals("Online", WidgetModel.clockLine(online, -1, true))
    }

    @Test fun layoutForSize() {
        assertEquals(WidgetModel.Size.SMALL, WidgetModel.size(130, 60))
        assertEquals(WidgetModel.Size.SMALL, WidgetModel.size(150, 200))
        assertEquals(WidgetModel.Size.COMPACT, WidgetModel.size(280, 120))
        assertEquals(WidgetModel.Size.MEDIUM, WidgetModel.size(280, 150))
        assertEquals(WidgetModel.Size.MEDIUM, WidgetModel.size(200, 400)) // too narrow for the large layout
        assertEquals(WidgetModel.Size.LARGE, WidgetModel.size(280, 260))
        assertEquals(1, WidgetModel.largeRows(200))
        assertEquals(3, WidgetModel.largeRows(300))
        assertEquals(false, WidgetModel.clockFull(160, 100))
        assertEquals(true, WidgetModel.clockFull(200, 120))
    }

    @Test fun noRoomMeansNoServiceRowsRatherThanAWrongCount() {
        val services = (1..5).map { WidgetModel.Row("S$it", "", WidgetModel.OK) }
        val (p, s) = WidgetModel.fit(listOf(WidgetModel.Row("P", "", WidgetModel.OK)), services, 1)
        assertEquals(1, p.size)
        assertEquals(emptyList<WidgetModel.Row>(), s)
        val (_, s2) = WidgetModel.fit(emptyList(), services, 2)
        assertEquals(listOf("S1", "+4 more"), s2.map { it.name })
    }

    @Test fun rowsFitTheWidgetWithAMoreLine() {
        val phones = (1..6).map { WidgetModel.Row("P$it", "", WidgetModel.OK) }
        val services = (1..10).map { WidgetModel.Row("S$it", "", WidgetModel.OK) }
        val (p, s) = WidgetModel.fit(phones, services, 8)
        assertEquals(4, p.size)
        assertEquals(listOf("S1", "S2", "S3", "+7 more"), s.map { it.name }) // 3 shown + 7 = all 10
        val (p2, s2) = WidgetModel.fit(phones.take(1), services.take(3), 8)
        assertEquals(1, p2.size)
        assertEquals(3, s2.size)
    }
}
