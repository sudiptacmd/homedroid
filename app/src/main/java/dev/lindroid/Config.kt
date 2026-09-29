package dev.lindroid

import android.content.Context

/** User settings, persisted in private SharedPreferences. */
class Config(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

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
    val dashboardPassword: String
        get() = prefs.getString("dashboard_password", null) ?: randomPassword().also {
            prefs.edit().putString("dashboard_password", it).apply()
        }

    private fun randomPassword(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.chunked(4)
            .joinToString("-") { it.joinToString("") }
    }

    val dashboardPort: Int get() = 8800

    val sshPort: Int get() = 8022
    val webPort: Int get() = 8080
}
