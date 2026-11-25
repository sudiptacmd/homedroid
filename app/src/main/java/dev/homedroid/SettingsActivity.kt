package dev.homedroid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import java.io.File

/** Phone settings, grouped by topic; edits happen in bottom sheets and apply when saved. */
class SettingsActivity : MobileActivity() {
    private lateinit var cfg: Config
    private val p get() = design.p
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var keepOn: MobileUi.SwitchRow
    private lateinit var burnIn: MobileUi.SwitchRow
    private lateinit var standbyAfter: MobileUi.ListRow
    private lateinit var autostart: MobileUi.SwitchRow
    private lateinit var ssh: MobileUi.SwitchRow
    private lateinit var sshPort: MobileUi.ListRow
    private lateinit var web: MobileUi.SwitchRow
    private lateinit var tailscale: MobileUi.SwitchRow
    private lateinit var tunnel: MobileUi.ListRow
    private lateinit var repeater: MobileUi.SwitchRow
    private lateinit var repeaterInfo: MobileUi.ListRow
    private lateinit var dns: MobileUi.ListRow
    private lateinit var mcp: MobileUi.SwitchRow
    private lateinit var mcpWrite: MobileUi.SwitchRow
    private lateinit var mcpToken: MobileUi.ListRow
    private lateinit var lock: MobileUi.SwitchRow
    private lateinit var changePin: MobileUi.ListRow
    private var biometric: MobileUi.SwitchRow? = null
    private lateinit var lockAfter: MobileUi.ListRow
    private lateinit var background: MobileUi.ListRow
    private lateinit var backgroundPill: MobileUi.Pill

    private val lockTimes = listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", 900 to "After 15 minutes")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        val page = design.page("Settings", "Settings")

        // Display
        val look = design.group(page, "Display")
        look.item(design.column().apply {
            setPadding(design.dp(Space.LG), design.dp(Space.MD), design.dp(Space.LG), design.dp(Space.MD))
            val head = design.row()
            head.addView(design.iconBadge(Icon.Contrast), design.lp(design.dp(40), design.dp(40), end = Space.LG))
            val words = design.column()
            words.addView(design.text("Theme", TypeStyle.BodyLarge))
            words.addView(design.text("OLED black saves power and is gentlest on OLED screens", TypeStyle.BodyMedium, p.onSurfaceVariant))
            head.addView(words, design.lp(0, -2, 1f))
            addView(head)
            val values = listOf("system", "light", "dark", "oled")
            addView(design.segmented(listOf("Auto", "Light", "Dark", "OLED"), values.indexOf(cfg.appearance).coerceAtLeast(0)) { i ->
                cfg.appearance = values[i]; ui.postDelayed({ recreate() }, 180)
            }.view, design.lp(top = Space.SM))
        })
        if (Palette.dynamicAvailable()) look.item(design.switchRow(Icon.Swatch, "Wallpaper colours", "Match the colours of your phone's wallpaper", cfg.dynamicColor) {
            cfg.dynamicColor = it; ui.postDelayed({ recreate() }, 250)
        }.view)
        keepOn = design.switchRow(Icon.Phone, "Keep screen on", "On the Overview, for a phone used as a status display. Off lets the screen time out as usual.",
            cfg.keepScreenOn) { cfg.keepScreenOn = it }
        look.item(keepOn.view)
        burnIn = design.switchRow(Icon.Shield, "Burn-in protection", "", cfg.burnInProtection) { cfg.burnInProtection = it; bind() }
        look.item(burnIn.view)
        standbyAfter = design.listRow(Icon.Clock, "Standby after", "Time without a touch before the Overview dims") {
            design.choose("Standby after", BurnIn.MINUTES.map { (if (it == 1) "1 minute" else "$it minutes") to null },
                BurnIn.MINUTES.indexOf(cfg.standbyMinutes)) { i -> cfg.standbyMinutes = BurnIn.MINUTES[i]; bind() }
        }
        look.item(standbyAfter.view)
        page.addView(design.text("Dock mode has its own, stronger OLED care for phones that stay on a stand all day.",
            TypeStyle.BodySmall, p.onSurfaceVariant), design.lp(top = Space.SM, start = Space.LG, end = Space.LG))

        // Server
        val server = design.group(page, "Server")
        autostart = design.switchRow(Icon.Power, "Start on boot", "Bring the server back when this phone restarts", cfg.autostart) { cfg.autostart = it }
        server.item(autostart.view)

        // Services & connections
        val services = design.group(page, "Services & connections", "Changes restart services that are running.")
        ssh = design.switchRow(Icon.Terminal, "SSH & SFTP", "", cfg.sshEnabled) { cfg.sshEnabled = it; applyServices() }
        services.item(ssh.view)
        sshPort = design.listRow(Icon.Plug, "SSH port", "Change it if another app already uses this port") { editSshPort() }
        services.item(sshPort.view)
        web = design.switchRow(Icon.Globe, "Web hosting", "Serve websites with Caddy · port ${cfg.webPort}", cfg.webEnabled) { cfg.webEnabled = it; applyServices() }
        services.item(web.view)
        tailscale = design.switchRow(Icon.Nodes, "Tailscale", "Private access from your own devices, anywhere", cfg.tailscaleEnabled) { cfg.tailscaleEnabled = it; applyServices() }
        services.item(tailscale.view)
        services.item(design.listRow(Icon.Key, "Tailscale setup", "Device name and optional auth key") { editTailscale() }.view)
        repeater = design.switchRow(Icon.Wifi, "Wi-Fi repeater", "", cfg.repeaterEnabled) { setRepeater(it) }
        services.item(repeater.view)
        repeaterInfo = design.listRow(Icon.Key, "Repeater network", "Name, passphrase and proxy address") { repeaterSheet() }
        services.item(repeaterInfo.view)
        tunnel = design.listRow(Icon.Cloud, "Cloudflare Tunnel", "Reach this phone from the internet") { editTunnel() }
        services.item(tunnel.view)
        dns = design.listRow(Icon.Server, "App DNS servers", "Fixes app installs that fail with DNS errors") { editDns() }
        services.item(dns.view)
        services.item(design.listRow(Icon.Tune, "All modules", "AI, camera and advanced network options", Trailing.External) {
            DashboardActivity.open(this, "modules")
        }.view)

        // MCP server
        val ai = design.group(page, "MCP server", "Let AI assistants like Claude check on and manage this phone.")
        mcp = design.switchRow(Icon.Sparkle, "MCP server", "", cfg.mcpEnabled) { on ->
            cfg.mcpEnabled = on; bind()
            if (on) design.snackbar("MCP server on. Copy the token into your assistant.", "Token") { tokenSheet() }
        }
        ai.item(mcp.view)
        mcpWrite = design.switchRow(Icon.Shield, "Allow changes", "", cfg.mcpAllowWrite) { cfg.mcpAllowWrite = it; bind() }
        ai.item(mcpWrite.view)
        mcpToken = design.listRow(Icon.Key, "Access token", "Copy it into your MCP client") { tokenSheet() }
        ai.item(mcpToken.view)

        // App lock
        val security = design.group(page, "App lock", "Ask for a PIN before anyone can open Homedroid on this phone.")
        lock = design.switchRow(Icon.Lock, "PIN lock", "4 to 8 digits", AppLock.enabled(this)) { on -> if (on) setPin(change = false) else removePin() }
        security.item(lock.view)
        changePin = design.listRow(Icon.Key, "Change PIN", null) { setPin(change = true) }
        security.item(changePin.view)
        if (AppLock.biometricAvailable(this)) {
            biometric = design.switchRow(Icon.Fingerprint, "Fingerprint or face", "Unlock without typing your PIN", AppLock.biometricEnabled(this)) {
                AppLock.setBiometricEnabled(this, it)
            }.also { security.item(it.view) }
        }
        lockAfter = design.listRow(Icon.Clock, "Lock after", "Time in the background before the PIN is asked again") {
            val current = lockTimes.indexOfFirst { it.first == AppLock.timeoutSeconds(this) }
            design.choose("Lock after", lockTimes.map { it.second to null }, current) { i ->
                AppLock.setTimeoutSeconds(this, lockTimes[i].first); bind()
            }
        }
        security.item(lockAfter.view)

        // Access & security
        val access = design.group(page, "Access")
        access.item(design.listRow(Icon.Key, "Dashboard password", "Change it and sign out browsers") { editPassword() }.view)
        access.item(design.listRow(Icon.Shield, "SSH public keys", "Choose who can connect to this phone") { editKeys() }.view)
        access.item(design.listRow(Icon.Terminal, "Terminal & access history", "Open a shell and review connections", Trailing.External) {
            DashboardActivity.open(this, "ssh")
        }.view)

        // Phone & permissions
        val phone = design.group(page, "Phone & permissions")
        background = design.listRow(Icon.Bolt, "Background running", "") {
            val pm = getSystemService(PowerManager::class.java)
            val intent = if (pm.isIgnoringBatteryOptimizations(packageName)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            systemSettings(intent)
        }
        backgroundPill = design.pill()
        background.end.addView(backgroundPill.view, 0, design.lp(-2, -2, start = Space.SM))
        phone.item(background.view)
        phone.item(design.listRow(Icon.Bell, "App permissions", "Camera, microphone and notifications", Trailing.External) {
            systemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.view)
        phone.item(design.listRow(Icon.Folder, "Shared storage access", "Phone storage, SD cards and USB drives", Trailing.External) { Storage.requestAccess(this) }.view)
        if (Build.VERSION.SDK_INT >= 31) phone.item(design.listRow(Icon.Info, "Keep services reliable", "One-time Android setup from a computer") {
            val s = design.sheet("Keep services reliable", "Android 12 and newer stop apps that run many processes. Do this once from a computer with adb.")
            s.body.addView(design.text(getString(R.string.phantom_tip), TypeStyle.BodyMedium, p.onSurfaceVariant).apply {
                setTextIsSelectable(true); background = design.shape(p.surfaceHighest, Shape.MD)
                setPadding(design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD))
            }, design.lp(top = Space.SM))
            s.done(); s.show()
        }.view)

        // About
        val about = design.group(page, "About")
        about.item(design.listRow(Icon.Download, "App updates", "Check for an update or install an APK", Trailing.External) { DashboardActivity.open(this, "settings") }.view)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        page.addView(design.text("Homedroid $version\nYour old phone, now a home server.", TypeStyle.BodySmall, p.onSurfaceVariant).apply {
            gravity = android.view.Gravity.CENTER
        }, design.lp(top = Space.XXL))

        bind()
        if (savedInstanceState == null) design.enter(page)
    }

    override fun onResumeUnlocked() = bind()

    /** Shows the current values; settings can also change from the web dashboard. */
    private fun bind() {
        keepOn.set(cfg.keepScreenOn, animate = false)
        burnIn.set(cfg.burnInProtection, animate = false)
        burnIn.row.setSupporting(if (cfg.burnInProtection) "An untouched Overview turns into a dim clock that moves every minute"
            else "Off: the Overview stays as it is while the screen is on")
        standbyAfter.setVisible(cfg.burnInProtection)
        standbyAfter.setValue(if (cfg.standbyMinutes == 1) "1 min" else "${cfg.standbyMinutes} min")
        autostart.set(cfg.autostart, animate = false)
        ssh.set(cfg.sshEnabled, animate = false)
        ssh.row.setSupporting("Secure shell and file transfers · port ${cfg.sshPort}")
        sshPort.setValue(cfg.sshPort.toString())
        web.set(cfg.webEnabled, animate = false)
        tailscale.set(cfg.tailscaleEnabled, animate = false)
        val rep = WifiRepeater.get(this).json()
        repeater.set(cfg.repeaterEnabled, animate = false)
        repeater.row.setSupporting(when (rep.getString("state")) {
            "running" -> "On · ${rep.getJSONObject("network").getString("name")} · ${rep.getJSONObject("clients").getInt("count")} connected"
            "off" -> "Share this phone's internet with other devices through a proxy"
            else -> rep.getString("message").ifEmpty { "Starting…" }
        })
        repeaterInfo.setVisible(cfg.repeaterEnabled)
        tunnel.setValue(if (cfg.tunnelToken.isNotEmpty()) "Connected" else "Off")
        dns.setValue(cfg.appsDns.ifEmpty { "Automatic" })

        mcp.set(cfg.mcpEnabled, animate = false)
        val ip = Device.localIps().firstOrNull()
        mcp.row.setSupporting(if (!cfg.mcpEnabled) "Off · tools for status, logs and apps" else if (ip != null) "On · http://$ip:${cfg.dashboardPort}/mcp" else "On · connect to Wi-Fi to use it")
        mcpWrite.set(cfg.mcpAllowWrite, animate = false)
        mcpWrite.setEnabled(cfg.mcpEnabled)
        mcpWrite.row.setSupporting(if (cfg.mcpAllowWrite) "Assistants can restart services, redeploy and wake devices" else "Read-only: assistants can look but not change anything")
        mcpToken.setEnabled(cfg.mcpEnabled)

        val locked = AppLock.enabled(this)
        lock.set(locked, animate = false)
        changePin.setVisible(locked)
        lockAfter.setVisible(locked)
        lockAfter.setValue(lockTimes.firstOrNull { it.first == AppLock.timeoutSeconds(this) }?.second?.removePrefix("After ")
            ?: "${AppLock.timeoutSeconds(this)} s")
        biometric?.let { it.row.setVisible(locked); it.set(AppLock.biometricEnabled(this), animate = false) }

        val allowed = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        background.setSupporting(if (allowed) "Battery optimization is off for Homedroid" else "Allow it so your server keeps running with the screen off")
        backgroundPill.set(if (allowed) "Allowed" else "Action needed", if (allowed) Tone.Success else Tone.Warning)
    }

    private fun systemSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private var repeaterPending = false

    private fun setRepeater(on: Boolean) {
        val r = WifiRepeater.get(this)
        if (on && !r.hasPermission()) {
            repeaterPending = true
            requestPermissions(arrayOf(WifiRepeater.permission()), REQUEST_REPEATER)
            return
        }
        applyRepeater(on)
    }

    private fun applyRepeater(on: Boolean) {
        val problem = WifiRepeater.get(this).setEnabled(on)
        if (problem != null) design.snackbar(problem)
        else if (on) design.snackbar(if (ServerService.active) "Creating the Wi-Fi network…" else "On. It starts with the server.")
        ui.postDelayed({ bind() }, 1500)
        bind()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_REPEATER || !repeaterPending) return
        repeaterPending = false
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) applyRepeater(true)
        else { repeater.set(false); design.snackbar("The repeater needs permission to create a Wi-Fi network") }
    }

    private fun repeaterSheet() {
        val j = WifiRepeater.get(this).json()
        val net = j.getJSONObject("network")
        val proxy = j.getJSONObject("proxy")
        val s = design.sheet("Repeater network", "Join this network from another device, then set a proxy there.")
        val text = "Network: ${net.getString("name")}\nPassphrase: ${net.getString("passphrase")}\nProxy: ${proxy.getString("http")}\nSOCKS5: ${proxy.getString("socks")}\nAuto-config: ${proxy.getString("pac")}\n\nThe web dashboard (Modules, Wi-Fi repeater) has a QR code and step-by-step help for each device."
        s.body.addView(design.text(text, TypeStyle.BodyMedium, p.onSurface).apply {
            setTextIsSelectable(true); background = design.shape(p.surfaceHighest, Shape.MD)
            setPadding(design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD), design.dp(Space.MD))
        }, design.lp(top = Space.SM))
        s.done(); s.show()
    }

    private fun applyServices() { if (ServerService.active) ServerService.restart(this) }

    private fun saved(message: String = "Saved") = design.snackbar(if (ServerService.active) "$message. Services are restarting." else message)

    /** A bottom sheet with fields; [save] returns true when the input was valid and applied. */
    private fun editor(title: String, subtitle: String?, action: String = "Save", build: (MobileUi.Sheet) -> (() -> Boolean)) {
        val s = design.sheet(title, subtitle)
        val save = build(s)
        s.actions(action) { save() }
        s.show()
    }

    // --- services ---------------------------------------------------------------------------

    private fun editSshPort() = editor("SSH port", "Another SSH server on the phone (Termux's, for one) may already use $DEFAULT_SSH.") { s ->
        val port = design.field(s.body, "Port", cfg.sshPort.toString(), numeric = true, supporting = "1024–65535. Homedroid uses 8080, 8800 and 8801.")
        val save: () -> Boolean = save@{
            port.error(null)
            val value = port.value.trim().toIntOrNull()
            if (value == null || !Config.validPort(value)) { port.error("Choose a port between 1024 and 65535 that Homedroid doesn't use"); return@save false }
            if (value != cfg.sshPort) {
                if (!portFree(value) || Deploys(this, Paths(this)).usesPort(value)) { port.error("Another app on the phone is already using port $value"); return@save false }
                cfg.sshPort = value
                if (cfg.sshEnabled && ServerService.active) ServerService.restart(this)
                design.snackbar("SSH now uses port $value" + if (cfg.sshEnabled && ServerService.active) ". Restarting services." else ".")
            }
            bind(); true
        }
        save
    }

    private fun portFree(port: Int) = try { java.net.ServerSocket(port).close(); true } catch (_: java.io.IOException) { false }

    private fun editDns() = editor("App DNS servers",
        "If installing apps fails with DNS errors (\"could not resolve host\", \"temporary error\"), your network's DNS may not work inside apps. Set public servers here.") { s ->
        val field = design.field(s.body, "DNS servers", cfg.appsDns, hint = "Automatic",
            supporting = "Up to three IP addresses, separated by commas. Leave empty for automatic.")
        val presets = FlowLayout(this, design.dp(Space.SM), 0)
        listOf("Cloudflare" to "1.1.1.1, 1.0.0.1", "Google" to "8.8.8.8, 8.8.4.4", "Quad9" to "9.9.9.9, 149.112.112.112", "Automatic" to "").forEach { (name, value) ->
            presets.addView(design.chip(name, if (value.isEmpty()) Icon.Refresh else Icon.Server) { field.edit.setText(value); field.error(null) })
        }
        s.body.addView(presets, design.lp(top = Space.SM))
        val save: () -> Boolean = save@{
            field.error(null)
            val list = DnsConfig.parseCustom(field.value) ?: run { field.error("Enter up to three IP addresses, like 1.1.1.1, 9.9.9.9"); return@save false }
            cfg.appsDns = list.joinToString(", ")
            val ctx = applicationContext
            Thread({ runCatching { Alpine(ctx, Paths(ctx)).refresh() } }, "dns").start()
            design.snackbar(if (list.isEmpty()) "Using your network's DNS servers" else "Apps now use ${cfg.appsDns}")
            bind(); true
        }
        save
    }

    private fun editTunnel() = editor("Cloudflare Tunnel", "Paste your tunnel token to connect; clear it to disconnect.") { s ->
        val token = design.field(s.body, "Tunnel token", cfg.tunnelToken, secret = true, supporting = "From Cloudflare Zero Trust → Networks → Tunnels.")
        val save: () -> Boolean = { cfg.tunnelToken = token.value.trim(); applyServices(); saved(if (cfg.tunnelToken.isEmpty()) "Tunnel disconnected" else "Tunnel token saved"); bind(); true }
        save
    }

    private fun editTailscale() = editor("Tailscale setup", "Without an auth key, use the login link in Control panel → Modules → Tailscale.") { s ->
        val host = design.field(s.body, "Device name", cfg.tailscaleHostname)
        val key = design.field(s.body, "Auth key (optional)", cfg.tailscaleAuthKey, secret = true)
        val save: () -> Boolean = {
            host.error(null)
            val value = host.value.trim().lowercase()
            if (!PhoneSettings.validHostname(value)) { host.error("Use 1–63 letters, numbers or hyphens; start and end with a letter or number"); false }
            else { cfg.tailscaleHostname = value; cfg.tailscaleAuthKey = key.value.trim(); applyServices(); saved(); true }
        }
        save
    }

    // --- MCP --------------------------------------------------------------------------------

    private fun tokenSheet() {
        val s = design.sheet("MCP access token", "Assistants send this as a bearer token. Keep it private, like a password.")
        val token = design.field(s.body, "Token", cfg.mcpToken, secret = true, mono = true)
        token.edit.isFocusable = false; token.edit.isLongClickable = false
        val ip = Device.localIps().firstOrNull()
        val endpoint = ip?.let { "http://$it:${cfg.dashboardPort}/mcp" }
        if (endpoint != null) {
            s.body.addView(design.text("Endpoint", TypeStyle.LabelLarge, p.onSurfaceVariant), design.lp(top = Space.LG, start = 4))
            s.body.addView(design.text(endpoint, TypeStyle.BodyLarge).apply { setTextIsSelectable(true) }, design.lp(top = Space.XS, start = 4))
        }
        val actions = FlowLayout(this, design.dp(Space.SM), 0)
        actions.addView(design.button("Copy token", ButtonKind.Tonal, Icon.Copy) { design.copy("MCP token", cfg.mcpToken, sensitive = true, host = s.frame) })
        if (endpoint != null) actions.addView(design.button("Copy endpoint", ButtonKind.Outlined, Icon.Copy) { design.copy("MCP endpoint", endpoint, host = s.frame) })
        actions.addView(design.button("New token", ButtonKind.DangerText, Icon.Refresh) {
            design.confirm("Make a new token?", "Assistants using the current token stop working until you give them the new one.", "New token", destructive = true) {
                cfg.mcpToken = McpCore.newToken()
                token.edit.setText(cfg.mcpToken)
                s.snackbar("New token made")
            }
        })
        s.body.addView(actions, design.lp(top = Space.LG))
        s.done()
        s.show()
    }

    // --- app lock ---------------------------------------------------------------------------

    private fun setPin(change: Boolean) {
        val s = design.sheet(if (change) "Change PIN" else "Set a PIN", "Use 4 to 8 digits. You'll need it to open Homedroid on this phone.")
        val current = if (change) design.field(s.body, "Current PIN", "", secret = true, numeric = true) else null
        val pin = design.field(s.body, "New PIN", "", secret = true, numeric = true)
        val again = design.field(s.body, "Confirm new PIN", "", secret = true, numeric = true)
        var done = false
        s.actions(if (change) "Change PIN" else "Turn on") {
            listOfNotNull(current, pin, again).forEach { it.error(null) }
            val wait = AppLock.waitMs(this)
            when {
                current != null && wait > 0 -> { current.error("Too many wrong PINs. Try again in ${(wait + 999) / 1000} s."); false }
                current != null && !AppLock.tryPin(this, current.value) -> { current.error("Wrong PIN"); current.edit.setText(""); false }
                !AppLockCore.validPin(pin.value) -> { pin.error("Use 4 to 8 digits"); false }
                pin.value != again.value -> { again.error("The PINs don't match"); again.edit.setText(""); false }
                else -> {
                    AppLock.setPin(this, pin.value)
                    done = true
                    design.snackbar(if (change) "PIN changed" else "App lock is on")
                    true
                }
            }
        }
        s.onClosed = { bind(); if (!done && !change) lock.set(false) }
        s.show()
    }

    private fun removePin() {
        val s = design.sheet("Turn off app lock?", "Enter your PIN to confirm. Anyone with this phone can then open Homedroid.")
        val pin = design.field(s.body, "PIN", "", secret = true, numeric = true)
        s.actions("Turn off", ButtonKind.Danger) {
            pin.error(null)
            val wait = AppLock.waitMs(this)
            when {
                wait > 0 -> { pin.error("Too many wrong PINs. Try again in ${(wait + 999) / 1000} s."); false }
                !AppLock.tryPin(this, pin.value) -> { pin.error("Wrong PIN"); pin.edit.setText(""); false }
                else -> { AppLock.clearPin(this); design.snackbar("App lock is off"); true }
            }
        }
        s.onClosed = { bind() }
        s.show()
    }

    // --- access -----------------------------------------------------------------------------

    private fun editPassword() = editor("Dashboard password", "Saving signs out every browser that's signed in.") { s ->
        val password = design.field(s.body, "New password", "", secret = true, supporting = "12 to 128 characters.")
        val confirm = design.field(s.body, "Confirm password", "", secret = true)
        val save: () -> Boolean = {
            listOf(password, confirm).forEach { it.error(null) }
            val value = password.value
            when {
                !PhoneSettings.validPassword(value) -> { password.error("Use 12 to 128 characters without control characters"); false }
                value != confirm.value -> { confirm.error("The passwords don't match"); false }
                else -> { Dashboard.changePasswordFromPhone(this, value); design.snackbar("Password changed. Browsers were signed out."); true }
            }
        }
        save
    }

    private fun editKeys() = editor("SSH public keys", "One authorized_keys entry per line. Changes apply to new connections right away.") { s ->
        val paths = Paths(this)
        val keys = design.field(s.body, "Authorized keys", paths.authorizedKeys.takeIf { it.exists() }?.readText().orEmpty(),
            multiline = true, mono = true, hint = "ssh-ed25519 AAAA… you@laptop", supporting = "Paste public keys only (the .pub file).")
        val save: () -> Boolean = save@{
            keys.error(null)
            val value = keys.value.trim()
            if (value.contains("PRIVATE KEY")) { keys.error("That's a private key. Paste the public key (.pub) instead."); return@save false }
            paths.authorizedKeys.parentFile!!.mkdirs()
            val tmp = File(paths.authorizedKeys.path + ".phone.tmp")
            tmp.writeText(if (value.isEmpty()) "" else "$value\n")
            check(tmp.renameTo(paths.authorizedKeys)) { "Couldn't save the SSH keys" }
            design.snackbar("SSH keys saved")
            true
        }
        save
    }

    private companion object {
        const val DEFAULT_SSH = Config.DEFAULT_SSH_PORT
        const val REQUEST_REPEATER = 77
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

}
