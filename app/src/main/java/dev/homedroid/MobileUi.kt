package dev.homedroid

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*

/** Small native design system; no network fonts or UI runtime required. */
class MobileUi(val activity: Activity) {
    companion object {
        fun dark(activity: Activity): Boolean = when (Config(activity).appearance) {
            "light" -> false
            "dark" -> true
            else -> activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }
    private val dark = dark(activity)
    val background = Color.parseColor(if (dark) "#101A22" else "#F3F6F7")
    val surface = Color.parseColor(if (dark) "#1A2732" else "#FFFFFF")
    val ink = Color.parseColor(if (dark) "#EEF5F6" else "#142B35")
    val muted = Color.parseColor(if (dark) "#ABBFC9" else "#566D78")
    val accent = Color.parseColor(if (dark) "#78E0C5" else "#006B57")
    val tint = Color.parseColor(if (dark) "#203C3A" else "#E0F3ED")
    val warning = Color.parseColor(if (dark) "#FFBC93" else "#A13D13")
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun touch(color: Int, radius: Int = 12) = RippleDrawable(
        android.content.res.ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x30000000),
        shape(color, radius), null,
    )
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun label(value: String) = text(value, 12f, muted, true).apply {
        letterSpacing = 0.08f; setPadding(dp(2), dp(22), 0, dp(10))
    }
    fun card(): LinearLayout = column().apply {
        background = shape(surface); setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    fun button(value: String, primary: Boolean = false, action: () -> Unit): Button = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) this@MobileUi.background else accent)
        background = touch(if (primary) accent else tint)
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { action() }
    }
    fun row(title: String, detail: String, action: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = touch(surface, 16); setPadding(dp(18), dp(16), dp(18), dp(16))
        val words = column().apply {
            addView(text(title, 16f, ink, true)); addView(text(detail, 13f, muted))
        }
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("›", 28f, muted).apply { setPadding(dp(12), 0, 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        isFocusable = true; setOnClickListener { action() }
    }
    fun field(parent: LinearLayout, title: String, value: String, secret: Boolean = false, multiline: Boolean = false): EditText {
        parent.addView(text(title, 13f, muted, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        return EditText(activity).apply {
            setText(value); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
            background = shape(this@MobileUi.background, 10); setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = when {
                secret -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> android.text.InputType.TYPE_CLASS_TEXT
            }
            if (multiline) { minLines = 3; gravity = Gravity.TOP } else setSingleLine(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    fun switch(parent: LinearLayout, title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
        parent.addView(text(detail, 13f, muted).apply { setPadding(0, dp(8), 0, 0) })
        parent.addView(Switch(activity).apply {
            text = title; textSize = 16f; setTextColor(ink); minHeight = dp(52)
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, on -> changed(on) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    fun page(selected: String? = null): LinearLayout {
        val shell = column().apply { setBackgroundColor(this@MobileUi.background) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val content = column().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        content.addView(text("H O M E D R O I D", 11f, accent, true).apply { setPadding(0, 0, 0, dp(16)) })
        shell.addView(ScrollView(activity).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (selected != null) {
            val nav = LinearLayout(activity).apply { setBackgroundColor(surface); setPadding(dp(8), dp(6), dp(8), dp(6)) }
            listOf("Overview" to MainActivity::class.java, "Apps" to AppsActivity::class.java, "Settings" to SettingsActivity::class.java).forEachIndexed { index, (name, target) ->
                val item = column().apply {
                    gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = touch(if (name == selected) tint else surface, 14)
                    val color = if (name == selected) accent else muted
                    addView(NavIcon(activity, index, color), LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(text(name, 12f, color, name == selected).apply { gravity = Gravity.CENTER })
                    contentDescription = name; isFocusable = true; isSelected = name == selected
                    setOnClickListener {
                        if (name != selected) activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }
                nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            }
            shell.addView(nav)
        }
        activity.setContentView(shell); shell.requestApplyInsets()
        return content
    }
}

/** Three simple line icons keep navigation crisp at every screen density. */
private class NavIcon(activity: Activity, private val kind: Int, color: Int) : View(activity) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.scale(width / 24f, height / 24f)
        when (kind) {
            0 -> {
                val path = Path().apply { moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f); moveTo(5f, 10f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 10f); moveTo(10f, 21f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 21f) }
                canvas.drawPath(path, pen)
            }
            1 -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) canvas.drawRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, pen)
            else -> {
                for (y in listOf(5f, 12f, 19f)) canvas.drawLine(3f, y, 21f, y, pen)
                canvas.drawCircle(8f, 5f, 2.5f, pen); canvas.drawCircle(16f, 12f, 2.5f, pen); canvas.drawCircle(8f, 19f, 2.5f, pen)
            }
        }
        canvas.restore()
    }
}

open class MobileActivity : Activity() {
    protected lateinit var design: MobileUi
    private var appearance = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        appearance = Config(this).appearance
        setTheme(if (MobileUi.dark(this)) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        design = MobileUi(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (MobileUi.dark(this)) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    override fun onResume() {
        super.onResume()
        if (appearance != Config(this).appearance) recreate()
    }
}
