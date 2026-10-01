package dev.homedroid

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

/** The phone's calm landing screen. Configuration lives in Settings. */
class MainActivity : MobileActivity() {
    private lateinit var cfg: Config
    private lateinit var headline: TextView
    private lateinit var summary: TextView
    private lateinit var toggle: Button
    private lateinit var battery: TextView
    private lateinit var batteryCaption: TextView
    private lateinit var temperature: TextView
    private lateinit var memory: TextView
    private lateinit var disk: TextView
    private lateinit var address: TextView
    private lateinit var camera: Button
    private lateinit var heat: TextView
    private lateinit var services: LinearLayout
    private val serviceRows = linkedMapOf<String, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        Paths(this).ensure()
        val page = design.page("Your home server", "A little phone. A lot of possibilities.", "Overview")
        val hero = design.card()
        hero.addView(design.text("SERVER STATUS", 11f, design.accent, true))
        headline = design.text("Ready when you are", 25f, design.ink, true)
        summary = design.text("", 14f, design.muted)
        hero.addView(headline); hero.addView(summary)
        toggle = design.button("Start server", true) {
            if (ServerService.active) {
                AlertDialog.Builder(this).setTitle("Stop your server?")
                    .setMessage("Apps, remote connections, the dashboard and camera access will stop.")
                    .setPositiveButton("Stop server") { _, _ -> ServerService.stop(this); refresh() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else { ServerService.start(this); ui.postDelayed(::refresh, 300) }
        }
        hero.addView(toggle)
        hero.addView(design.button("Open control panel") { DashboardActivity.open(this, "overview") })
        page.addView(hero)
        page.addView(design.label("PHONE HEALTH"))
        fun metric(parent: LinearLayout, title: String): TextView {
            val card = design.card()
            val value = design.text("—", 22f, design.ink, true)
            val caption = design.text(title, 12f, design.muted)
            if (title == "Battery") batteryCaption = caption
            card.addView(value); card.addView(caption)
            parent.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(design.dp(3), 0, design.dp(3), design.dp(6)) })
            return value
        }
        val first = LinearLayout(this)
        battery = metric(first, "Battery"); temperature = metric(first, "Temperature")
        page.addView(first)
        val second = LinearLayout(this)
        memory = metric(second, "Memory available"); disk = metric(second, "Storage available")
        page.addView(second)
        heat = design.text("Phone is warm. Keep it ventilated and reduce heavy workloads.", 14f, design.warning)
        page.addView(heat)
        page.addView(design.label("CONNECT"))
        val connect = design.card()
        connect.addView(design.text("Dashboard address", 16f, design.ink, true))
        address = design.text("", 14f, design.muted).apply { setTextIsSelectable(true) }
        connect.addView(address)
        connect.addView(design.button("Share address") {
            val ip = Device(this).ips.firstOrNull()
            if (ip == null) Toast.makeText(this, "Connect to Wi-Fi to share an address", Toast.LENGTH_SHORT).show()
            else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Homedroid dashboard: http://$ip:${cfg.dashboardPort}")
            }, "Share dashboard"))
        })
        connect.addView(design.button("Dashboard login") { showCredentials() })
        page.addView(connect)
        page.addView(design.label("SERVICES"))
        services = design.column(); page.addView(services)
        page.addView(design.row("Service logs", "Inspect activity and troubleshoot a service") { showLogs() })
        page.addView(design.label("DO MORE"))
        page.addView(design.row("Files & storage", "Browse, upload and manage your files") { DashboardActivity.open(this, "files") })
        page.addView(design.row("AI & routines", "Models, chat and scheduled briefs") { DashboardActivity.open(this, "ai") })
        page.addView(design.row("Deployments", "Host and manage projects from Git") { DashboardActivity.open(this, "deploys") })
        page.addView(design.row("Your phones", "Pair phones and manage your cluster") { DashboardActivity.open(this, "cluster") })
        val cameraCard = design.card()
        cameraCard.addView(design.text("Camera & microphone", 17f, design.ink, true))
        cameraCard.addView(design.text("Enable access here, then use live view and capture controls in the panel.", 14f, design.muted))
        camera = design.button("Enable camera access") { toggleCamera() }
        cameraCard.addView(camera)
        cameraCard.addView(design.button("Camera controls") { DashboardActivity.open(this, "camera") })
        page.addView(cameraCard)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        refresh()
    }

    private fun showCredentials() {
        val box = design.column().apply { setPadding(design.dp(24), design.dp(8), design.dp(24), design.dp(8)) }
        val password = design.field(box, "Dashboard password", cfg.dashboardPassword, secret = true)
        password.isFocusable = false
        design.switch(box, "Show password", "Use this password when connecting from another device.", false) { show ->
            password.transformationMethod = if (show) null else android.text.method.PasswordTransformationMethod.getInstance()
        }
        AlertDialog.Builder(this).setTitle("Dashboard login").setView(box).setPositiveButton("Done", null).show()
    }

    private fun showLogs() {
        val daemons = ServerService.supervisor?.daemons.orEmpty()
        val choices = arrayOf("All services") + daemons.map { it.spec.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Service logs").setItems(choices) { _, index ->
            val selected = if (index == 0) daemons else listOf(daemons[index - 1])
            val output = selected.flatMap { d -> d.log.tail(80).map { "[${d.spec.name}] $it" } }.joinToString("\n").ifEmpty { "No activity yet. Start the server to see service logs." }
            val text = design.text(output, 12f).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(design.dp(20), design.dp(12), design.dp(20), design.dp(12)) }
            AlertDialog.Builder(this).setTitle(choices[index]).setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Done", null).show()
        }.show()
    }

    private fun toggleCamera() {
        if (CameraService.instance != null) CameraService.stop(this)
        else {
            val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) armCamera()
            else requestPermissions(permissions, 2)
        }
        refresh()
    }
    private fun armCamera() {
        cfg.cameraEnabled = true
        try { CameraService.arm(this) } catch (e: Exception) { Toast.makeText(this, "IPCam: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.size == 2 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) armCamera()
            else Toast.makeText(this, "Allow camera and microphone permissions to use IPCam", Toast.LENGTH_LONG).show()
        }
    }
    override fun onResume() { super.onResume(); ui.removeCallbacks(tick); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private fun refresh() {
        val sv = ServerService.supervisor
        val live = sv?.daemons.orEmpty().count { it.state == Supervisor.State.RUNNING }
        headline.setTextIfChanged(if (sv != null) "Your server is online" else if (ServerService.active) "Starting your server…" else "Ready when you are")
        summary.setTextIfChanged(if (sv != null) "$live of ${sv.daemons.size} services running" else if (ServerService.active) "Getting your services ready." else "Start your server to bring your apps online.")
        toggle.setTextIfChanged(if (ServerService.active) "Stop server" else "Start server")
        val dev = Device(this)
        battery.setTextIfChanged(if (dev.batteryPercent < 0) "Unknown" else "${dev.batteryPercent}%")
        batteryCaption.setTextIfChanged(if (dev.charging) "Battery · charging" else "Battery")
        temperature.setTextIfChanged("${dev.batteryTempC} °C")
        memory.setTextIfChanged("%.1f GB".format(dev.ramFreeMb / 1024.0))
        disk.setTextIfChanged("%.1f GB".format(dev.storageFreeMb / 1024.0))
        heat.setVisibleIfChanged(dev.hot)
        address.setTextIfChanged(dev.ips.firstOrNull()?.let { "http://$it:${cfg.dashboardPort}" } ?: "Connect to Wi-Fi to access from another device.")
        camera.setTextIfChanged(if (CameraService.instance == null) "Enable camera access" else "Stop camera access")
        val names = sv?.daemons.orEmpty().map { it.spec.name }
        if (names != serviceRows.keys.toList() || services.childCount == 0) {
            services.removeAllViews(); serviceRows.clear()
            if (names.isEmpty()) services.addView(design.text("Services appear here when the server starts.", 14f, design.muted).apply { setPadding(0, 0, 0, design.dp(16)) })
            sv?.daemons?.forEach { daemon ->
                val card = design.card()
                card.addView(design.text(daemon.spec.name.replaceFirstChar { it.uppercase() }, 16f, design.ink, true))
                val state = design.text("", 13f, design.muted); serviceRows[daemon.spec.name] = state; card.addView(state)
                card.addView(design.button("Restart service") { ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == daemon.spec.name }?.restart() })
                services.addView(card)
            }
        }
        sv?.daemons?.forEach { d ->
            serviceRows[d.spec.name]?.apply {
                setTextIfChanged(when (d.state) {
                    Supervisor.State.RUNNING -> "Running"
                    Supervisor.State.STARTING -> "Starting…"
                    Supervisor.State.STOPPED -> "Stopped"
                    Supervisor.State.BACKOFF -> "Needs attention · retrying"
                } + if (d.restarts > 0) " · ${d.restarts} restarts" else "")
                setTextColor(if (d.state == Supervisor.State.BACKOFF) design.warning else design.muted)
            }
        }
    }
}
