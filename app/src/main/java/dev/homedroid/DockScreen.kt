package dev.homedroid

import android.animation.ValueAnimator
import android.app.AlarmManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * The dock: a clock face, vital tiles, and (when [interactive]) tap-to-show controls and the
 * customisation sheet. Shared by [DockActivity] and the [DockDream] screen saver.
 */
class DockScreen(val ctx: Context, private val interactive: Boolean, private val host: Host) {
    interface Host {
        fun exit()
        /** Night mode or auto-dim changed: lower the backlight while [low] is true. */
        fun setDim(low: Boolean)
        /** "Screen off" hours with the sleep option: let the system turn the screen off. */
        fun setKeepScreenOn(on: Boolean) {}
        /** Runs [then] once the phone is unlocked (immediately if it isn't locked). */
        fun requestUnlock(then: () -> Unit) = then()
        fun requestNotificationPermission() {}
    }

    val prefs = DockPrefs(ctx)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    val colors = DockColors()
    private var target = DockColors()
    private val from = DockColors()
    private var colorAnim: ValueAnimator? = null
    private var chromeAnim: ValueAnimator? = null
    private var night = false
    private var display: DockCore.Display? = null
    private var lastTouch = android.os.SystemClock.elapsedRealtime()
    private var slot = 0L

    private val ui = Handler(Looper.getMainLooper())
    private var sampler: ScheduledExecutorService? = null
    @Volatile private var snapshot = DockCore.Snapshot()
    private var running = false
    private var lastMinute = -1

    val root: DockRoot = DockRoot(ctx)
    val clock = ClockView(ctx, colors)
    private val layout = DockLayout(ctx)
    private val tileViews = HashMap<DockCore.Tile, TileView>()
    private var tiles = prefs.tiles
    private val controls = LinearLayout(ctx)
    private val hint = TextView(ctx)
    /** Black cover for "screen off" hours; a tap wakes the dock for a minute. */
    private val blanker = View(ctx)
    private var settings: DockSettings? = null

    init {
        root.setBackgroundColor(colors.bg)
        root.setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
        root.addView(layout, FrameLayout.LayoutParams(-1, -1))
        layout.addView(clock)
        blanker.setBackgroundColor(0xFF000000.toInt())
        blanker.visibility = View.GONE
        blanker.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(blanker, FrameLayout.LayoutParams(-1, -1))
        clock.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        clock.isFocusable = interactive
        root.setOnApplyWindowInsetsListener { v, insets ->
            // Stay clear of camera cut-outs; the system bars are hidden anyway.
            val cut = insets.displayCutout
            val p = dp(20f)
            v.setPadding(p + (cut?.safeInsetLeft ?: 0), p + (cut?.safeInsetTop ?: 0), p + (cut?.safeInsetRight ?: 0), p + (cut?.safeInsetBottom ?: 0))
            insets
        }
        clock.state.prompt = "${DockData.host(ctx)}:~$ date"
        if (interactive) buildControls()
        applyPrefs()
        colorAnim?.cancel()
        colors.set(target)
        root.setBackgroundColor(colors.bg)
    }

    // ---- Lifecycle -------------------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        lastMinute = -1
        tick.run()
        sampler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dock-sampler").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                val s = runCatching { DockData.sample(ctx, prefs.use24h) }.getOrNull() ?: return@scheduleWithFixedDelay
                snapshot = s
                ui.post { if (running) showData(s) }
            }, 0, SAMPLE_SECONDS, TimeUnit.SECONDS)
        }
        if (interactive && !prefs.hinted) showHint()
        scheduleChromeFade()
    }

    /** A touch: wake from auto-dim or "screen off", show the chrome again. */
    fun touched() {
        lastTouch = android.os.SystemClock.elapsedRealtime()
        if (colors.chrome < 1f) animateChrome(1f, 200)
        scheduleChromeFade()
        if (display?.let { it.blank || it.level < 1f || it.lowLight } == true) updateDisplay()
    }

    private val fadeChrome = Runnable { if (prefs.oledCare && prefs.fadeChrome) animateChrome(0f, 1500) }

    private fun scheduleChromeFade() {
        ui.removeCallbacks(fadeChrome)
        if (running && prefs.oledCare && prefs.fadeChrome) ui.postDelayed(fadeChrome, DockCore.CHROME_FADE_SECONDS * 1000L)
        else if (colors.chrome < 1f) animateChrome(1f, 300)
    }

    private fun animateChrome(to: Float, ms: Long) {
        chromeAnim?.cancel()
        chromeAnim = ValueAnimator.ofFloat(colors.chrome, to).apply {
            duration = ms
            addUpdateListener { colors.chrome = it.animatedValue as Float; invalidateAll() }
            start()
        }
    }

    private fun invalidateAll() {
        clock.invalidate()
        for (i in 1 until layout.childCount) layout.getChildAt(i).invalidate()
    }

    fun stop() {
        running = false
        ui.removeCallbacksAndMessages(null)
        sampler?.shutdownNow(); sampler = null
        // Finish any crossfade now, so the colours never freeze half-way.
        colorAnim?.end()
        chromeAnim?.end()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = Calendar.getInstance()
            val h = now.get(Calendar.HOUR_OF_DAY); val m = now.get(Calendar.MINUTE); val sec = now.get(Calendar.SECOND)
            clock.setTime(h, m, sec, prefs.use24h, prefs.showSeconds)
            val minuteOfDay = h * 60 + m
            if (minuteOfDay != lastMinute) {
                lastMinute = minuteOfDay
                onMinute(now, minuteOfDay)
            }
            // Wake exactly on the next second (or minute) boundary: no drift, no wasted frames.
            val ms = now.get(Calendar.MILLISECOND)
            val delay = if (prefs.showSeconds) 1000L - ms else 60_000L - sec * 1000L - ms
            ui.postDelayed(this, delay.coerceAtLeast(16))
        }
    }

    private fun onMinute(now: Calendar, minuteOfDay: Int) {
        val locale = Locale.getDefault()
        clock.state.date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now).toString()
        clock.state.dateShort = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEd"), now).toString().uppercase(locale)
        clock.contentDescription = "${DockCore.spoken(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), prefs.use24h)}, ${clock.state.date}"
        clock.relayout()
        val epochMinute = now.timeInMillis / 60_000
        updateDisplay(minuteOfDay)
        swapLayout(if (prefs.oledCare && prefs.layoutSwap) DockCore.swapSlot(epochMinute) else 0L)
        shiftForBurnIn(epochMinute)
    }

    private fun shiftForBurnIn(minute: Long) {
        val (dx, dy) = if (prefs.oledCare && prefs.burnIn) DockCore.burnInOffset(minute, dp(BURN_IN_DP)) else 0 to 0
        layout.animate().translationX(dx.toFloat()).translationY(dy.toFloat()).setDuration(1500).start()
    }

    /** Every [DockCore.SWAP_MINUTES]: fade out, trade the clock's side and rotate the tiles, fade in. */
    private fun swapLayout(newSlot: Long) {
        if (newSlot == slot) return
        slot = newSlot
        layout.animate().alpha(0f).setDuration(500).withEndAction {
            arrangeTiles()
            layout.animate().alpha(1f).setDuration(700).start()
        }.start()
    }

    private fun arrangeTiles() {
        layout.mirrored = DockCore.mirrored(slot)
        layout.removeViews(1, layout.childCount - 1)
        for (t in DockCore.rotate(tiles, slot)) layout.addView(tileViews.getOrPut(t) { TileView(ctx, colors) })
        layout.requestLayout()
    }

    /** Applies night mode, dimming, "screen off" and the keep-on flag for the current minute. */
    private fun updateDisplay(minuteOfDay: Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }) {
        val idle = (android.os.SystemClock.elapsedRealtime() - lastTouch) / 60_000
        // The screen saver ends on any touch, so "idle" there means "since it started".
        val d = DockCore.display(prefs.care(), minuteOfDay, idle)
        val old = display
        if (d == old) return
        display = d
        night = d.night
        host.setDim(d.lowLight)
        host.setKeepScreenOn(!d.mayTimeout)
        if (old == null || old.night != d.night || old.level != d.level) { target = palette(d.night).dimmed(d.level); animateColors() }
        if (old?.blank != d.blank) {
            blanker.animate().cancel()
            if (d.blank) { blanker.alpha = 0f; blanker.visibility = View.VISIBLE; blanker.animate().alpha(1f).setDuration(1500).start() }
            else blanker.animate().alpha(0f).setDuration(400).withEndAction { blanker.visibility = View.GONE }.start()
        }
    }

    // ---- Preferences and colour ------------------------------------------------------------

    /** Re-reads every setting; called at start and after each change in the sheet. */
    fun applyPrefs() {
        clock.setFace(prefs.face)
        clock.oled = prefs.oledCare
        tiles = prefs.tiles
        if (!(prefs.oledCare && prefs.layoutSwap)) slot = 0L
        arrangeTiles()
        showData(snapshot)
        display = null // re-evaluate night, dimming and screen-off with the new settings
        updateDisplay()
        if (running) {
            lastMinute = -1
            ui.removeCallbacks(tick); tick.run()
        }
        scheduleChromeFade()
        clock.relayout()
    }

    private fun palette(night: Boolean): DockColors {
        if (night) return DockColors.night()
        if (prefs.palette == DockCore.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
            return DockColors.day(ctx.getColor(android.R.color.system_accent1_200), ctx.getColor(android.R.color.system_accent3_300))
        }
        val p = DockCore.palette(prefs.palette)
        return DockColors.day(p.primary, p.secondary)
    }

    private fun animateColors() {
        colorAnim?.cancel()
        from.set(colors)
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                colors.mix(from, target, it.animatedFraction)
                root.setBackgroundColor(colors.bg)
                invalidateAll()
            }
            start()
        }
    }

    private fun showData(s: DockCore.Snapshot) {
        val st = clock.state
        st.battery = s.battery
        st.charging = s.charging
        st.cpu = if (s.cpuPercent < 0) -1f else (s.cpuPercent / 100).toFloat()
        st.batteryLine = if (s.battery < 0) "" else "${s.battery}%" + if (s.charging) " · CHARGING" else ""
        st.serverLine = DockCore.serverLine(s)
        st.ringLine = listOfNotNull(
            if (s.battery >= 0) "BATTERY ${s.battery}%" else null,
            if (s.cpuPercent >= 0) "CPU ${s.cpuPercent.toInt()}%" else null,
        ).joinToString(" · ")
        clock.invalidate()
        for (t in tiles) tileViews[t]?.set(DockCore.tile(t, s))
    }

    // ---- Controls --------------------------------------------------------------------------

    private val hideControls = Runnable { fadeControls(false) }
    private val alwaysShowControls get() = ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun buildControls() {
        controls.orientation = LinearLayout.HORIZONTAL
        controls.alpha = 0f
        controls.visibility = View.GONE
        fun button(kind: Int, label: String, action: () -> Unit) = IconButton(ctx, kind, colors).apply {
            contentDescription = label
            setOnClickListener { action(); scheduleHide() }
            controls.addView(this, LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { marginStart = dp(8f) })
        }
        button(IconButton.MOON, "Night mode") {
            prefs.night = if (night) DockCore.NIGHT_OFF else DockCore.NIGHT_ALWAYS
            applyPrefs()
        }
        button(IconButton.TUNE, "Customize dock") { openSettings() }
        button(IconButton.CLOSE, "Close dock") { host.exit() }
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        hint.text = "Tap for controls  ·  long-press to customize  ·  swipe down to close"
        hint.textSize = 13f
        hint.setTextColor(colors.muted)
        hint.gravity = Gravity.CENTER
        hint.alpha = 0f
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { touched(); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { fadeControls(controls.visibility != View.VISIBLE || controls.alpha < 1f); return true }
            override fun onLongPress(e: MotionEvent) = openSettings()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (vy > 1200 * density && dy > 96 * density && abs(dy) > abs(e2.x - (e1?.x ?: e2.x))) { host.exit(); return true }
                return false
            }
        })
        root.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        root.performClickAction = { fadeControls(true) }
        if (alwaysShowControls) fadeControls(true)
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (!alwaysShowControls) ui.postDelayed(hideControls, 5000)
    }

    private fun fadeControls(show: Boolean) {
        controls.animate().cancel()
        if (show) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(220).start()
            scheduleHide()
        } else {
            controls.animate().alpha(0f).setDuration(320).withEndAction { controls.visibility = View.GONE }.start()
        }
    }

    private fun showHint() {
        hint.setTextColor(colors.muted)
        hint.animate().alpha(1f).setStartDelay(600).setDuration(500).withEndAction {
            hint.animate().alpha(0f).setStartDelay(4000).setDuration(800).start()
        }.start()
        prefs.hinted = true
    }

    fun openSettings() {
        if (!interactive) return
        host.requestUnlock {
            fadeControls(false)
            val sheet = settings ?: DockSettings(this, host).also { settings = it }
            sheet.show(root)
        }
    }

    /** Back: closes the sheet if open. Returns true if it handled the press. */
    fun back(): Boolean = settings?.let { if (it.open) { it.hide(); true } else false } ?: false

    val settingsOpen get() = settings?.open == true

    /** After a rotation: re-plan the layout and re-anchor the sheet. */
    fun rotated() {
        layout.requestLayout()
        clock.relayout()
        settings?.let { if (it.open) it.place(root) }
    }

    companion object {
        private const val SAMPLE_SECONDS = 3L
        private const val BURN_IN_DP = 10f
    }
}

/** The dock's root; routes accessibility clicks to the controls. */
class DockRoot(ctx: Context) : FrameLayout(ctx) {
    var performClickAction: (() -> Unit)? = null
    override fun performClick(): Boolean { super.performClick(); performClickAction?.invoke(); return true }
}

/**
 * Places the clock and the tiles: tiles in a column on the right in landscape, in a band
 * along the bottom in portrait; the clock takes all the space when no tiles are chosen.
 */
class DockLayout(ctx: Context) : ViewGroup(ctx) {
    /** OLED care: clock on the right (landscape) or bottom (portrait), tiles on the other side. */
    var mirrored = false
    private val density = ctx.resources.displayMetrics.density
    private val gap = (12 * density).toInt()
    private val rects = ArrayList<IntArray>()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        plan(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom)
        for (i in 0 until childCount) {
            val r = rects.getOrNull(i) ?: continue
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(r[2] - r[0], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r[3] - r[1], MeasureSpec.EXACTLY))
        }
    }

    private fun plan(w: Int, h: Int) {
        rects.clear()
        val n = childCount - 1
        val landscape = w > h
        val (cols, rows) = DockCore.grid(n, landscape)
        if (n <= 0) { rects += intArrayOf(0, 0, w, h); return }
        if (landscape) {
            val areaW = min((w * 0.36f).toInt(), (cols * 210 * density).toInt())
            val cellW = (areaW - gap * (cols - 1)) / cols
            val cellH = min(((h - gap * (rows - 1)) / rows), (124 * density).toInt())
            val top = (h - (cellH * rows + gap * (rows - 1))) / 2
            val clockX = if (mirrored) areaW + gap * 2 else 0
            val tilesX = if (mirrored) 0 else w - areaW
            rects += intArrayOf(clockX, 0, clockX + w - areaW - gap * 2, h)
            for (i in 0 until n) {
                val c = i % cols; val r = i / cols
                val x = tilesX + c * (cellW + gap); val y = top + r * (cellH + gap)
                rects += intArrayOf(x, y, x + cellW, y + cellH)
            }
        } else {
            val cellW = (w - gap * (cols - 1)) / cols
            val cellH = min(((h * 0.36f).toInt() - gap * (rows - 1)) / rows, (116 * density).toInt())
            val areaH = cellH * rows + gap * (rows - 1)
            val clockY = if (mirrored) areaH + gap * 2 else 0
            val tilesY = if (mirrored) 0 else h - areaH
            rects += intArrayOf(0, clockY, w, clockY + h - areaH - gap * 2)
            for (i in 0 until n) {
                val c = i % cols; val r = i / cols
                // A short last row is centred.
                val inRow = if (r == rows - 1) n - r * cols else cols
                val offset = (w - (inRow * cellW + (inRow - 1) * gap)) / 2
                val x = offset + c * (cellW + gap); val y = tilesY + r * (cellH + gap)
                rects += intArrayOf(x, y, x + cellW, y + cellH)
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val rc = rects.getOrNull(i) ?: continue
            getChildAt(i).layout(paddingLeft + rc[0], paddingTop + rc[1], paddingLeft + rc[2], paddingTop + rc[3])
        }
    }
}

/** One vital: a small label, a big value, a detail line and an optional bar. Canvas-drawn. */
class TileView(ctx: Context, private val colors: DockColors) : View(ctx) {
    private val density = ctx.resources.displayMetrics.density
    private var data = DockCore.TileText("", "", "", DockCore.Tone.NEUTRAL)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = DockFaces.sans(700); letterSpacing = 0.12f }
    private val value = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = DockFaces.sans(600); fontFeatureSettings = "tnum" }
    private val detail = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = DockFaces.sans(400) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var shownValue = ""; private var shownDetail = ""; private var shownLabel = ""
    private var pad = 0f; private var yLabel = 0f; private var yValue = 0f; private var yDetail = 0f; private var yBar = 0f
    private var radius = 0f; private var dotR = 0f; private var showDetail = true
    private var valueSize = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun set(t: DockCore.TileText) {
        if (t == data) return
        val resize = t.value.length != data.value.length || t.detail != data.detail
        data = t
        contentDescription = "${t.label.lowercase()}: ${t.value}, ${t.detail}"
        if (resize) fitText() else shownValue = fitValue(t.value)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        radius = min(h * 0.22f, 26 * density)
        pad = min(16 * density, h * 0.15f)
        label.textSize = (h * 0.1f).coerceIn(10 * density, 13 * density)
        valueSize = (h * 0.25f).coerceIn(15 * density, 30 * density)
        value.textSize = valueSize
        detail.textSize = (h * 0.11f).coerceIn(11 * density, 14 * density)
        yLabel = pad + label.textSize * 0.8f
        yValue = yLabel + valueSize * 1.12f
        yDetail = yValue + detail.textSize * 1.45f
        yBar = h - pad * 0.7f
        showDetail = yDetail + detail.textSize * 0.3f < yBar - 2 * density
        dotR = 3.5f * density
        bar.strokeWidth = 3 * density
        fitText()
    }

    private fun ellipsize(s: String, p: TextPaint) = if (width <= 0) s else TextUtils.ellipsize(s, p, width - pad * 2, TextUtils.TruncateAt.END).toString()

    /** Shrinks a long value (down to 60%) before cutting it short: "↓ 103 B/s" beats "↓ 103…". */
    private fun fitValue(s: String): String {
        if (width <= 0 || valueSize <= 0f) return s
        value.textSize = valueSize
        val room = width - pad * 2
        val w = value.measureText(s)
        if (w > room) value.textSize = max(valueSize * 0.6f, valueSize * room / w)
        return ellipsize(s, value)
    }

    private fun fitText() {
        shownValue = fitValue(data.value); shownDetail = ellipsize(data.detail, detail)
        // Leave room for the status dot at the top right.
        shownLabel = if (width <= 0) data.label else TextUtils.ellipsize(data.label, label, width - pad * 2 - dotR * 3, TextUtils.TruncateAt.END).toString()
    }

    private fun toneColor() = when (data.tone) {
        DockCore.Tone.OK -> colors.accent
        DockCore.Tone.WARN -> colors.warn
        DockCore.Tone.BAD -> colors.bad
        DockCore.Tone.NEUTRAL -> 0
    }

    override fun onDraw(canvas: Canvas) {
        // Surfaces, labels and tracks are static "chrome": under OLED care they fade when idle.
        bg.color = colors.faded(colors.surface)
        canvas.drawRoundRect(rect, radius, radius, bg)
        label.color = colors.faded(colors.muted)
        canvas.drawText(shownLabel, pad, yLabel, label)
        val tone = toneColor()
        if (tone != 0) { bg.color = tone; canvas.drawCircle(width - pad - dotR, yLabel - label.textSize * 0.32f, dotR, bg) }
        value.color = colors.ink
        canvas.drawText(shownValue, pad, yValue, value)
        if (showDetail) { detail.color = colors.muted; canvas.drawText(shownDetail, pad, yDetail, detail) }
        if (data.progress >= 0f) {
            val x0 = pad; val x1 = width - pad
            bar.color = colors.faded(colors.track)
            canvas.drawLine(x0, yBar, x1, yBar, bar)
            bar.color = if (tone != 0 && data.tone != DockCore.Tone.OK) tone else colors.accent
            canvas.drawLine(x0, yBar, x0 + (x1 - x0) * data.progress.coerceIn(0.01f, 1f), yBar, bar)
        }
    }
}

/** Round, line-drawn icon buttons for the dock's controls. */
class IconButton(ctx: Context, private val kind: Int, private val colors: DockColors) : View(ctx) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moon = Path()

    init { isClickable = true; isFocusable = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = min(w, h) / 24f
        moon.reset()
        val outer = Path().apply { addCircle(w / 2f, h / 2f, 6.5f * s, Path.Direction.CW) }
        val bite = Path().apply { addCircle(w / 2f + 3.6f * s, h / 2f - 3f * s, 5.4f * s, Path.Direction.CW) }
        moon.op(outer, bite, Path.Op.DIFFERENCE)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = min(width, height) / 24f
        fill.color = if (isPressed) colors.track else colors.surface
        canvas.drawCircle(cx, cy, min(cx, cy), fill)
        pen.color = colors.ink; pen.strokeWidth = 1.7f * s; fill.color = colors.ink
        when (kind) {
            CLOSE -> { canvas.drawLine(cx - 4.5f * s, cy - 4.5f * s, cx + 4.5f * s, cy + 4.5f * s, pen); canvas.drawLine(cx + 4.5f * s, cy - 4.5f * s, cx - 4.5f * s, cy + 4.5f * s, pen) }
            TUNE -> for ((i, y) in floatArrayOf(-4.5f, 0f, 4.5f).withIndex()) {
                canvas.drawLine(cx - 6f * s, cy + y * s, cx + 6f * s, cy + y * s, pen)
                val kx = cx + floatArrayOf(-2.5f, 3f, -0.5f)[i] * s
                fill.color = colors.surface; canvas.drawCircle(kx, cy + y * s, 2.2f * s, fill)
                canvas.drawCircle(kx, cy + y * s, 2.2f * s, pen)
            }
            else -> canvas.drawPath(moon, fill)
        }
    }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    companion object { const val MOON = 0; const val TUNE = 1; const val CLOSE = 2 }
}

/** Reads the live figures behind the tiles. Slow-ish (files, sockets): call off the main thread. */
object DockData {
    private var tailscaleAt = 0L
    private var tailscale: Pair<String, Int>? = null

    fun host(ctx: Context): String = (Cluster.instance?.name ?: Build.MODEL).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "homedroid" }

    fun sample(ctx: Context, use24h: Boolean): DockCore.Snapshot {
        val dev = Device(ctx)
        val m = Metrics.sample(ctx)
        val cfg = Config(ctx)
        val daemons = ServerService.supervisor?.daemons.orEmpty()
        val now = System.currentTimeMillis()
        if (cfg.tailscaleEnabled && ServerService.running && now - tailscaleAt > 30_000) {
            tailscaleAt = now
            tailscale = runCatching { Tailscale(Paths(ctx), cfg).status().let { it.optString("state") to it.optInt("peersOnline") } }.getOrNull()
        }
        val alarm = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        val cal = Calendar.getInstance()
        val locale = Locale.getDefault()
        var alarmLabel: String? = null; var alarmDetail: String? = null
        if (alarm != null) {
            val a = Calendar.getInstance().apply { timeInMillis = alarm }
            alarmLabel = DockCore.clockLabel(a.get(Calendar.HOUR_OF_DAY) * 60 + a.get(Calendar.MINUTE), use24h)
            val days = a.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) + if (a.get(Calendar.YEAR) != cal.get(Calendar.YEAR)) cal.getActualMaximum(Calendar.DAY_OF_YEAR) else 0
            val day = when (days) { 0 -> "Today"; 1 -> "Tomorrow"; else -> DateFormat.format("EEEE", a).toString() }
            val inMin = ((alarm - now) / 60_000).coerceAtLeast(0)
            alarmDetail = if (inMin < 24 * 60) "$day · in ${DockCore.uptime(inMin * 60)}" else day
        }
        return DockCore.Snapshot(
            serverActive = ServerService.active,
            serverRunning = ServerService.running,
            servicesRunning = daemons.count { it.state == Supervisor.State.RUNNING },
            servicesTotal = daemons.size,
            attention = daemons.count { it.state == Supervisor.State.BACKOFF },
            battery = dev.batteryPercent,
            charging = dev.charging,
            batteryTempC = dev.batteryTempC,
            cpuPercent = m.optDouble("cpu", -1.0),
            cores = m.optInt("cores"),
            cpuTempC = m.optDouble("cpuTemp").takeIf { !it.isNaN() },
            ramFreeMb = dev.ramFreeMb, ramTotalMb = dev.ramTotalMb,
            storageFreeMb = dev.storageFreeMb, storageTotalMb = dev.storageTotalMb,
            rxBps = m.optLong("rxBps", -1), txBps = m.optLong("txBps", -1),
            uptimeS = dev.uptimeS,
            ip = dev.ips.firstOrNull(),
            port = cfg.dashboardPort,
            tailscaleEnabled = cfg.tailscaleEnabled,
            tailscaleState = if (!ServerService.running) "Stopped" else tailscale?.first,
            tailscalePeersOnline = tailscale?.second ?: 0,
            alarmLabel = alarmLabel, alarmDetail = alarmDetail,
            dateValue = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEd"), cal).toString(),
            dateDetail = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), cal).toString(),
        )
    }
}

