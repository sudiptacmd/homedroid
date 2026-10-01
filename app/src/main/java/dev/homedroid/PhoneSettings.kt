package dev.homedroid

import java.net.URI

/** Validation shared by the native controls and their security boundaries. */
object PhoneSettings {
    fun validPassword(value: String) = value.length in 12..128 && value.none { it.isISOControl() }
    fun validHostname(value: String) = value.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(value)
    fun isPanelUrl(value: String, port: Int): Boolean = try {
        val uri = URI(value)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port && uri.rawUserInfo == null
    } catch (_: Exception) { false }
}
