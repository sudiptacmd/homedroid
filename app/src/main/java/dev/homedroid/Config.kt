package dev.homedroid

import android.content.Context

/** User settings, persisted in private SharedPreferences. */
class Config(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    var cameraEnabled: Boolean
        get() = prefs.getBoolean("camera", false)
        set(v) = prefs.edit().putBoolean("camera", v).apply()

    /** Set while an update from the dashboard installs, so the server comes back afterwards. */
    var resumeAfterUpdate: Boolean
        get() = prefs.getBoolean("resume_after_update", false)
        // commit(), not apply(): the process is killed as soon as the update installs.
        set(v) { prefs.edit().putBoolean("resume_after_update", v).commit() }

    var autostart: Boolean
        get() = prefs.getBoolean("autostart", true)
        set(v) = prefs.edit().putBoolean("autostart", v).apply()

    var sshEnabled: Boolean
        get() = prefs.getBoolean("ssh", true)
        set(v) = prefs.edit().putBoolean("ssh", v).apply()

    var webEnabled: Boolean
        get() = prefs.getBoolean("web", true)
        set(v) = prefs.edit().putBoolean("web", v).apply()

    var tunnelToken: String
        get() = prefs.getString("tunnel_token", "")!!
        set(v) = prefs.edit().putString("tunnel_token", v).apply()

    var tailscaleEnabled: Boolean
        get() = prefs.getBoolean("tailscale", false)
        set(v) = prefs.edit().putBoolean("tailscale", v).apply()

    /** Optional; without one, the dashboard shows a login link instead. */
    var tailscaleAuthKey: String
        get() = prefs.getString("tailscale_authkey", "")!!
        set(v) = prefs.edit().putString("tailscale_authkey", v).apply()

    var tailscaleHostname: String
        get() = prefs.getString("tailscale_hostname", null) ?: "homedroid"
        set(v) = prefs.edit().putString("tailscale_hostname", v).apply()

    var tailscaleExitNode: Boolean
        get() = prefs.getBoolean("tailscale_exit_node", false)
        set(v) = prefs.edit().putBoolean("tailscale_exit_node", v).apply()

    /** Subnets to advertise, comma-separated CIDRs, e.g. the home LAN. */
    var tailscaleRoutes: String
        get() = prefs.getString("tailscale_routes", "")!!
        set(v) = prefs.edit().putString("tailscale_routes", v).apply()

    var tailscaleTags: String
        get() = prefs.getString("tailscale_tags", "")!!
        set(v) = prefs.edit().putString("tailscale_tags", v).apply()

    /** How the dashboard is published over HTTPS: "off", "tailnet" (Serve) or "funnel". */
    var tailscaleServe: String
        get() = prefs.getString("tailscale_serve", "off")!!
        set(v) = prefs.edit().putString("tailscale_serve", v).apply()

    /** A SOCKS5 and HTTP proxy on localhost so programs on the phone can reach the tailnet. */
    var tailscaleProxy: Boolean
        get() = prefs.getBoolean("tailscale_proxy", false)
        set(v) = prefs.edit().putBoolean("tailscale_proxy", v).apply()

    /** Host folder for an app's bulk data, or null to keep it inside Alpine. */
    fun storageDir(app: AppDef): String? = prefs.getString("storage.${app.storageKey}", null)

    fun setStorageDir(app: AppDef, dir: String?) =
        prefs.edit().apply {
            if (dir == null) remove("storage.${app.storageKey}") else putString("storage.${app.storageKey}", dir)
        }.apply()

    /** Installed apps the user switched off; they stay installed but don't run. */
    fun isDisabled(id: String) = prefs.getBoolean("disabled.$id", false)

    fun setDisabled(id: String, off: Boolean) = prefs.edit().putBoolean("disabled.$id", off).apply()

    /** Password for the web dashboard, generated on first use. */
    var dashboardPassword: String
        get() = prefs.getString("dashboard_password", null) ?: randomPassword().also {
            prefs.edit().putString("dashboard_password", it).apply()
        }
        set(value) = prefs.edit().putString("dashboard_password", value).apply()

    private fun randomPassword(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.chunked(4)
            .joinToString("-") { it.joinToString("") }
    }

    /** Expiring hashed sessions and failed-password counters. Legacy sessions require a new login. */
    var dashboardAuth: String
        get() = prefs.getString("dashboard_auth_v2", "{}")!!
        set(v) {
            // Persist before accepting a new session or another attempt, including across process death.
            check(prefs.edit().remove("dashboard_sessions").putString("dashboard_auth_v2", v).commit()) {
                "Could not persist authentication state"
            }
        }

    val dashboardPort: Int get() = 8800

    val sshPort: Int get() = 8022
    val webPort: Int get() = 8080
}
