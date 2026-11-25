package dev.homedroid

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/** Material motion curves. */
object Motion {
    val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val accelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    const val SHORT = 150L
    const val MEDIUM = 300L
    const val LONG = 450L

    /** False when the user turned animations off (developer options or accessibility). */
    fun enabled() = ValueAnimator.areAnimatorsEnabled()
}

/**
 * A Material 3 switch: 52×32 dp track, thumb that grows when on. Purely visual; the row that
 * holds it is the accessible, clickable element.
 */
@android.annotation.SuppressLint("ViewConstructor")
class M3Switch(ctx: Context, private val p: Palette) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var position = 0f
    private var animator: ValueAnimator? = null
    var checked = false
        private set

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setChecked(on: Boolean, animate: Boolean) {
        if (on == checked && animator == null) return
        checked = on
        animator?.cancel(); animator = null
        val target = if (on) 1f else 0f
        if (!animate || !Motion.enabled() || !isAttachedToWindow) { position = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = Motion.MEDIUM; interpolator = Motion.emphasized
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (animator === a) animator = null }
            })
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((52 * density).toInt(), (32 * density).toInt())

    override fun onDraw(canvas: Canvas) {
        val d = density
        val alphaScale = if (isEnabled) 1f else 0.38f
        val w = width.toFloat(); val h = height.toFloat()
        track.color = p.alpha(Palette.mix(p.surfaceHighest, p.primary, position), alphaScale)
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, track)
        if (position < 1f) {
            outline.color = p.alpha(p.outline, (1 - position) * alphaScale)
            canvas.drawRoundRect(d, d, w - d, h - d, h / 2, h / 2, outline)
        }
        val radius = (8 + 4 * position) * d
        val cx = (16 + 20 * position) * d
        thumb.color = p.alpha(Palette.mix(p.outline, p.onPrimary, position), alphaScale)
        canvas.drawCircle(cx, h / 2, radius, thumb)
        if (position > 0.5f) {
            check.color = p.alpha(p.primary, (position - 0.5f) * 2 * alphaScale)
            val s = 5 * d
            canvas.drawLine(cx - s * 0.8f, h / 2, cx - s * 0.2f, h / 2 + s * 0.6f, check)
            canvas.drawLine(cx - s * 0.2f, h / 2 + s * 0.6f, cx + s * 0.9f, h / 2 - s * 0.6f, check)
        }
    }
}

/** A progress ring for vitals: a track and a rounded arc that animates to [setValue]. */
@android.annotation.SuppressLint("ViewConstructor")
class RingGauge(ctx: Context, private var trackColor: Int, private var arcColor: Int) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4.5f * density }
    private val box = RectF()
    private var shown = 0f
    private var target = -1f
    private var animator: ValueAnimator? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** [fraction] 0–1. Changes under half a percent are ignored to keep the screen idle. */
    fun setValue(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        if (target >= 0 && kotlin.math.abs(v - target) < 0.005f) return
        target = v
        animator?.cancel()
        if (!Motion.enabled() || !isAttachedToWindow) { shown = v; invalidate(); return }
        animator = ValueAnimator.ofFloat(shown, v).apply {
            duration = Motion.LONG; interpolator = Motion.emphasized
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setColors(track: Int, arc: Int) {
        if (track == trackColor && arc == arcColor) return
        trackColor = track; arcColor = arc; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = minOf(4.5f * density, minOf(width, height) / 9f)
        val inset = paint.strokeWidth / 2 + density
        box.set(inset, inset, width - inset, height - inset)
        paint.color = trackColor
        canvas.drawArc(box, 0f, 360f, false, paint)
        if (shown > 0.001f) {
            paint.color = arcColor
            canvas.drawArc(box, -90f, 360f * shown, false, paint)
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}

/** A 4 dp linear progress indicator; indeterminate (a sliding segment) when [fraction] is null. */
@android.annotation.SuppressLint("ViewConstructor")
class LinearProgress(ctx: Context, private val track: Int, private val bar: Int) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var loop: ValueAnimator? = null
    var fraction: Float? = null
        set(v) { field = v; syncLoop(); invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (4 * density).toInt())

    private fun syncLoop() {
        val want = fraction == null && isAttachedToWindow && isShown && Motion.enabled()
        if (want && loop == null) {
            loop = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!want) { loop?.cancel(); loop = null }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncLoop() }
    override fun onDetachedFromWindow() { loop?.cancel(); loop = null; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); syncLoop() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncLoop() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        val gap = 4 * density
        val f = fraction
        paint.color = bar
        if (f != null) {
            val end = w * f.coerceIn(0f, 1f)
            if (end > 0) canvas.drawRoundRect(0f, 0f, end, h, r, r, paint)
            paint.color = track
            if (end + gap < w) canvas.drawRoundRect(end + gap, 0f, w, h, r, r, paint)
            return
        }
        // Indeterminate: a segment that grows and slides across, with the track around it.
        val head = Motion.standard.getInterpolation(phase) * (w + w * 0.4f) - w * 0.1f
        val tail = Motion.accelerate.getInterpolation(phase) * (w + w * 0.4f) - w * 0.4f
        val s = tail.coerceIn(0f, w); val e = head.coerceIn(0f, w)
        if (e - s > 1) canvas.drawRoundRect(s, 0f, e, h, r, r, paint)
        paint.color = track
        if (s - gap > 0) canvas.drawRoundRect(0f, 0f, s - gap, h, r, r, paint)
        if (e + gap < w) canvas.drawRoundRect(e + gap, 0f, w, h, r, r, paint)
    }
}

/** Lays children out left to right, wrapping to new lines: button and chip rows that survive large fonts. */
@android.annotation.SuppressLint("ViewConstructor")
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            widest = maxOf(widest, x - hGap)
            lineH = maxOf(lineH, c.measuredHeight)
        }
        val w = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0; var y = paddingTop; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            val left = if (rtl) r - l - paddingRight - x - c.measuredWidth else paddingLeft + x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}

/** Equal-width tiles, measured at their natural text height before matching each row. */
@android.annotation.SuppressLint("ViewConstructor")
class AdaptiveGrid(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
    private val rows = mutableListOf<List<View>>()
    private var columns = 2

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val density = resources.displayMetrics.density
        val windowDp = width / density + 2 * Space.GUTTER
        columns = when {
            windowDp >= 840 -> 4
            windowDp >= 600 -> 3
            else -> 2
        }
        // At very large text sizes, retain enough width for values and action titles.
        if (resources.configuration.fontScale >= 1.6f) columns = maxOf(1, columns - 1)
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        rows.clear(); rows.addAll(visible.chunked(columns))
        val cellWidth = ((width - (columns - 1) * gap) / columns).coerceAtLeast(0)
        var height = 0
        for (row in rows) {
            row.forEach { it.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
            val rowHeight = row.maxOf { it.measuredHeight }
            row.forEach { it.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)) }
            height += rowHeight
        }
        height += gap * (rows.size - 1).coerceAtLeast(0)
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        for (row in rows) {
            row.forEachIndexed { i, child ->
                val x = i * (child.measuredWidth + gap)
                val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - child.measuredWidth else x
                child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
            }
            y += row.first().measuredHeight + gap
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}
