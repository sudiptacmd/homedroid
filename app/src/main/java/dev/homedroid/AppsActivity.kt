package dev.homedroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** The app directory: install, open and remove apps that run inside Alpine. */
class AppsActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var cfg: Config

    // A location picked before storage access was granted, applied once it is.
    private var pending: Pair<AppDef, File>? = null
    private val rows = mutableListOf<Row>()
    private lateinit var jobLog: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    companion object {
        private val ERROR = Regex("error|exception|fatal|failed|insufficient|denied", RegexOption.IGNORE_CASE)
    }

    private class Row(
        val app: AppDef,
        val status: TextView,
        val storage: TextView?,
        val action: Button,
        val open: Button,
        val toggle: Button,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Apps"
        apps = Apps(this, Paths(this))
        cfg = Config(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        list.addView(text("Apps run inside a small Alpine Linux environment that is downloaded on first install.", 13f))
        for (app in apps.catalog) list.addView(card(app))
        jobLog = text("", 10f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        list.addView(jobLog)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(list) })
    }

    override fun onResume() {
        super.onResume()
        applyPendingIfAllowed()
        ui.post(tick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == Storage.REQUEST_CODE) applyPendingIfAllowed()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun card(app: AppDef): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        box.addView(text(app.name, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        box.addView(text(app.description, 14f))
        box.addView(text("Port ${app.port} · ${app.size}", 12f).apply { alpha = 0.7f })
        val status = text("", 12f)
        box.addView(status)
        val storage = app.storagePath?.let { text("", 12f).also(box::addView) }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val action = Button(this)
        val open = Button(this).apply {
            text = "Open"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${app.port}")))
            }
        }
        val toggle = Button(this).apply {
            setOnClickListener {
                cfg.setDisabled(app.id, !cfg.isDisabled(app.id))
                if (ServerService.running) ServerService.restart(this@AppsActivity)
            }
        }
        buttons.addView(action)
        buttons.addView(open)
        buttons.addView(toggle)
        if (app.storagePath != null) {
            buttons.addView(Button(this).apply {
                text = "Change location"
                setOnClickListener { chooseStorage(app) }
            })
        }
        box.addView(buttons)
        rows += Row(app, status, storage, action, open, toggle)
        return box
    }

    private fun refresh() {
        val busy = Jobs.running
        val running = ServerService.supervisor?.daemons.orEmpty().associateBy { it.spec.name }
        for (row in rows) {
            val installed = apps.isInstalled(row.app)
            // A multi-service app is as healthy as its least healthy process.
            val d = row.app.serviceNames.mapNotNull { running[it] }.maxByOrNull { it.state.severity() }
                ?.takeIf { row.app.serviceNames.all { n -> running.containsKey(n) } }
            row.status.setTextIfChanged(when {
                busy && Jobs.title?.endsWith(row.app.name) == true -> "${Jobs.title}…"
                !installed -> "Not installed"
                cfg.isDisabled(row.app.id) -> "Installed · turned off"
                d == null -> "Installed · server stopped"
                else -> "Installed · ${d.state.name.lowercase()}" +
                    (if (d.restarts > 0) " (${d.restarts} restarts)" else "") +
                    // A crash loop is useless without its reason.
                    (if (d.state == Supervisor.State.BACKOFF) "\n" + lastError(d) else "") +
                    notice(row.app, d)
            })
            row.storage?.setTextIfChanged(
                "${row.app.storageLabel}: " + (cfg.storageDir(row.app) ?: "inside the app (default)")
            )
            row.open.setVisibleIfChanged(installed && d != null)
            row.toggle.setVisibleIfChanged(installed)
            row.toggle.setTextIfChanged(if (cfg.isDisabled(row.app.id)) "Turn on" else "Turn off")
            if (row.action.isEnabled == busy) row.action.isEnabled = !busy
            row.action.setTextIfChanged(if (installed) "Remove" else "Install")
            row.action.setOnClickListener {
                if (installed) confirmRemove(row.app) else ServerService.install(this, row.app)
            }
        }
        jobLog.setTextIfChanged(Jobs.log.tail(40).joinToString("\n"))
    }

    private fun chooseStorage(app: AppDef) {
        val volumes = Storage.volumes(this)
        val labels = listOf("Inside the app (default, no permission needed)") +
            volumes.map {
                "${it.label} · ${formatSize(it.free)} free" +
                    if (it.appDirOnly) " (deleted if Homedroid is uninstalled)" else ""
            }
        AlertDialog.Builder(this)
            .setTitle("Where should ${app.name} keep its ${app.storageLabel?.lowercase()}?")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) {
                    setStorage(app, null)
                    return@setItems
                }
                val volume = volumes[i - 1]
                val dir = Storage.folderOn(volume, app)
                if (!Storage.needsAccess(volume) || Storage.hasAccess(this)) {
                    setStorage(app, dir)
                } else {
                    pending = app to dir
                    Toast.makeText(this, "Allow file access so ${app.name} can use $dir", Toast.LENGTH_LONG).show()
                    Storage.requestAccess(this)
                }
            }
            .show()
    }

    private fun applyPendingIfAllowed() {
        val (app, dir) = pending ?: return
        if (!Storage.hasAccess(this)) return
        pending = null
        setStorage(app, dir)
    }

    private fun setStorage(app: AppDef, dir: File?) {
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
            Toast.makeText(this, "Couldn't create $dir", Toast.LENGTH_LONG).show()
            return
        }
        cfg.setStorageDir(app, dir?.path)
        if (ServerService.running) ServerService.restart(this)
        val sharing = apps.catalog.filter { it.storageKey == app.storageKey && it != app }.joinToString { it.name }
        Toast.makeText(
            this,
            "${app.name}${if (sharing.isEmpty()) "" else " and $sharing"} now use ${dir ?: "app storage"}. " +
                "Existing files were not moved.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun confirmRemove(app: AppDef) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${app.name}?")
            .setMessage("The program is removed. Its settings and data stay in Alpine.")
            .setPositiveButton("Remove") { _, _ -> ServerService.uninstall(this, app) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        return "\n" + app.noticeText.orEmpty().replace("$1", match.groupValues.getOrElse(1) { "" })
    }

    /** The most telling recent log line of a crash-looping daemon. */
    private fun lastError(d: Supervisor.Daemon): String {
        val lines = d.log.tail(40).filterNot { it.startsWith("! ") || it.startsWith("proot ") || it.startsWith("   at ") }
        return lines.lastOrNull { ERROR.containsMatchIn(it) } ?: lines.lastOrNull().orEmpty()
    }

    private fun formatSize(bytes: Long) =
        if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        textSize = sp
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
