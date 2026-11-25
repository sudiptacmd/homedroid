package dev.homedroid

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** The app library: install, open, update and remove apps that run inside Alpine. */
class AppsActivity : MobileActivity() {
    private lateinit var apps: Apps
    private lateinit var cfg: Config
    private val p get() = design.p

    // A location picked before storage access was granted, applied once it is.
    private var pending: Pair<AppDef, File>? = null
    private val cards = mutableListOf<Card>()
    private var filter = 0
    private var subtitle: TextView? = null
    private lateinit var job: MobileUi.Banner
    private lateinit var jobProgress: LinearProgress
    private lateinit var emptyInstalled: View
    private var errorSeen: String? = null

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    companion object {
        private const val CLEAR_TEXT =
            "Its settings, accounts and database are deleted and it starts like a fresh install."
        private val ERROR = Regex("error|exception|fatal|failed|insufficient|denied", RegexOption.IGNORE_CASE)
    }

    private class Card(
        val app: AppDef,
        val view: LinearLayout,
        val pill: MobileUi.Pill,
        val notice: TextView,
        val progress: LinearProgress,
        val storageRow: View?,
        val storage: TextView?,
        val install: UiButton,
        val open: UiButton,
        val turnOn: UiButton,
        val update: UiButton,
        val manage: UiButton,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this, Paths(this))
        cfg = Config(this)

        val page = design.page("Apps", "Apps", " ")
        subtitle = page.findViewWithTag("subtitle")
        design.topAction(Icon.Logs, "Installation activity") { activitySheet() }

        job = design.banner(Tone.Primary, Icon.Download, "", "", "View activity") { errorSeen = Jobs.error; activitySheet() }
        job.body.apply { typeface = Typeface.MONOSPACE; textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        jobProgress = LinearProgress(this, p.alpha(p.onPrimaryContainer, 0.2f), p.onPrimaryContainer)
        job.view.addView(jobProgress, 1, design.lp(top = Space.MD, bottom = Space.XS))
        job.view.visibility = View.GONE
        page.addView(job.view)

        val modules = design.group(page)
        modules.item(design.listRow(Icon.Tune, "Modules & connections", "SSH, web hosting, tunnels, camera and AI") {
            DashboardActivity.open(this, "modules")
        }.view)

        page.addView(design.header("Library"))
        val seg = design.segmented(listOf("All", "Installed", "Available"), 0) { filter = it; refresh() }
        page.addView(seg.view, design.lp(bottom = Space.MD))
        emptyInstalled = design.state(Icon.Apps, "No apps installed yet", "Pick one from the library to get started. Installs run in the background.",
            "Show all apps") { seg.select(0, notify = true) }
        page.addView(emptyInstalled)
        apps.catalog.forEachIndexed { i, app -> page.addView(card(app, i)) }
        if (savedInstanceState == null) design.enter(page)
    }

    override fun onResumeUnlocked() {
        applyPendingIfAllowed()
        ui.removeCallbacks(tick); ui.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == Storage.REQUEST_CODE) applyPendingIfAllowed()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun card(app: AppDef, index: Int): View {
        val box = design.card(padding = Space.MD)
        val head = design.row().apply { gravity = Gravity.TOP }
        val (bg, fg) = when (index % 3) {
            0 -> p.primaryContainer to p.onPrimaryContainer
            1 -> p.secondaryContainer to p.onSecondaryContainer
            else -> p.tertiaryContainer to p.onTertiaryContainer
        }
        head.addView(FrameLayout(this).apply {
            background = design.shape(bg, Shape.MD + 2)
            addView(design.text(app.name.take(1).uppercase(), TypeStyle.TitleLarge, fg).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, design.lp(design.dp(40), design.dp(40), end = Space.SM))
        val words = design.column()
        words.addView(design.text(app.name, TypeStyle.TitleMedium).apply { isAccessibilityHeading = true })
        words.addView(design.text("Port ${app.port} · ${app.size}", TypeStyle.BodySmall, p.onSurfaceVariant), design.lp(top = 2))
        head.addView(words, design.lp(0, -2, 1f))
        val pill = design.pill()
        head.addView(pill.view, design.lp(-2, -2, start = Space.SM))
        box.addView(head)

        box.addView(design.text(app.description.substringBefore(". ").let { if (it.endsWith('.')) it else "$it." }, TypeStyle.BodyMedium, p.onSurfaceVariant),
            design.lp(top = Space.SM))
        ModuleResources.of(this, app.id)?.let { resources ->
            box.addView(design.text(resources.summary, TypeStyle.BodySmall, p.onSurfaceVariant).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                tooltipText = resources.summary
            }, design.lp(top = Space.XS))
        }
        val notice = design.text("", TypeStyle.BodySmall).apply {
            setPadding(design.dp(Space.MD), design.dp(Space.SM + 2), design.dp(Space.MD), design.dp(Space.SM + 2)); visibility = View.GONE
            setTextIsSelectable(true)
        }
        box.addView(notice, design.lp(top = Space.MD))
        val progress = LinearProgress(this, p.secondaryContainer, p.primary).apply { visibility = View.GONE }
        box.addView(progress, design.lp(top = Space.MD))

        var storageRow: View? = null
        var storage: TextView? = null
        if (app.storagePath != null) {
            val r = design.row().apply { minimumHeight = design.dp(48) }
            r.addView(design.icon(Icon.Folder, p.onSurfaceVariant, 18), design.lp(design.dp(18), design.dp(18), end = Space.SM))
            storage = design.text("", TypeStyle.BodySmall, p.onSurfaceVariant).also { r.addView(it, design.lp(0, -2, 1f)) }
            r.addView(design.button("Change", ButtonKind.Text) { chooseStorage(app) }.apply { contentDescription = "Change where ${app.name} keeps its ${app.storageLabel?.lowercase()}" })
            storageRow = r
            box.addView(r, design.lp(top = Space.XS))
        }

        val actions = design.row()
        val install = design.button("Install", ButtonKind.Filled, compact = true) {
            design.haptic(box, Haptic.Confirm)
            ServerService.install(this, app)
            design.snackbar("Installing ${app.name}. You can leave this screen.")
        }
        val open = design.button("Open", ButtonKind.Filled, compact = true) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${app.port}"))) }
                .onFailure { design.snackbar("Install a browser to open ${app.name}") }
        }
        val turnOn = design.button("Turn on", ButtonKind.Filled, compact = true) { setDisabled(app, false) }
        val update = design.button("Update", ButtonKind.Tonal, compact = true) { confirmUpdate(app) }
        val manage = design.button("Manage", ButtonKind.Outlined, compact = true) { manageSheet(app) }
        for (b in listOf(install, open, turnOn, update, manage)) {
            actions.addView(b, design.lp(0, -2, 1f, start = Space.XXS, end = Space.XXS))
        }
        box.addView(actions, design.lp(top = Space.SM))
        cards += Card(app, box, pill, notice, progress, storageRow, storage, install, open, turnOn, update, manage)
        return box
    }

    private fun refresh() {
        val busy = Jobs.running
        val running = ServerService.supervisor?.daemons.orEmpty().associateBy { it.spec.name }
        var installedCount = 0
        for (c in cards) {
            val app = c.app
            val installed = apps.isInstalled(app)
            if (installed) installedCount++
            val off = cfg.isDisabled(app.id)
            // A multi-service app is as healthy as its least healthy process.
            val d = app.serviceNames.mapNotNull { running[it] }.maxByOrNull { it.state.severity() }
                ?.takeIf { app.serviceNames.all { n -> running.containsKey(n) } }
            val mine = busy && Jobs.title?.endsWith(app.name) == true
            when {
                mine -> c.pill.set(Jobs.title.orEmpty().substringBefore(' '), Tone.Primary)
                !installed -> c.pill.set("Available", Tone.Neutral)
                off -> c.pill.set("Off", Tone.Neutral)
                d == null -> c.pill.set("Installed", Tone.Neutral)
                d.state == Supervisor.State.RUNNING -> c.pill.set("Running", Tone.Success)
                d.state == Supervisor.State.STARTING -> c.pill.set("Starting", Tone.Primary)
                d.state == Supervisor.State.STOPPED -> c.pill.set("Stopped", Tone.Neutral)
                else -> c.pill.set("Needs attention", Tone.Error)
            }
            c.progress.setVisibleIfChanged(mine)
            // A crash loop is useless without its reason; some apps print something worth showing (a first password).
            val problem = if (installed && !off && d?.state == Supervisor.State.BACKOFF) lastError(d) else ""
            val note = if (installed && d != null) notice(app, d) else ""
            val message = listOf(problem, note).filter { it.isNotEmpty() }.joinToString("\n")
            c.notice.setVisibleIfChanged(message.isNotEmpty())
            if (message.isNotEmpty()) {
                c.notice.setTextIfChanged(message)
                val tone = if (problem.isNotEmpty()) Tone.Error else Tone.Primary
                if (c.notice.tag != tone) {
                    c.notice.tag = tone
                    c.notice.background = design.shape(design.containerOf(tone), Shape.MD)
                    c.notice.setTextColor(design.onContainerOf(tone))
                }
            }
            c.storageRow?.setVisibleIfChanged(installed)
            c.storage?.setTextIfChanged("${app.storageLabel}: " + (cfg.storageDir(app) ?: "inside the app"))

            c.install.setVisibleIfChanged(!installed)
            c.open.setVisibleIfChanged(installed && !off && d?.state == Supervisor.State.RUNNING)
            c.turnOn.setVisibleIfChanged(installed && off)
            c.update.setVisibleIfChanged(installed)
            c.manage.setVisibleIfChanged(installed)
            for (b in listOf(c.install, c.turnOn, c.update, c.manage)) if (b.isEnabled == busy) b.isEnabled = !busy
            c.view.setVisibleIfChanged(when (filter) { 1 -> installed; 2 -> !installed; else -> true })
        }
        emptyInstalled.setVisibleIfChanged(filter == 1 && installedCount == 0)
        subtitle?.setTextIfChanged("$installedCount installed · ${cards.size} in the library")

        // The current (or last failed) job.
        val title = Jobs.title
        val error = Jobs.error
        val last = Jobs.log.tail(1).firstOrNull()?.takeUnless { it.startsWith("──") }
        when {
            busy && title != null -> {
                job.set(Tone.Primary, Icon.Download, "$title…", last ?: "Starting…")
                jobProgress.setVisibleIfChanged(true)
                job.view.setVisibleIfChanged(true)
            }
            !busy && error != null && error != errorSeen && title != null -> {
                job.set(Tone.Error, Icon.Alert, "$title didn't finish", error)
                jobProgress.setVisibleIfChanged(false)
                job.view.setVisibleIfChanged(true)
            }
            else -> job.view.setVisibleIfChanged(false)
        }
    }

    private fun setDisabled(app: AppDef, off: Boolean) {
        cfg.setDisabled(app.id, off)
        if (ServerService.running) ServerService.restart(this)
        design.snackbar(if (off) "${app.name} is off. It stays installed." else "Turning ${app.name} on…")
        refresh()
    }

    private fun confirmUpdate(app: AppDef) {
        design.confirm("Update ${app.name}?", "Downloads the newest version. Its settings and data are kept; it restarts when the update is done.",
            "Update") {
            ServerService.update(this, app)
            design.snackbar("Updating ${app.name}…")
        }
    }

    /** Less frequent and destructive actions for an installed app. */
    private fun manageSheet(app: AppDef) {
        val s = design.sheet(app.name, "Port ${app.port} · ${app.size}")
        ModuleResources.of(this, app.id)?.let { resources ->
            s.body.addView(design.text(resources.summary, TypeStyle.BodyMedium, p.onSurfaceVariant), design.lp(bottom = Space.XS))
            if (resources.note.isNotBlank()) s.body.addView(design.text(resources.note, TypeStyle.BodySmall, p.onSurfaceVariant),
                design.lp(bottom = Space.MD))
        }
        val busy = Jobs.running
        val g = design.group(s.body)
        val run = design.switchRow(Icon.Power, "Run this app", "When it's off, it stays installed but doesn't start", !cfg.isDisabled(app.id)) { on ->
            setDisabled(app, !on)
        }
        g.item(run.view)
        g.item(design.listRow(Icon.Refresh, "Update", "Get the newest version; data is kept") { s.close(); confirmUpdate(app) }.also { it.setEnabled(!busy) }.view)
        if (app.storagePath != null) g.item(design.listRow(Icon.Folder, "${app.storageLabel} location", cfg.storageDir(app) ?: "Inside the app") {
            s.close(); chooseStorage(app)
        }.view)
        val danger = design.group(s.body)
        (danger.layoutParams as? LinearLayout.LayoutParams)?.topMargin = design.dp(Space.MD)
        danger.item(design.listRow(Icon.Trash, "Clear data", "Start over like a fresh install", Trailing.None) { s.close(); confirmClear(app) }.also { it.setEnabled(!busy) }.view)
        danger.item(design.listRow(Icon.Close, "Remove", "Uninstall ${app.name}", Trailing.None) { s.close(); confirmRemove(app) }.also {
            it.title.setTextColor(p.error); it.setEnabled(!busy)
        }.view)
        s.done()
        s.show()
    }

    private fun activitySheet() {
        val s = design.sheet("Installation activity", Jobs.title?.let { if (Jobs.running) "$it…" else it })
        val output = Jobs.log.tail(80).joinToString("\n").ifEmpty { "No installation activity yet." }
        s.body.addView(design.text(output, TypeStyle.BodySmall, p.onSurfaceVariant).apply {
            typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextIsSelectable(true)
            background = design.shape(p.surfaceHighest, Shape.MD)
            setPadding(design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD))
        }, design.lp(top = Space.SM))
        val r = design.row()
        r.addView(design.button("Copy", ButtonKind.Tonal, Icon.Copy) { design.copy("Installation activity", output, host = s.frame) })
        s.body.addView(r, design.lp(top = Space.MD))
        s.done(); s.show()
    }

    private fun chooseStorage(app: AppDef) {
        val volumes = Storage.volumes(this)
        val current = cfg.storageDir(app)
        val options = listOf<Pair<String, String?>>("Inside the app" to "Default · no permission needed") +
            volumes.map { it.label to "${formatSize(it.free)} free" + if (it.appDirOnly) " · deleted if Homedroid is uninstalled" else "" }
        val selected = if (current == null) 0 else volumes.indexOfFirst { Storage.folderOn(it, app).path == current }.let { if (it < 0) -1 else it + 1 }
        design.choose("${app.storageLabel} location", options, selected, "Where ${app.name} keeps its ${app.storageLabel?.lowercase()}. Existing files are not moved.") { i ->
            if (i == 0) { setStorage(app, null); return@choose }
            val volume = volumes[i - 1]
            val dir = Storage.folderOn(volume, app)
            if (!Storage.needsAccess(volume) || Storage.hasAccess(this)) {
                setStorage(app, dir)
            } else {
                pending = app to dir
                design.snackbar("Allow file access so ${app.name} can use $dir", long = true)
                Storage.requestAccess(this)
            }
        }
    }

    private fun applyPendingIfAllowed() {
        val (app, dir) = pending ?: return
        if (!Storage.hasAccess(this)) return
        pending = null
        setStorage(app, dir)
    }

    private fun setStorage(app: AppDef, dir: File?) {
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
            design.snackbar("Couldn't create $dir", long = true)
            return
        }
        cfg.setStorageDir(app, dir?.path)
        if (ServerService.running) ServerService.restart(this)
        val sharing = apps.catalog.filter { it.storageKey == app.storageKey && it != app }.joinToString { it.name }
        design.snackbar("${app.name}${if (sharing.isEmpty()) "" else " and $sharing"} now use ${dir ?: "app storage"}. Existing files were not moved.", long = true)
    }

    private fun confirmRemove(app: AppDef) {
        val checks = listOf("Also delete its settings and database") + listOfNotNull(libraryOption(app))
        design.confirm("Remove ${app.name}?", "Keep its settings to pick up where you left off if you install it again.", "Remove",
            destructive = true, checks = checks) { checked ->
            val library = checked.getOrElse(1) { false }
            ServerService.uninstall(this, app, deleteData = checked[0] || library, deleteLibrary = library)
            design.snackbar("Removing ${app.name}…")
        }
    }

    /** Resets an app to a fresh install, like Android's "Clear storage". */
    private fun confirmClear(app: AppDef) {
        design.confirm("Clear ${app.name}'s data?", CLEAR_TEXT, "Clear data", destructive = true,
            checks = listOfNotNull(libraryOption(app))) { checked ->
            ServerService.clearData(this, app, checked.getOrElse(0) { false })
            design.snackbar("Clearing ${app.name}'s data…")
        }
    }

    /** The checkbox label for deleting [app]'s library, naming apps that share it; null if it has none. */
    private fun libraryOption(app: AppDef): String? {
        val label = app.storageLabel?.lowercase() ?: return null
        val sharing = apps.sharing(app).joinToString { it.name }
        return "Also delete everything in the $label" + if (sharing.isEmpty()) "" else " (also used by $sharing)"
    }

    private fun Supervisor.State.severity() = when (this) {
        Supervisor.State.RUNNING -> 0
        Supervisor.State.STARTING -> 1
        Supervisor.State.STOPPED -> 2
        Supervisor.State.BACKOFF -> 3
    }

    /** The app's notice (see [AppDef.noticePattern]) from its recent output, if any. */
    private fun notice(app: AppDef, d: Supervisor.Daemon): String {
        val pattern = app.noticePattern ?: return ""
        val match = d.log.tail(400).asReversed().firstNotNullOfOrNull { pattern.find(it) } ?: return ""
        return app.noticeText.orEmpty().replace("$1", match.groupValues.getOrElse(1) { "" })
    }

    /** The most telling recent log line of a crash-looping daemon. */
    private fun lastError(d: Supervisor.Daemon): String {
        val lines = d.log.tail(40).filterNot { it.startsWith("! ") || it.startsWith("proot ") || it.startsWith("   at ") }
        return lines.lastOrNull { ERROR.containsMatchIn(it) } ?: lines.lastOrNull().orEmpty()
    }

    private fun formatSize(bytes: Long) =
        if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"
}
