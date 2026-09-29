package dev.lindroid

/** A daemon to supervise: its argv plus extra environment. */
class Spec(val name: String, val command: List<String>, val env: Map<String, String> = emptyMap())

object Services {
    fun enabled(p: Paths, c: Config): List<Spec> = buildList {
        if (c.sshEnabled) add(
            Spec(
                "sshd", listOf(
                    p.exe("sshd"),
                    "-listen", ":${c.sshPort}",
                    "-hostkey", p.hostKey.path,
                    "-authorized-keys", p.authorizedKeys.path,
                    "-home", p.home.path,
                )
            )
        )
        if (c.webEnabled) add(
            Spec("caddy", listOf(p.exe("caddy"), "run", "--config", p.caddyfile.path, "--adapter", "caddyfile"))
        )
        if (c.tunnelToken.isNotEmpty()) add(
            // Token goes through the environment so it doesn't show up in `ps`.
            Spec(
                "cloudflared",
                listOf(p.exe("cloudflared"), "tunnel", "--no-autoupdate", "run"),
                mapOf("TUNNEL_TOKEN" to c.tunnelToken),
            )
        )
    }
}
