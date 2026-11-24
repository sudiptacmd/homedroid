package dev.homedroid

import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min

/** The dock's customisation sheet: slides in from the right (landscape) or the bottom (portrait). */
class DockSettings(private val screen: DockScreen, private val host: DockScreen.Host) {
    private val ctx: Context = screen.ctx
    private val prefs = screen.prefs
    private val c get() = screen.colors
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val scrim = View(ctx)
    private val sheet = FrameLayout(ctx)
    private val scroll = ScrollView(ctx)
    private val content = LinearLayout(ctx)
    private var landscape = false
    var open = false; private set

    init {
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.setOnClickListener { hide() }
        scrim.contentDescription = "Close settings"
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(24), dp(20), dp(24), dp(32))
        scroll.addView(content)
        scroll.isVerticalScrollBarEnabled = false
        sheet.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        sheet.isClickable = true // Touches on the sheet never reach the dock's gestures.
    }

    fun show(root: ViewGroup) {
        if (scrim.parent == null) { root.addView(scrim, FrameLayout.LayoutParams(-1, -1)); root.addView(sheet) }
        place(root)
        build()
        open = true
        scrim.visibility = View.VISIBLE; sheet.visibility = View.VISIBLE
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(220).start()
        val w = sheet.layoutParams.width.toFloat(); val h = sheet.layoutParams.height.toFloat()
        if (landscape) sheet.translationX = w else sheet.translationY = h
        sheet.alpha = 0f
        sheet.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    fun hide() {
        if (!open) return
        open = false
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        val lp = sheet.layoutParams
        sheet.animate().alpha(0f).setDuration(240)
            .translationX(if (landscape) lp.width.toFloat() else 0f).translationY(if (landscape) 0f else lp.height.toFloat())
            .withEndAction { sheet.visibility = View.GONE }.start()
    }

    /** Re-anchors the sheet after a rotation. */
    fun place(root: ViewGroup) {
        val w = root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        landscape = w > h
        val r = dp(28).toFloat()
        val bg = GradientDrawable().apply {
            setColor(DockCore.blend(c.accent, 0xFF0D0F0F.toInt(), 0.07f))
            cornerRadii = if (landscape) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        sheet.background = bg
        sheet.layoutParams = if (landscape) FrameLayout.LayoutParams(min(dp(440), (w * 0.56f).toInt()), -1, Gravity.END)
            else FrameLayout.LayoutParams(-1, (h * 0.78f).toInt(), Gravity.BOTTOM)
        // The dock root pads for cut-outs; the sheet should reach the screen edges.
        (sheet.layoutParams as FrameLayout.LayoutParams).setMargins(if (landscape) 0 else -root.paddingLeft, if (landscape) -root.paddingTop else 0, -root.paddingRight, -root.paddingBottom)
        (scrim.layoutParams as? FrameLayout.LayoutParams)?.setMargins(-root.paddingLeft, -root.paddingTop, -root.paddingRight, -root.paddingBottom)
        if (open) build()
    }

    private fun changed() {
        screen.touched()
        screen.applyPrefs()
        build()
    }

    // ---- Building --------------------------------------------------------------------------

    private fun build() {
        content.removeAllViews()
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Dock", 24f, c.ink, 700), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill("Done", true) { hide() })
        content.addView(header)
        content.addView(text("Your server, as a bedside clock.", 14f, c.muted, 400).apply { setPadding(0, dp(4), 0, 0) })

        section("Clock face")
        chips(DockFaces.IDS.map { it to DockFaces.NAMES.getValue(it) }, prefs.face) { prefs.face = it; changed() }

        section("Accent")
        val swatches = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val options = DockCore.PALETTES.map { Triple(it.id, it.name, it.primary) }.toMutableList()
        if (Build.VERSION.SDK_INT >= 31) options.add(0, Triple(DockCore.DYNAMIC, "Wallpaper", ctx.getColor(android.R.color.system_accent1_200)))
        for ((id, name, color) in options) {
            swatches.addView(Swatch(ctx, color, id == prefs.palette, c.ink, id == DockCore.DYNAMIC).apply {
                contentDescription = "$name accent"
                setOnClickListener { prefs.palette = id; changed() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(6) })
        }
        content.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(swatches) })
        val current = options.firstOrNull { it.first == prefs.palette }?.second ?: "Mint"
        content.addView(text(if (prefs.palette == DockCore.DYNAMIC) "Wallpaper colours (Android 12+)" else current, 13f, c.muted, 500).apply { setPadding(dp(2), dp(8), 0, 0) })

        section("Time")
        toggle("24-hour clock", null, prefs.use24h) { prefs.use24h = it; changed() }
        toggle("Show seconds", "Redraws every second instead of every minute", prefs.showSeconds) { prefs.showSeconds = it; changed() }

        section("Tiles")
        content.addView(text("Up to ${DockCore.MAX_TILES}, in this order.", 13f, c.muted, 400).apply { setPadding(0, 0, 0, dp(6)) })
        val on = prefs.tiles
        for (t in on + DockCore.Tile.entries.filter { it !in on }) tileRow(t, on)

        section("Night")
        chips(listOf(DockCore.NIGHT_OFF to "Off", DockCore.NIGHT_AUTO to "Scheduled", DockCore.NIGHT_ALWAYS to "Always"), prefs.night) { prefs.night = it; changed() }
        if (prefs.night == DockCore.NIGHT_AUTO) {
            action("Dim from", DockCore.clockLabel(prefs.nightStart, prefs.use24h)) { pickTime(prefs.nightStart) { prefs.nightStart = it; changed() } }
            action("Until", DockCore.clockLabel(prefs.nightEnd, prefs.use24h)) { pickTime(prefs.nightEnd) { prefs.nightEnd = it; changed() } }
        }
        content.addView(text("Night mode switches to deep red and turns the backlight right down.", 13f, c.muted, 400).apply { setPadding(0, dp(8), 0, 0) })

        section("OLED care")
        toggle("OLED care", "Guards OLED screens against burn-in. Harmless on LCD screens.", prefs.oledCare) { prefs.oledCare = it; changed() }
        if (prefs.oledCare) {
            toggle("Pixel shift", "Everything drifts a few pixels each minute", prefs.burnIn) { prefs.burnIn = it; changed() }
            toggle("Swap layout", "Clock and tiles trade places every ${DockCore.SWAP_MINUTES} minutes", prefs.layoutSwap) { prefs.layoutSwap = it; changed() }
            toggle("Dim text", "Lower brightness for the clock and tiles", prefs.dimText) { prefs.dimText = it; changed() }
            toggle("Fade labels when idle", "Tile backgrounds and labels fade after a few seconds", prefs.fadeChrome) { prefs.fadeChrome = it; changed() }
            content.addView(text("Dim when nobody touches it for", 14f, c.ink, 500).apply { setPadding(dp(2), dp(14), 0, dp(8)) })
            chips(listOf("0" to "Never", "5" to "5 min", "10" to "10 min", "30" to "30 min"), prefs.autoDim.toString()) { prefs.autoDim = it.toInt(); changed() }
            content.addView(text("Thin strokes and dimmer colours on every face; true black everywhere else.", 13f, c.muted, 400).apply { setPadding(0, dp(10), 0, 0) })
        }

        section("Screen off hours")
        chips(listOf(DockCore.SCREEN_OFF_NONE to "Off", DockCore.SCREEN_OFF_BLANK to "Go black", DockCore.SCREEN_OFF_SLEEP to "Let it sleep"), prefs.screenOff) { prefs.screenOff = it; changed() }
        if (prefs.screenOff != DockCore.SCREEN_OFF_NONE) {
            action("From", DockCore.clockLabel(prefs.offStart, prefs.use24h)) { pickTime(prefs.offStart) { prefs.offStart = it; changed() } }
            action("Until", DockCore.clockLabel(prefs.offEnd, prefs.use24h)) { pickTime(prefs.offEnd) { prefs.offEnd = it; changed() } }
            content.addView(text(if (prefs.screenOff == DockCore.SCREEN_OFF_BLANK) "The screen shows pure black; tap to see the clock for a minute."
                else "The phone's normal screen timeout applies, then the screen turns off.", 13f, c.muted, 400).apply { setPadding(0, dp(8), 0, 0) })
        }

        section("Charging")
        toggle("Open when charging", "While the server runs, plugging in offers Dock mode", prefs.openOnCharge) {
            prefs.openOnCharge = it
            if (it) host.requestNotificationPermission()
            changed()
        }
        if (prefs.openOnCharge && Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            action("Allow full-screen", "Needed to open over the lock screen") {
                launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
            }
        }
        action("Use as screen saver", "Most reliable: shows whenever the phone charges") { launch(Intent(Settings.ACTION_DREAM_SETTINGS)) }
    }

    private fun launch(i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {
            try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
        }
    }

    private fun pickTime(minute: Int, done: (Int) -> Unit) {
        TimePickerDialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert, { _, h, m -> done(h * 60 + m) },
            minute / 60, minute % 60, prefs.use24h).show()
    }

    // ---- Pieces ----------------------------------------------------------------------------

    private fun text(s: String, size: Float, color: Int, weight: Int) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); typeface = DockFaces.sans(weight)
    }

    private fun section(title: String) {
        content.addView(text(title.uppercase(), 12f, c.accent, 700).apply { letterSpacing = 0.12f; setPadding(dp(2), dp(28), 0, dp(12)) })
    }

    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun touch(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf((c.accent and 0x00FFFFFF) or 0x33000000), shape(color, radius), null)

    private fun pill(label: String, selected: Boolean, action: () -> Unit) = text(label, 14f, if (selected) c.bg else c.ink, 600).apply {
        background = touch(if (selected) c.accent else c.surface, 22)
        gravity = Gravity.CENTER
        minHeight = dp(44); minWidth = dp(64)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isSelected = selected
        setOnClickListener { action() }
    }

    private fun chips(items: List<Pair<String, String>>, selected: String, pick: (String) -> Unit) {
        val row = LinearLayout(ctx)
        for ((id, name) in items) row.addView(pill(name, id == selected) { pick(id) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        content.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun rowBase(title: String, detail: String?): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = touch(c.surface, 16)
        }
        val words = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(title, 16f, c.ink, 500))
        if (detail != null) words.addView(text(detail, 13f, c.muted, 400))
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        return row to words
    }

    private fun switchView(checked: Boolean) = Switch(ctx).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(c.accent, c.muted))
        trackTintList = ColorStateList(states, intArrayOf(DockCore.blend(c.accent, c.bg, 0.45f), c.track))
        isClickable = false; isFocusable = false // the row handles the tap
    }

    private fun toggle(title: String, detail: String?, checked: Boolean, set: (Boolean) -> Unit) {
        val (row, _) = rowBase(title, detail)
        val sw = switchView(checked)
        row.addView(sw)
        row.contentDescription = title
        row.accessibilityDelegate = checkedDelegate(checked)
        row.setOnClickListener { set(!checked) }
    }

    private fun action(title: String, detail: String, go: () -> Unit) {
        val (row, _) = rowBase(title, detail)
        row.addView(text("›", 24f, c.muted, 400).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        row.setOnClickListener { go() }
    }

    private fun tileRow(t: DockCore.Tile, on: List<DockCore.Tile>) {
        val enabled = t in on
        val full = on.size >= DockCore.MAX_TILES
        val (row, words) = rowBase(t.title, if (enabled) "#${on.indexOf(t) + 1}" else if (full) "Turn another off first" else null)
        if (!enabled && full) words.alpha = 0.5f
        if (enabled) {
            fun arrow(glyph: String, label: String, delta: Int, active: Boolean) = text(glyph, 20f, c.ink, 500).apply {
                gravity = Gravity.CENTER
                contentDescription = "$label ${t.title}"
                alpha = if (active) 1f else 0.25f
                isEnabled = active
                background = touch(c.surface, 20)
                setOnClickListener { prefs.tiles = DockCore.move(prefs.tiles, t, delta); changed() }
                row.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            arrow("↑", "Move up", -1, on.indexOf(t) > 0)
            arrow("↓", "Move down", 1, on.indexOf(t) < on.size - 1)
        }
        row.addView(switchView(enabled), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        row.contentDescription = "${t.title} tile"
        row.accessibilityDelegate = checkedDelegate(enabled)
        row.setOnClickListener { if (enabled || !full) { prefs.tiles = DockCore.toggle(on, t, !enabled); changed() } }
    }

    private fun checkedDelegate(checked: Boolean) = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.isCheckable = true
            if (Build.VERSION.SDK_INT >= 36) info.setChecked(if (checked) android.view.accessibility.AccessibilityNodeInfo.CHECKED_STATE_TRUE else android.view.accessibility.AccessibilityNodeInfo.CHECKED_STATE_FALSE)
            else @Suppress("DEPRECATION") info.setChecked(checked)
        }
    }

    /** A colour choice; the wallpaper one is split to hint that it follows the system. */
    private class Swatch(ctx: Context, private val color: Int, private val chosen: Boolean, private val ring: Int, private val dynamic: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        init { isClickable = true; isFocusable = true; isSelected = chosen }
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy)
            p.color = color
            canvas.drawCircle(cx, cy, r * 0.66f, p)
            if (dynamic) {
                p.color = 0x55000000
                canvas.drawArc(cx - r * 0.66f, cy - r * 0.66f, cx + r * 0.66f, cy + r * 0.66f, 90f, 180f, true, p)
            }
            if (chosen) { stroke.color = ring; stroke.strokeWidth = r * 0.09f; canvas.drawCircle(cx, cy, r * 0.88f, stroke) }
        }
    }
}
