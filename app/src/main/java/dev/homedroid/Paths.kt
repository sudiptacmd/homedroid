package dev.homedroid

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
    /** One JSON line per SSH or web terminal session, written by sshd. */
    val sessionLog = File(logs, "ssh-sessions.jsonl")
    /** Commands typed in the phone's shell ("<unix time> <command>"), see [writeShellRc]. */
    val shellHistory = File(home, ".shell_history")
    private val shellRc = File(etc, "mkshrc")

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

    /**
     * Startup file for interactive SSH shells (mksh reads $ENV). Defines `alpine`, which opens
     * a shell inside Alpine, keeps a history of typed commands, then sources the user's own
     * ~/.mkshrc.
     */
    fun writeShellRc(alpineShell: String?, alpineRun: String?) {
        val missing = "echo \"Alpine is not installed yet: install an app from the Apps screen.\""
        trimHistory()
        shellRc.writeText(
            "alias alpine='${alpineShell ?: missing}'\n" +
                // A function rather than an alias, so `ssh phone torrent …` works too.
                "torrent() { ${alpineRun?.let { "$it /usr/local/bin/torrent \"\$@\"" } ?: missing}; }\n" +
                HISTORY_HOOK +
                "[ -f \"\$HOME/.mkshrc\" ] && . \"\$HOME/.mkshrc\"\n"
        )
    }

    /** Keeps the shell history file short; it only ever grows otherwise. */
    private fun trimHistory() {
        if (shellHistory.length() < 256 * 1024) return
        val lines = shellHistory.readLines()
        shellHistory.writeText(lines.takeLast(1000).joinToString("\n", postfix = "\n"))
    }

    /** Environment shared by every daemon (on top of the app's own). */
    fun env(): Map<String, String> = mapOf(
        "HOME" to home.path,
        "TMPDIR" to tmp.path,
        "PATH" to "${bin.path}:/system/bin:/system/xbin",
        "SHELL" to "/system/bin/sh",
        "ENV" to shellRc.path,
        "LANG" to "C.UTF-8",
        "XDG_CONFIG_HOME" to "${home.path}/.config",
        "XDG_DATA_HOME" to "${home.path}/.local/share",
    )

    companion object {
        /**
         * Android's mksh has no history file, so the prompt records the last command: ${|…;}
         * runs in the shell itself, so remembering the previous entry costs no process.
         */
        private val HISTORY_HOOK = """
            _homedroid_history() {
            	REPLY=
            	typeset c
            	c=${'$'}(fc -ln -1 2>/dev/null) || return 0
            	c=${'$'}{c#${'$'}'\t'}
            	[[ -n ${'$'}c && ${'$'}c != "${'$'}_homedroid_last" ]] || return 0
            	_homedroid_last=${'$'}c
            	print -r -- "${'$'}{EPOCHREALTIME%.*} ${'$'}c" >> "${'$'}HOME/.shell_history"
            }
            [[ -o interactive ]] && PS1='${'$'}{|_homedroid_history;}'"${'$'}PS1"
        """.trimIndent() + "\n"

        val TOOLS = listOf("sshd", "caddy", "cloudflared", "proot")

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
            "<!doctype html><title>Homedroid</title><h1>It works!</h1>" +
                "<p>Served by Caddy from an Android phone. Edit ~/www over SFTP.</p>\n"
    }
}
