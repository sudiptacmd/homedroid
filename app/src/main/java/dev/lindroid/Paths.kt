package dev.lindroid

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File

/**
 * On-device filesystem layout.
 *
 * Daemons ship inside the APK as lib<name>.so and run from nativeLibraryDir, the only place
 * an app targeting SDK >= 29 may exec from. [bin] holds symlinks with their real names so
 * they are usable from SSH shells.
 */
class Paths(ctx: Context) {
    val libDir = File(ctx.applicationInfo.nativeLibraryDir)
    val root: File = ctx.filesDir
    val bin = File(root, "bin")
    val etc = File(root, "etc")
    val logs = File(root, "logs")
    val home = File(root, "home")
    val www = File(home, "www")
    val tmp: File = ctx.cacheDir
    val caddyfile = File(etc, "Caddyfile")
    val hostKey = File(etc, "ssh_host_ed25519_key")
    val authorizedKeys = File(home, ".ssh/authorized_keys")

    fun exe(name: String) = File(libDir, "lib$name.so").path

    fun ensure() {
        for (dir in listOf(bin, etc, logs, www, authorizedKeys.parentFile!!)) dir.mkdirs()
        // nativeLibraryDir changes on every app update, so always recreate the links.
        for (name in TOOLS) {
            val link = File(bin, name)
            link.delete()
            try {
                Os.symlink(exe(name), link.path)
            } catch (_: ErrnoException) {
            }
        }
        if (!caddyfile.exists()) caddyfile.writeText(DEFAULT_CADDYFILE)
        File(www, "index.html").let { if (!it.exists()) it.writeText(DEFAULT_INDEX) }
        if (!authorizedKeys.exists()) authorizedKeys.writeText("")
    }

    /** Environment shared by every daemon (on top of the app's own). */
    fun env(): Map<String, String> = mapOf(
        "HOME" to home.path,
        "TMPDIR" to tmp.path,
        "PATH" to "${bin.path}:/system/bin:/system/xbin",
        "SHELL" to "/system/bin/sh",
        "LANG" to "C.UTF-8",
        "XDG_CONFIG_HOME" to "${home.path}/.config",
        "XDG_DATA_HOME" to "${home.path}/.local/share",
    )

    companion object {
        val TOOLS = listOf("sshd", "caddy", "cloudflared")

        private val DEFAULT_CADDYFILE = """
            {
            	# Ports < 1024 need root and public TLS is terminated by the Cloudflare tunnel.
            	auto_https off
            }

            :8080 {
            	root * {${'$'}HOME}/www
            	encode gzip
            	file_server
            }
        """.trimIndent() + "\n"

        private const val DEFAULT_INDEX =
            "<!doctype html><title>Lindroid</title><h1>It works!</h1>" +
                "<p>Served by Caddy from an Android phone. Edit ~/www over SFTP.</p>\n"
    }
}
