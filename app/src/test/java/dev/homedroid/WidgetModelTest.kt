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
