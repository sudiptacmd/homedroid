package dev.homedroid

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import java.io.File

/** Phone settings apply at the point of interaction; dialogs keep edits explicit. */
class SettingsActivity : MobileActivity() {
    private lateinit var cfg: Config
    private lateinit var backgroundStatus: TextView
    private var renderedSettings = ""
    private fun signature() = listOf(cfg.sshEnabled, cfg.webEnabled, cfg.tailscaleEnabled, cfg.autostart).joinToString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        val page = design.page("Settings", "Make this phone work your way.", "Settings")
        page.addView(design.label("PREFERENCES"))
        page.addView(design.row("Appearance", "${cfg.appearance.replaceFirstChar { it.uppercase() }} · light, dark or follow your phone") {
            val values = arrayOf("system", "light", "dark")
            AlertDialog.Builder(this).setTitle("Appearance")
                .setSingleChoiceItems(arrayOf("Follow phone", "Light", "Dark"), values.indexOf(cfg.appearance)) { dialog, index ->
                    cfg.appearance = values[index]; dialog.dismiss(); recreate()
                }.setNegativeButton(android.R.string.cancel, null).show()
        })
        val startup = design.card()
        design.switch(startup, "Start on boot", "Bring the server back when this phone restarts.", cfg.autostart) { cfg.autostart = it }
        page.addView(startup)
        page.addView(design.label("SERVICES & CONNECTIONS"))
        val modules = design.card()
        modules.addView(design.text("Changes restart active services.", 13f, design.muted))
        design.switch(modules, "SSH & SFTP", "Secure shell and file transfers · port ${cfg.sshPort}", cfg.sshEnabled) { cfg.sshEnabled = it; applyServices() }
        design.switch(modules, "Web hosting", "Serve websites with Caddy · port ${cfg.webPort}", cfg.webEnabled) { cfg.webEnabled = it; applyServices() }
        design.switch(modules, "Tailscale", "Private access from your own devices, anywhere.", cfg.tailscaleEnabled) { cfg.tailscaleEnabled = it; applyServices() }
        page.addView(modules)
        page.addView(design.row("Tailscale setup", "Device name and optional authentication key") { editTailscale() })
        page.addView(design.row("Cloudflare Tunnel", "Connect or disconnect with a tunnel token") { editTunnel() })
        page.addView(design.row("All modules", "AI, camera and advanced network options") { DashboardActivity.open(this, "modules") })
        page.addView(design.label("ACCESS & SECURITY"))
        page.addView(design.row("Dashboard password", "Change the password and sign out browser sessions") { editPassword() })
        page.addView(design.row("SSH public keys", "Choose who can connect to this phone") { editKeys() })
        page.addView(design.row("Terminal & access history", "Open a shell and review recent connections") { DashboardActivity.open(this, "ssh") })
        page.addView(design.label("PHONE & PERMISSIONS"))
        val background = design.card()
        background.addView(design.text("Background running", 17f, design.ink, true))
        backgroundStatus = design.text("", 14f, design.muted); background.addView(backgroundStatus)
        background.addView(design.button("Battery settings") {
            val pm = getSystemService(PowerManager::class.java)
            val intent = if (pm.isIgnoringBatteryOptimizations(packageName)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            systemSettings(intent)
        })
        page.addView(background)
        page.addView(design.row("App permissions", "Camera, microphone and notifications") {
            systemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        page.addView(design.row("Shared storage access", "Allow access to phone storage, SD cards and USB") { Storage.requestAccess(this) })
        if (Build.VERSION.SDK_INT >= 31) page.addView(design.row("Keep services reliable", "One-time Android background process setup") {
            val text = design.text(getString(R.string.phantom_tip), 14f).apply { setTextIsSelectable(true); setPadding(design.dp(24), design.dp(12), design.dp(24), design.dp(12)) }
            AlertDialog.Builder(this).setTitle("Background process setup").setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Done", null).show()
        })
        page.addView(design.label("ABOUT"))
        page.addView(design.row("App updates", "Check for an update or install an APK") { DashboardActivity.open(this, "settings") })
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        page.addView(design.text("Homedroid $version\nYour old phone, now a home server.", 13f, design.muted).apply { setPadding(design.dp(2), design.dp(16), 0, 0) })
        renderedSettings = signature()
    }

    override fun onResume() {
        super.onResume()
        if (renderedSettings != signature()) { recreate(); return }
        backgroundStatus.setTextIfChanged(if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
            "Battery optimization is disabled for Homedroid." else "Allow background running to keep your server available with the screen off.")
    }
    override fun onPause() { renderedSettings = signature(); super.onPause() }
    private fun systemSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private fun applyServices() { if (ServerService.active) ServerService.restart(this) }
    private fun saved() { Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show() }
    private fun editor(title: String, build: (LinearLayout) -> (() -> Boolean)) {
        val content = design.column().apply { setPadding(design.dp(24), design.dp(4), design.dp(24), design.dp(20)) }
        val save = build(content)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { if (save()) { saved(); dialog.dismiss() } }
                catch (e: Exception) { Toast.makeText(this, e.message ?: "Could not save settings", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
    private fun editTunnel() = editor("Cloudflare Tunnel") { box ->
        box.addView(design.text("Paste your tunnel token to connect. Clear it to disconnect. Active services restart after saving.", 14f, design.muted))
        val token = design.field(box, "Tunnel token", cfg.tunnelToken, secret = true)
        val save: () -> Boolean = { cfg.tunnelToken = token.text.toString().trim(); applyServices(); true }
        save
    }
    private fun editTailscale() = editor("Tailscale setup") { box ->
        box.addView(design.text("Without an authentication key, use the login link in Control panel → Modules → Tailscale. Changes restart active services.", 14f, design.muted))
        val host = design.field(box, "Device name", cfg.tailscaleHostname)
        val key = design.field(box, "Authentication key (optional)", cfg.tailscaleAuthKey, secret = true)
        val save: () -> Boolean = {
            val value = host.text.toString().trim().lowercase()
            if (!PhoneSettings.validHostname(value)) { host.error = "Use 1–63 letters, numbers or hyphens; start and end with a letter or number"; false }
            else { cfg.tailscaleHostname = value; cfg.tailscaleAuthKey = key.text.toString().trim(); applyServices(); true }
        }
        save
    }
    private fun editPassword() = editor("Dashboard password") { box ->
        box.addView(design.text("Use 12–128 characters. Saving signs out existing browser sessions.", 14f, design.muted))
        val password = design.field(box, "New password", "", secret = true)
        val confirm = design.field(box, "Confirm password", "", secret = true)
        val save: () -> Boolean = {
            val value = password.text.toString()
            when {
                !PhoneSettings.validPassword(value) -> { password.error = "Use 12–128 characters without control characters"; false }
                value != confirm.text.toString() -> { confirm.error = "Passwords do not match"; false }
                else -> { Dashboard.changePasswordFromPhone(this, value); true }
            }
        }
        save
    }
    private fun editKeys() = editor("SSH public keys") { box ->
        box.addView(design.text("One authorized_keys entry per line. Paste public keys only. Changes apply to new connections immediately.", 14f, design.muted))
        val paths = Paths(this)
        val keys = design.field(box, "Authorized keys", paths.authorizedKeys.takeIf { it.exists() }?.readText().orEmpty(), multiline = true)
        keys.typeface = android.graphics.Typeface.MONOSPACE; keys.textSize = 12f
        val save: () -> Boolean = {
            val value = keys.text.toString().trim()
            require(!value.contains("PRIVATE KEY")) { "Paste a public key, not a private key" }
            paths.authorizedKeys.parentFile!!.mkdirs()
            val tmp = File(paths.authorizedKeys.path + ".phone.tmp")
            tmp.writeText(if (value.isEmpty()) "" else "$value\n")
            check(tmp.renameTo(paths.authorizedKeys)) { "Could not save SSH keys" }
            true
        }
        save
    }
}
