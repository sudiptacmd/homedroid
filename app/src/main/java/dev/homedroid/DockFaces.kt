package dev.homedroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The dock's live colours. Faces and tiles read these on every draw, so a crossfade is just new values here. */
class DockColors {
    var bg = 0xFF000000.toInt()
    var ink = 0xFFF2F4F3.toInt()
    var muted = 0xFF8D9894.toInt()
    var accent = 0xFF78E0C5.toInt()
    var secondary = 0xFF3E9C8A.toInt()
    var surface = 0xFF0C1714.toInt()
    var track = 0xFF16302A.toInt()
    var warn = 0xFFFFC35A.toInt()
    var bad = 0xFFFF6B6B.toInt()
    /** Opacity of static chrome (tile surfaces, labels, tracks); fades to 0 under OLED care. */
    var chrome = 1f

    fun set(o: DockColors) {
        bg = o.bg; ink = o.ink; muted = o.muted; accent = o.accent; secondary = o.secondary
        surface = o.surface; track = o.track; warn = o.warn; bad = o.bad
    }

    /** this = from·(1−t) + to·t. */
    fun mix(from: DockColors, to: DockColors, t: Float) {
        fun m(a: Int, b: Int) = DockCore.blend(b, a, t)
        bg = m(from.bg, to.bg); ink = m(from.ink, to.ink); muted = m(from.muted, to.muted)
        accent = m(from.accent, to.accent); secondary = m(from.secondary, to.secondary)
        surface = m(from.surface, to.surface); track = m(from.track, to.track)
        warn = m(from.warn, to.warn); bad = m(from.bad, to.bad)
    }

    /** A copy with everything bright scaled toward the background by [level] (1 = unchanged). */
    fun dimmed(level: Float): DockColors {
        if (level >= 1f) return this
        val o = DockColors()
        o.set(this)
        fun d(c: Int) = DockCore.blend(c, bg, level)
        o.ink = d(ink); o.muted = d(muted); o.accent = d(accent); o.secondary = d(secondary)
        o.warn = d(warn); o.bad = d(bad); o.surface = d(surface); o.track = d(track)
        return o
    }

    /** [c] with its alpha multiplied by [chrome]. */
    fun faded(c: Int): Int = ((c ushr 24) * chrome).toInt().coerceIn(0, 255) shl 24 or (c and 0xFFFFFF)

    companion object {
        fun day(primary: Int, secondary: Int) = DockColors().apply {
            accent = primary; this.secondary = secondary
            surface = DockCore.blend(primary, bg, 0.09f)
            track = DockCore.blend(primary, bg, 0.2f)
        }

        fun night() = DockColors().apply {
            ink = DockCore.NIGHT_INK; muted = DockCore.NIGHT_MUTED; accent = DockCore.NIGHT_ACCENT
            secondary = DockCore.NIGHT_SECONDARY; surface = 0xFF0E0302.toInt(); track = 0xFF230806.toInt()
            warn = DockCore.NIGHT_ACCENT; bad = DockCore.NIGHT_ACCENT
        }
    }
}

/** What every face can draw. Strings are prepared by [ClockView.setTime], never in onDraw. */
class ClockState {
    var hour24 = 0
    var minute = 0
    var second = 0
    var time = DockCore.TimeText("12", "00", "00", "")
    var hhmm = "12:00"
    var showSeconds = false
    var date = ""
    var dateShort = ""
    var battery = -1
    var charging = false
    var cpu = -1f
    var batteryLine = ""
    var ringLine = ""
    var serverLine = ""
    /** Seconds and/or AM-PM for faces that show them together, e.g. "07 PM". */
    var secondsLine = ""
    var prompt = "homedroid:~$ date"
}

/** Hosts one clock face; redraws only when told the time or data changed. */
class ClockView(context: Context, val colors: DockColors) : View(context) {
    val state = ClockState()
    val density = resources.displayMetrics.density
    fun sp(x: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, x, resources.displayMetrics)
    private var face: DockFaces.Face = DockFaces.Bold(this)
    var faceId = DockFaces.BOLD; private set
    /** OLED care: faces use thinner strokes and dimmer colours. */
    var oled = false
        set(v) { if (field != v) { field = v; relayout() } }

    fun setFace(id: String) {
        if (id == faceId) return
        faceId = id
        face = DockFaces.create(id, this)
        relayout()
    }

    /** New time (and maybe data). Measurement happens here, so drawing stays allocation-free. */
    fun setTime(hour24: Int, minute: Int, second: Int, use24h: Boolean, showSeconds: Boolean) {
        val secondsChanged = showSeconds != state.showSeconds
        state.hour24 = hour24; state.minute = minute; state.second = second; state.showSeconds = showSeconds
        val t = DockCore.time(hour24, minute, second, use24h)
        if (t != state.time || secondsChanged) {
            val shapeChanged = secondsChanged || t.hours.length != state.time.hours.length || t.suffix.isEmpty() != state.time.suffix.isEmpty()
            state.time = t
            state.hhmm = "${t.hours}:${t.minutes}"
            state.secondsLine = listOf(if (showSeconds) t.seconds else "", t.suffix).filter { it.isNotEmpty() }.joinToString(" ")
            if (shapeChanged) relayout()
        }
        invalidate()
    }

    fun relayout() {
        if (width > 0 && height > 0) face.layout(width.toFloat(), height.toFloat())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    override fun onDraw(canvas: Canvas) = face.draw(canvas)
}

object DockFaces {
    const val BOLD = "bold"
    const val ANALOG = "analog"
    const val SPLIT = "split"
    const val RING = "ring"
    const val MONO = "mono"
    val IDS = listOf(BOLD, ANALOG, SPLIT, RING, MONO)
    val NAMES = mapOf(BOLD to "Bold", ANALOG to "Analog", SPLIT to "Split", RING to "Ring", MONO to "Mono")

    fun create(id: String, v: ClockView): Face = when (id) {
        ANALOG -> Analog(v)
        SPLIT -> Split(v)
        RING -> Ring(v)
        MONO -> Mono(v)
        else -> Bold(v)
    }

    private val weights = HashMap<Int, Typeface>()
    fun sans(weight: Int): Typeface = weights.getOrPut(weight) { Typeface.create(Typeface.SANS_SERIF, weight, false) }
    fun mono(weight: Int): Typeface = weights.getOrPut(-weight) { Typeface.create(Typeface.MONOSPACE, weight, false) }

    /** Height of lining digits relative to the text size (Roboto and most system fonts). */
    private const val CAP = 0.72f

    fun paint(tf: Typeface? = null, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        if (tf != null) typeface = tf
        textAlign = align
        // Tabular digits: the time doesn't shuffle sideways as numbers change.
        fontFeatureSettings = "tnum"
    }

    abstract class Face(val v: ClockView) {
        val c get() = v.colors
        val s get() = v.state
        fun dp(x: Float) = x * v.density
        fun sp(x: Float) = v.sp(x)
        abstract fun layout(w: Float, h: Float)
        abstract fun draw(canvas: Canvas)

        /** Text size at which [width] (measured at size 100) fits [maxW] and its digits fit [maxH]. */
        fun fit(width100: Float, maxW: Float, maxH: Float) = min(maxW / width100 * 100f, maxH / CAP)

        /** Shrinks [p] (down to [min]) until [text] fits [maxW]; ellipsizes if it still doesn't. */
        fun fitLine(p: Paint, text: String, maxW: Float, min: Float): String {
            while (p.textSize > min && p.measureText(text) > maxW) p.textSize = (p.textSize * 0.92f).coerceAtLeast(min)
            if (p.measureText(text) <= maxW) return text
            return android.text.TextUtils.ellipsize(text, android.text.TextPaint(p), maxW, android.text.TextUtils.TruncateAt.END).toString()
        }
    }

    /** Heavy hours, hairline minutes: a confident, editorial look. */
    class Bold(v: ClockView) : Face(v) {
        private val hours = paint(sans(900))
        private val colon = paint(sans(300))
        private val minutes = paint(sans(250))
        private val small = paint(sans(600))
        private val date = paint(sans(500), Paint.Align.CENTER)
        private var x0 = 0f; private var xc = 0f; private var xm = 0f; private var xs = 0f
        private var base = 0f; private var dateBase = 0f; private var size = 0f; private var cx = 0f
        private var dateText = ""

        override fun layout(w: Float, h: Float) {
            hours.typeface = sans(if (v.oled) 600 else 900)
            fun measure(sz: Float): Float {
                hours.textSize = sz; colon.textSize = sz; minutes.textSize = sz; small.textSize = sz * 0.2f
                val side = if (s.showSeconds || s.time.suffix.isNotEmpty()) sz * 0.06f + small.measureText("00") else 0f
                return hours.measureText(s.time.hours) + colon.measureText(":") + minutes.measureText(s.time.minutes) + side
            }
            size = fit(measure(100f), w * 0.9f, h * 0.56f)
            val total = measure(size)
            cx = w / 2
            x0 = (w - total) / 2
            xc = x0 + hours.measureText(s.time.hours)
            xm = xc + colon.measureText(":")
            xs = xm + minutes.measureText(s.time.minutes) + size * 0.06f
            date.textSize = (size * 0.13f).coerceIn(sp(14f), sp(34f))
            date.letterSpacing = 0.04f
            dateText = fitLine(date, s.date, w * 0.92f, sp(11f))
            val block = size * CAP + size * 0.22f + date.textSize * CAP
            base = (h - block) / 2 + size * CAP
            dateBase = base + size * 0.22f + date.textSize * CAP
        }

        override fun draw(canvas: Canvas) {
            hours.color = c.ink; colon.color = c.accent; minutes.color = c.ink; date.color = c.muted
            canvas.drawText(s.time.hours, x0, base, hours)
            canvas.drawText(":", xc, base - size * 0.04f, colon)
            canvas.drawText(s.time.minutes, xm, base, minutes)
            if (s.showSeconds) { small.color = c.accent; canvas.drawText(s.time.seconds, xs, base - size * CAP + small.textSize * CAP, small) }
            if (s.time.suffix.isNotEmpty()) { small.color = c.muted; canvas.drawText(s.time.suffix, xs, base, small) }
            canvas.drawText(dateText, cx, dateBase, date)
        }
    }

    /** A quiet dial: 60 ticks, slim hands, the date in a window at three o'clock. */
    class Analog(v: ClockView) : Face(v) {
        private val minor = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val major = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val sec = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = paint(sans(600), Paint.Align.CENTER)
        private val minorTicks = FloatArray(48 * 4)
        private val majorTicks = FloatArray(12 * 4)
        private var cx = 0f; private var cy = 0f; private var r = 0f; private var thin = 1f

        override fun layout(w: Float, h: Float) {
            cx = w / 2; cy = h / 2; r = min(w, h) * 0.44f
            thin = if (v.oled) 0.6f else 1f
            var a = 0; var b = 0
            for (i in 0 until 60) {
                val ang = Math.toRadians(i * 6.0)
                val isMajor = i % 5 == 0
                val inner = if (isMajor) r * 0.86f else r * 0.93f
                val arr = if (isMajor) majorTicks else minorTicks
                val k = if (isMajor) a else b
                arr[k] = cx + (sin(ang) * inner).toFloat(); arr[k + 1] = cy - (cos(ang) * inner).toFloat()
                arr[k + 2] = cx + (sin(ang) * r).toFloat(); arr[k + 3] = cy - (cos(ang) * r).toFloat()
                if (isMajor) a += 4 else b += 4
            }
            minor.strokeWidth = r * 0.008f; major.strokeWidth = r * 0.022f * thin
            sec.strokeWidth = r * 0.012f
            label.textSize = (r * 0.085f).coerceAtLeast(sp(12f))
            label.letterSpacing = 0.08f
        }

        private fun handAt(canvas: Canvas, deg: Float, len: Float, tail: Float, p: Paint) {
            val a = Math.toRadians(deg.toDouble())
            val sx = sin(a).toFloat(); val cy0 = cos(a).toFloat()
            canvas.drawLine(cx - sx * tail, cy + cy0 * tail, cx + sx * len, cy - cy0 * len, p)
        }

        override fun draw(canvas: Canvas) {
            minor.color = c.faded(c.muted); major.color = c.ink; label.color = c.accent
            canvas.drawLines(minorTicks, minor)
            canvas.drawLines(majorTicks, major)
            // Date window at three o'clock, battery at six.
            canvas.drawText(s.dateShort, cx + r * 0.58f, cy + label.textSize * 0.36f, label)
            if (s.battery >= 0) { label.color = c.muted; canvas.drawText(s.batteryLine, cx, cy + r * 0.55f, label) }
            val m = s.minute + s.second / 60f
            hand.color = c.ink
            hand.strokeWidth = r * 0.05f * thin
            handAt(canvas, (s.hour24 % 12 + m / 60f) * 30f, r * 0.5f, 0f, hand)
            hand.strokeWidth = r * 0.03f * thin
            handAt(canvas, m * 6f, r * 0.8f, 0f, hand)
            fill.color = c.ink; canvas.drawCircle(cx, cy, r * 0.045f * thin, fill)
            if (s.showSeconds) {
                sec.color = c.accent
                handAt(canvas, s.second * 6f, r * 0.9f, r * 0.14f, sec)
                fill.color = c.accent; canvas.drawCircle(cx, cy, r * 0.025f, fill)
            }
            fill.color = c.bg; canvas.drawCircle(cx, cy, r * 0.012f, fill)
        }
    }

    /** Hours over outlined minutes; side by side when the space is wide. */
    class Split(v: ClockView) : Face(v) {
        private val hours = paint(sans(800), Paint.Align.CENTER)
        private val minutes = paint(sans(800), Paint.Align.CENTER).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        private val meta = paint(sans(600), Paint.Align.CENTER)
        private val top = paint(sans(600), Paint.Align.CENTER)
        private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private var hx = 0f; private var hy = 0f; private var mx = 0f; private var my = 0f
        private var metaX = 0f; private var metaY = 0f; private var secX = 0f; private var secY = 0f
        private var r0x = 0f; private var r0y = 0f; private var r1x = 0f; private var r1y = 0f
        private var dateText = ""

        override fun layout(w: Float, h: Float) {
            val wide = w > h * 1.2f
            hours.typeface = sans(if (v.oled) 600 else 800)
            hours.textSize = 100f
            val wide100 = hours.measureText("00").coerceAtLeast(hours.measureText(s.time.hours))
            meta.textSize = sp(15f); meta.letterSpacing = 0.12f
            top.letterSpacing = 0.12f
            dateText = fitLine(meta, s.date, w * 0.9f, sp(11f))
            top.textSize = meta.textSize
            // Room for the date line below and the seconds/AM-PM line above, at any font scale.
            val line = meta.textSize * 1.8f
            val topLine = if (s.secondsLine.isNotEmpty()) line else 0f
            if (wide) {
                val size = fit(wide100, w * 0.4f, (h - line - topLine) * 0.86f)
                hours.textSize = size; minutes.textSize = size
                hx = w * 0.27f; mx = w * 0.73f
                val blockTop = (h - (size * CAP + line + topLine)) / 2 + topLine
                hy = blockTop + size * CAP; my = hy
                r0x = w / 2; r1x = w / 2; r0y = blockTop; r1y = hy
                metaX = w / 2; metaY = hy + line
                secX = w / 2; secY = blockTop - line * 0.45f
            } else {
                val size = fit(wide100, w * 0.86f, (h - line - topLine) * 0.42f)
                hours.textSize = size; minutes.textSize = size
                val gap = size * 0.22f
                val block = size * CAP * 2 + gap
                val blockTop = (h - block - line - topLine) / 2 + topLine
                hx = w / 2; mx = w / 2
                hy = blockTop + size * CAP; my = hy + gap + size * CAP
                r0x = w / 2 - size * 0.5f; r1x = w / 2 + size * 0.5f; r0y = hy + gap / 2; r1y = r0y
                metaX = w / 2; metaY = my + line
                secX = w / 2; secY = blockTop - line * 0.45f
            }
            minutes.strokeWidth = hours.textSize * (if (v.oled) 0.01f else 0.018f)
            rule.strokeWidth = dp(2f)
        }

        override fun draw(canvas: Canvas) {
            hours.color = c.ink; minutes.color = c.accent; rule.color = c.faded(c.track); meta.color = c.muted
            canvas.drawText(s.time.hours, hx, hy, hours)
            canvas.drawText(s.time.minutes, mx, my, minutes)
            canvas.drawLine(r0x, r0y, r1x, r1y, rule)
            canvas.drawText(dateText, metaX, metaY, meta)
            if (s.secondsLine.isNotEmpty()) { top.color = c.accent; canvas.drawText(s.secondsLine, secX, secY, top) }
        }
    }

    /** Time inside two labelled progress rings: battery outside, the server's CPU inside. */
    class Ring(v: ClockView) : Face(v) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val time = paint(sans(500), Paint.Align.CENTER)
        private val meta = paint(sans(600), Paint.Align.CENTER)
        private val outer = RectF(); private val inner = RectF()
        private var cx = 0f; private var cy = 0f; private var r = 0f; private var ri = 0f; private var ow = 0f; private var iw = 0f

        override fun layout(w: Float, h: Float) {
            cx = w / 2; cy = h / 2
            // Under OLED care the rings are hairlines, so no thick bright arc sits on the panel for hours.
            ow = min(w, h) * (if (v.oled) 0.014f else 0.035f); iw = ow * 0.6f
            r = min(w, h) * 0.46f - ow / 2
            ri = r - (if (v.oled) min(w, h) * 0.05f else ow * 1.9f)
            outer.set(cx - r, cy - r, cx + r, cy + r)
            inner.set(cx - ri, cy - ri, cx + ri, cy + ri)
            time.typeface = sans(if (v.oled) 300 else 500)
            time.textSize = 100f
            time.textSize = fit(time.measureText(s.hhmm), ri * 1.45f, ri * 0.62f)
            meta.textSize = (ri * 0.09f).coerceAtLeast(sp(11f)); meta.letterSpacing = 0.1f
            while (meta.textSize > sp(9f) && meta.measureText("BATTERY 100% · CPU 100%") > ri * 1.6f) meta.textSize *= 0.92f
        }

        override fun draw(canvas: Canvas) {
            track.color = c.faded(c.track); track.strokeWidth = ow
            canvas.drawCircle(cx, cy, r, track)
            track.strokeWidth = iw
            canvas.drawCircle(cx, cy, ri, track)
            if (s.battery >= 0) {
                arc.color = if (v.oled) DockCore.blend(c.accent, c.bg, 0.7f) else c.accent; arc.strokeWidth = ow
                canvas.drawArc(outer, -90f, 360f * s.battery / 100f, false, arc)
            }
            if (s.cpu >= 0) {
                arc.color = if (v.oled) DockCore.blend(c.secondary, c.bg, 0.7f) else c.secondary; arc.strokeWidth = iw
                canvas.drawArc(inner, -90f, 360f * s.cpu.coerceAtLeast(0.005f), false, arc)
            }
            if (s.showSeconds) {
                val a = Math.toRadians(s.second * 6.0)
                dot.color = c.ink
                canvas.drawCircle(cx + (sin(a) * r).toFloat(), cy - (cos(a) * r).toFloat(), (ow * 0.28f).coerceAtLeast(dp(2f)), dot)
            }
            time.color = c.ink
            canvas.drawText(s.hhmm, cx, cy + time.textSize * CAP / 2, time)
            meta.color = c.accent
            canvas.drawText(s.dateShort, cx, cy - time.textSize * CAP / 2 - meta.textSize * 1.4f, meta)
            // The legend names both rings, so colour alone never carries the meaning.
            meta.color = c.muted
            canvas.drawText(s.ringLine, cx, cy + time.textSize * CAP / 2 + meta.textSize * 2.2f, meta)
            if (s.secondsLine.isNotEmpty()) canvas.drawText(s.secondsLine, cx, cy + time.textSize * CAP / 2 + meta.textSize * 3.8f, meta)
        }
    }

    /** A terminal session that happens to tell the time. */
    class Mono(v: ClockView) : Face(v) {
        private val big = paint(mono(700))
        private val small = paint(mono(400))
        private val block = Paint()
        private var x = 0f; private var y1 = 0f; private var y2 = 0f; private var y3 = 0f; private var y4 = 0f; private var y5 = 0f
        private var secX = 0f; private var cursorW = 0f; private var promptW = 0f; private var colonW = 0f; private var suffixX = 0f
        private var promptText = ""; private var dateText = ""; private var maxW = 0f

        override fun layout(w: Float, h: Float) {
            // OLED care: regular weight instead of bold, in a dimmer colour (see draw).
            big.typeface = mono(if (v.oled) 400 else 700)
            big.textSize = 100f
            val chars = s.hhmm.length + (if (s.showSeconds) 3 else 0) + (if (s.time.suffix.isNotEmpty()) 1 else 0)
            val per = big.measureText("0")
            big.textSize = fit(per * chars, w * 0.88f, h * 0.36f)
            small.textSize = (big.textSize * 0.16f).coerceIn(sp(13f), sp(26f))
            val line = small.textSize * 1.6f
            val blockW = big.measureText("0") * chars
            x = ((w - blockW) / 2).coerceAtLeast(dp(16f))
            maxW = w - x - dp(8f)
            val total = line * 2 + big.textSize * CAP + line * 3
            y1 = (h - total) / 2 + small.textSize
            y2 = y1 + line * 0.9f + big.textSize * CAP + small.textSize * 0.4f
            y3 = y2 + line * 1.1f
            y4 = y3 + line
            y5 = y4 + line
            secX = x + big.measureText(s.hhmm)
            colonW = big.measureText(":")
            suffixX = secX + (if (s.showSeconds) colonW + big.measureText(s.time.seconds) else 0f) + small.measureText(" ")
            cursorW = small.measureText("0")
            promptW = small.measureText("$ ")
            promptText = ellipsize(s.prompt)
            dateText = ellipsize(s.date.lowercase())
        }

        private fun ellipsize(t: String) =
            android.text.TextUtils.ellipsize(t, android.text.TextPaint(small), maxW, android.text.TextUtils.TruncateAt.END).toString()

        override fun draw(canvas: Canvas) {
            val hero = if (v.oled) DockCore.blend(c.accent, c.bg, 0.75f) else c.accent
            small.color = c.muted
            canvas.drawText(promptText, x, y1, small)
            big.color = hero
            canvas.drawText(s.hhmm, x, y2, big)
            if (s.showSeconds) { big.color = c.secondary; canvas.drawText(":", secX, y2, big); canvas.drawText(s.time.seconds, secX + colonW, y2, big) }
            if (s.time.suffix.isNotEmpty()) { small.color = c.secondary; canvas.drawText(s.time.suffix, suffixX, y2, small) }
            small.color = c.ink
            canvas.drawText(dateText, x, y3, small)
            small.color = c.muted
            canvas.drawText(s.serverLine, x, y4, small)
            small.color = hero
            canvas.drawText("$", x, y5, small)
            // A steady block cursor: no blinking, so nothing flashes on a bedside table.
            block.color = hero
            canvas.drawRect(x + promptW, y5 - small.textSize * 0.78f, x + promptW + cursorW, y5 + small.textSize * 0.12f, block)
        }
    }
}
