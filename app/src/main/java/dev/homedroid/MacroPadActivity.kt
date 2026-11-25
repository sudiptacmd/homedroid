package dev.homedroid

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** Full-screen native deck; uses the app's palette, sheets, type and Path icon family. */
class MacroPadActivity : MobileActivity() {
    companion object {
        fun open(ctx: Context) { ctx.startActivity(Intent(ctx, MacroPadActivity::class.java).apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
    }
    private lateinit var pads: MacroPads
    private lateinit var desktop: DesktopLink
    private lateinit var model: JSONObject
    private lateinit var looks: MacroLook
    private lateinit var curLook: MacroLookCore.Look
    /** Color for text drawn straight on a custom deck background. */
    private var onBackground = 0
    /** True while the look editor is open: its unsaved edits live in [model] and must survive onResume. */
    private var lookDraft = false
    private class PendingPick(val kind: String, val square: Boolean, val then: (String) -> Unit)
    private var pendingPick: PendingPick? = null
    private var profileId = ""
    private var pageId = ""
    private var editing = false
    private val toggled = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val history = ArrayDeque<Pair<String, String>>()
    private val handler = Handler(Looper.getMainLooper())
    private var connection: TextView? = null
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val refresh = object : Runnable {
        override fun run() { updateConnections(); handler.postDelayed(this, 2000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pads = MacroPads.get(this); desktop = DesktopLink.get(this); model = pads.read(); looks = MacroLook(this, pads.assets)
        profileId = savedInstanceState?.getString("profile").orEmpty(); pageId = savedInstanceState?.getString("page").orEmpty()
        editing = savedInstanceState?.getBoolean("editing") ?: false
        toggled.addAll(savedInstanceState?.getStringArrayList("toggled").orEmpty())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }
    override fun onResumeUnlocked() { if (!lookDraft) model = pads.read(); render(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("profile", profileId); outState.putString("page", pageId); outState.putBoolean("editing", editing)
        outState.putStringArrayList("toggled", ArrayList(toggled)); super.onSaveInstanceState(outState)
    }
    private fun objects(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun profile(): JSONObject = objects(model.getJSONArray("profiles")).firstOrNull { it.getString("id") == profileId }
        ?: model.getJSONArray("profiles").getJSONObject(0).also { profileId = it.getString("id") }
    private fun page(): JSONObject = objects(profile().getJSONArray("pages")).firstOrNull { it.getString("id") == pageId }
        ?: profile().getJSONArray("pages").getJSONObject(0).also { pageId = it.getString("id") }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun save(): Boolean = try { pads.save(model); render(); true } catch (e: Exception) { model = pads.read(); toast(e.message ?: "Could not save deck"); false }
    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    private fun render() {
        val p = profile(); val pg = page(); val ui = design
        curLook = MacroLookCore.resolve(model.optJSONObject("look"), p.optJSONObject("look"), pg.optJSONObject("look")); val look = curLook
        val backdrop = looks.backdrop(look)
        // Header text sits straight on the deck background, so it follows that background's brightness.
        val luminance = MacroLookCore.backgroundLuminance(look, look.bgImage?.let { looks.bitmap(it, 64) }?.let(looks::luminance))
        onBackground = if (backdrop == null) ui.p.onSurfaceVariant else if (luminance < 0.5f) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()
        val strong = if (backdrop == null) ui.p.onSurface else onBackground
        val root = ui.column().apply {
            if (backdrop != null) background = backdrop else setBackgroundColor(ui.p.background)
            setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(6)); clipChildren = false; clipToPadding = false
        }
        val header = ui.row()
        header.addView(ui.iconButton(Icon.ChevronRight, "Exit MacroPads", color = if (backdrop == null) null else strong) { finish() }.apply { rotation = 180f })
        header.addView(ui.text(p.getString("name"), TypeStyle.TitleLarge, strong).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, IconDrawable(Icon.ChevronDown, if (backdrop == null) ui.p.primary else strong, ui.dp(20)), null)
            compoundDrawablePadding = ui.dp(8); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Switch profile: ${p.getString("name")}"; setOnClickListener { profiles() }
        }, ui.lp(0, -2, 1f, start = 4))
        val tint = if (backdrop == null) null else strong
        header.addView(ui.iconButton(if (editing) Icon.Check else Icon.Tune, if (editing) "Finish editing" else "Edit deck", color = tint) { editing = !editing; render() })
        header.addView(ui.iconButton(Icon.Swatch, "Deck appearance", color = tint) { appearance() })
        header.addView(ui.iconButton(Icon.Dock, "Computers and pairing", color = tint) { computers() })
        header.addView(ui.iconButton(Icon.Settings, "Deck settings", color = tint) { settings() })
        root.addView(header, ui.lp(-1, ui.dp(54)))
        connection = ui.text("", TypeStyle.LabelSmall, if (backdrop == null) ui.p.onSurfaceVariant else onBackground).apply {
            setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(4)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { computers() }
        }.also { root.addView(it) }
        val grid = ui.column().apply { clipChildren = false; clipToPadding = false }; val rows = p.getInt("rows"); val cols = p.getInt("cols")
        val buttons = objects(pg.getJSONArray("buttons"))
        // A wide grid on a tall screen would stretch keys into slabs: show the same keys, in the
        // same reading order, with the long side running down the screen instead.
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val shownCols = if (portrait && cols > rows) rows else cols
        val shownRows = (rows * cols + shownCols - 1) / shownCols
        val gap = look.gap / 2
        for (r in 0 until shownRows) {
            val line = ui.row().apply { clipChildren = false; clipToPadding = false }
            for (c in 0 until shownCols) {
                val index = r * shownCols + c
                if (index >= rows * cols) { line.addView(android.view.View(this), ui.lp(0, -1, 1f, start = gap, end = gap, top = gap, bottom = gap)); continue }
                val row = index / cols; val col = index % cols
                val b = buttons.firstOrNull { it.getInt("row") == row && it.getInt("col") == col }
                val key = key(b, row, col)
                line.addView(key, ui.lp(0, -1, 1f, start = gap, end = gap, top = gap, bottom = gap))
            }
            grid.addView(line, ui.lp(-1, 0, 1f))
        }
        root.addView(grid, ui.lp(-1, 0, 1f))
        val dots = ui.row().apply { gravity = Gravity.CENTER }
        val pages = objects(p.getJSONArray("pages"))
        pages.forEach { item ->
            val selected = item.getString("id") == pageId
            dots.addView(ui.text(if (selected) "●  ${item.getString("name")}" else "○", TypeStyle.LabelMedium,
                if (backdrop != null) onBackground else if (selected) ui.p.primary else ui.p.onSurfaceVariant).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(10), 0, ui.dp(10), 0)
                contentDescription = "Page ${item.getString("name")}"; isSelected = selected
                setOnClickListener { pageId = item.getString("id"); render() }
            }, ui.lp(-2, ui.dp(40)))
        }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dots) })
        root.setOnApplyWindowInsetsListener { _, insets ->
            val cutout = insets.displayCutout
            root.setPadding(ui.dp(10) + (cutout?.safeInsetLeft ?: 0), cutout?.safeInsetTop ?: 0,
                ui.dp(10) + (cutout?.safeInsetRight ?: 0), ui.dp(6) + (cutout?.safeInsetBottom ?: 0)); insets
        }
        setContentView(root); immersive(); updateConnections()
    }
    private fun updateConnections() {
        connection?.text = if (editing) "EDIT MODE  ·  Tap to edit · Hold and drag to move" else {
            val computers = objects(desktop.list())
            if (computers.isEmpty()) "●  Pair a computer to get started" else computers.joinToString("    ") {
                "${if (it.getBoolean("online")) "●" else "○"} ${it.getString("name")} · ${if (it.getBoolean("online")) "Online" else "Offline"}"
            }
        }
    }
    private fun key(button: JSONObject?, row: Int, col: Int): View {
        val ui = design; val id = button?.getString("id").orEmpty(); val look = curLook
        val state = button?.optJSONObject("toggle")?.takeIf { id in toggled }
        fun value(name: String, default: String) = state?.optString(name)?.takeIf { it.isNotEmpty() }
            ?: button?.optString(name)?.takeIf { it.isNotEmpty() && it != "null" } ?: default
        // A look's key color replaces every key's own color, except a toggled key that sets its own.
        val color = state?.optString("color")?.takeIf { it.isNotEmpty() }?.let(Color::parseColor) ?: look.keyColor
            ?: Color.parseColor(value("color", if (ui.dark) "#263142" else "#E4E9F2"))
        val fg = if (button == null) (if (look.themedBackground) onBackground else ui.p.onSurfaceVariant)
            else look.fontColor ?: Color.parseColor(value("textColor", "#FFFFFF"))
        val label = value("label", if (editing) "Add key" else "")
        val inside = look.position == "inside"; val align = button?.optString("align")?.takeIf { it in MacroLookCore.aligns } ?: "center"
        val gravity = when (align) { "start" -> Gravity.START or Gravity.CENTER_VERTICAL; "end" -> Gravity.END or Gravity.CENTER_VERTICAL; else -> Gravity.CENTER }
        val face = looks.typeface(look)
        val key = ui.column().apply {
            orientation = if (inside) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            this.gravity = gravity; setPadding(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4)); clipChildren = false
            val shape = if (look.gradient) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.mix(color, Color.WHITE, 0.09f), color, Palette.mix(color, Color.BLACK, 0.13f)))
                else GradientDrawable().apply { setColor(color) }
            shape.cornerRadius = ui.dp(look.radius).toFloat()
            if (look.border > 0) shape.setStroke(ui.dp(look.border).coerceAtLeast(1), look.borderColor ?: Palette.mix(color, Color.WHITE, 0.22f))
            background = ui.ripple(shape, ui.shape(Color.WHITE, look.radius), fg)
            val lift = maxOf(look.shadow * 0.1f, look.glow * 0.16f)
            if (lift > 0f) {
                elevation = ui.dpf(lift)
                val tone = if (look.glow > 0) look.borderColor ?: Palette.mix(color, Color.WHITE, 0.5f) else Color.BLACK
                outlineSpotShadowColor = tone; outlineAmbientShadowColor = tone
            }
            alpha = if (button?.optBoolean("disabled") == true) 0.45f else 1f
            contentDescription = label + if (button?.optBoolean("disabled") == true) ", disabled until configured" else ""
            isFocusable = button != null || editing; ui.role(this, "android.widget.Button")
        }
        if (button != null || editing) {
            val emoji = value("emoji", ""); val tint = look.iconTint ?: fg
            val imageId = value("image", ""); val picture = if (button != null && imageId.isNotEmpty()) looks.bitmap(imageId, 256) else null
            if (picture != null) key.addView(android.widget.ImageView(this).apply {
                setImageBitmap(picture); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(look.iconSize), ui.dp(look.iconSize)))
            else if (emoji.isNotEmpty()) key.addView(ui.text(emoji, TypeStyle.HeadlineSmall, fg).apply { this.gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            else key.addView(ui.icon(if (button == null) Icon.Plus else MacroIcons.get(value("icon", "grid")), tint, look.iconSize).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            val fixed = button?.optInt("textSize", 0) ?: 0
            key.addView(ui.text(if (look.upper) label.uppercase() else label, TypeStyle.LabelMedium, fg).apply {
                this.gravity = if (inside) Gravity.CENTER_VERTICAL or (if (align == "end") Gravity.END else Gravity.START) else when (align) { "start" -> Gravity.START; "end" -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
                maxLines = 2; typeface = face; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (fixed > 0) setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fixed.toFloat() * look.scale)
                else setAutoSizeTextTypeUniformWithConfiguration(maxOf(4, (8 * look.scale).toInt()), maxOf(6, (13 * look.scale).toInt()), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                visibility = if (look.position == "none" || label.isEmpty()) View.GONE else View.VISIBLE
            }, if (inside) ui.lp(0, -2, 1f, start = 6) else ui.lp(-1, ui.dp(34), top = 5))
            key.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val availableH = (bottom - top - key.paddingTop - key.paddingBottom).coerceAtLeast(1)
                val availableW = (right - left - key.paddingLeft - key.paddingRight).coerceAtLeast(1)
                val glyph = key.getChildAt(0)
                val caption = key.getChildAt(1) as TextView
                val labelled = caption.visibility == View.VISIBLE
                val glyphSize = (if (inside && labelled) minOf(ui.dp(look.iconSize), (availableH * 0.8f).toInt(), availableW / 2)
                    else minOf(ui.dp(look.iconSize), (availableH * (if (labelled) 0.43f else 0.8f)).toInt(), availableW)).coerceAtLeast(1)
                if (glyph.layoutParams.height != glyphSize) {
                    glyph.layoutParams = LinearLayout.LayoutParams(if (glyph is TextView) -2 else glyphSize, glyphSize)
                    if (glyph is TextView) glyph.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, glyphSize * 0.8f)
                    caption.layoutParams = if (inside) ui.lp(0, -2, 1f, start = 6) else ui.lp(-1, (availableH - glyphSize - ui.dp(2)).coerceIn(1, ui.dp(34)), top = 2)
                }
            }
            ui.pressScale(key, 0.94f)
            key.setOnClickListener {
                ui.haptic(key, Haptic.Tick)
                if (editing || button == null) edit(button, row, col) else press(button, key)
            }
            key.setOnLongClickListener {
                ui.haptic(key, Haptic.Confirm)
                if (editing && button != null) key.startDragAndDrop(ClipData.newPlainText("key", id), View.DragShadowBuilder(key), id, 0)
                else { editing = true; render(); edit(button, row, col) }
                true
            }
        }
        key.setOnDragListener { view, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> editing && event.localState is String
                DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.55f; true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; true }
                DragEvent.ACTION_DROP -> {
                    val source = objects(page().getJSONArray("buttons")).firstOrNull { it.getString("id") == event.localState }
                    if (source != null && source !== button) {
                        button?.put("row", source.getInt("row"))?.put("col", source.getInt("col"))
                        source.put("row", row).put("col", col); save()
                    }; true
                }
                else -> true
            }
        }
        return key
    }
    private fun press(button: JSONObject, view: View) {
        val id = button.getString("id")
        if (!pending.add(id)) return
        view.alpha = 0.65f
        pads.press(profileId, pageId, id).whenComplete { result, failure -> runOnUiThread {
            pending.remove(id)
            if (isDestroyed || isFinishing) return@runOnUiThread
            view.alpha = 1f
            val error = failure?.message ?: result?.error
            val old = view.background
            if (error != null) {
                view.background = design.shape(design.p.error, curLook.radius)
                if (Motion.enabled()) ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -10f, 10f, -7f, 7f, 0f).setDuration(320).start()
                design.haptic(view, Haptic.Reject); toast(error)
            } else {
                view.background = design.shape(Color.rgb(38, 143, 96), curLook.radius); design.haptic(view, Haptic.Confirm)
                if (button.has("toggle")) { if (!toggled.add(id)) toggled.remove(id) }
                result?.navigation?.let(::navigate)
            }
            handler.postDelayed({ if (!isDestroyed) { view.background = old; if (button.has("toggle") && error == null) render() } }, 420)
        } }
    }
    private fun navigate(a: JSONObject) {
        when (a.getString("kind")) {
            "page" -> { history.addLast(profileId to pageId); pageId = a.getString("page") }
            "profile" -> { history.addLast(profileId to pageId); profileId = a.getString("profile"); pageId = "" }
            "back" -> if (history.isNotEmpty()) { val last = history.removeLast(); profileId = last.first; pageId = last.second }
        }
        while (history.size > 64) history.removeFirst()
        render()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (design.openSheets == 0 && !editing) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; swiping = false }
                MotionEvent.ACTION_MOVE -> if (!swiping && abs(event.x - downX) > design.dp(48) && abs(event.x - downX) > abs(event.y - downY) * 1.5f) {
                    swiping = true
                    val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel); cancel.recycle()
                }
                MotionEvent.ACTION_UP -> if (swiping) {
                    val pages = objects(profile().getJSONArray("pages")); val index = pages.indexOfFirst { it.getString("id") == pageId }
                    pageId = pages[(index + if (event.x < downX) 1 else -1).coerceIn(0, pages.lastIndex)].getString("id"); render(); swiping = false; return true
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            if (swiping) return true
        }
        return super.dispatchTouchEvent(event)
    }
    /** Opens the system picker for an image or font, stores it as a deck asset, and hands back its id. */
    private fun pickAsset(kind: String, square: Boolean, then: (String) -> Unit) {
        pendingPick = PendingPick(kind, square, then)
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (kind == "image") "image/*" else "*/*"), 71)
        } catch (_: Exception) { pendingPick = null; toast("No file picker is available") }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val pick = pendingPick; pendingPick = null; val uri = data?.data
        if (requestCode != 71 || pick == null || resultCode != RESULT_OK || uri == null) return
        Thread {
            try {
                val bytes = if (pick.kind == "font") MacroLook.readFont(this, uri) else MacroLook.readImage(this, uri, pick.square)
                val id = pads.assets.put(pick.kind, bytes, uri.lastPathSegment.orEmpty().substringAfterLast('/'))
                runOnUiThread { if (!isDestroyed) pick.then(id) }
            } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Could not import that file") } }
        }.start()
    }
    private fun appearance() {
        val snapshot = model.toString(); var saved = false
        lookDraft = true
        MacroLookEditor(design, model, profile(), page(), ::pickAsset, { render() }).show(
            onSave = { m -> try { pads.save(m); model = pads.read(); saved = true; true } catch (e: Exception) { toast(e.message ?: "Could not save the look"); false } },
            onCancel = { lookDraft = false; if (!saved) model = JSONObject(snapshot); render() })
    }
    private fun edit(button: JSONObject?, row: Int, col: Int) {
        MacroPadEditor(this, design, desktop.list(), model, profile(), pads.assets, ::pickAsset).show(button, row, col) { updated ->
            val buttons = page().getJSONArray("buttons")
            val index = objects(buttons).indexOfFirst { it.optString("id") == button?.optString("id") }
            if (index >= 0) buttons.remove(index)
            if (updated != null) buttons.put(updated)
            save()
        }
    }
    private fun profiles() {
        val sheet = design.sheet("Your profiles", "A different deck for every part of your day")
        objects(model.getJSONArray("profiles")).forEach { p -> sheet.body.addView(design.button(p.getString("name"), ButtonKind.Tonal, Icon.Keypad) {
            profileId = p.getString("id"); pageId = ""; render(); sheet.close()
        }, design.lp(top = 8)) }
        sheet.body.addView(design.button("New profile", ButtonKind.Text, Icon.Plus) {
            val profile = JSONObject().put("id", "p-${UUID.randomUUID()}").put("name", "New profile").put("rows", 3).put("cols", 4)
                .put("pages", JSONArray().put(JSONObject().put("id", "pg-${UUID.randomUUID()}").put("name", "Main").put("buttons", JSONArray())))
            model.getJSONArray("profiles").put(profile); profileId = profile.getString("id"); pageId = ""
            if (save()) { sheet.close(); settings() }
        }); sheet.done(); sheet.show()
    }
    private fun settings() {
        val sheet = design.sheet("Deck settings", "Long-press any key to start editing")
        val p = JSONObject(profile().toString())
        val pg = objects(p.getJSONArray("pages")).first { it.getString("id") == pageId }
        fun saveProfile(): Boolean {
            val profiles = model.getJSONArray("profiles")
            val index = objects(profiles).indexOfFirst { it.getString("id") == p.getString("id") }
            if (index < 0) { toast("This profile was removed; reopen settings"); return false }
            profiles.put(index, JSONObject(p.toString()))
            return save()
        }
        val name = design.field(sheet.body, "Profile name", p.getString("name"))
        val pageName = design.field(sheet.body, "Page name", pg.getString("name"))
        val rows = design.field(sheet.body, "Rows · 2–6", p.getInt("rows").toString(), numeric = true)
        val cols = design.field(sheet.body, "Columns · 2–8", p.getInt("cols").toString(), numeric = true)
        sheet.body.addView(design.button("Add page", ButtonKind.Tonal, Icon.Plus) {
            val page = JSONObject().put("id", "pg-${UUID.randomUUID()}").put("name", "Page ${p.getJSONArray("pages").length() + 1}").put("buttons", JSONArray())
            p.getJSONArray("pages").put(page)
            if (saveProfile()) { pageId = page.getString("id"); render(); sheet.close() }
            else p.getJSONArray("pages").remove(p.getJSONArray("pages").length() - 1)
        }, design.lp(top = 16))
        sheet.body.addView(design.button("Remove this page", ButtonKind.DangerText, Icon.Trash) {
            val confirm = design.sheet("Remove ${pg.getString("name")} ?", "All keys on this page will be removed. Links to it must be removed first.")
            confirm.actions("Remove page", ButtonKind.Danger) {
                val pages = p.getJSONArray("pages"); val index = objects(pages).indexOfFirst { it.getString("id") == pg.getString("id") }
                val remaining = JSONArray(objects(pages).filterIndexed { i, _ -> i != index })
                p.put("pages", remaining)
                if (saveProfile()) { sheet.close(); true } else { p.put("pages", pages); false }
            }; confirm.show()
        })
        sheet.body.addView(design.button("Copy JSON", ButtonKind.Tonal, Icon.Copy) { design.copy("MacroPads JSON", exported().toString(2)) }, design.lp(top = 8))
        sheet.body.addView(design.button("Share JSON", ButtonKind.Text, Icon.Share) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, exported().toString(2)), "Export MacroPads"))
        })
        sheet.body.addView(design.button("Import JSON", ButtonKind.Text, Icon.Download) { sheet.close(); importDeck() })
        sheet.actions("Save layout") {
            p.put("name", name.value.trim()).put("rows", rows.value.toIntOrNull() ?: 0).put("cols", cols.value.toIntOrNull() ?: 0)
            pg.put("name", pageName.value.trim()); saveProfile()
        }; sheet.show()
    }
    /** The deck for sharing, with the look's images and fonts embedded when they are small enough. */
    private fun exported(): JSONObject {
        val (doc, complete) = pads.export()
        if (!complete) toast("Images and fonts are over 1.5 MB and are not included; they only work on this phone")
        return doc
    }
    private fun importDeck() {
        val sheet = design.sheet("Import deck", "Replaces all profiles after validation, including their look. Copy your current JSON first to keep a backup.")
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val value = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val field = design.field(sheet.body, "Deck JSON", value.take(3_000_000), multiline = true, mono = true)
        sheet.actions("Import") { pads.import(JSONObject(field.value)); model = pads.read(); profileId = ""; pageId = ""; toggled.clear(); render(); true }; sheet.show()
    }
    private fun computers() {
        val sheet = design.sheet("Your computers", "Open Homedroid Desktop on each computer to connect")
        objects(desktop.list()).forEach { c ->
            sheet.body.addView(design.text("${if (c.getBoolean("online")) "● Online" else "○ Offline"}  ·  ${c.getString("name")}  ·  ${c.getString("os")}", TypeStyle.TitleSmall), design.lp(top = 16))
            sheet.body.addView(design.button("Unpair ${c.getString("name")}", ButtonKind.DangerText, Icon.Close) {
                val confirm = design.sheet("Unpair computer?", "${c.getString("name")} will need a new pairing code to reconnect.")
                confirm.actions("Unpair", ButtonKind.Danger) { desktop.remove(c.getString("id")); updateConnections(); sheet.close(); true }; confirm.show()
            })
        }
        sheet.body.addView(design.button("Pair a computer", ButtonKind.Filled, Icon.Plus) { sheet.close(); pairing() }, design.lp(top = 16))
        sheet.body.addView(design.button(if (DesktopWebcamService.instance == null) "Enable Desktop webcam" else "Disable Desktop webcam", ButtonKind.Tonal, Icon.Camera) {
            if (DesktopWebcamService.instance != null) DesktopWebcamService.stop(this)
            else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 42)
            else { DesktopWebcamService.arm(this); toast("Webcam enabled; connect from Homedroid Desktop") }
            sheet.close()
        }, design.lp(top = 12))
        sheet.body.addView(design.text("Webcam access stays enabled with a notification. Stop IPCam capture before starting a desktop stream.", TypeStyle.BodySmall, design.p.onSurfaceVariant), design.lp(top = 12))
        sheet.done(); sheet.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { DesktopWebcamService.arm(this); toast("Desktop webcam enabled") }
            else toast("Camera permission is required for the webcam")
        }
    }
    private fun pairing() {
        val code = desktop.issueCode(); val sheet = design.sheet("Pair a computer", "Keep Homedroid's server running on this phone")
        val label = design.text(code.getString("code"), TypeStyle.HeadlineSmall, design.p.primary).apply {
            textSize = 52f; letterSpacing = 0.15f; gravity = Gravity.CENTER; setTextIsSelectable(true)
        }
        sheet.body.addView(label, design.lp(top = 20, bottom = 12))
        val expiry = design.text("", TypeStyle.BodySmall, design.p.onSurfaceVariant).apply { gravity = Gravity.CENTER }
        sheet.body.addView(expiry)
        val address = Device.localIps().firstOrNull()?.let { "http://${if (':' in it) "[$it]" else it}:${Config(this).dashboardPort}" } ?: "http://<phone-address>:${Config(this).dashboardPort}"
        val command = "homedroid-desktop pair $address ${code.getString("code")}"
        sheet.body.addView(design.text("1. Install Homedroid Desktop from github.com/sudiptacmd/homedroid/releases.\n\n2. On your computer, run the command below or pair using its local page.\n\n3. Keep Homedroid Desktop running. Your buttons light up when actions are acknowledged.", TypeStyle.BodyMedium), design.lp(top = 20))
        sheet.body.addView(design.text(command, TypeStyle.BodyMedium).apply { setTextIsSelectable(true) }, design.lp(top = 16))
        sheet.body.addView(design.button("Copy pairing command", ButtonKind.Tonal, Icon.Copy) { design.copy("Pairing command", command, sensitive = true) }, design.lp(top = 12))
        val tick = object : Runnable {
            override fun run() {
                val remaining = ((code.getLong("expires") - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                expiry.text = if (remaining == 0L) "Code expired · close and pair again" else "Single use · expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
                if (remaining > 0) handler.postDelayed(this, 1000)
            }
        }
        sheet.onClosed = { handler.removeCallbacks(tick) }; handler.post(tick); sheet.done(); sheet.show()
    }
}
