package dev.homedroid

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Appearance sheet for the deck, a profile or a page. Every change is written to the in-memory deck
 * and reported through [changed] so the deck behind the sheet previews it live; nothing is saved
 * until the caller's primary action, and cancelling restores the original.
 */
@SuppressLint("SetTextI18n")
internal class MacroLookEditor(
    private val ui: MobileUi,
    private val model: JSONObject,
    private val profile: JSONObject,
    private val page: JSONObject,
    private val pickAsset: (kind: String, square: Boolean, then: (String) -> Unit) -> Unit,
    private val changed: () -> Unit,
) {
    private var scope = 0
    private lateinit var body: LinearLayout
    private fun owner(): JSONObject = when (scope) { 1 -> profile; 2 -> page; else -> model }
    private fun look(): JSONObject = owner().optJSONObject("look") ?: JSONObject().also { owner().put("look", it) }
    private fun group(name: String): JSONObject = look().optJSONObject(name) ?: JSONObject().also { look().put(name, it) }

    /** Sets (or, for null, clears) one option; empty groups disappear, and any hand edit clears the theme tag. */
    private fun set(group: String, key: String, value: Any?, rebuild: Boolean = false) {
        val g = group(group)
        if (value == null) g.remove(key) else g.put(key, value)
        if (g.length() == 0) look().remove(group)
        look().remove("theme")
        if (look().length() == 0) owner().remove("look")
        changed(); if (rebuild) build()
    }
    private fun get(group: String, key: String): Any? = owner().optJSONObject("look")?.optJSONObject(group)?.opt(key)?.takeIf { it != JSONObject.NULL }
    private fun num(group: String, key: String, default: Int) = (get(group, key) as? Number)?.toInt() ?: default

    fun show(onSave: (JSONObject) -> Boolean, onCancel: () -> Unit) {
        val sheet = ui.sheet("Deck appearance", "Changes preview live behind this sheet")
        body = sheet.body
        sheet.onClosed = onCancel
        build()
        sheet.actions("Save look") { onSave(model) }
        sheet.show()
    }

    private fun heading(text: String) = body.addView(ui.text(text, TypeStyle.TitleSmall, ui.p.primary), ui.lp(top = 24))
    private fun note(text: String) = body.addView(ui.text(text, TypeStyle.BodySmall, ui.p.onSurfaceVariant), ui.lp(top = 6))

    private fun slider(title: String, group: String, key: String, min: Int, max: Int, default: Int, unit: String) {
        val value = num(group, key, default)
        val label = ui.text("$title · $value$unit", TypeStyle.LabelLarge, ui.p.onSurfaceVariant)
        body.addView(label, ui.lp(top = 14, start = 4))
        body.addView(SeekBar(ui.activity).apply {
            this.max = max - min; progress = value - min
            progressTintList = ColorStateList.valueOf(ui.p.primary); thumbTintList = ColorStateList.valueOf(ui.p.primary)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    label.text = "$title · ${p + min}$unit"; set(group, key, p + min)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }, ui.lp(-1, ui.dp(40)))
    }

    private fun colorField(title: String, group: String, key: String, fallback: String?, help: String? = null, onSet: (String?) -> Unit = { set(group, key, it) }) {
        val initial = (get(group, key) as? String) ?: fallback.orEmpty()
        val field = ui.field(body, title, initial, hint = "#RRGGBB", supporting = help)
        field.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s.toString().trim()
                if (v.isEmpty()) { field.error(null); onSet(null) }
                else if (Regex("#[0-9a-fA-F]{6}").matches(v)) { field.error(null); onSet(v) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val row = ui.row()
        listOf("#000000", "#101418", "#1B2230", "#285A78", "#67509B", "#A53E54", "#23765A", "#F1E7D0", "#FFFFFF").forEach { c ->
            row.addView(TextView(ui.activity).apply {
                text = "●"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(c)); contentDescription = "$title $c"; isFocusable = true
                background = ui.ripple(ui.shape(ui.p.surfaceHigh, 12), ui.shape(Color.WHITE, 12))
                setOnClickListener { field.edit.setText(c) }
            }, ui.lp(0, ui.dp(44), 1f, start = 1, end = 1))
        }
        body.addView(row, ui.lp(top = 6))
    }

    private fun choiceButton(title: String, options: List<Pair<String, String>>, group: String, key: String, default: String, rebuild: Boolean = false, then: ((String) -> Unit)? = null) {
        val current = (get(group, key) as? String) ?: default
        body.addView(ui.button("$title · ${options.firstOrNull { it.first == current }?.second ?: current}", ButtonKind.Tonal, Icon.ChevronDown) {
            ui.choose(title, options.map { it.second to null }, options.indexOfFirst { it.first == current }) { i ->
                val v = options[i].first
                if (then != null) then(v) else set(group, key, v, rebuild = true)
            }
        }, ui.lp(top = 12))
    }

    private fun build() {
        body.removeAllViews()
        body.addView(ui.segmented(listOf("Deck", "Profile", "Page"), scope) { scope = it; build() }.view, ui.lp(top = 8))
        note(when (scope) { 1 -> "Applies to \"${profile.optString("name")}\" and overrides the deck look."; 2 -> "Applies to the page \"${page.optString("name")}\" only."; else -> "The default look of every profile and page." })

        heading("Themes")
        val themes = ui.row()
        MacroLookCore.themes.forEach { t ->
            themes.addView(ui.chip(t.name) {
                owner().put("look", JSONObject(t.look.toString())); changed(); build()
            }, ui.lp(-2, -2, end = 6))
        }
        body.addView(android.widget.HorizontalScrollView(ui.activity).apply { isHorizontalScrollBarEnabled = false; addView(themes) })
        val tag = owner().optJSONObject("look")?.optString("theme").orEmpty()
        note(if (tag.isNotEmpty()) "Theme: ${MacroLookCore.theme(tag)?.name ?: tag}. Tweaking any option below makes it a custom look." else "Pick a theme, then tweak anything below.")
        body.addView(ui.button("Reset to app default", ButtonKind.DangerText, Icon.Refresh) { owner().remove("look"); changed(); build() }, ui.lp(top = 4))

        heading("Background")
        val types = listOf("app", "color", "gradient", "image", "oled")
        val type = (get("bg", "type") as? String) ?: "app"
        body.addView(ui.segmented(listOf("App", "Color", "Gradient", "Image", "OLED"), types.indexOf(type).coerceAtLeast(0)) { i ->
            when (types[i]) {
                "app" -> { look().remove("bg"); look().remove("theme"); if (look().length() == 0) owner().remove("look"); changed() }
                "color" -> { set("bg", "type", "color"); if (get("bg", "color") == null) set("bg", "color", "#101418") }
                "gradient" -> { set("bg", "type", "gradient"); if (get("bg", "colors") == null) set("bg", "colors", JSONArray(listOf("#1B2230", "#0E121A"))) }
                "image" -> set("bg", "type", "image")
                else -> set("bg", "type", "oled")
            }
            build()
        }.view, ui.lp(top = 8))
        when (type) {
            "color" -> colorField("Background color", "bg", "color", "#101418")
            "gradient" -> {
                val stops = (get("bg", "colors") as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("#1B2230", "#0E121A")
                fun write(list: List<String?>) { val v = list.filterNotNull(); if (v.size >= 2) set("bg", "colors", JSONArray(v)) }
                val edit = stops.toMutableList<String?>().also { while (it.size < 3) it.add(null) }
                for (i in 0 until 3) colorField(if (i < 2) "Color ${i + 1}" else "Color 3 (optional)", "bg", "colors", edit[i]) { v -> edit[i] = v; write(edit) }
                choiceButton("Angle", (0 until 8).map { (it * 45).toString() to "${it * 45}°" }, "bg", "angle", "135", then = { set("bg", "angle", it.toInt()); build() })
            }
            "image" -> {
                val image = get("bg", "image") as? String
                body.addView(ui.button(if (image == null) "Choose image from gallery" else "Change image", ButtonKind.Tonal, Icon.Image) {
                    pickAsset("image", false) { id -> set("bg", "image", id, rebuild = true) }
                }, ui.lp(top = 12))
                if (image != null) {
                    slider("Blur", "bg", "blur", 0, 25, 0, "")
                    slider("Dim", "bg", "dim", 0, 90, 0, "%")
                } else note("The image is scaled to fit and stored on this phone.")
            }
        }

        heading("Font")
        val families = listOf("system" to "System", "sans" to "Sans", "serif" to "Serif", "mono" to "Monospace", "rounded" to "Rounded", "condensed" to "Condensed", "custom" to "My font file…")
        choiceButton("Family", families, "font", "family", "system", then = { v ->
            if (v == "custom") pickAsset("font", false) { id -> look().remove("theme"); group("font").put("family", "custom").put("custom", id); changed(); build() }
            else { group("font").remove("custom"); set("font", "family", v, rebuild = true) }
        })
        if (get("font", "family") == "custom") note("Custom font file in use. Choose \"My font file…\" again to replace it.")
        choiceButton("Weight", listOf(300, 400, 500, 600, 700, 800, 900).map { it.toString() to when (it) { 300 -> "Light"; 400 -> "Regular"; 500 -> "Medium"; 600 -> "Semibold"; 700 -> "Bold"; 800 -> "Extra bold"; else -> "Black" } },
            "font", "weight", "500", then = { set("font", "weight", it.toInt(), rebuild = true) })
        slider("Text size", "font", "scale", 50, 200, 100, "%")
        choiceButton("Label position", listOf("below" to "Below the icon", "inside" to "Beside the icon", "none" to "Hidden"), "font", "position", "below")
        colorField("Text color", "font", "color", null, "Leave empty to use each key's own text color.")
        body.addView(ui.switchRow(null, "Uppercase labels", null, get("font", "upper") == true) { set("font", "upper", if (it) true else null) }.row.view, ui.lp(top = 8))

        heading("Keys")
        slider("Corner radius", "key", "radius", 0, 40, 20, " dp")
        slider("Gap", "key", "gap", 0, 24, 6, " dp")
        slider("Border", "key", "border", 0, 6, 1, " dp")
        colorField("Border color", "key", "borderColor", null, "Leave empty for an automatic edge color.")
        slider("Glow", "key", "glow", 0, 100, 0, "%")
        slider("Shadow", "key", "shadow", 0, 100, 0, "%")
        colorField("Key color", "key", "color", null, "Leave empty to use each key's own color.")
        body.addView(ui.switchRow(null, "Gradient on keys", null, get("key", "gradient") != false) { set("key", "gradient", if (it) null else false) }.row.view, ui.lp(top = 8))

        heading("Icons")
        slider("Icon size", "icon", "size", 16, 64, 30, " dp")
        colorField("Icon tint", "icon", "tint", null, "Leave empty to match the label color. Image icons are never tinted.")
    }
}
