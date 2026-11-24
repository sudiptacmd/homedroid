package dev.homedroid

import android.content.Context

/** User settings, persisted in private SharedPreferences. */
class Config(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    /** Tokens and passwords, encrypted with a keystore key (older plaintext values move over on read). */
    private val secrets = SecretStore(prefs)

    /** Native phone interface only: system, light or dark. */
    var appearance: String
        get() = prefs.getString("appearance", "system")!!
        set(v) = prefs.edit().putString("appearance", v).apply()

    var cameraEnabled: Boolean
        get() = prefs.getBoolean("camera", false)
        set(v) = prefs.edit().putBoolean("camera", v).apply()

    var wolEnabled: Boolean
        get() = prefs.getBoolean("wol", false)
        set(v) = prefs.edit().putBoolean("wol", v).apply()

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
        get() = secrets.get("tunnel_token").orEmpty()
        set(v) = secrets.put("tunnel_token", v)

    var tailscaleEnabled: Boolean
        get() = prefs.getBoolean("tailscale", false)
        set(v) = prefs.edit().putBoolean("tailscale", v).apply()

    /** Optional; without one, the dashboard shows a login link instead. */
    var tailscaleAuthKey: String
        get() = secrets.get("tailscale_authkey").orEmpty()
        set(v) = secrets.put("tailscale_authkey", v)

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
        get() = secrets.get("dashboard_password") ?: randomPassword().also { secrets.put("dashboard_password", it) }
        set(value) = secrets.put("dashboard_password", value)

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

    /** The built-in MCP server (see [Mcp]); off until the user turns it on. */
    var mcpEnabled: Boolean
        get() = prefs.getBoolean("mcp", false)
        set(v) = prefs.edit().putBoolean("mcp", v).apply()

    /** Lets MCP clients use tools that change things (restarts, redeploys, wake on LAN). */
    var mcpAllowWrite: Boolean
        get() = prefs.getBoolean("mcp_write", false)
        set(v) = prefs.edit().putBoolean("mcp_write", v).apply()

    /** The MCP clients' bearer token, made on first use. */
    var mcpToken: String
        get() = secrets.get("mcp_token") ?: McpCore.newToken().also { secrets.put("mcp_token", it) }
        set(v) = secrets.put("mcp_token", v)

    val dashboardPort: Int get() = 8800

    val sshPort: Int get() = 8022
    val webPort: Int get() = 8080
}
