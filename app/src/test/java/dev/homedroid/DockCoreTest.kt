package dev.homedroid

import dev.homedroid.DockCore.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DockCoreTest {
    @Test fun timeIn12And24Hours() {
        assertEquals(DockCore.TimeText("07", "05", "09", ""), DockCore.time(7, 5, 9, true))
        assertEquals(DockCore.TimeText("7", "05", "09", "AM"), DockCore.time(7, 5, 9, false))
        assertEquals("12", DockCore.time(0, 0, 0, false).hours)
        assertEquals("AM", DockCore.time(0, 0, 0, false).suffix)
        assertEquals("12", DockCore.time(12, 30, 0, false).hours)
        assertEquals("PM", DockCore.time(12, 30, 0, false).suffix)
        assertEquals("11", DockCore.time(23, 59, 0, false).hours)
        assertEquals("23", DockCore.time(23, 59, 0, true).hours)
    }

    @Test fun spokenTime() {
        assertEquals("7:05 PM", DockCore.spoken(19, 5, false))
        assertEquals("19:05", DockCore.spoken(19, 5, true))
        assertEquals("0:00", DockCore.spoken(0, 0, true))
        assertEquals("10:00 PM", DockCore.clockLabel(22 * 60, false))
        assertEquals("07:30", DockCore.clockLabel(7 * 60 + 30, true))
    }

    @Test fun nightWindowWithinADay() {
        assertTrue(DockCore.inWindow(13 * 60, 12 * 60, 14 * 60))
        assertTrue(DockCore.inWindow(12 * 60, 12 * 60, 14 * 60))
        assertFalse(DockCore.inWindow(14 * 60, 12 * 60, 14 * 60)) // end is exclusive
        assertFalse(DockCore.inWindow(11 * 60, 12 * 60, 14 * 60))
    }

    @Test fun nightWindowAcrossMidnight() {
        val start = 22 * 60; val end = 7 * 60
        assertTrue(DockCore.inWindow(23 * 60, start, end))
        assertTrue(DockCore.inWindow(0, start, end))
        assertTrue(DockCore.inWindow(6 * 60 + 59, start, end))
        assertFalse(DockCore.inWindow(7 * 60, start, end))
        assertFalse(DockCore.inWindow(12 * 60, start, end))
        assertTrue(DockCore.inWindow(22 * 60, start, end))
        assertFalse(DockCore.inWindow(21 * 60 + 59, start, end))
        assertFalse(DockCore.inWindow(5, 600, 600)) // empty window
    }

    @Test fun nightModes() {
        assertTrue(DockCore.nightActive(DockCore.NIGHT_ALWAYS, 12 * 60, 22 * 60, 7 * 60))
        assertFalse(DockCore.nightActive(DockCore.NIGHT_OFF, 23 * 60, 22 * 60, 7 * 60))
        assertTrue(DockCore.nightActive(DockCore.NIGHT_AUTO, 23 * 60, 22 * 60, 7 * 60))
        assertFalse(DockCore.nightActive(DockCore.NIGHT_AUTO, 12 * 60, 22 * 60, 7 * 60))
    }

    @Test fun burnInPathStaysSmallAndMovesOneCellAtATime() {
        val amp = 10
        val seen = HashSet<Pair<Int, Int>>()
        for (m in 0L until DockCore.SHIFT_PERIOD * 2L) {
            val (x, y) = DockCore.burnInOffset(m, amp)
            assertTrue(abs(x) <= amp && abs(y) <= amp)
            val (nx, ny) = DockCore.burnInOffset(m + 1, amp)
            // Neighbouring minutes differ by at most one grid step on one axis.
            assertTrue("minute $m", abs(nx - x) + abs(ny - y) <= amp / 2 + 1)
            seen += x to y
        }
        assertEquals(25, seen.size) // every grid position is visited
        assertEquals(DockCore.burnInOffset(3, amp), DockCore.burnInOffset(3L + DockCore.SHIFT_PERIOD, amp))
        assertEquals(DockCore.burnInOffset(-1, amp), DockCore.burnInOffset(DockCore.SHIFT_PERIOD - 1L, amp))
        assertEquals(0 to 0, DockCore.burnInOffset(5, 0))
    }

    @Test fun layoutSwapSchedule() {
        val p = DockCore.SWAP_MINUTES.toLong()
        assertEquals(0L, DockCore.swapSlot(0))
        assertEquals(0L, DockCore.swapSlot(p - 1))
        assertEquals(1L, DockCore.swapSlot(p))
        assertEquals(-1L, DockCore.swapSlot(-1))
        assertFalse(DockCore.mirrored(0)); assertTrue(DockCore.mirrored(1)); assertFalse(DockCore.mirrored(2)); assertTrue(DockCore.mirrored(-1))
        assertTrue(DockCore.SWAP_MINUTES in 15..30)
        val t = listOf("a", "b", "c")
        assertEquals(t, DockCore.rotate(t, 0))
        assertEquals(listOf("b", "c", "a"), DockCore.rotate(t, 1))
        assertEquals(listOf("c", "a", "b"), DockCore.rotate(t, 5))
        assertEquals(listOf("c", "a", "b"), DockCore.rotate(t, -1))
        assertEquals(listOf("x"), DockCore.rotate(listOf("x"), 3))
        // Over a full cycle every tile visits every position.
        for (i in t.indices) assertEquals(t.toSet(), (0L until t.size).map { DockCore.rotate(t, it)[i] }.toSet())
    }

    @Test fun displayScheduleNormalAndNight() {
        val care = DockCore.Care(autoDimMinutes = 0)
        assertEquals(DockCore.Display(false, 1f, false, false, false), DockCore.display(care, 12 * 60, 0))
        val night = DockCore.display(care, 23 * 60, 0)
        assertTrue(night.night); assertTrue(night.lowLight); assertEquals(1f, night.level)
        assertTrue(DockCore.display(care, 3 * 60, 0).night) // across midnight
        assertFalse(DockCore.display(care.copy(night = DockCore.NIGHT_OFF), 23 * 60, 0).night)
    }

    @Test fun dimTextAndAutoDim() {
        val care = DockCore.Care(dimText = true, autoDimMinutes = 10, night = DockCore.NIGHT_OFF)
        assertEquals(DockCore.DIM_TEXT_LEVEL, DockCore.display(care, 12 * 60, 0).level, 0.001f)
        assertFalse(DockCore.display(care, 12 * 60, 9).lowLight)
        val dimmed = DockCore.display(care, 12 * 60, 10)
        assertEquals(DockCore.DIM_TEXT_LEVEL * DockCore.AUTO_DIM_LEVEL, dimmed.level, 0.001f)
        assertTrue(dimmed.lowLight)
        // OLED care off: neither applies.
        assertEquals(1f, DockCore.display(care.copy(enabled = false), 12 * 60, 60).level)
        assertFalse(DockCore.display(care.copy(autoDimMinutes = 0), 12 * 60, 600).lowLight)
    }

    @Test fun screenOffHours() {
        val blank = DockCore.Care(autoDimMinutes = 0, night = DockCore.NIGHT_OFF, screenOff = DockCore.SCREEN_OFF_BLANK, offStart = 60, offEnd = 6 * 60)
        assertTrue(DockCore.display(blank, 2 * 60, 5).blank)
        assertTrue(DockCore.display(blank, 2 * 60, 5).lowLight)
        assertFalse(DockCore.display(blank, 2 * 60, 0).blank) // just tapped: awake for a minute
        assertFalse(DockCore.display(blank, 7 * 60, 5).blank)
        assertFalse(DockCore.display(blank, 2 * 60, 5).mayTimeout)
        val sleep = blank.copy(screenOff = DockCore.SCREEN_OFF_SLEEP)
        assertTrue(DockCore.display(sleep, 2 * 60, 5).mayTimeout)
        assertFalse(DockCore.display(sleep, 2 * 60, 5).blank)
        assertFalse(DockCore.display(sleep, 12 * 60, 5).mayTimeout)
        val crossing = blank.copy(offStart = 23 * 60, offEnd = 5 * 60)
        assertTrue(DockCore.display(crossing, 0, 5).blank)
        assertTrue(DockCore.display(crossing, 23 * 60 + 30, 5).blank)
        assertFalse(DockCore.display(crossing, 22 * 60, 5).blank)
    }

    @Test fun tileOrderParsing() {
        assertEquals(DockCore.DEFAULT_TILES, DockCore.parseTiles(null))
        assertEquals(listOf(Tile.CPU, Tile.SERVER), DockCore.parseTiles("cpu, server,bogus,cpu"))
        assertEquals(emptyList<Tile>(), DockCore.parseTiles(""))
        assertEquals(DockCore.MAX_TILES, DockCore.parseTiles(Tile.entries.joinToString(",") { it.id }).size)
        assertEquals("cpu,server", DockCore.saveTiles(listOf(Tile.CPU, Tile.SERVER, Tile.CPU)))
        val saved = DockCore.saveTiles(DockCore.DEFAULT_TILES)
        assertEquals(DockCore.DEFAULT_TILES, DockCore.parseTiles(saved))
    }

    @Test fun movingAndTogglingTiles() {
        val t = listOf(Tile.SERVER, Tile.BATTERY, Tile.CPU)
        assertEquals(listOf(Tile.BATTERY, Tile.SERVER, Tile.CPU), DockCore.move(t, Tile.BATTERY, -1))
        assertEquals(listOf(Tile.SERVER, Tile.CPU, Tile.BATTERY), DockCore.move(t, Tile.BATTERY, 1))
        assertEquals(t, DockCore.move(t, Tile.SERVER, -1))
        assertEquals(t, DockCore.move(t, Tile.CPU, 1))
        assertEquals(t, DockCore.move(t, Tile.DATE, 1))
        assertEquals(t + Tile.DATE, DockCore.toggle(t, Tile.DATE, true))
        assertEquals(listOf(Tile.SERVER, Tile.CPU), DockCore.toggle(t, Tile.BATTERY, false))
        val full = Tile.entries.take(DockCore.MAX_TILES)
        assertEquals(full, DockCore.toggle(full, Tile.entries.last(), true)) // no room
    }

    @Test fun serverTile() {
        val s = DockCore.Snapshot(serverActive = true, serverRunning = true, servicesRunning = 4, servicesTotal = 5)
        assertEquals(DockCore.TileText("SERVER", "Online", "4 of 5 running", DockCore.Tone.OK), DockCore.tile(Tile.SERVER, s))
        assertEquals("2 need attention", DockCore.tile(Tile.SERVER, s.copy(attention = 2)).detail)
        assertEquals("1 needs attention", DockCore.tile(Tile.SERVER, s.copy(attention = 1)).detail)
        assertEquals(DockCore.Tone.WARN, DockCore.tile(Tile.SERVER, s.copy(attention = 1)).tone)
        assertEquals("Starting", DockCore.tile(Tile.SERVER, s.copy(serverRunning = false)).value)
        assertEquals("Stopped", DockCore.tile(Tile.SERVER, DockCore.Snapshot()).value)
        assertEquals("online · 4 of 5 running", DockCore.serverLine(s))
    }

    @Test fun batteryTile() {
        val s = DockCore.Snapshot(battery = 84, charging = true, batteryTempC = 31.4f)
        val t = DockCore.tile(Tile.BATTERY, s)
        assertEquals("84%", t.value)
        assertEquals("Charging · 31 °C", t.detail)
        assertEquals(0.84f, t.progress, 0.001f)
        assertEquals(DockCore.Tone.OK, t.tone)
        assertEquals("Full · 31 °C", DockCore.tile(Tile.BATTERY, s.copy(battery = 100)).detail)
        assertEquals(DockCore.Tone.WARN, DockCore.tile(Tile.BATTERY, s.copy(battery = 10, charging = false)).tone)
        assertEquals(DockCore.Tone.BAD, DockCore.tile(Tile.BATTERY, s.copy(batteryTempC = 47f)).tone)
        assertEquals("—", DockCore.tile(Tile.BATTERY, DockCore.Snapshot()).value)
    }

    @Test fun otherTiles() {
        val s = DockCore.Snapshot(cpuPercent = 12.6, cores = 8, cpuTempC = 48.2, ramFreeMb = 2048, ramTotalMb = 8192,
            storageFreeMb = 41 * 1024, storageTotalMb = 128 * 1024, rxBps = 1_258_291, txBps = 24 * 1024, uptimeS = 3 * 86400 + 4 * 3600,
            ip = "192.168.1.20", port = 8800, serverRunning = true, tailscaleEnabled = true, tailscaleState = "Running", tailscalePeersOnline = 1,
            alarmLabel = "7:00 AM", alarmDetail = "Tomorrow · in 8h 2m", dateValue = "Tue 24", dateDetail = "November 2026")
        val cpu = DockCore.tile(Tile.CPU, s)
        assertEquals("13%", cpu.value)
        assertEquals("8 cores · 48 °C", cpu.detail)
        assertEquals(0.126f, cpu.progress, 0.0001f)
        assertEquals(DockCore.Tone.WARN, DockCore.tile(Tile.CPU, s.copy(cpuPercent = 92.0)).tone)
        assertEquals("75%", DockCore.tile(Tile.RAM, s).value)
        assertEquals("6.0 GB of 8.0 GB", DockCore.tile(Tile.RAM, s).detail)
        assertEquals("41 GB", DockCore.tile(Tile.STORAGE, s).value)
        assertEquals("free of 128 GB", DockCore.tile(Tile.STORAGE, s).detail)
        assertEquals("↓ 1.2 MB/s", DockCore.tile(Tile.NETWORK, s).value)
        assertEquals("↑ 24 KB/s", DockCore.tile(Tile.NETWORK, s).detail)
        assertEquals("3d 4h", DockCore.tile(Tile.UPTIME, s).value)
        assertEquals("192.168.1.20", DockCore.tile(Tile.ADDRESS, s).value)
        assertEquals("port 8800", DockCore.tile(Tile.ADDRESS, s).detail)
        assertEquals("Offline", DockCore.tile(Tile.ADDRESS, s.copy(ip = null)).value)
        assertEquals("1 peer online", DockCore.tile(Tile.TAILSCALE, s).detail)
        assertEquals("Sign in", DockCore.tile(Tile.TAILSCALE, s.copy(tailscaleState = "NeedsLogin")).value)
        assertEquals("Off", DockCore.tile(Tile.TAILSCALE, s.copy(tailscaleEnabled = false)).value)
        assertEquals("7:00 AM", DockCore.tile(Tile.ALARM, s).value)
        assertEquals("None", DockCore.tile(Tile.ALARM, s.copy(alarmLabel = null)).value)
        assertEquals("Tue 24", DockCore.tile(Tile.DATE, s).value)
        assertEquals("—", DockCore.tile(Tile.CPU, DockCore.Snapshot()).value)
    }

    @Test fun unitFormatting() {
        assertEquals("512 MB", DockCore.size(512))
        assertEquals("1.5 GB", DockCore.size(1536))
        assertEquals("120 GB", DockCore.size(120 * 1024))
        assertEquals("2.0 TB", DockCore.size(2L * 1024 * 1024))
        assertEquals("0 B/s", DockCore.rate(0))
        assertEquals("820 B/s", DockCore.rate(820))
        assertEquals("14 KB/s", DockCore.rate(14 * 1024))
        assertEquals("12 MB/s", DockCore.rate(12L shl 20))
        assertEquals("42m", DockCore.uptime(42 * 60))
        assertEquals("5h 12m", DockCore.uptime(5 * 3600 + 12 * 60))
    }

    @Test fun gridShapes() {
        assertEquals(0 to 0, DockCore.grid(0, true))
        assertEquals(1 to 3, DockCore.grid(3, true))
        assertEquals(2 to 3, DockCore.grid(6, true))
        assertEquals(2 to 1, DockCore.grid(2, false))
        assertEquals(2 to 2, DockCore.grid(4, false))
        assertEquals(3 to 2, DockCore.grid(5, false))
    }

    @Test fun palettesAreReadableOnBlack() {
        for (p in DockCore.PALETTES) assertTrue(p.name, DockCore.contrast(p.primary, 0xFF000000.toInt()) >= 7.0)
        // Night red stays legible but dim.
        val c = DockCore.contrast(DockCore.NIGHT_INK, 0xFF000000.toInt())
        assertTrue(c in 3.0..5.0)
        assertEquals("mint", DockCore.palette("nope").id)
        assertEquals(0xFF808080.toInt(), DockCore.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f))
        assertEquals(0xFF000000.toInt(), DockCore.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0f))
    }
}
