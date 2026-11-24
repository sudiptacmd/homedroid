package dev.homedroid

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Button styles, in order of emphasis. */
enum class ButtonKind { Filled, Tonal, Outlined, Text, Danger, DangerText }

enum class CardKind { Filled, Outlined, Elevated }

enum class Haptic { Confirm, Reject, ToggleOn, ToggleOff, Tick }

/** What sits at the end of a list row. */
enum class Trailing { None, Chevron, External }

/**
 * Homedroid's native design system: Material 3 (Expressive) components built from framework
 * views only. Tokens live in UiTokens.kt, icons in Icons.kt, custom-drawn widgets in UiWidgets.kt.
 */
class MobileUi(val activity: Activity) {
    companion object {
        fun dark(ctx: Context): Boolean = when (Config(ctx).appearance) {
            "light" -> false
            "dark", "oled" -> true
            else -> ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        fun palette(ctx: Context): Palette {
            val cfg = Config(ctx)
            return Palette.create(ctx, dark(ctx), cfg.dynamicColor, oled = cfg.appearance == "oled")
        }
    }

    val p: Palette = palette(activity)
    val dark get() = p.dark
    private val density = activity.resources.displayMetrics.density

    /** Large font sizes (accessibility): grids collapse to one column. */
    val largeFont = activity.resources.configuration.fontScale >= 1.3f

    /** Lists and cards on the page background. */
    val containerColor get() = if (p.dark) p.surfaceContainer else p.surfaceLowest

    // Short names used by the control-panel wrapper.
    val background get() = p.background
    val muted get() = p.onSurfaceVariant

    fun dp(value: Int) = (value * density).toInt()
    fun dpf(value: Float) = value * density

    // --- primitives ---------------------------------------------------------------------------

    fun shape(color: Int, radius: Int = Shape.LG, strokeColor: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius.toFloat())
        if (strokeColor != null) setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), strokeColor)
    }

    /** A shape with separate top and bottom radii (dp), for grouped lists and sheets. */
    fun corners(color: Int, top: Int, bottom: Int) = GradientDrawable().apply {
        setColor(color)
        val t = dpf(top.toFloat()); val b = dpf(bottom.toFloat())
        cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
    }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** A ripple over [content], clipped to [mask] (or to the content). */
    fun ripple(content: Drawable?, mask: Drawable? = null, color: Int = p.onSurface) =
        RippleDrawable(ColorStateList.valueOf(p.alpha(color, 0.12f)), content, mask)

    /** Press feedback: a subtle scale, as in Material 3 Expressive. */
    fun pressScale(view: View, scale: Float = 0.97f) {
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, scale), PropertyValuesHolder.ofFloat(View.SCALE_Y, scale)).setDuration(Motion.SHORT))
            addState(intArrayOf(), ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)).setDuration(Motion.MEDIUM).apply {
                interpolator = Motion.emphasized
            })
        }
    }

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }

    fun TextView.style(s: TypeStyle) {
        textSize = s.size; typeface = s.typeface; letterSpacing = s.tracking
        lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, s.line, resources.displayMetrics).toInt()
    }

    fun text(value: CharSequence, style: TypeStyle = TypeStyle.BodyMedium, color: Int = p.onSurface) = TextView(activity).apply {
        text = value; style(style); setTextColor(color)
    }

    fun icon(icon: Icon, color: Int = p.onSurfaceVariant, sizeDp: Int = 24) = IconView(activity, icon, color).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** An icon centred in a tonal circle or rounded square. */
    fun iconBadge(icon: Icon, bg: Int = p.secondaryContainer, fg: Int = p.onSecondaryContainer, sizeDp: Int = 40, radius: Int = Shape.FULL): FrameLayout =
        FrameLayout(activity).apply {
            background = shape(bg, radius)
            addView(IconView(activity, icon, fg), FrameLayout.LayoutParams(dp(sizeDp * 3 / 5), dp(sizeDp * 3 / 5), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    fun lp(w: Int = -1, h: Int = -2, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end) }

    fun spacer(heightDp: Int) = View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }

    /** Tells accessibility services what kind of control a custom view is. */
    fun role(view: View, className: String, checked: Boolean? = null) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = className
                val state = (host.getTag(R.id.checked_state) as? Boolean) ?: checked
                if (state != null) { info.isCheckable = true; info.isChecked = state }
            }
        }
    }

    fun animateColor(from: Int, to: Int, apply: (Int) -> Unit) {
        if (from == to) { apply(to); return }
        if (!Motion.enabled()) { apply(to); return }
        ValueAnimator.ofArgb(from, to).apply {
            duration = Motion.MEDIUM; interpolator = Motion.standard
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }

    fun haptic(view: View, kind: Haptic) {
        val constant = when (kind) {
            Haptic.Confirm -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            Haptic.ToggleOn -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
            Haptic.ToggleOff -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** Copies [value]; Android 13+ confirms on its own, older versions get a snackbar. */
    fun copy(label: String, value: String, sensitive: Boolean = false, host: ViewGroup? = null) {
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) snackbar("$label copied", host = host)
    }

    /** Fades and lifts the children of [container] in, one after another. */
    fun enter(container: ViewGroup) {
        if (!Motion.enabled()) return
        var delay = 0L
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.alpha = 0f; v.translationY = dpf(16f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(Motion.LONG).setInterpolator(Motion.decelerate).start()
            delay = (delay + 35).coerceAtMost(280)
        }
    }

    // --- window and page structure ------------------------------------------------------------

    private class Bars(val left: Int, val top: Int, val right: Int, val bottom: Int, val ime: Int)

    @Suppress("DEPRECATION")
    private fun bars(insets: WindowInsets): Bars = if (Build.VERSION.SDK_INT >= 30) {
        val s = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        Bars(s.left, s.top, s.right, s.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
    } else {
        Bars(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight,
            insets.stableInsetBottom, insets.systemWindowInsetBottom)
    }

    /** Draws behind the system bars, with bar icons that suit the theme. */
    @Suppress("DEPRECATION")
    fun applyWindow(window: Window = activity.window, navColor: Int = Color.TRANSPARENT) {
        val light = !p.dark
        window.decorView // installs the decor, which the insets controller needs
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.setSystemBarsAppearance(
                if (light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = navColor
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }

    private var topBar: LinearLayout? = null
    private var topActions: LinearLayout? = null
    private var nav: View? = null
    private var bottomInset = 0

    /**
     * Sets up a screen: an optional top app bar whose large [title] collapses into the bar as
     * the page scrolls, the scrolling content (returned) and the bottom navigation when
     * [selected] names a tab.
     */
    fun page(selected: String? = null, title: String? = null, subtitle: String? = null, back: Boolean = false): LinearLayout {
        applyWindow()
        activity.window.setBackgroundDrawable(ColorDrawable(p.background))
        val shell = column().apply { setBackgroundColor(p.background) }
        val content = column().apply { setPadding(dp(Space.GUTTER), 0, dp(Space.GUTTER), dp(Space.XXXL)) }
        val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false; addView(content) }

        val small: TextView? = title?.let { text(it, TypeStyle.TitleLarge) }
        if (title != null && small != null) {
            val bar = row().apply { minimumHeight = dp(64); setPadding(dp(4), 0, dp(4), 0) }
            if (back) bar.addView(iconButton(Icon.ChevronRight, "Back") { activity.finish() }.apply { rotation = 180f })
            else bar.addView(View(activity), LinearLayout.LayoutParams(dp(12), 1))
            small.apply { alpha = 0f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            bar.addView(small, lp(0, -2, 1f, start = 4))
            topActions = row().also { bar.addView(it) }
            topBar = bar
            shell.addView(bar)
            content.addView(text(title, TypeStyle.HeadlineMedium).apply {
                isAccessibilityHeading = true
                setPadding(dp(4), dp(8), dp(4), if (subtitle == null) dp(Space.LG) else dp(Space.XS))
            })
            if (subtitle != null) content.addView(text(subtitle, TypeStyle.BodyMedium, p.onSurfaceVariant).apply {
                setPadding(dp(4), 0, dp(4), dp(Space.LG)); tag = "subtitle"
            })
        } else {
            content.setPadding(dp(Space.GUTTER), dp(Space.LG), dp(Space.GUTTER), dp(Space.XXXL))
        }
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Collapse the large title into the bar, and lift the bar with a container colour once scrolled.
        var lastLift = -1f
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            small?.let { s ->
                val a = ((y - dp(28)) / dpf(24f)).coerceIn(0f, 1f)
                if (s.alpha != a) s.alpha = a
            }
            val lift = (y / dpf(12f)).coerceIn(0f, 1f)
            if (lift != lastLift) {
                lastLift = lift
                topBar?.setBackgroundColor(Palette.mix(p.background, p.surfaceContainer, lift))
            }
        }

        if (selected != null) {
            val tabs = listOf(Triple("Overview", Icon.Home, MainActivity::class.java), Triple("Apps", Icon.Apps, AppsActivity::class.java),
                Triple("Settings", Icon.Settings, SettingsActivity::class.java))
            val bar = row().apply { setBackgroundColor(p.surfaceContainer); setPadding(dp(8), dp(12), dp(8), dp(12)) }
            for ((name, icon, target) in tabs) {
                val on = name == selected
                val item = column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL; minimumHeight = dp(56)
                    val pill = FrameLayout(activity).apply {
                        background = ripple(shape(if (on) p.secondaryContainer else Color.TRANSPARENT, Shape.FULL), shape(Color.WHITE, Shape.FULL))
                        isDuplicateParentStateEnabled = true
                        addView(IconView(activity, icon, if (on) p.onSecondaryContainer else p.onSurfaceVariant), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                    }
                    addView(pill, LinearLayout.LayoutParams(dp(64), dp(32)))
                    if (on && Motion.enabled()) {
                        pill.scaleX = 0.5f
                        pill.animate().scaleX(1f).setDuration(Motion.LONG).setInterpolator(Motion.emphasized).start()
                    }
                    addView(text(name, TypeStyle.LabelMedium, if (on) p.onSurface else p.onSurfaceVariant).apply {
                        gravity = Gravity.CENTER; if (on) typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, 700, false)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, lp(-2, -2, top = 4))
                    contentDescription = name; isSelected = on; isFocusable = true; isClickable = true
                    role(this, "android.widget.Button")
                    setOnClickListener {
                        if (!on) {
                            haptic(this, Haptic.Tick)
                            activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            @Suppress("DEPRECATION") activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                    }
                }
                bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            }
            nav = bar
            shell.addView(bar)
        }

        val navBar = nav
        shell.setOnApplyWindowInsetsListener { _, insets ->
            val b = bars(insets)
            shell.setPadding(b.left, if (topBar == null) b.top else 0, b.right, 0)
            topBar?.let { it.setPadding(dp(4), b.top, dp(4), 0); it.minimumHeight = dp(64) + b.top }
            bottomInset = b.bottom
            if (navBar != null) {
                navBar.setPadding(dp(8), dp(12), dp(8), dp(12) + b.bottom)
                // The keyboard covers the navigation bar; lift the content above it instead.
                shell.setPadding(b.left, shell.paddingTop, b.right, (b.ime - navBar.height).coerceAtLeast(0))
            } else {
                shell.setPadding(b.left, shell.paddingTop, b.right, maxOf(b.bottom, b.ime))
            }
            insets
        }
        activity.setContentView(shell)
        shell.requestApplyInsets()
        return content
    }

    /** An icon button in the top app bar. */
    fun topAction(icon: Icon, description: String, action: () -> Unit) =
        iconButton(icon, description, action = action).also { topActions?.addView(it) }

    /** A list section title, aligned with the rows' content. */
    fun header(title: String, supporting: String? = null): LinearLayout = column().apply {
        setPadding(dp(Space.LG), dp(Space.XXL), dp(Space.LG), dp(Space.SM))
        addView(text(title, TypeStyle.TitleSmall, p.primary).apply { isAccessibilityHeading = true })
        if (supporting != null) addView(text(supporting, TypeStyle.BodySmall, p.onSurfaceVariant), lp(top = 2))
    }

    // --- containers ---------------------------------------------------------------------------

    /** A column whose visible children get Material 3 Expressive segmented corners and 2 dp gaps. */
    inner class GroupLayout : LinearLayout(activity) {
        private var signature = ""
        init { orientation = VERTICAL }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            restyle()
            super.onMeasure(widthSpec, heightSpec)
        }
        fun item(view: View): View {
            view.setTag(R.id.group_item, true)
            addView(view, LayoutParams(-1, -2))
            return view
        }
        fun restyle() {
            val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE && it.getTag(R.id.group_item) == true }
            val sig = visible.joinToString { System.identityHashCode(it).toString() }
            if (sig == signature) return
            signature = sig
            visible.forEachIndexed { i, v ->
                val top = if (i == 0) Shape.XL - 8 else Shape.XS
                val bottom = if (i == visible.lastIndex) Shape.XL - 8 else Shape.XS
                val bg = corners(containerColor, top, bottom)
                v.background = if (v.isClickable) ripple(bg, corners(Color.WHITE, top, bottom)) else bg
                (v.layoutParams as LayoutParams).topMargin = if (i == 0) 0 else dp(Space.XXS)
            }
        }
    }

    fun group(parent: LinearLayout? = null, title: String? = null, supporting: String? = null): GroupLayout {
        if (title != null) parent?.addView(header(title, supporting))
        return GroupLayout().also { parent?.addView(it, lp()) }
    }

    fun card(kind: CardKind = CardKind.Filled, color: Int? = null, padding: Int = Space.XL, radius: Int = Shape.XL - 4): LinearLayout = column().apply {
        val fill = color ?: when (kind) { CardKind.Filled -> containerColor; CardKind.Outlined -> p.background; CardKind.Elevated -> p.surfaceLow }
        background = shape(fill, radius, if (kind == CardKind.Outlined) p.outlineVariant else null)
        if (kind == CardKind.Elevated) elevation = dpf(2f)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        layoutParams = lp(bottom = Space.MD)
    }

    /** Lays [items] out in a grid of [columns] equal-width cells. */
    fun grid(parent: LinearLayout, items: List<View>, columns: Int = if (largeFont) 1 else 2, gap: Int = Space.SM) {
        items.chunked(columns).forEach { line ->
            val r = row().apply { gravity = Gravity.TOP }
            line.forEachIndexed { i, v ->
                r.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(gap) })
            }
            repeat(columns - line.size) { r.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(gap) }) }
            parent.addView(r, lp(bottom = gap))
        }
    }

    // --- list rows ----------------------------------------------------------------------------

    inner class ListRow(val view: LinearLayout, val title: TextView, val supporting: TextView, val value: TextView, val end: LinearLayout) {
        fun setTitle(s: CharSequence) = title.setTextIfChanged(s)
        fun setSupporting(s: CharSequence?) {
            supporting.setVisibleIfChanged(!s.isNullOrEmpty())
            if (s != null) supporting.setTextIfChanged(s)
        }
        fun setValue(s: CharSequence?) {
            value.setVisibleIfChanged(!s.isNullOrEmpty())
            if (s != null) value.setTextIfChanged(s)
        }
        fun setEnabled(on: Boolean) {
            if (view.isEnabled == on) return
            view.isEnabled = on; view.alpha = if (on) 1f else 0.38f
        }
        fun setVisible(on: Boolean) = view.setVisibleIfChanged(on)
    }

    /**
     * A list row: leading icon in a tonal circle, title, supporting text, and a trailing
     * value / chevron / custom view. Tappable when [action] is given.
     */
    fun listRow(icon: Icon?, title: String, supporting: String? = null, trailing: Trailing = Trailing.Chevron,
                value: String? = null, action: (() -> Unit)? = null): ListRow {
        val v = row().apply {
            minimumHeight = dp(if (supporting == null) 56 else 72)
            setPadding(dp(Space.LG), dp(Space.MD), dp(if (trailing == Trailing.None) Space.LG else Space.MD), dp(Space.MD))
        }
        if (icon != null) v.addView(iconBadge(icon), lp(dp(40), dp(40), end = Space.LG))
        val words = column()
        val t = text(title, TypeStyle.BodyLarge)
        val s = text(supporting.orEmpty(), TypeStyle.BodyMedium, p.onSurfaceVariant).apply { visibility = if (supporting == null) View.GONE else View.VISIBLE }
        words.addView(t); words.addView(s)
        v.addView(words, lp(0, -2, 1f))
        val end = row()
        val valueText = text(value.orEmpty(), TypeStyle.BodyMedium, p.onSurfaceVariant).apply {
            visibility = if (value == null) View.GONE else View.VISIBLE; maxLines = 2; gravity = Gravity.END; maxWidth = dp(160); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        end.addView(valueText, lp(-2, -2, start = Space.SM))
        when (trailing) {
            Trailing.Chevron -> end.addView(icon(Icon.ChevronRight, p.onSurfaceVariant, 20), lp(dp(20), dp(20), start = Space.XS))
            Trailing.External -> end.addView(icon(Icon.OpenExternal, p.onSurfaceVariant, 18), lp(dp(18), dp(18), start = Space.SM))
            Trailing.None -> {}
        }
        v.addView(end)
        if (action != null) {
            v.isClickable = true; v.isFocusable = true
            v.setOnClickListener { action() }
            role(v, "android.widget.Button")
        }
        return ListRow(v, t, s, valueText, end)
    }

    inner class SwitchRow(val row: ListRow, val toggle: M3Switch, private var changed: (Boolean) -> Unit) {
        val view get() = row.view
        var checked: Boolean
            get() = toggle.checked
            set(v) { set(v, animate = false) }
        fun set(on: Boolean, animate: Boolean = true) {
            toggle.setChecked(on, animate); view.setTag(R.id.checked_state, on)
        }
        fun setEnabled(on: Boolean) { row.setEnabled(on); toggle.isEnabled = on; toggle.invalidate() }
        fun onChange(block: (Boolean) -> Unit) { changed = block }
        internal fun flip() {
            val on = !toggle.checked
            set(on)
            haptic(view, if (on) Haptic.ToggleOn else Haptic.ToggleOff)
            changed(on)
        }
    }

    /** A row whose whole area toggles an M3 switch; announced as a switch with its state. */
    fun switchRow(icon: Icon?, title: String, supporting: String?, checked: Boolean, changed: (Boolean) -> Unit): SwitchRow {
        val r = listRow(icon, title, supporting, Trailing.None)
        r.view.setPadding(r.view.paddingLeft, r.view.paddingTop, dp(Space.LG), r.view.paddingBottom)
        val toggle = M3Switch(activity, p)
        r.end.addView(toggle, lp(-2, -2, start = Space.MD))
        val sw = SwitchRow(r, toggle, changed)
        sw.set(checked, animate = false)
        r.view.isClickable = true; r.view.isFocusable = true
        role(r.view, "android.widget.Switch")
        r.view.setOnClickListener { sw.flip() }
        return sw
    }

    // --- buttons ------------------------------------------------------------------------------

    /** A Material 3 button: 40 dp tall (56 dp when [large]) inside a 48 dp touch target. */
    fun button(label: String, kind: ButtonKind = ButtonKind.Filled, icon: Icon? = null, large: Boolean = false, action: () -> Unit): UiButton =
        UiButton(activity, this, large).apply {
            text = label
            this.icon = icon
            paint(kind)
            setOnClickListener { action() }
            layoutParams = lp(-2, -2)
        }

    fun colorsFor(kind: ButtonKind): Triple<Int, Int, Int?> = when (kind) {
        ButtonKind.Filled -> Triple(p.primary, p.onPrimary, null)
        ButtonKind.Tonal -> Triple(p.secondaryContainer, p.onSecondaryContainer, null)
        ButtonKind.Outlined -> Triple(Color.TRANSPARENT, p.primary, p.outlineVariant)
        ButtonKind.Text -> Triple(Color.TRANSPARENT, p.primary, null)
        ButtonKind.Danger -> Triple(p.errorContainer, p.onErrorContainer, null)
        ButtonKind.DangerText -> Triple(Color.TRANSPARENT, p.error, null)
    }

    /** A 48 dp icon button; [filled] gives it a tonal container. */
    fun iconButton(icon: Icon, description: String, tonal: Boolean = false, color: Int? = null, action: () -> Unit): FrameLayout =
        FrameLayout(activity).apply {
            val fg = color ?: if (tonal) p.onSecondaryContainer else p.onSurfaceVariant
            val circle = InsetDrawable(oval(if (tonal) p.secondaryContainer else Color.TRANSPARENT), dp(4))
            background = RippleDrawable(ColorStateList.valueOf(p.alpha(fg, 0.12f)), circle, InsetDrawable(oval(Color.WHITE), dp(4)))
            addView(IconView(activity, icon, fg), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = description; isClickable = true; isFocusable = true
            role(this, "android.widget.Button")
            setOnClickListener { action() }
            tooltipText = description
        }

    inner class Segmented(val view: LinearLayout, private val items: List<TextView>, private val pick: (Int) -> Unit) {
        var selected = -1
            private set
        fun select(index: Int, notify: Boolean = false) {
            if (index == selected) return
            selected = index
            items.forEachIndexed { i, t -> paintSegment(t, i, items.size, i == index) }
            if (notify) pick(index)
        }
    }

    private fun paintSegment(t: TextView, i: Int, n: Int, on: Boolean) {
        val r = Shape.FULL
        val left = if (i == 0) r else 0
        val right = if (i == n - 1) r else 0
        fun seg(color: Int) = GradientDrawable().apply {
            setColor(color)
            val l = dpf(left.toFloat()); val rr = dpf(right.toFloat())
            cornerRadii = floatArrayOf(l, l, rr, rr, rr, rr, l, l)
            setStroke(dp(1), p.outline)
        }
        val fg = if (on) p.onSecondaryContainer else p.onSurface
        t.background = RippleDrawable(ColorStateList.valueOf(p.alpha(fg, 0.12f)),
            InsetDrawable(seg(if (on) p.secondaryContainer else Color.TRANSPARENT), 0, dp(4), 0, dp(4)),
            InsetDrawable(seg(Color.WHITE), 0, dp(4), 0, dp(4)))
        t.setTextColor(fg)
        t.setCompoundDrawablesRelative(if (on) IconDrawable(Icon.Check, fg, dp(18)).apply { setBounds(0, 0, dp(18), dp(18)) } else null, null, null, null)
        t.setTag(R.id.checked_state, on)
    }

    /** Segmented buttons for a single choice among two to four short options. */
    fun segmented(options: List<String>, selected: Int, pick: (Int) -> Unit): Segmented {
        val bar = row()
        lateinit var seg: Segmented
        val items = options.mapIndexed { i, label ->
            TextView(activity).apply {
                text = label; style(TypeStyle.LabelLarge); gravity = Gravity.CENTER
                minHeight = dp(48); setPadding(dp(12), 0, dp(12), 0); compoundDrawablePadding = dp(6)
                isClickable = true; isFocusable = true
                role(this, "android.widget.RadioButton")
                setOnClickListener { if (seg.selected != i) { haptic(this, Haptic.Tick); seg.select(i, notify = true) } }
                bar.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = -dp(1) })
            }
        }
        seg = Segmented(bar, items, pick)
        seg.select(selected)
        return seg
    }

    /** An assist chip: 32 dp tall inside a 48 dp touch target. */
    fun chip(label: String, icon: Icon? = null, action: () -> Unit): TextView = TextView(activity).apply {
        text = label; style(TypeStyle.LabelLarge); setTextColor(p.onSurface); gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48); setPadding(dp(if (icon != null) 8 else 16), 0, dp(16), 0)
        val inset = dp(8)
        background = RippleDrawable(ColorStateList.valueOf(p.alpha(p.onSurface, 0.12f)),
            InsetDrawable(shape(Color.TRANSPARENT, Shape.SM, p.outlineVariant), 0, inset, 0, inset),
            InsetDrawable(shape(Color.WHITE, Shape.SM), 0, inset, 0, inset))
        if (icon != null) {
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelative(IconDrawable(icon, p.primary, dp(18)).apply { setBounds(0, 0, dp(18), dp(18)) }, null, null, null)
        }
        isClickable = true; isFocusable = true
        role(this, "android.widget.Button")
        setOnClickListener { action() }
    }

    // --- status -------------------------------------------------------------------------------

    fun containerOf(t: Tone) = when (t) {
        Tone.Neutral -> p.surfaceHighest; Tone.Primary -> p.primaryContainer; Tone.Success -> p.successContainer
        Tone.Warning -> p.warningContainer; Tone.Error -> p.errorContainer
    }

    fun onContainerOf(t: Tone) = when (t) {
        Tone.Neutral -> p.onSurfaceVariant; Tone.Primary -> p.onPrimaryContainer; Tone.Success -> p.onSuccessContainer
        Tone.Warning -> p.onWarningContainer; Tone.Error -> p.onErrorContainer
    }

    fun accentOf(t: Tone) = when (t) {
        Tone.Neutral -> p.outline; Tone.Primary -> p.primary; Tone.Success -> p.success
        Tone.Warning -> p.warning; Tone.Error -> p.error
    }

    inner class Pill(val view: LinearLayout, private val dot: GradientDrawable, private val label: TextView, private val bg: GradientDrawable) {
        private var tone: Tone? = null
        fun set(value: String, t: Tone) {
            label.setTextIfChanged(value)
            if (t == tone) return
            val old = tone
            tone = t
            if (old == null) { bg.setColor(containerOf(t)); dot.setColor(accentOf(t)); label.setTextColor(onContainerOf(t)); return }
            animateColor(containerOf(old), containerOf(t)) { bg.setColor(it) }
            animateColor(accentOf(old), accentOf(t)) { dot.setColor(it) }
            animateColor(onContainerOf(old), onContainerOf(t)) { label.setTextColor(it) }
        }
    }

    /** A status pill: a coloured dot and a short label on a tonal container. */
    fun pill(value: String = "", tone: Tone = Tone.Neutral): Pill {
        val bg = shape(containerOf(tone), Shape.FULL)
        val dot = oval(accentOf(tone))
        val v = row().apply { background = bg; setPadding(dp(10), dp(4), dp(12), dp(4)) }
        v.addView(View(activity).apply { background = dot; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(dp(8), dp(8), end = 6))
        val label = text(value, TypeStyle.LabelMedium, onContainerOf(tone)).apply { maxLines = 1 }
        v.addView(label)
        return Pill(v, dot, label, bg).also { it.set(value, tone) }
    }

    /** A tonal message card: icon, title, body and an optional action. */
    inner class Banner(val view: LinearLayout, val title: TextView, val body: TextView, private val badge: IconView, private val bg: GradientDrawable, private val action: TextView?) {
        private var tone: Tone? = null
        fun set(t: Tone, icon: Icon, heading: String, message: String?) {
            title.setTextIfChanged(heading)
            body.setVisibleIfChanged(!message.isNullOrEmpty()); if (message != null) body.setTextIfChanged(message)
            badge.setIcon(icon)
            if (t != tone) {
                tone = t
                bg.setColor(containerOf(t)); title.setTextColor(onContainerOf(t)); body.setTextColor(onContainerOf(t)); badge.color = onContainerOf(t)
                action?.setTextColor(if (t == Tone.Neutral) p.primary else onContainerOf(t))
            }
        }
    }

    fun banner(tone: Tone, icon: Icon, title: String, body: String? = null, actionLabel: String? = null, action: (() -> Unit)? = null): Banner {
        val bg = shape(containerOf(tone), Shape.XL - 8)
        val v = column().apply { background = bg; setPadding(dp(Space.LG), dp(Space.LG), dp(Space.LG), dp(if (actionLabel != null) Space.XS else Space.LG)); layoutParams = lp(bottom = Space.MD) }
        val top = row().apply { gravity = Gravity.TOP }
        val badge = IconView(activity, icon, onContainerOf(tone))
        top.addView(badge, lp(dp(24), dp(24), end = Space.MD))
        val words = column()
        val t = text(title, TypeStyle.TitleSmall, onContainerOf(tone))
        val b = text(body.orEmpty(), TypeStyle.BodyMedium, onContainerOf(tone)).apply { visibility = if (body == null) View.GONE else View.VISIBLE }
        words.addView(t); words.addView(b, lp(top = 2))
        top.addView(words, lp(0, -2, 1f))
        v.addView(top)
        var act: TextView? = null
        if (actionLabel != null && action != null) {
            act = button(actionLabel, ButtonKind.Text) { action() }.apply { setTextColor(onContainerOf(tone)) }
            v.addView(act, lp(-2, -2).apply { gravity = Gravity.END })
        }
        return Banner(v, t, b, badge, bg, act).also { it.set(tone, icon, title, body) }
    }

    /** Empty and error states: an icon, a title, a sentence and an optional action. */
    fun state(icon: Icon, title: String, body: String, actionLabel: String? = null, tone: Tone = Tone.Neutral, action: (() -> Unit)? = null): LinearLayout =
        column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(Space.XXL), dp(Space.XXL), dp(Space.XXL), dp(Space.XXL))
            background = shape(containerColor, Shape.XL - 8)
            val badgeBg = if (tone == Tone.Neutral) p.surfaceHighest else containerOf(tone)
            addView(iconBadge(icon, badgeBg, if (tone == Tone.Neutral) p.onSurfaceVariant else onContainerOf(tone), 56))
            addView(text(title, TypeStyle.TitleMedium).apply { gravity = Gravity.CENTER }, lp(-2, -2, top = Space.LG))
            addView(text(body, TypeStyle.BodyMedium, p.onSurfaceVariant).apply { gravity = Gravity.CENTER }, lp(-2, -2, top = Space.XS))
            if (actionLabel != null && action != null) addView(button(actionLabel, ButtonKind.Tonal) { action() }, lp(-2, -2, top = Space.LG))
            layoutParams = lp(bottom = Space.MD)
        }

    /** A loading state with a spinner. */
    fun loading(label: String): LinearLayout = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(Space.XXL), 0, dp(Space.XXL))
        addView(ProgressBar(activity).apply { indeterminateTintList = ColorStateList.valueOf(p.primary); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(dp(40), dp(40)))
        addView(text(label, TypeStyle.BodyMedium, p.onSurfaceVariant).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, lp(-2, -2, top = Space.MD))
    }

    /** A vitals tile: a ring gauge with an icon inside, a value, a label and a caption. */
    inner class GaugeTile(val view: LinearLayout, private val ring: RingGauge?, private val badge: IconView, private val value: TextView, private val caption: TextView, private val label: String) {
        private var tone = Tone.Primary
        fun set(valueText: String, captionText: String, fraction: Float?, t: Tone = Tone.Primary, spoken: String? = null) {
            value.setTextIfChanged(valueText)
            caption.setTextIfChanged(captionText)
            if (fraction != null) ring?.setValue(fraction)
            if (t != tone) {
                tone = t
                val arc = if (t == Tone.Primary) p.primary else accentOf(t)
                ring?.setColors(p.alpha(arc, 0.18f), arc)
                badge.color = arc
            }
            val description = "$label, ${spoken ?: valueText}, $captionText"
            if (view.contentDescription != description) view.contentDescription = description
        }
    }

    fun gaugeTile(icon: Icon, label: String, ring: Boolean = true): GaugeTile {
        val v = column().apply {
            background = shape(containerColor, Shape.XL - 8)
            setPadding(dp(Space.LG), dp(Space.LG), dp(Space.LG), dp(Space.LG))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true
        }
        val top = row()
        val holder = FrameLayout(activity)
        val gauge = if (ring) RingGauge(activity, p.alpha(p.primary, 0.18f), p.primary) else null
        if (gauge != null) holder.addView(gauge, FrameLayout.LayoutParams(dp(48), dp(48)))
        else holder.background = shape(p.secondaryContainer, Shape.FULL)
        val badge = IconView(activity, icon, if (ring) p.primary else p.onSecondaryContainer)
        holder.addView(badge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        top.addView(holder, lp(dp(48), dp(48)))
        top.addView(View(activity), lp(0, 1, 1f))
        top.addView(text(label, TypeStyle.LabelMedium, p.onSurfaceVariant).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; gravity = Gravity.END })
        v.addView(top)
        val value = text("—", TypeStyle.HeadlineSmall).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val caption = text("", TypeStyle.BodySmall, p.onSurfaceVariant).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        v.addView(value, lp(top = Space.MD)); v.addView(caption)
        return GaugeTile(v, gauge, badge, value, caption, label)
    }

    /** A compact action tile: icon on a tonal square, title, subtitle and an optional badge. */
    fun actionTile(icon: Icon, title: String, subtitle: String, badge: String? = null, tone: Tone = Tone.Primary, action: (() -> Unit)?): LinearLayout =
        column().apply {
            val bg = shape(containerColor, Shape.XL - 8)
            background = if (action != null) ripple(bg, shape(Color.WHITE, Shape.XL - 8)) else bg
            setPadding(dp(Space.LG), dp(Space.LG), dp(Space.LG), dp(Space.LG))
            val top = row().apply { gravity = Gravity.TOP }
            top.addView(iconBadge(icon, containerOf(tone), onContainerOf(tone), 44, Shape.MD + 2))
            top.addView(View(activity), lp(0, 1, 1f))
            if (badge != null) top.addView(pill(badge, Tone.Neutral).view)
            addView(top)
            addView(text(title, TypeStyle.TitleSmall), lp(top = Space.MD))
            addView(text(subtitle, TypeStyle.BodySmall, p.onSurfaceVariant), lp(top = 2))
            contentDescription = listOfNotNull(title, subtitle, badge).joinToString(", ")
            if (action != null) {
                isClickable = true; isFocusable = true; role(this, "android.widget.Button")
                pressScale(this)
                setOnClickListener { action() }
            } else {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true
            }
        }

    // --- text fields --------------------------------------------------------------------------

    inner class Field(val view: LinearLayout, val edit: EditText, private val label: TextView, private val support: TextView, private val help: String?) {
        val value: String get() = edit.text.toString()
        fun error(message: String?) {
            val on = message != null
            support.setVisibleIfChanged(on || help != null)
            support.text = message ?: help.orEmpty()
            support.setTextColor(if (on) p.error else p.onSurfaceVariant)
            label.setTextColor(if (on) p.error else p.onSurfaceVariant)
            edit.background = fieldBackground(on)
            if (on) { edit.requestFocus(); haptic(edit, Haptic.Reject); support.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE }
        }
    }

    private fun fieldBackground(error: Boolean) = StateListDrawable().apply {
        val base = p.surfaceHighest
        addState(intArrayOf(android.R.attr.state_focused), shape(base, Shape.MD, if (error) p.error else p.primary, 2f))
        addState(intArrayOf(), shape(base, Shape.MD, if (error) p.error else null, 2f))
    }

    /** A filled text field with a label above and supporting or error text below. */
    fun field(parent: LinearLayout, label: String, value: String, secret: Boolean = false, multiline: Boolean = false,
              numeric: Boolean = false, supporting: String? = null, mono: Boolean = false, hint: String? = null): Field {
        val box = column().apply { layoutParams = lp(top = Space.LG) }
        val title = text(label, TypeStyle.LabelLarge, p.onSurfaceVariant)
        box.addView(title, lp(bottom = Space.SM - 2, start = 4))
        val edit = EditText(activity).apply {
            setText(value); style(TypeStyle.BodyLarge); setTextColor(p.onSurface); setHintTextColor(p.alpha(p.onSurfaceVariant, 0.7f))
            if (hint != null) this.hint = hint
            background = fieldBackground(false)
            setPadding(dp(Space.LG), dp(14), dp(if (secret) 52 else Space.LG), dp(14))
            minHeight = dp(56)
            highlightColor = p.alpha(p.primary, 0.3f)
            textCursorDrawable = GradientDrawable().apply { setColor(p.primary); setSize(dp(2), dp(20)) }
            inputType = when {
                secret && numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiline) { minLines = 4; gravity = Gravity.TOP } else isSingleLine = true
            // setSingleLine resets the transformation, which would show secrets in plain text.
            if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
            if (mono) { typeface = android.graphics.Typeface.MONOSPACE; textSize = 13f }
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = null
            setOnFocusChangeListener { _, focused -> if (title.currentTextColor != p.error) title.setTextColor(if (focused) p.primary else p.onSurfaceVariant) }
        }
        title.labelFor = View.generateViewId().also { edit.id = it }
        if (secret) {
            val frame = FrameLayout(activity)
            frame.addView(edit, FrameLayout.LayoutParams(-1, -2))
            var shown = false
            lateinit var eye: FrameLayout
            eye = iconButton(Icon.Eye, "Show $label".lowercase().replaceFirstChar { it.uppercase() }) {
                shown = !shown
                val at = edit.selectionEnd
                edit.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
                edit.setSelection(at.coerceIn(0, edit.length()))
                eye.contentDescription = (if (shown) "Hide " else "Show ") + label.lowercase()
                (eye.getChildAt(0) as IconView).alpha = if (shown) 1f else 0.7f
            }
            frame.addView(eye, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) })
            box.addView(frame, lp())
        } else box.addView(edit, lp())
        val support = text(supporting.orEmpty(), TypeStyle.BodySmall, p.onSurfaceVariant).apply { visibility = if (supporting == null) View.GONE else View.VISIBLE }
        box.addView(support, lp(top = Space.XS, start = Space.LG, end = Space.LG))
        parent.addView(box)
        return Field(box, edit, title, support, supporting)
    }

    // --- sheets -------------------------------------------------------------------------------

    /**
     * A modal bottom sheet (a Dialog with a rounded top, drag handle and slide-in motion) for
     * editors, choices and confirmations.
     */
    inner class Sheet(title: String?, subtitle: String? = null) {
        val dialog = Dialog(activity)
        val frame = FrameLayout(activity)
        private val scrim = View(activity)
        private val panel = column()
        val body = column()
        private val inner = column()
        private var closing = false
        /** Space under the sheet's content (navigation bar or keyboard), for its snackbars. */
        val bottomOffset get() = panel.paddingBottom
        var onClosed: (() -> Unit)? = null

        init {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            scrim.setBackgroundColor(p.alpha(Color.BLACK, 0.45f)); scrim.alpha = 0f
            scrim.setOnClickListener { close() }
            scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))

            panel.background = corners(p.surfaceLow, Shape.XL, 0)
            panel.isClickable = true
            val handleZone = FrameLayout(activity).apply {
                addView(View(activity).apply { background = shape(p.alpha(p.onSurfaceVariant, 0.4f), Shape.FULL) },
                    FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER))
                contentDescription = "Close sheet"; isClickable = true; isFocusable = true
                role(this, "android.widget.Button")
                setOnClickListener { close() }
            }
            dragToClose(handleZone)
            panel.addView(handleZone, lp(-1, dp(36)))
            inner.setPadding(dp(Space.XXL), 0, dp(Space.XXL), dp(Space.XL))
            if (title != null) inner.addView(text(title, TypeStyle.HeadlineSmall).apply { isAccessibilityHeading = true }, lp(top = Space.XS))
            if (subtitle != null) inner.addView(text(subtitle, TypeStyle.BodyMedium, p.onSurfaceVariant), lp(top = Space.SM))
            inner.addView(body, lp(top = if (title != null || subtitle != null) Space.SM else 0))
            panel.addView(ScrollView(activity).apply { addView(inner); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }, lp())
            val maxWidth = dp(640)
            frame.addView(panel, FrameLayout.LayoutParams(minOf(activity.resources.displayMetrics.widthPixels, maxWidth).let { if (it == maxWidth) it else -1 }, -2,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

            dialog.setContentView(frame)
            dialog.window?.let { w ->
                w.setLayout(-1, -1)
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setWindowAnimations(0)
                @Suppress("DEPRECATION")
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                applyWindow(w, if (Build.VERSION.SDK_INT >= 30) Color.TRANSPARENT else p.surfaceLow)
                if (Build.VERSION.SDK_INT < 30) {
                    // API 29 lets the window fit the system bars; the panel's colour fills the bar.
                    @Suppress("DEPRECATION")
                    w.decorView.systemUiVisibility = if (!p.dark) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
                }
            }
            frame.setOnApplyWindowInsetsListener { _, insets ->
                val b = bars(insets)
                (panel.layoutParams as FrameLayout.LayoutParams).let { it.topMargin = b.top + dp(48); it.leftMargin = b.left; it.rightMargin = b.right; panel.layoutParams = it }
                panel.setPadding(0, 0, 0, if (Build.VERSION.SDK_INT >= 30) maxOf(b.bottom, b.ime) else 0)
                insets
            }
            dialog.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); true } else code == KeyEvent.KEYCODE_BACK
            }
            if (Build.VERSION.SDK_INT >= 33) {
                dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
            }
            dialog.setOnShowListener { openSheets++; showing += dialog }
            frame.tag = this
            dialog.setOnDismissListener { openSheets = (openSheets - 1).coerceAtLeast(0); showing -= dialog; onClosed?.invoke() }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun dragToClose(handle: View) {
            var startY = 0f
            var moved = false
            handle.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = e.rawY; moved = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = (e.rawY - startY).coerceAtLeast(0f)
                        if (dy > dp(4)) moved = true
                        panel.translationY = dy
                        scrim.alpha = 1f - (dy / panel.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        else if (panel.translationY > panel.height * 0.25f) close()
                        else { panel.animate().translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.emphasized).start(); scrim.animate().alpha(1f).start() }
                        true
                    }
                    else -> false
                }
            }
        }

        /** Primary and secondary actions; [primary] returns true to close the sheet. */
        fun actions(primary: String, kind: ButtonKind = ButtonKind.Filled, secondary: String? = "Cancel", onPrimary: () -> Boolean) {
            val r = row().apply { gravity = Gravity.END }
            if (secondary != null) r.addView(button(secondary, ButtonKind.Text) { close() }, lp(0, -2, 1f, end = Space.SM))
            r.addView(button(primary, kind) {
                val ok = try { onPrimary() } catch (e: Exception) { this@MobileUi.snackbar(e.message ?: "Something went wrong", host = frame); false }
                if (ok) close()
            }, lp(0, -2, 1f))
            inner.addView(r, lp(top = Space.XXL))
        }

        /** A single full-width "Done". */
        fun done(label: String = "Done") {
            inner.addView(button(label, ButtonKind.Tonal) { close() }.apply { }, lp(top = Space.XXL))
        }

        fun snackbar(message: String) = this@MobileUi.snackbar(message, host = frame)

        fun show(): Sheet {
            dialog.show()
            panel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    panel.viewTreeObserver.removeOnPreDrawListener(this)
                    if (Motion.enabled()) {
                        panel.translationY = panel.height.toFloat()
                        panel.animate().translationY(0f).setDuration(Motion.LONG).setInterpolator(Motion.decelerate).start()
                        scrim.animate().alpha(1f).setDuration(Motion.MEDIUM).start()
                    } else scrim.alpha = 1f
                    return true
                }
            })
            return this
        }

        fun close() {
            if (closing) return
            closing = true
            if (!Motion.enabled()) { dialog.dismiss(); return }
            scrim.animate().alpha(0f).setDuration(Motion.MEDIUM).start()
            panel.animate().translationY(panel.height.toFloat()).setDuration(Motion.MEDIUM).setInterpolator(Motion.accelerate)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { if (dialog.isShowing) dialog.dismiss() }
                }).start()
        }
    }

    fun sheet(title: String?, subtitle: String? = null) = Sheet(title, subtitle)

    /** Bottom sheets showing now; their touches don't reach the activity. */
    var openSheets = 0
        private set
    private val showing = mutableSetOf<Dialog>()

    /** Closes every sheet at once, so none leaks when the activity goes away. */
    fun dismissSheets() {
        showing.toList().forEach { runCatching { it.dismiss() } }
        showing.clear()
    }

    /** A sheet with one row per option; the current one is checked. */
    fun choose(title: String, options: List<Pair<String, String?>>, selected: Int, subtitle: String? = null, pick: (Int) -> Unit) {
        val s = sheet(title, subtitle)
        options.forEachIndexed { i, (label, detail) ->
            val on = i == selected
            val r = row().apply {
                minimumHeight = dp(56); setPadding(dp(Space.LG), dp(Space.SM), dp(Space.LG), dp(Space.SM))
                val bg = shape(if (on) p.secondaryContainer else Color.TRANSPARENT, Shape.LG)
                background = ripple(bg, shape(Color.WHITE, Shape.LG))
                val words = column()
                words.addView(text(label, TypeStyle.BodyLarge, if (on) p.onSecondaryContainer else p.onSurface))
                if (detail != null) words.addView(text(detail, TypeStyle.BodyMedium, if (on) p.onSecondaryContainer else p.onSurfaceVariant))
                addView(words, lp(0, -2, 1f))
                if (on) addView(icon(Icon.Check, p.onSecondaryContainer, 20).apply { layoutParams = lp(dp(20), dp(20), start = Space.MD) })
                isClickable = true; isFocusable = true
                role(this, "android.widget.RadioButton", on)
                setOnClickListener { haptic(this, Haptic.Tick); s.close(); if (!on) pick(i) }
            }
            s.body.addView(r, lp(top = if (i == 0) Space.SM else Space.XXS))
        }
        s.show()
    }

    /** A confirmation sheet with a message and optional checkboxes (both, unlike AlertDialog). */
    fun confirm(title: String, message: String?, confirmLabel: String, destructive: Boolean = false,
                checks: List<String> = emptyList(), onConfirm: (BooleanArray) -> Unit): Sheet {
        val s = sheet(title)
        if (message != null) s.body.addView(text(message, TypeStyle.BodyLarge, p.onSurfaceVariant), lp(top = Space.XS))
        val state = BooleanArray(checks.size)
        checks.forEachIndexed { i, label ->
            s.body.addView(CheckBox(activity).apply {
                text = label; style(TypeStyle.BodyLarge); setTextColor(p.onSurface)
                buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.primary, p.onSurfaceVariant))
                minHeight = dp(48); setPadding(dp(Space.SM), dp(Space.SM), 0, dp(Space.SM))
                setOnCheckedChangeListener { _, on -> state[i] = on }
            }, lp(top = Space.SM))
        }
        s.actions(confirmLabel, if (destructive) ButtonKind.Danger else ButtonKind.Filled) { onConfirm(state); true }
        return s.show()
    }

    // --- snackbars ----------------------------------------------------------------------------

    private var snack: View? = null

    /** A short message at the bottom of the screen (above the navigation bar), with an optional action. */
    fun snackbar(message: String, actionLabel: String? = null, host: ViewGroup? = null, long: Boolean = false, action: (() -> Unit)? = null) {
        val parent = host ?: activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        snack?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        val bar = row().apply {
            background = shape(p.inverseSurface, Shape.MD)
            elevation = dpf(6f)
            minimumHeight = dp(48)
            setPadding(dp(Space.LG), dp(Space.XS), dp(if (actionLabel != null) Space.XS else Space.LG), dp(Space.XS))
            addView(text(message, TypeStyle.BodyMedium, p.inverseOnSurface).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(0, dp(10), 0, dp(10))
            }, lp(0, -2, 1f))
        }
        val dismiss = Runnable {
            if (Motion.enabled()) bar.animate().alpha(0f).translationY(dpf(12f)).setDuration(Motion.SHORT)
                .withEndAction { (bar.parent as? ViewGroup)?.removeView(bar) }.start()
            else (bar.parent as? ViewGroup)?.removeView(bar)
            if (snack === bar) snack = null
        }
        if (actionLabel != null && action != null) {
            bar.addView(button(actionLabel, ButtonKind.Text) { action(); bar.removeCallbacks(dismiss); dismiss.run() }.apply { setTextColor(p.inversePrimary) })
        }
        val navHeight = if (host == null) nav?.height ?: bottomInset else (host.tag as? Sheet)?.bottomOffset ?: 0
        parent.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(Space.MD), 0, dp(Space.MD), navHeight + dp(Space.MD))
        })
        snack = bar
        if (Motion.enabled()) {
            bar.alpha = 0f; bar.translationY = dpf(24f)
            bar.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.decelerate).start()
        }
        bar.postDelayed(dismiss, if (long || actionLabel != null) 6000 else 3500)
    }
}

/** A Material 3 button; dims itself when disabled. */
@SuppressLint("ViewConstructor")
class UiButton(ctx: Context, private val ui: MobileUi, private val large: Boolean) : Button(ctx) {
    var icon: Icon? = null
        set(v) { field = v; applyIcon() }
    private var fg = 0

    init {
        isAllCaps = false; stateListAnimator = null
        with(ui) { style(if (large) TypeStyle.TitleMedium else TypeStyle.LabelLarge) }
        minHeight = ui.dp(if (large) 64 else 48); minimumHeight = minHeight
        minWidth = ui.dp(48); minimumWidth = minWidth
        gravity = Gravity.CENTER
        compoundDrawablePadding = ui.dp(8)
        ui.pressScale(this, 0.96f)
    }

    fun paint(kind: ButtonKind) {
        val (bg, fg, stroke) = ui.colorsFor(kind)
        paint(bg, fg, stroke)
    }

    fun paint(bg: Int, fg: Int, stroke: Int? = null) {
        this.fg = fg
        val inset = ui.dp(if (large) 4 else 4)
        val radius = Shape.FULL
        val content = InsetDrawable(ui.shape(bg, radius, stroke), 0, inset, 0, inset)
        val mask = InsetDrawable(ui.shape(Color.WHITE, radius), 0, inset, 0, inset)
        background = RippleDrawable(ColorStateList.valueOf(ui.p.alpha(fg, 0.14f)), content, mask)
        setTextColor(fg)
        val padH = ui.dp(if (large) 28 else if (bg == Color.TRANSPARENT && stroke == null) 12 else 24)
        setPadding(if (icon != null) padH - ui.dp(6) else padH, ui.dp(if (large) 14 else 10), padH, ui.dp(if (large) 14 else 10))
        applyIcon()
    }

    private fun applyIcon() {
        val i = icon
        val size = ui.dp(if (large) 22 else 18)
        setCompoundDrawablesRelative(i?.let { IconDrawable(it, fg, size).apply { setBounds(0, 0, size, size) } }, null, null, null)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }
}

/**
 * Base for the phone screens: theme, window and the app lock. Screens refresh in
 * [onResumeUnlocked], which is skipped while the lock screen is showing.
 */
open class MobileActivity : Activity() {
    protected lateinit var design: MobileUi
    private var appearance = ""
    private var dynamic = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val cfg = Config(this)
        appearance = cfg.appearance
        dynamic = cfg.dynamicColor
        setTheme(if (MobileUi.dark(this)) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        design = MobileUi(this)
        design.applyWindow()
    }

    override fun onResume() {
        super.onResume()
        if (!AppLock.requireUnlock(this)) return
        val cfg = Config(this)
        if (appearance != cfg.appearance || dynamic != cfg.dynamicColor) { recreate(); return }
        onResumeUnlocked()
    }

    override fun onDestroy() {
        if (::design.isInitialized) design.dismissSheets()
        super.onDestroy()
    }

    /** onResume, once the app is unlocked. */
    protected open fun onResumeUnlocked() {}
}
