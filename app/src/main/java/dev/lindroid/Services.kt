package dev.lindroid

import android.content.Context
import java.io.File

/** A daemon to supervise: its argv plus extra environment. */
class Spec(val name: String, val command: List<String>, val env: Map<String, String> = emptyMap())

object Services {
    private const val WAIT_FOR_DIR =
        "[ -d \"\$1\" ] && [ -w \"\$1\" ] || " +
            "{ echo \"Storage \$1 is not available (card or drive removed?)\"; exit 1; }; shift; exec \"\$@\""

    /**
     * Checked on every (re)start, so an app whose card was removed waits for it to come back
     * instead of writing somewhere else.
     */
    private fun waitFor(dir: String?, command: List<String>) =
        if (dir == null) command else listOf("/system/bin/sh", "-c", WAIT_FOR_DIR, "sh", dir) + command

    /** One process of a multi-service app, in its image's rootfs or in Alpine. */
    private fun serviceSpec(
        app: AppDef, svc: ServiceDef, apps: Apps, alpine: Alpine, p: Paths, storageDir: String?,
        procEnv: Map<String, String>,
    ): Spec {
        val rootfs = svc.image?.let { apps.imageDir(app, it) } ?: alpine.root
        val image = svc.image?.let { ImageConfig.read(rootfs) }
        val argv = (svc.run ?: image?.let { it.entrypoint + it.cmd }.orEmpty()) + svc.args
        val secret = if (svc.env.values.any { "{{secret}}" in it }) apps.secret(app) else ""
        val env = svc.env.mapValues { it.value.replace("{{secret}}", secret) }
        val binds = svc.binds.map { (host, guest) ->
            val path = when {
                host == "@storage" -> storageDir ?: File(apps.dataDir(app), "storage").path
                host.startsWith("@data/") -> File(apps.dataDir(app), host.removePrefix("@data/")).path
                else -> host
            }
            File(path).mkdirs()
            path to guest
        }
        val usesStorage = svc.binds.any { it.first == "@storage" }
        val command = alpine.command(
            argv, env, binds,
            rootfs = rootfs,
            workdir = image?.workdir ?: "/root",
            baseEnv = image?.env ?: Alpine.BASE_ENV,
            sysvipc = svc.sysvipc,
        )
        return Spec("${app.id}-${svc.name}", waitFor(storageDir.takeIf { usesStorage }, command), procEnv)
    }

    fun enabled(ctx: Context, p: Paths, c: Config): List<Spec> = buildList {
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
        if (c.tunnelToken.isNotEmpty() && !c.isDisabled("tunnel")) add(
            // Token goes through the environment so it doesn't show up in `ps`.
            Spec(
                "cloudflared",
                listOf(p.exe("cloudflared"), "tunnel", "--no-autoupdate", "run"),
                mapOf("TUNNEL_TOKEN" to c.tunnelToken),
            )
        )
        val alpine = Alpine(ctx, p)
        if (alpine.installed) {
            val deploys = Deploys(ctx, p)
            for (d in deploys.all()) deploys.spec(d, alpine)?.let(::add)
            val procEnv = alpine.processEnv()
            val apps = Apps(ctx, p)
            for (app in apps.installed().filterNot { c.isDisabled(it.id) }) {
                val dir = app.storagePath?.let { c.storageDir(app) }
                if (app.services.isEmpty()) {
                    val binds = if (dir != null) listOf(dir to app.storagePath!!) else emptyList()
                    add(Spec(app.id, waitFor(dir, alpine.command(app.run, app.env, binds)), procEnv))
                } else {
                    for (svc in app.services) add(serviceSpec(app, svc, apps, alpine, p, dir, procEnv))
                }
            }
        }
    }
}
