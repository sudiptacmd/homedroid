package dev.homedroid

import android.content.Context
import android.text.format.DateFormat

/** Dock mode settings, in their own SharedPreferences file ("dock"). */
class DockPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val system24h = DateFormat.is24HourFormat(ctx)

    /** One of [DockFaces.IDS]. */
    var face: String
        get() = prefs.getString("face", DockFaces.BOLD)!!
        set(v) = prefs.edit().putString("face", v).apply()

    /** A [DockCore.PALETTES] id, or [DockCore.DYNAMIC] for the system accent (Android 12+). */
    var palette: String
        get() = prefs.getString("palette", "mint")!!
        set(v) = prefs.edit().putString("palette", v).apply()

    /** Follows the phone's 12/24-hour setting until changed here. */
    var use24h: Boolean
        get() = prefs.getBoolean("use24h", system24h)
        set(v) = prefs.edit().putBoolean("use24h", v).apply()

    var showSeconds: Boolean
        get() = prefs.getBoolean("seconds", false)
        set(v) = prefs.edit().putBoolean("seconds", v).apply()

    var tiles: List<DockCore.Tile>
        get() = DockCore.parseTiles(prefs.getString("tiles", null))
        set(v) = prefs.edit().putString("tiles", DockCore.saveTiles(v)).apply()

    /** [DockCore.NIGHT_OFF], [DockCore.NIGHT_AUTO] or [DockCore.NIGHT_ALWAYS]. */
    var night: String
        get() = prefs.getString("night", DockCore.NIGHT_AUTO)!!
        set(v) = prefs.edit().putString("night", v).apply()

    /** Minutes after midnight. */
    var nightStart: Int
        get() = prefs.getInt("night_start", 22 * 60)
        set(v) = prefs.edit().putInt("night_start", v).apply()

    var nightEnd: Int
        get() = prefs.getInt("night_end", 7 * 60)
        set(v) = prefs.edit().putInt("night_end", v).apply()

    // ---- OLED care: on by default, since OLED can't be detected reliably and it costs LCDs nothing.

    var oledCare: Boolean
        get() = prefs.getBoolean("oled_care", true)
        set(v) = prefs.edit().putBoolean("oled_care", v).apply()

    /** Pixel shift: everything drifts a few dp each minute. */
    var burnIn: Boolean
        get() = prefs.getBoolean("burn_in", true)
        set(v) = prefs.edit().putBoolean("burn_in", v).apply()

    /** Clock and tiles trade places every [DockCore.SWAP_MINUTES] minutes. */
    var layoutSwap: Boolean
        get() = prefs.getBoolean("layout_swap", true)
        set(v) = prefs.edit().putBoolean("layout_swap", v).apply()

    var dimText: Boolean
        get() = prefs.getBoolean("dim_text", false)
        set(v) = prefs.edit().putBoolean("dim_text", v).apply()

    /** Labels and tile outlines fade out when nobody has touched the screen for a while. */
    var fadeChrome: Boolean
        get() = prefs.getBoolean("fade_chrome", true)
        set(v) = prefs.edit().putBoolean("fade_chrome", v).apply()

    /** Minutes without a touch before dimming; 0 = never. */
    var autoDim: Int
        get() = prefs.getInt("auto_dim", 10)
        set(v) = prefs.edit().putInt("auto_dim", v).apply()

    /** [DockCore.SCREEN_OFF_NONE], [DockCore.SCREEN_OFF_BLANK] or [DockCore.SCREEN_OFF_SLEEP]. */
    var screenOff: String
        get() = prefs.getString("screen_off", DockCore.SCREEN_OFF_NONE)!!
        set(v) = prefs.edit().putString("screen_off", v).apply()

    var offStart: Int
        get() = prefs.getInt("off_start", 60)
        set(v) = prefs.edit().putInt("off_start", v).apply()

    var offEnd: Int
        get() = prefs.getInt("off_end", 6 * 60)
        set(v) = prefs.edit().putInt("off_end", v).apply()

    /** Everything the display schedule needs. */
    fun care() = DockCore.Care(oledCare, dimText, if (oledCare) autoDim else 0, night, nightStart, nightEnd, screenOff, offStart, offEnd)

    /** Offer Dock mode when the phone is plugged in (see [DockLauncher]). */
    var openOnCharge: Boolean
        get() = prefs.getBoolean("open_on_charge", false)
        set(v) = prefs.edit().putBoolean("open_on_charge", v).apply()

    /** Whether the hint ("tap for controls…") has been shown. */
    var hinted: Boolean
        get() = prefs.getBoolean("hinted", false)
        set(v) = prefs.edit().putBoolean("hinted", v).apply()
}
