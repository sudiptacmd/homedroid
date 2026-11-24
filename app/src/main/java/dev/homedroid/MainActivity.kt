package dev.homedroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** The phone's landing screen: server state, how to connect, vitals and services. */
class MainActivity : MobileActivity() {
    private lateinit var cfg: Config
    private val p get() = design.p

    // Hero
    private lateinit var hero: LinearLayout
    private lateinit var heroBg: GradientDrawable
    private lateinit var status: MobileUi.Pill
    private lateinit var phone: TextView
    private lateinit var headline: TextView
    private lateinit var summary: TextView
    private lateinit var toggle: UiButton
    private lateinit var connect: LinearLayout
    private lateinit var connectBg: GradientDrawable
    private lateinit var connectLabel: TextView
    private lateinit var url: TextView
    private lateinit var noAddress: TextView
    private val heroTexts = mutableListOf<TextView>()
    private val heroIcons = mutableListOf<IconView>()
    private var heroOnline: Boolean? = null

    // Vitals
    private lateinit var heat: MobileUi.Banner
    private lateinit var battery: MobileUi.GaugeTile
    private lateinit var temperature: MobileUi.GaugeTile
    private lateinit var cpu: MobileUi.GaugeTile
    private lateinit var memory: MobileUi.GaugeTile
    private lateinit var storage: MobileUi.GaugeTile
    private lateinit var network: MobileUi.GaugeTile

    // Services
    private lateinit var services: MobileUi.GroupLayout
    private lateinit var servicesEmpty: View
    private val serviceRows = linkedMapOf<String, Pair<MobileUi.ListRow, MobileUi.Pill>>()
    private lateinit var camera: MobileUi.SwitchRow

    // Burn-in protection: a dim, drifting standby view after a while without a touch.
    private var lastTouch = SystemClock.elapsedRealtime()
    private var standby: FrameLayout? = null
    private var standbySince = 0L
    private var standbyStep = -1L
    private var lastBattery = ""
    private var swallowGesture = false

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var sampling = false
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            checkStandby()
            if (standby == null) refresh()
            sampleVitals() // standby shows the battery too
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        Paths(this).ensure()
        val page = design.page("Overview", "Overview")
        page.addView(buildHero())

        heat = design.banner(Tone.Warning, Icon.Thermometer, "Your phone is warm",
            "Keep it ventilated and out of the sun, and pause heavy work like transcoding until it cools down.")
        heat.view.visibility = View.GONE
        page.addView(heat.view)

        page.addView(design.header("Vitals"))
        battery = design.gaugeTile(Icon.Battery, "Battery")
        temperature = design.gaugeTile(Icon.Thermometer, "Temperature")
        cpu = design.gaugeTile(Icon.Cpu, "CPU")
        memory = design.gaugeTile(Icon.Memory, "Memory")
        storage = design.gaugeTile(Icon.Storage, "Storage")
        network = design.gaugeTile(Icon.Network, "Network", ring = false)
        design.grid(page, listOf(battery, temperature, cpu, memory, storage, network).map { it.view })

        services = design.group(page, "Services")
        servicesEmpty = design.state(Icon.Server, "No services running",
            "SSH, web hosting, tunnels and your apps appear here once the server starts.")
        servicesEmpty.layoutParams = design.lp()
        page.addView(servicesEmpty)
        val logs = design.group(page)
        logs.item(design.listRow(Icon.Logs, "Service logs", "Recent activity from every service") { showLogs() }.view)
        (logs.layoutParams as LinearLayout.LayoutParams).topMargin = design.dp(Space.SM)

        page.addView(design.header("Quick actions"))
        design.grid(page, listOf(
            design.actionTile(Icon.Tune, "Control panel", "Every setting and module") { DashboardActivity.open(this, "overview") },
            design.actionTile(Icon.Folder, "Files", "Browse and upload", tone = Tone.Primary) { DashboardActivity.open(this, "files") },
            design.actionTile(Icon.Sparkle, "AI & routines", "Models, chat, briefs") { DashboardActivity.open(this, "ai") },
            design.actionTile(Icon.Layers, "Deployments", "Host projects from Git") { DashboardActivity.open(this, "deploys") },
            design.actionTile(Icon.Nodes, "Your phones", "Pair and manage a cluster") { DashboardActivity.open(this, "cluster") },
            design.actionTile(Icon.Camera, "Camera", "Live view and recordings") { DashboardActivity.open(this, "camera") },
        ))
        val cameraGroup = design.group(page)
        camera = design.switchRow(Icon.Camera, "Camera & microphone access", "Lets the control panel use this phone's camera",
            CameraService.instance != null) { toggleCamera() }
        cameraGroup.item(camera.view)

        page.addView(design.header("Tools", "More ways to put this phone to work"))
        design.grid(page, listOf(
            design.actionTile(Icon.Dock, "Dock", "Standby clock and status while charging", tone = Tone.Primary, action = { DockActivity.open(this) }),
            // TODO(macropads): wire MacroPads once MacroPadActivity lands (feature branch): make the
            //  tile clickable with { MacroPadActivity.open(this) } and drop the "Soon" badge.
            design.actionTile(Icon.Keypad, "MacroPads", "Control your PC with custom buttons", badge = "Soon", tone = Tone.Neutral, action = null),
        ))

        if (savedInstanceState == null) design.enter(page)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        refresh()
    }

    private fun buildHero(): LinearLayout {
        heroBg = design.shape(p.surfaceHigh, Shape.XL)
        hero = design.column().apply {
            background = heroBg
            setPadding(design.dp(Space.XL), design.dp(Space.XL), design.dp(Space.XL), design.dp(Space.XL))
            layoutParams = design.lp(bottom = Space.MD)
        }
        val top = design.row()
        status = design.pill("Offline", Tone.Neutral)
        top.addView(status.view)
        top.addView(View(this), design.lp(0, 1, 1f))
        val phoneIcon = design.icon(Icon.Phone, p.onSurfaceVariant, 18)
        heroIcons += phoneIcon
        top.addView(phoneIcon, design.lp(design.dp(18), design.dp(18), end = Space.XS))
        val name = runCatching { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        phone = design.text(name,TypeStyle.LabelLarge, p.onSurfaceVariant).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(phone, design.lp(-2, -2))
        hero.addView(top)

        headline = design.text("Ready when you are", TypeStyle.HeadlineMedium).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        hero.addView(headline, design.lp(top = Space.LG))
        summary = design.text("", TypeStyle.BodyMedium, p.onSurfaceVariant)
        hero.addView(summary, design.lp(top = Space.XS))
        heroTexts += headline

        toggle = design.button("Start server", ButtonKind.Filled, Icon.Power, large = true) { onToggle() }
        hero.addView(toggle, design.lp(top = Space.LG))

        // How to reach the dashboard from another device.
        connectBg = design.shape(p.alpha(p.onSurface, 0.05f), Shape.LG)
        connect = design.column().apply { background = connectBg; setPadding(design.dp(Space.LG), design.dp(Space.MD), design.dp(Space.XS), design.dp(Space.XS)) }
        val line = design.row()
        val words = design.column()
        connectLabel = design.text("Dashboard on your network", TypeStyle.LabelMedium, p.onSurfaceVariant)
        url = design.text("", TypeStyle.TitleMedium).apply { setTextIsSelectable(true); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        words.addView(connectLabel); words.addView(url, design.lp(top = 2))
        line.addView(words, design.lp(0, -2, 1f))
        connect.addView(line)
        val actions = design.row()
        actions.addView(design.iconButton(Icon.Copy, "Copy dashboard address") { dashboardUrl()?.let { design.copy("Dashboard address", it) } })
        actions.addView(design.iconButton(Icon.Share, "Share dashboard address") { share() })
        actions.addView(design.iconButton(Icon.Key, "Dashboard login") { showCredentials() })
        actions.addView(View(this), design.lp(0, 1, 1f))
        actions.addView(design.button("Open", ButtonKind.Text, Icon.OpenExternal) { DashboardActivity.open(this, "overview") }.apply {
            contentDescription = "Open the control panel"
        })
        connect.addView(actions, design.lp(top = Space.XS))
        noAddress = design.text("Connect to Wi-Fi to reach the dashboard from your other devices.", TypeStyle.BodyMedium, p.onSurfaceVariant)
        heroTexts += url
        hero.addView(connect, design.lp(top = Space.LG))
        hero.addView(noAddress, design.lp(top = Space.LG))
        return hero
    }

    private fun dashboardUrl(): String? = Device.localIps().firstOrNull()?.let { "http://$it:${cfg.dashboardPort}" }

    private fun share() {
        val address = dashboardUrl() ?: return design.snackbar("Connect to Wi-Fi to share an address")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Homedroid dashboard: $address")
        }, "Share dashboard"))
    }

    private fun onToggle() {
        if (ServerService.active) {
            design.confirm("Stop your server?", "Apps, remote connections, the dashboard and camera access stop until you start it again.",
                "Stop server", destructive = true) {
                ServerService.stop(this); design.haptic(toggle, Haptic.Confirm)
                ui.postDelayed(::refresh, 300)
            }
        } else {
            design.haptic(toggle, Haptic.Confirm)
            ServerService.start(this); ui.postDelayed(::refresh, 300)
        }
    }

    private fun showCredentials() {
        val s = design.sheet("Dashboard login", "Sign in with this password when you open the dashboard from another device.")
        val field = design.field(s.body, "Password", cfg.dashboardPassword, secret = true)
        field.edit.isFocusable = false; field.edit.isLongClickable = false
        val row = design.row()
        row.addView(design.button("Copy password", ButtonKind.Tonal, Icon.Copy) { design.copy("Password", cfg.dashboardPassword, sensitive = true, host = s.frame) })
        row.addView(design.button("Change", ButtonKind.Text) { s.close(); startActivity(Intent(this, SettingsActivity::class.java)) }, design.lp(-2, -2, start = Space.SM))
        s.body.addView(row, design.lp(top = Space.LG))
        s.done()
        s.show()
    }

    private fun showLogs() {
        val daemons = ServerService.supervisor?.daemons.orEmpty()
        if (daemons.isEmpty()) { logSheet("Service logs", null); return }
        val options = listOf("All services" to null as String?) + daemons.map { serviceName(it.spec.name) to null }
        design.choose("Service logs", options, -1, "Choose a service to see its recent output.") { index ->
            logSheet(options[index].first, if (index == 0) null else daemons[index - 1].spec.name)
        }
    }

    /** The recent output of [service], or of all services when null. */
    private fun logSheet(title: String, service: String?) {
        val daemons = ServerService.supervisor?.daemons.orEmpty().filter { service == null || it.spec.name == service }
        val output = daemons.flatMap { d -> d.log.tail(if (service == null) 40 else 120).map { if (service == null) "[${d.spec.name}] $it" else it } }
            .joinToString("\n").ifEmpty { "No activity yet. Start the server to see service logs." }
        val s = design.sheet(title)
        s.body.addView(logBox(output), design.lp(top = Space.SM))
        val r = design.row()
        r.addView(design.button("Copy", ButtonKind.Tonal, Icon.Copy) { design.copy("Logs", output, host = s.frame) })
        s.body.addView(r, design.lp(top = Space.MD))
        s.done(); s.show()
    }

    private fun logBox(output: String) = design.text(output, TypeStyle.BodySmall, p.onSurfaceVariant).apply {
        typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextIsSelectable(true)
        background = design.shape(p.surfaceHighest, Shape.MD)
        setPadding(design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD))
    }

    private fun serviceSheet(name: String) {
        val d = ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == name } ?: return
        val s = design.sheet(serviceName(name))
        val (label, tone) = stateOf(d)
        s.body.addView(design.pill(label, tone).view, design.lp(-2, -2, top = Space.XS))
        if (d.restarts > 0) s.body.addView(design.text("Restarted ${d.restarts} time${if (d.restarts == 1) "" else "s"} since the server started.",
            TypeStyle.BodyMedium, p.onSurfaceVariant), design.lp(top = Space.MD))
        s.body.addView(design.text("Recent output", TypeStyle.TitleSmall), design.lp(top = Space.LG, bottom = Space.SM))
        s.body.addView(logBox(d.log.tail(60).joinToString("\n").ifEmpty { "No output yet." }))
        s.actions("Restart", ButtonKind.Tonal, "Close") {
            ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == name }?.restart()
            design.snackbar("Restarting ${serviceName(name)}…")
            true
        }
        s.show()
    }

    private fun toggleCamera() {
        if (CameraService.instance != null) CameraService.stop(this)
        else {
            val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) armCamera()
            else requestPermissions(permissions, 2)
        }
        ui.postDelayed(::refresh, 400)
    }

    private fun armCamera() {
        cfg.cameraEnabled = true
        try { CameraService.arm(this) } catch (e: Exception) { design.snackbar("Camera couldn't start: ${e.message}", long = true) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            if (grantResults.size == 2 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) armCamera()
            else design.snackbar("Allow camera and microphone to use the camera from the control panel", "Settings", long = true) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
            }
            refresh()
        }
    }

    override fun onResumeUnlocked() {
        // Holding the screen on is opt-in (Settings → Display); otherwise it times out as usual.
        if (cfg.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastTouch = SystemClock.elapsedRealtime()
        exitStandby()
        ui.removeCallbacks(tick); ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && standby != null) {
            // The tap that wakes the view up shouldn't also press whatever is under it.
            swallowGesture = true
            exitStandby()
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        return super.dispatchKeyEvent(event)
    }

    private fun checkStandby() {
        val now = SystemClock.elapsedRealtime()
        // Touches in a bottom sheet go to its own window; an open sheet counts as in use.
        if (design.openSheets > 0) lastTouch = now
        if (standby == null) {
            if (BurnIn.shouldStandby(cfg.burnInProtection, lastTouch, now, cfg.standbyMinutes)) enterStandby()
            return
        }
        val step = BurnIn.step(standbySince, now)
        if (step != standbyStep) { standbyStep = step; drawStandby() }
    }

    /** A black screen with small, dim status text that moves every minute; a tap returns. */
    private fun enterStandby() {
        val root = findViewById<FrameLayout>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true; isFocusable = true
            contentDescription = "Standby screen. Tap to return to Homedroid."
            setOnClickListener { exitStandby() }
        }
        val box = design.column().apply { tag = "box"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        box.addView(design.text("", TypeStyle.DisplaySmall, Palette.c("#6E6E6E")).apply { tag = "time"; typeface = Typeface.create(Typeface.SANS_SERIF, 300, false) })
        box.addView(design.text("", TypeStyle.BodyMedium, Palette.c("#555555")).apply { tag = "status" })
        overlay.addView(box, FrameLayout.LayoutParams(-2, -2))
        // What's underneath is invisible, so screen readers and keyboards mustn't reach it either.
        for (i in 0 until root.childCount) root.getChildAt(i).let {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (it as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.requestFocus()
        standby = overlay
        standbySince = SystemClock.elapsedRealtime(); standbyStep = 0
        window.attributes = window.attributes.apply { screenBrightness = 0.05f }
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        if (Motion.enabled()) { overlay.alpha = 0f; overlay.animate().alpha(1f).setDuration(Motion.LONG).start() }
        overlay.post { drawStandby() }
    }

    private fun drawStandby() {
        val overlay = standby ?: return
        val box = overlay.findViewWithTag<View>("box") ?: return
        overlay.findViewWithTag<TextView>("time")?.setTextIfChanged(android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date()))
        val sv = ServerService.supervisor
        val state = when {
            sv != null -> "Server online · ${sv.daemons.count { it.state == Supervisor.State.RUNNING }} running"
            ServerService.active -> "Server starting"
            else -> "Server off"
        }
        overlay.findViewWithTag<TextView>("status")?.setTextIfChanged(listOf(state, lastBattery).filter { it.isNotEmpty() }.joinToString(" · "))
        if (overlay.width == 0) return
        box.measure(View.MeasureSpec.makeMeasureSpec(overlay.width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val margin = design.dp(24)
        val (x, y) = BurnIn.position(standbyStep, overlay.width - 2 * margin, overlay.height - 2 * margin, box.measuredWidth, box.measuredHeight)
        box.translationX = (x + margin).toFloat(); box.translationY = (y + margin).toFloat()
    }

    private fun exitStandby() {
        val overlay = standby ?: return
        standby = null
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
        else design.applyWindow() // back to the normal (non-immersive) flags
        val root = overlay.parent as? ViewGroup
        root?.removeView(overlay)
        if (root != null) for (i in 0 until root.childCount) root.getChildAt(i).let {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            (it as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        lastTouch = SystemClock.elapsedRealtime()
        refresh(); sampleVitals()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun refresh() {
        val sv = ServerService.supervisor
        val daemons = sv?.daemons.orEmpty()
        val online = sv != null
        val running = daemons.count { it.state == Supervisor.State.RUNNING }
        val trouble = daemons.count { it.state == Supervisor.State.BACKOFF }
        when {
            online && trouble > 0 -> status.set("Needs attention", Tone.Warning)
            online -> status.set("Online", Tone.Success)
            ServerService.active -> status.set("Starting", Tone.Warning)
            else -> status.set("Offline", Tone.Neutral)
        }
        headline.setTextIfChanged(if (online) "Your server is online" else if (ServerService.active) "Starting your server…" else "Ready when you are")
        summary.setTextIfChanged(when {
            online && daemons.isEmpty() -> "No services are turned on yet."
            online -> "$running of ${daemons.size} services running" + if (trouble > 0) " · $trouble retrying" else ""
            ServerService.active -> "Getting your services ready."
            else -> "Start the server to bring your apps and connections online."
        })
        toggle.setTextIfChanged(if (ServerService.active) "Stop server" else "Start server")
        paintHero(ServerService.active)

        val address = dashboardUrl()
        connect.setVisibleIfChanged(address != null)
        noAddress.setVisibleIfChanged(address == null)
        if (address != null) url.setTextIfChanged(address)
        if (camera.checked != (CameraService.instance != null)) camera.set(CameraService.instance != null)

        // Services: rebuild the rows only when the set of services changes.
        val names = daemons.map { it.spec.name }
        if (names != serviceRows.keys.toList()) {
            services.removeAllViews(); serviceRows.clear()
            for (d in daemons) {
                val row = design.listRow(iconFor(d.spec.name), serviceName(d.spec.name), null, Trailing.None) { serviceSheet(d.spec.name) }
                val pill = design.pill()
                row.end.addView(pill.view, design.lp(-2, -2, start = Space.SM))
                services.item(row.view)
                serviceRows[d.spec.name] = row to pill
            }
        }
        services.setVisibleIfChanged(daemons.isNotEmpty())
        servicesEmpty.setVisibleIfChanged(daemons.isEmpty())
        for (d in daemons) {
            val (row, pill) = serviceRows[d.spec.name] ?: continue
            val (label, tone) = stateOf(d)
            pill.set(label, tone)
            row.setSupporting(if (d.restarts > 0) "${d.restarts} restart${if (d.restarts == 1) "" else "s"}" else null)
        }
    }

    /** Colours the hero by server state: primary container when on, a neutral surface when off. */
    private fun paintHero(on: Boolean) {
        if (heroOnline == on) return
        val first = heroOnline == null
        heroOnline = on
        val bg = if (on) p.primaryContainer else p.surfaceHigh
        val ink = if (on) p.onPrimaryContainer else p.onSurface
        val soft = if (on) p.alpha(p.onPrimaryContainer, 0.8f) else p.onSurfaceVariant
        fun apply(b: Int, i: Int, s: Int) {
            heroBg.setColor(b)
            heroTexts.forEach { it.setTextColor(i) }
            summary.setTextColor(s); phone.setTextColor(s); connectLabel.setTextColor(s); noAddress.setTextColor(s)
            heroIcons.forEach { it.color = s }
        }
        if (first) apply(bg, ink, soft)
        else {
            val oldBg = if (!on) p.primaryContainer else p.surfaceHigh
            design.animateColor(oldBg, bg) { heroBg.setColor(it) }
            apply(bg, ink, soft)
        }
        connectBg.setColor(p.alpha(ink, 0.06f))
        if (on) toggle.paint(p.onPrimaryContainer, p.primaryContainer) else toggle.paint(ButtonKind.Filled)
        toggle.icon = if (on) Icon.Stop else Icon.Power
    }

    private fun sampleVitals() {
        if (sampling) return
        sampling = true
        try {
            worker.execute {
                val dev = Device(this)
                val m = runCatching { Metrics.sample(this) }.getOrNull()
                runOnUiThread {
                    sampling = false
                    if (!isFinishing && !isDestroyed) showVitals(dev, m)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { sampling = false }
    }

    private fun showVitals(dev: Device, m: org.json.JSONObject?) {
        val pct = dev.batteryPercent
        lastBattery = if (pct < 0) "" else "Battery $pct%" + if (dev.charging) " charging" else ""
        battery.set(if (pct < 0) "—" else "$pct%", if (dev.charging) "Charging" else "On battery", if (pct < 0) 0f else pct / 100f,
            when { dev.charging -> Tone.Success; pct in 0..15 -> Tone.Error; pct in 16..30 -> Tone.Warning; else -> Tone.Primary },
            spoken = if (pct < 0) "unknown" else "$pct percent")
        val t = dev.batteryTempC
        val cpuTemp = m?.optDouble("cpuTemp")?.takeIf { !it.isNaN() }
        temperature.set("%.0f °C".format(t), when {
            dev.hot -> "Too warm"
            cpuTemp != null -> "CPU %.0f °C".format(cpuTemp)
            else -> "Battery"
        }, (t - 20f) / 30f, when { dev.hot -> Tone.Error; t >= 40f -> Tone.Warning; else -> Tone.Primary }, spoken = "%.0f degrees Celsius".format(t))
        heat.view.setVisibleIfChanged(dev.hot)

        val load = m?.optDouble("cpu", 0.0) ?: 0.0
        val cores = m?.optInt("cores", Runtime.getRuntime().availableProcessors()) ?: Runtime.getRuntime().availableProcessors()
        cpu.set("%.0f%%".format(load), "$cores cores · servers", (load / 100).toFloat(), if (load >= 85) Tone.Warning else Tone.Primary,
            spoken = "%.0f percent".format(load))

        val ramUsed = 1f - dev.ramFreeMb.toFloat() / dev.ramTotalMb.coerceAtLeast(1)
        memory.set(gb(dev.ramFreeMb) + " free", "of ${gb(dev.ramTotalMb, 0)}", ramUsed, if (ramUsed >= 0.9f) Tone.Warning else Tone.Primary)
        val diskUsed = 1f - dev.storageFreeMb.toFloat() / dev.storageTotalMb.coerceAtLeast(1)
        storage.set(gb(dev.storageFreeMb) + " free", "of ${gb(dev.storageTotalMb, 0)}", diskUsed,
            when { diskUsed >= 0.95f -> Tone.Error; diskUsed >= 0.85f -> Tone.Warning; else -> Tone.Primary })
        val rx = m?.optLong("rxBps", 0) ?: 0
        val tx = m?.optLong("txBps", 0) ?: 0
        network.set("↓ ${rate(rx)}", "↑ ${rate(tx)}", null, Tone.Primary, spoken = "download ${rate(rx)}, upload ${rate(tx)}")
    }

    private fun gb(mb: Long, decimals: Int = 1) = if (decimals == 0) "%.0f GB".format(mb / 1024.0) else "%.1f GB".format(mb / 1024.0)

    private fun rate(bps: Long) = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun stateOf(d: Supervisor.Daemon): Pair<String, Tone> = when (d.state) {
        Supervisor.State.RUNNING -> "Running" to Tone.Success
        Supervisor.State.STARTING -> "Starting" to Tone.Primary
        Supervisor.State.STOPPED -> "Stopped" to Tone.Neutral
        Supervisor.State.BACKOFF -> "Retrying" to Tone.Error
    }

    private fun iconFor(name: String) = when (name) {
        "sshd" -> Icon.Terminal
        "caddy" -> Icon.Globe
        "cloudflared" -> Icon.Cloud
        "tailscaled" -> Icon.Nodes
        "ai-llama" -> Icon.Sparkle
        else -> Icon.Apps
    }

    private fun serviceName(name: String): String = when (name) {
        "sshd" -> "SSH & SFTP"
        "caddy" -> "Web hosting"
        "cloudflared" -> "Cloudflare Tunnel"
        "tailscaled" -> "Tailscale"
        "ai-llama" -> "AI (llama.cpp)"
        else -> name.replaceFirstChar { it.uppercase() }
    }
}
