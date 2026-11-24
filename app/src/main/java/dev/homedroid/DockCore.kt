package dev.homedroid

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dock mode's logic, kept apart from Android so it can be unit-tested: time formatting, the
 * night-dim schedule, the burn-in pixel-shift path, colour palettes and the vital tiles.
 */
object DockCore {
    // ---- Time ------------------------------------------------------------------------------

    /** The pieces a clock face draws. [suffix] is "AM"/"PM" in 12-hour mode, else empty. */
    data class TimeText(val hours: String, val minutes: String, val seconds: String, val suffix: String)

    fun time(hour24: Int, minute: Int, second: Int, use24h: Boolean): TimeText {
        val h = if (use24h) "%02d".format(Locale.ROOT, hour24) else (if (hour24 % 12 == 0) 12 else hour24 % 12).toString()
        return TimeText(h, "%02d".format(Locale.ROOT, minute), "%02d".format(Locale.ROOT, second),
            if (use24h) "" else if (hour24 < 12) "AM" else "PM")
    }

    /** What TalkBack reads for the clock, e.g. "7:05 PM" or "19:05". */
    fun spoken(hour24: Int, minute: Int, use24h: Boolean): String {
        val t = time(hour24, minute, 0, use24h)
        return if (use24h) "${hour24}:${t.minutes}" else "${t.hours}:${t.minutes} ${t.suffix}"
    }

    // ---- Night dimming ---------------------------------------------------------------------

    const val NIGHT_OFF = "off"
    const val NIGHT_AUTO = "auto"
    const val NIGHT_ALWAYS = "always"

    /**
     * Whether [minuteOfDay] (0..1439) falls in the dim window [start, end). The window may cross
     * midnight (22:00 → 07:00); start == end means no window.
     */
    fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    fun nightActive(mode: String, minuteOfDay: Int, start: Int, end: Int): Boolean = when (mode) {
        NIGHT_ALWAYS -> true
        NIGHT_AUTO -> inWindow(minuteOfDay, start, end)
        else -> false
    }

    /** "22:00" / "10:00 PM" for a minute of the day. */
    fun clockLabel(minuteOfDay: Int, use24h: Boolean): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        val t = time(m / 60, m % 60, 0, use24h)
        return if (use24h) "${t.hours}:${t.minutes}" else "${t.hours}:${t.minutes} ${t.suffix}"
    }

    // ---- Burn-in protection ----------------------------------------------------------------

    /** Grid positions per axis the content walks through. */
    const val SHIFT_STEPS = 5
    /** One full there-and-back walk, in minutes. */
    const val SHIFT_PERIOD = 2 * (SHIFT_STEPS * SHIFT_STEPS - 1)

    /**
     * The content offset for minute [step]. It walks a 5×5 grid in a serpentine and then back
     * again, so consecutive minutes move by one grid cell only (never a visible jump) and every
     * pixel spends time at every position. Offsets are within ±[amplitude].
     */
    fun burnInOffset(step: Long, amplitude: Int): Pair<Int, Int> {
        val cells = SHIFT_STEPS * SHIFT_STEPS
        val p = Math.floorMod(step, SHIFT_PERIOD.toLong()).toInt()
        val i = if (p < cells) p else SHIFT_PERIOD - p
        val row = i / SHIFT_STEPS
        val col = if (row % 2 == 0) i % SHIFT_STEPS else SHIFT_STEPS - 1 - i % SHIFT_STEPS
        val half = (SHIFT_STEPS - 1) / 2f
        return ((col - half) / half * amplitude).roundToInt() to ((row - half) / half * amplitude).roundToInt()
    }

    // ---- OLED care -------------------------------------------------------------------------

    /** Minutes between layout swaps (clock and tiles trade sides, tiles rotate). */
    const val SWAP_MINUTES = 20

    /** Which arrangement is showing at [epochMinute]: 0, 1, 2, … changing every [period] minutes. */
    fun swapSlot(epochMinute: Long, period: Int = SWAP_MINUTES): Long = Math.floorDiv(epochMinute, period.toLong())

    /** Odd slots mirror the layout: the clock moves right (landscape) or down (portrait). */
    fun mirrored(slot: Long): Boolean = Math.floorMod(slot, 2L) == 1L

    /** Tiles rotate one place per slot, so no tile sits in the same spot for long. */
    fun <T> rotate(items: List<T>, slot: Long): List<T> {
        if (items.size < 2) return items
        val k = Math.floorMod(slot, items.size.toLong()).toInt()
        return items.drop(k) + items.take(k)
    }

    const val SCREEN_OFF_NONE = "off"
    const val SCREEN_OFF_BLANK = "blank"
    const val SCREEN_OFF_SLEEP = "sleep"

    /** After a tap during "screen off" hours or auto-dim, the dock stays fully awake this long. */
    const val WAKE_MINUTES = 1

    /** OLED-care and night settings, as the display schedule needs them. */
    data class Care(
        val enabled: Boolean = true,
        val dimText: Boolean = false,
        /** Dim after this many minutes without a touch; 0 = never. */
        val autoDimMinutes: Int = 10,
        val night: String = NIGHT_AUTO,
        val nightStart: Int = 22 * 60,
        val nightEnd: Int = 7 * 60,
        val screenOff: String = SCREEN_OFF_NONE,
        val offStart: Int = 1 * 60,
        val offEnd: Int = 6 * 60,
    )

    /**
     * What the screen should do now. [level] scales the clock and tile colours (1 = full);
     * [lowLight] also turns the backlight down; [blank] paints everything black; [mayTimeout]
     * lets the system turn the screen off.
     */
    data class Display(val night: Boolean, val level: Float, val lowLight: Boolean, val blank: Boolean, val mayTimeout: Boolean)

    const val DIM_TEXT_LEVEL = 0.7f
    const val AUTO_DIM_LEVEL = 0.5f

    /** The display state at [minuteOfDay], [idleMinutes] after the last touch. */
    fun display(c: Care, minuteOfDay: Int, idleMinutes: Long): Display {
        val night = nightActive(c.night, minuteOfDay, c.nightStart, c.nightEnd)
        val awake = idleMinutes < WAKE_MINUTES
        val offHours = c.screenOff != SCREEN_OFF_NONE && inWindow(minuteOfDay, c.offStart, c.offEnd)
        val blank = offHours && c.screenOff == SCREEN_OFF_BLANK && !awake
        val mayTimeout = offHours && c.screenOff == SCREEN_OFF_SLEEP
        var level = 1f
        var lowLight = night
        if (c.enabled && c.dimText) level *= DIM_TEXT_LEVEL
        if (c.enabled && c.autoDimMinutes > 0 && idleMinutes >= c.autoDimMinutes) { level *= AUTO_DIM_LEVEL; lowLight = true }
        return Display(night, level, lowLight || blank, blank, mayTimeout)
    }

    /** Labels and tile outlines fade after this long without a touch (OLED care). */
    const val CHROME_FADE_SECONDS = 8

    // ---- Colour ----------------------------------------------------------------------------

    /** A curated accent pair: [primary] for hero elements, [secondary] for supporting ones. */
    data class Palette(val id: String, val name: String, val primary: Int, val secondary: Int)

    val PALETTES = listOf(
        Palette("mint", "Mint", 0xFF78E0C5.toInt(), 0xFF3E9C8A.toInt()),
        Palette("ember", "Ember", 0xFFFF9466.toInt(), 0xFFC4553A.toInt()),
        Palette("iris", "Iris", 0xFFB3A6FF.toInt(), 0xFF7466D6.toInt()),
        Palette("tide", "Tide", 0xFF7FCBFF.toInt(), 0xFF3F86C4.toInt()),
        Palette("citrus", "Citrus", 0xFFE3EE73.toInt(), 0xFF9AA83A.toInt()),
        Palette("bloom", "Bloom", 0xFFFF9EBE.toInt(), 0xFFC95D86.toInt()),
        Palette("paper", "Paper", 0xFFECE6DA.toInt(), 0xFF9C958A.toInt()),
    )
    const val DYNAMIC = "dynamic"

    fun palette(id: String): Palette = PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]

    /** Low-light colours: deep reds keep night vision and barely light the room. */
    const val NIGHT_INK = 0xFFB3261E.toInt()
    const val NIGHT_MUTED = 0xFF5C1610.toInt()
    const val NIGHT_ACCENT = 0xFFD0372A.toInt()
    const val NIGHT_SECONDARY = 0xFF7A1D15.toInt()

    /** Mixes [a] into [b] by [t] (0 = b, 1 = a), keeping full opacity. */
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * t + (b shr shift and 0xFF) * (1 - t)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG relative luminance, for checking text contrast. */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c shr 16 and 0xFF) + 0.7152 * lin(c shr 8 and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la > lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---- Tiles -----------------------------------------------------------------------------

    enum class Tile(val id: String, val title: String) {
        SERVER("server", "Server"), BATTERY("battery", "Battery"), CPU("cpu", "CPU"), RAM("ram", "Memory"),
        STORAGE("storage", "Storage"), NETWORK("network", "Network"), UPTIME("uptime", "Uptime"),
        ADDRESS("address", "Dashboard"), TAILSCALE("tailscale", "Tailscale"), ALARM("alarm", "Next alarm"),
        DATE("date", "Date");

        companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
    }

    const val MAX_TILES = 6
    val DEFAULT_TILES = listOf(Tile.SERVER, Tile.BATTERY, Tile.CPU, Tile.RAM, Tile.NETWORK, Tile.ALARM)

    /** Reads a saved "server,battery,…" order: unknown and repeated ids are dropped, at most [MAX_TILES]. */
    fun parseTiles(saved: String?): List<Tile> {
        if (saved == null) return DEFAULT_TILES
        return saved.split(',').mapNotNull { Tile.of(it.trim()) }.distinct().take(MAX_TILES)
    }

    fun saveTiles(tiles: List<Tile>): String = tiles.distinct().take(MAX_TILES).joinToString(",") { it.id }

    /** Moves [tile] one place up ([delta] = -1) or down (+1); out-of-range moves change nothing. */
    fun move(tiles: List<Tile>, tile: Tile, delta: Int): List<Tile> {
        val i = tiles.indexOf(tile)
        val j = i + delta
        if (i < 0 || j !in tiles.indices) return tiles
        return tiles.toMutableList().apply { this[i] = this[j]; this[j] = tile }
    }

    /** Turns [tile] on (appended, if there's room) or off. */
    fun toggle(tiles: List<Tile>, tile: Tile, on: Boolean): List<Tile> = when {
        on && tile !in tiles && tiles.size < MAX_TILES -> tiles + tile
        !on -> tiles - tile
        else -> tiles
    }

    enum class Tone { OK, WARN, BAD, NEUTRAL }

    /** One tile as drawn: small caps [label], big [value], a [detail] line and an optional 0..1 bar. */
    data class TileText(val label: String, val value: String, val detail: String, val tone: Tone, val progress: Float = -1f)

    /** Everything the tiles show, sampled off the main thread. Unknown values are -1 / null. */
    data class Snapshot(
        val serverActive: Boolean = false,
        val serverRunning: Boolean = false,
        val servicesRunning: Int = 0,
        val servicesTotal: Int = 0,
        val attention: Int = 0,
        val battery: Int = -1,
        val charging: Boolean = false,
        val batteryTempC: Float = Float.NaN,
        val cpuPercent: Double = -1.0,
        val cores: Int = 0,
        val cpuTempC: Double? = null,
        val ramFreeMb: Long = -1,
        val ramTotalMb: Long = -1,
        val storageFreeMb: Long = -1,
        val storageTotalMb: Long = -1,
        val rxBps: Long = -1,
        val txBps: Long = -1,
        val uptimeS: Long = -1,
        val ip: String? = null,
        val port: Int = 8800,
        val tailscaleEnabled: Boolean = false,
        val tailscaleState: String? = null,
        val tailscalePeersOnline: Int = 0,
        val alarmLabel: String? = null,
        val alarmDetail: String? = null,
        val dateValue: String = "",
        val dateDetail: String = "",
    )

    fun tile(t: Tile, s: Snapshot): TileText = when (t) {
        Tile.SERVER -> when {
            s.serverRunning && s.attention > 0 -> TileText("SERVER", "Online", "${s.attention} need${if (s.attention == 1) "s" else ""} attention", Tone.WARN)
            s.serverRunning -> TileText("SERVER", "Online",
                if (s.servicesTotal == 0) "No services on" else "${s.servicesRunning} of ${s.servicesTotal} running", Tone.OK)
            s.serverActive -> TileText("SERVER", "Starting", "Bringing services up", Tone.WARN)
            else -> TileText("SERVER", "Stopped", "Open the app to start", Tone.NEUTRAL)
        }
        Tile.BATTERY -> if (s.battery < 0) TileText("BATTERY", "—", "Unavailable", Tone.NEUTRAL) else {
            val temp = if (s.batteryTempC.isNaN() || s.batteryTempC <= 0f) "" else " · ${s.batteryTempC.roundToInt()} °C"
            val state = when { s.charging && s.battery >= 100 -> "Full"; s.charging -> "Charging"; else -> "On battery" }
            val tone = when {
                !s.batteryTempC.isNaN() && s.batteryTempC >= 45f -> Tone.BAD
                s.battery <= 15 && !s.charging -> Tone.WARN
                else -> Tone.OK
            }
            TileText("BATTERY", "${s.battery}%", state + temp, tone, s.battery / 100f)
        }
        Tile.CPU -> if (s.cpuPercent < 0) TileText("CPU", "—", "Measuring…", Tone.NEUTRAL) else {
            val temp = s.cpuTempC?.let { " · ${it.roundToInt()} °C" }.orEmpty()
            TileText("CPU", "${s.cpuPercent.roundToInt()}%", "${s.cores} cores$temp",
                if (s.cpuPercent >= 85 || (s.cpuTempC ?: 0.0) >= 70) Tone.WARN else Tone.OK, (s.cpuPercent / 100).toFloat())
        }
        Tile.RAM -> if (s.ramTotalMb <= 0) TileText("MEMORY", "—", "Unavailable", Tone.NEUTRAL) else {
            val used = (s.ramTotalMb - s.ramFreeMb).coerceAtLeast(0)
            val pct = used * 100 / s.ramTotalMb
            TileText("MEMORY", "$pct%", "${size(used)} of ${size(s.ramTotalMb)}", if (pct >= 90) Tone.WARN else Tone.OK, pct / 100f)
        }
        Tile.STORAGE -> if (s.storageTotalMb <= 0) TileText("STORAGE", "—", "Unavailable", Tone.NEUTRAL) else {
            val used = 1f - s.storageFreeMb.toFloat() / s.storageTotalMb
            TileText("STORAGE", size(s.storageFreeMb), "free of ${size(s.storageTotalMb)}",
                if (s.storageFreeMb < 2048) Tone.WARN else Tone.OK, used)
        }
        Tile.NETWORK -> if (s.rxBps < 0) TileText("NETWORK", "—", "Measuring…", Tone.NEUTRAL)
            else TileText("NETWORK", "↓ ${rate(s.rxBps)}", "↑ ${rate(s.txBps)}", Tone.NEUTRAL)
        Tile.UPTIME -> if (s.uptimeS < 0) TileText("UPTIME", "—", "", Tone.NEUTRAL) else TileText("UPTIME", uptime(s.uptimeS), "since last boot", Tone.NEUTRAL)
        Tile.ADDRESS -> if (s.ip == null) TileText("DASHBOARD", "Offline", "No network connection", Tone.WARN)
            else TileText("DASHBOARD", s.ip, "port ${s.port}", if (s.serverRunning) Tone.OK else Tone.NEUTRAL)
        Tile.TAILSCALE -> when {
            !s.tailscaleEnabled -> TileText("TAILSCALE", "Off", "Not set up", Tone.NEUTRAL)
            s.tailscaleState == "Running" -> TileText("TAILSCALE", "Connected",
                "${s.tailscalePeersOnline} peer${if (s.tailscalePeersOnline == 1) "" else "s"} online", Tone.OK)
            s.tailscaleState == "NeedsLogin" || s.tailscaleState == "NeedsMachineAuth" -> TileText("TAILSCALE", "Sign in", "Approve this phone", Tone.WARN)
            s.tailscaleState == null -> TileText("TAILSCALE", "—", "Checking…", Tone.NEUTRAL)
            else -> TileText("TAILSCALE", s.tailscaleState.ifEmpty { "Stopped" }, if (s.serverRunning) "Starting" else "Server stopped", Tone.NEUTRAL)
        }
        Tile.ALARM -> if (s.alarmLabel == null) TileText("NEXT ALARM", "None", "No alarm set", Tone.NEUTRAL)
            else TileText("NEXT ALARM", s.alarmLabel, s.alarmDetail.orEmpty(), Tone.NEUTRAL)
        Tile.DATE -> TileText("TODAY", s.dateValue, s.dateDetail, Tone.NEUTRAL)
    }

    /** "512 MB", "3.4 GB", "1.2 TB". */
    fun size(mb: Long): String = when {
        mb >= 1024L * 1024 -> "%.1f TB".format(Locale.ROOT, mb / 1048576.0)
        mb >= 10 * 1024 -> "${(mb / 1024.0).roundToInt()} GB"
        mb >= 1024 -> "%.1f GB".format(Locale.ROOT, mb / 1024.0)
        else -> "$mb MB"
    }

    /** "0 B/s", "820 B/s", "14 KB/s", "1.2 MB/s". */
    fun rate(bps: Long): String = when {
        bps >= 10L shl 20 -> "${bps shr 20} MB/s"
        bps >= 1L shl 20 -> "%.1f MB/s".format(Locale.ROOT, bps / 1048576.0)
        bps >= 1024 -> "${bps shr 10} KB/s"
        else -> "${bps.coerceAtLeast(0)} B/s"
    }

    /** "42m", "5h 12m", "3d 4h". */
    fun uptime(s: Long): String {
        val d = s / 86400; val h = s % 86400 / 3600; val m = s % 3600 / 60
        return when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
    }

    /** Server line for faces that summarise it (Mono, Ring). */
    fun serverLine(s: Snapshot): String = tile(Tile.SERVER, s).let { "${it.value.lowercase()} · ${it.detail.lowercase()}" }

    /** Tile grid shape: columns × rows for [n] tiles in a [landscape] or portrait area. */
    fun grid(n: Int, landscape: Boolean): Pair<Int, Int> {
        if (n <= 0) return 0 to 0
        val cols = if (landscape) (if (n <= 3) 1 else 2) else (if (n <= 2) n else if (n <= 4) 2 else 3)
        return cols to (n + cols - 1) / cols
    }
}
