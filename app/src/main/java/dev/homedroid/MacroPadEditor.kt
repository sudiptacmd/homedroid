package dev.homedroid

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native, draft-only editor. A cancelled sheet never mutates the saved deck. */
// Matches MobileUi's programmatic English UI; labels include user-defined names.
@SuppressLint("SetTextI18n")
internal class MacroPadEditor(
    private val ctx: Context,
    private val ui: MobileUi,
    private val computers: JSONArray,
    private val model: JSONObject,
    private val profile: JSONObject,
) {
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun choice(parent: LinearLayout, title: String, values: List<Pair<String, String>>, initial: String): () -> String {
        var selected = initial
        lateinit var button: TextView
        fun caption() = "$title · ${values.firstOrNull { it.first == selected }?.second ?: selected}"
        button = ui.button(caption(), ButtonKind.Tonal, Icon.ChevronDown) {
            val sheet = ui.sheet(title)
            values.forEach { (value, label) ->
                sheet.body.addView(ui.button(label, if (selected == value) ButtonKind.Filled else ButtonKind.Text) {
                    selected = value; button.text = caption(); sheet.close()
                }, ui.lp(top = 4))
            }
            sheet.done(); sheet.show()
        }
        parent.addView(button, ui.lp(top = 12))
        return { selected }
    }
    private fun color(parent: LinearLayout, title: String, initial: String): () -> String {
        val field = ui.field(parent, title, initial, hint = "#RRGGBB")
        val colors = listOf("#285A78", "#1F6FEB", "#67509B", "#A53E54", "#806331", "#23765A", "#263142", "#FFFFFF")
        val row = ui.row()
        colors.forEach { value ->
            row.addView(TextView(ctx).apply {
                text = "●"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(value))
                contentDescription = "$title $value"; isFocusable = true
                background = ui.ripple(ui.shape(ui.p.surfaceHigh, 12), ui.shape(Color.WHITE, 12))
                setOnClickListener { field.edit.setText(value); ui.haptic(this, Haptic.Tick) }
            }, ui.lp(0, ui.dp(48), 1f, start = 2, end = 2))
        }
        parent.addView(row, ui.lp(top = 8))
        return { field.value.trim() }
    }
    private fun icon(parent: LinearLayout, initial: String): () -> String {
        var selected = initial
        lateinit var button: TextView
        button = ui.button("Icon · $selected", ButtonKind.Tonal, MacroIcons.get(selected)) {
            val sheet = ui.sheet("Choose an icon", "Line icons adapt to your key's text color")
            MacroIcons.all.entries.chunked(4).forEach { icons ->
                val row = ui.row()
                icons.forEach { (name, glyph) ->
                    row.addView(ui.column().apply {
                        gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(8))
                        background = ui.ripple(ui.shape(if (name == selected) ui.p.primaryContainer else ui.p.surfaceHigh, 16), ui.shape(Color.WHITE, 16))
                        addView(ui.icon(glyph, ui.p.onSurface, 26))
                        addView(ui.text(name, TypeStyle.LabelSmall).apply { gravity = Gravity.CENTER; maxLines = 1 }, ui.lp(top = 6))
                        contentDescription = name; isFocusable = true; ui.role(this, "android.widget.Button")
                        setOnClickListener { selected = name; button.text = "Icon · $name"; sheet.close() }
                    }, ui.lp(0, ui.dp(76), 1f, start = 3, end = 3))
                }
                sheet.body.addView(row, ui.lp(top = 6))
            }
            sheet.done(); sheet.show()
        }
        parent.addView(button, ui.lp(top = 12))
        return { selected }
    }
    fun show(original: JSONObject?, row: Int, col: Int, commit: (JSONObject?) -> Boolean) {
        val sheet = ui.sheet(if (original == null) "Create a key" else "Edit key", "Make it yours · changes apply when you save")
        val draft = original?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", "b-${UUID.randomUUID()}").put("row", row).put("col", col)
        val label = ui.field(sheet.body, "Label", draft.optString("label", "New key"))
        val selectedIcon = icon(sheet.body, draft.optString("icon", "star"))
        val emoji = ui.field(sheet.body, "Emoji (optional)", if (draft.isNull("emoji")) "" else draft.optString("emoji"), supporting = "An emoji replaces the line icon. Clear it to use the icon.")
        val background = color(sheet.body, "Key color", draft.optString("color", "#285A78"))
        val foreground = color(sheet.body, "Text color", draft.optString("textColor", "#FFFFFF"))
        val enabled = choice(sheet.body, "Button", listOf("yes" to "Enabled", "no" to "Disabled"), if (draft.optBoolean("disabled")) "no" else "yes")
        val action = actionForm(sheet.body, draft.optJSONObject("action") ?: JSONObject().put("kind", "media").put("key", "play_pause"))
        val targets = listOf("" to "All connected computers") + objects(computers).map { it.getString("id") to it.getString("name") }
        val existingTarget = draft.optString("computer").takeUnless { draft.isNull("computer") }.orEmpty()
            .ifEmpty { draft.optJSONObject("action")?.let { if (it.isNull("computer")) "" else it.optString("computer") }.orEmpty() }
        val target = choice(sheet.body, "Computer", targets + if (existingTarget.isNotEmpty() && targets.none { it.first == existingTarget }) listOf(existingTarget to "Unpaired computer ($existingTarget)") else emptyList(), existingTarget)
        val toggle = draft.optJSONObject("toggle")
        val hasToggle = choice(sheet.body, "Toggle appearance", listOf("no" to "Off", "yes" to "After successful presses"), if (toggle == null) "no" else "yes")
        val toggleLabel = ui.field(sheet.body, "Toggle label", toggle?.optString("label").orEmpty())
        val toggleIcon = icon(sheet.body, toggle?.optString("icon") ?: "check")
        val toggleColor = color(sheet.body, "Toggle color", toggle?.optString("color") ?: "#23765A")
        sheet.body.addView(ui.text("Toggle appearance is local feedback, not the computer's actual mute or playback state.", TypeStyle.BodySmall, ui.p.onSurfaceVariant), ui.lp(top = 12))
        if (original != null) sheet.body.addView(ui.button("Remove key", ButtonKind.DangerText, Icon.Trash) {
            val confirm = ui.sheet("Remove this key?", original.optString("label"))
            confirm.actions("Remove", ButtonKind.Danger) { if (commit(null)) { sheet.close(); true } else false }; confirm.show()
        }, ui.lp(top = 16))
        sheet.actions("Save key") {
            val a = action().apply { remove("computer") }
            draft.put("label", label.value).put("icon", selectedIcon()).put("emoji", emoji.value.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("color", background()).put("textColor", foreground()).put("action", a)
                .put("computer", target().takeIf { it.isNotEmpty() } ?: JSONObject.NULL).put("disabled", enabled() == "no")
            if (hasToggle() == "yes") draft.put("toggle", (toggle?.let { JSONObject(it.toString()) } ?: JSONObject()).put("label", toggleLabel.value).put("icon", toggleIcon()).put("color", toggleColor()))
            else draft.remove("toggle")
            MacroPadsCore.validateAction(a)
            commit(draft)
        }
        sheet.show()
    }

    private val names = mapOf("hotkey" to "Keyboard shortcut", "key" to "Single key", "text" to "Type text", "media" to "Media control",
        "open" to "Open URL, file or folder", "launch" to "Launch an app", "command" to "Run a command", "mouse" to "Mouse input",
        "sequence" to "Sequence of actions", "page" to "Go to page", "profile" to "Switch profile", "back" to "Previous page", "http" to "HTTP request from phone")

    /** Each form returns its draft; nested sequence sheets use the very same friendly forms. */
    private fun actionForm(parent: LinearLayout, initial: JSONObject, depth: Int = 0): () -> JSONObject {
        var kind = initial.getString("kind")
        val form = ui.column()
        var read: () -> JSONObject = { initial }
        lateinit var picker: TextView
        fun draw(seed: JSONObject) { form.removeAllViews(); read = fields(form, seed, depth) }
        picker = ui.button("Action · ${names[kind]}", ButtonKind.Tonal, Icon.Bolt) {
            val sheet = ui.sheet("What should this key do?")
            MacroPadsCore.kinds.filter { (depth == 0 || it !in MacroPadsCore.localKinds) && (depth < 3 || it != "sequence") }.forEach { next ->
                sheet.body.addView(ui.button(names.getValue(next), ButtonKind.Text) {
                    if (kind != next) { kind = next; picker.text = "Action · ${names[kind]}"; draw(JSONObject().put("kind", kind)) }
                    sheet.close()
                }, ui.lp(top = 4))
            }
            sheet.done(); sheet.show()
        }
        parent.addView(picker, ui.lp(top = 20)); parent.addView(form)
        draw(initial)
        return { read().also { MacroPadsCore.validateAction(it, depth) } }
    }
    private fun fields(parent: LinearLayout, a: JSONObject, depth: Int): () -> JSONObject {
        val kind = a.getString("kind")
        fun base() = JSONObject().put("kind", kind)
        fun field(title: String, key: String, default: String = "", multiline: Boolean = false) =
            ui.field(parent, title, a.optString(key, default), multiline = multiline)
        when (kind) {
            "hotkey" -> {
                val keys = a.optJSONArray("keys") ?: JSONArray()
                val selected = (0 until keys.length()).map { keys.getString(it) }.toMutableSet()
                val mods = listOf("ctrl", "shift", "alt", "meta")
                parent.addView(ui.text("Shortcut modifiers", TypeStyle.LabelLarge), ui.lp(top = 16))
                val row = ui.row()
                mods.forEach { mod ->
                    lateinit var chip: TextView
                    fun label() = (if (mod in selected) "✓ " else "") + mod
                    chip = ui.chip(label()) { if (!selected.add(mod)) selected.remove(mod); chip.text = label(); chip.isSelected = mod in selected }
                    chip.isSelected = mod in selected; row.addView(chip, ui.lp(0, -2, 1f, end = 4))
                }
                parent.addView(row, ui.lp(top = 8))
                val key = ui.field(parent, "Key", selected.filter { it !in mods }.joinToString(","), supporting = "For example m, enter, f5, or printscreen. Separate extra keys with commas.")
                return { base().put("keys", JSONArray(mods.filter { it in selected } + key.value.split(',').map { it.trim() }.filter { it.isNotEmpty() })) }
            }
            "key" -> {
                val key = field("Key name", "key", "enter"); val repeat = field("Repeat · 1–100", "repeat", "1")
                return { base().put("key", key.value.trim()).put("repeat", repeat.value.toInt()) }
            }
            "media" -> {
                val media = choice(parent, "Media key", MacroPadsCore.mediaKeys.map { it to it.replace('_', ' ') }, a.optString("key", "play_pause"))
                return { base().put("key", media()) }
            }
            "text" -> { val text = field("Text to type", "text", multiline = true); return { base().put("text", text.value) } }
            "open" -> { val target = field("URL, file or folder", "target"); return { base().put("target", target.value) } }
            "launch" -> {
                val path = field("Application path on the computer", "path")
                val args = a.optJSONArray("args") ?: JSONArray()
                val arguments = ui.field(parent, "Arguments · one per line", (0 until args.length()).joinToString("\n") { args.getString(it) }, multiline = true)
                return { base().put("path", path.value).put("args", JSONArray(if (arguments.value.isEmpty()) emptyList<String>() else arguments.value.lines())) }
            }
            "command" -> {
                parent.addView(ui.text("The computer must enable Allow commands before this action can run.", TypeStyle.BodySmall, ui.p.onSurfaceVariant), ui.lp(top = 12))
                val command = field("Command", "command", multiline = true)
                val shell = choice(parent, "Run through shell", listOf("no" to "No", "yes" to "Yes"), if (a.optBoolean("shell")) "yes" else "no")
                return { base().put("command", command.value).put("shell", shell() == "yes") }
            }
            "mouse" -> {
                val action = choice(parent, "Mouse action", listOf("click", "right_click", "move", "scroll").map { it to it.replace('_', ' ') }, a.optString("action", "click"))
                val x = field("X · for move", "x", "0"); val y = field("Y · for move", "y", "0"); val dy = field("Scroll amount", "dy", "1")
                return { base().put("action", action()).apply {
                    if (action() == "move") { put("x", x.value.toInt()); put("y", y.value.toInt()) }
                    if (action() == "scroll") put("dy", dy.value.toInt())
                } }
            }
            "page", "profile" -> {
                val entries = if (kind == "page") profile.getJSONArray("pages") else model.getJSONArray("profiles")
                val values = objects(entries).map { it.getString("id") to it.getString("name") }
                val selected = choice(parent, "Destination", values, a.optString(kind, values.first().first))
                return { base().put(kind, selected()) }
            }
            "back" -> {
                parent.addView(ui.text("Returns to the previous page or profile visited using a navigation key.", TypeStyle.BodySmall), ui.lp(top = 12))
                return ::base
            }
            "http" -> {
                val method = choice(parent, "HTTP method", listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").map { it to it }, a.optString("method", "POST"))
                val url = field("HTTP or HTTPS URL", "url"); val body = field("Body (optional JSON)", "body", multiline = true)
                return { base().put("method", method()).put("url", url.value.trim()).put("body", body.value) }
            }
            "sequence" -> {
                val steps = objects(a.optJSONArray("steps") ?: JSONArray()).map { JSONObject(it.toString()) }.toMutableList()
                val list = ui.column(); parent.addView(list)
                lateinit var redraw: () -> Unit
                fun edit(index: Int?) {
                    val sheet = ui.sheet(if (index == null) "Add sequence step" else "Edit step ${index + 1}")
                    val original = index?.let { steps[it] } ?: JSONObject().put("kind", "key").put("key", "enter")
                    val delay = ui.field(sheet.body, "Delay before step · milliseconds", original.optInt("delayMs", 0).toString(), numeric = true)
                    val action = actionForm(sheet.body, original, depth + 1)
                    sheet.actions("Save step") {
                        val updated = action().put("delayMs", delay.value.toInt()); MacroPadsCore.validateAction(updated, depth + 1)
                        if (index == null) { require(steps.size < 32) { "Use at most 32 steps" }; steps.add(updated) } else steps[index] = updated
                        redraw(); true
                    }; sheet.show()
                }
                redraw = {
                    list.removeAllViews()
                    steps.forEachIndexed { index, step ->
                        val row = ui.row()
                        row.addView(ui.button("${index + 1}. ${names[step.getString("kind")]} · ${step.optInt("delayMs")} ms", ButtonKind.Tonal) { edit(index) }, ui.lp(0, -2, 1f))
                        row.addView(ui.iconButton(Icon.ChevronDown, "Move step down") { if (index < steps.lastIndex) { val next = steps.removeAt(index); steps.add(index + 1, next); redraw() } })
                        row.addView(ui.iconButton(Icon.Trash, "Remove step") { steps.removeAt(index); redraw() })
                        list.addView(row, ui.lp(top = 8))
                    }
                }
                redraw()
                parent.addView(ui.button("Add step", ButtonKind.Text, Icon.Plus) { edit(null) }, ui.lp(top = 8))
                return { base().put("steps", JSONArray(steps)) }
            }
            else -> error("Unknown action kind")
        }
    }
}
