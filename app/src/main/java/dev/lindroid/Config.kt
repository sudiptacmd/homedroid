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

    val sshPort: Int get() = 8022
    val webPort: Int get() = 8080
}
