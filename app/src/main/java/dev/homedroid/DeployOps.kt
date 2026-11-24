package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What deployments do beyond building: uploaded archives, pipelines, rolling out to the other
 * phones in the cluster, and the load balancer in front of them. Runs on the job queue of
 * [ServerService], except [saveArtifact] which runs in the request.
 */
class DeployOps(private val ctx: Context, private val paths: Paths) {
    private val deploys = Deploys(ctx, paths)
    private val alpine = Alpine(ctx, paths)

    // --- uploads ------------------------------------------------------------------------

    /** Saves the archive in [r]'s body as [id]'s artifact; returns an error response, or null. */
    fun saveArtifact(r: Request, id: String): Response? {
        if (r.length <= 0) return Response.error(400, "Send the .zip or .tar.gz as the request body")
        if (r.length > MAX_UPLOAD) return Response.error(413, "Archives can be up to ${MAX_UPLOAD shr 20} MB")
        if (paths.root.usableSpace < r.length * 4 + (256L shl 20)) return Response.error(507, "Not enough free space on the phone")
        val dir = deploys.dataDir(id).apply { mkdirs() }
        val part = File(dir, "artifact.${System.nanoTime()}.part")
        val complete = try {
            part.outputStream().buffered(1 shl 16).use { r.bodyTo(it) }
        } catch (e: IOException) {
            false
        }
        if (!complete || !part.renameTo(deploys.artifact(id))) {
            part.delete()
            return Response.error(400, "The upload was cut off")
        }
        return null
    }

    /**
     * Unpacks [d]'s artifact into /srv/<id>, keeping installed dependencies, and returns its
     * revision ("upload-" and the start of its SHA-256).
     */
    fun stageUpload(d: Deploy): String {
        val artifact = deploys.artifact(d.id)
        if (!artifact.isFile) throw IOException("Upload the project first")
        val meta = File(alpine.root, "srv/.homedroid").apply { mkdirs() }
        val staged = File(meta, "${d.id}.upload")
        alpine.removeGuestHost(staged)
        alpine.removeGuestHost(File(meta, "${d.id}.upload.hoist"))
        Jobs.line("Unpacking ${artifact.length() shr 10} KB")
        try {
            UploadArchive.extract(artifact, staged, MAX_UNPACKED)
        } catch (e: IOException) {
            alpine.removeGuestHost(staged)
            throw e
        }
        val dir = File(alpine.root, "srv/${d.id}")
        for (keep in listOf("node_modules", ".venv")) {
            val old = File(dir, keep)
            if (old.isDirectory && !File(staged, keep).exists()) old.renameTo(File(staged, keep))
        }
        alpine.removeGuestHost(dir)
        if (!staged.renameTo(dir)) throw IOException("Couldn't move the project into place")
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return "upload-" + digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }

    // --- pipelines ----------------------------------------------------------------------

    /**
     * Runs [d]'s pipeline in /srv/<id>, recording it in [run] (or a new run for [trigger]).
     * Returns an error, or null when every step passed or there's no pipeline.
     */
    fun runPipeline(d: Deploy, trigger: String, run: JSONObject? = null): String? {
        val steps = try {
            deploys.pipeline(d, alpine)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        if (steps.isEmpty()) return null
        val runs = deploys.runs(d.id)
        val record = run ?: runs.create(trigger, steps)
        val started = System.currentTimeMillis()
        record.put("status", "running").put("started", started)
        runs.save(record)
        val list = record.getJSONArray("steps")
        var error: String? = null
        for ((i, step) in steps.withIndex()) {
            val o = list.optJSONObject(i) ?: JSONObject().also { list.put(i, it) }
            if (error != null) { o.put("status", "skipped"); continue }
            Jobs.line("▶ ${step.name}")
            o.put("status", "running")
            runs.save(record)
            val log = StringBuilder()
            val t0 = System.currentTimeMillis()
            val rc = try {
                alpine.run("cd \"/srv/${d.id}\"\nexec /bin/sh -c \"\$HOMEDROID_STEP\"", { line ->
                    Jobs.line(line)
                    log.append(line).append('\n')
                    if (log.length > PipelineRuns.MAX_STEP_LOG * 2) log.delete(0, log.length - PipelineRuns.MAX_STEP_LOG)
                }, deploys.runEnv(d) + ("HOMEDROID_STEP" to step.run))
            } catch (e: Exception) {
                log.append(e.message ?: e.toString()).append('\n')
                -1
            }
            o.put("ms", System.currentTimeMillis() - t0).put("log", PipelineRuns.clip(log.toString()))
                .put("status", if (rc == 0) "passed" else "failed")
            if (rc != 0) error = "pipeline step \"${step.name}\" exited with $rc"
            record.put("ms", System.currentTimeMillis() - started)
            runs.save(record)
        }
        record.put("status", if (error == null) "passed" else "failed").put("ms", System.currentTimeMillis() - started)
        runs.save(record)
        return error
    }

    /** A pipeline run started from the dashboard, on the current build: a job of its own. */
    fun manualPipeline(id: String, run: JSONObject) {
        val d = deploys.get(id) ?: return
        Jobs.begin("Pipeline for ${d.name}")
        val error = try {
            alpine.ensure(Jobs::line)
            runPipeline(d, "manual", run)
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (run.optString("status") in setOf("queued", "running")) deploys.runs(id).save(run.put("status", "failed"))
        Jobs.finish(error)
    }

    // --- rolling deploys ----------------------------------------------------------------

    /**
     * Deploys [d] on each other phone in the cluster in turn, waiting for its build and for it
     * to answer on its port before going on. Stops at the first failure; returns it, or null.
     */
    fun rollout(d: Deploy): String? {
        val cluster = Cluster.instance ?: return null
        val peers = cluster.state.peers()
        if (peers.isEmpty()) { Jobs.line("No other phones in the cluster to roll out to"); return null }
        val config = d.toStored()
        for (p in peers) {
            Jobs.line("Rolling out to ${p.name}")
            try {
                if (p.address.isEmpty()) throw IOException("its address isn't known yet")
                val (s, o) = cluster.peerJson(p, "POST", "/api/deploys/${d.id}/replica", config)
                if (s != 200) throw IOException(o.optString("error", "answered $s"))
                val (status, res) = if (d.source == "upload") {
                    val art = deploys.artifact(d.id)
                    PeerHttp.request(
                        cluster.identity, p.address, p.port, p.fingerprint, "POST", "/api/deploys/${d.id}/upload?wait=1",
                        mapOf("Content-Type" to "application/octet-stream"), art.length(),
                        { out -> art.inputStream().use { it.copyTo(out, 1 shl 16) } }, readTimeoutMs = WAIT_MS.toInt(),
                    ).use { it.status to (try { it.json() } catch (_: Exception) { JSONObject() }) }
                } else {
                    cluster.peerJson(p, "POST", "/api/deploys/${d.id}/redeploy?wait=1", JSONObject(), readTimeoutMs = WAIT_MS.toInt())
                }
                if (status != 200) throw IOException(res.optString("error", "answered $status"))
                if (!healthy(p.address, d.port, d.balance?.healthUri ?: "/")) throw IOException("it doesn't answer on port ${d.port}")
                Jobs.line("${p.name} serves ${d.name}")
            } catch (e: Exception) {
                return "rollout stopped at ${p.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return null
    }

    /** Whether an HTTP server answers at [host]:[port] (any status below 500) within 90 seconds. */
    private fun healthy(host: String, port: Int, uri: String): Boolean {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            try {
                val c = URL("http", host, port, uri).openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 5000
                c.instanceFollowRedirects = false
                try { if (c.responseCode in 100..499) return true } finally { c.disconnect() }
            } catch (_: IOException) {
            }
            Thread.sleep(2000)
        }
        return false
    }

    // --- load balancing -----------------------------------------------------------------

    /**
     * Rewrites the Caddy snippet for balanced deployments, with this phone and every other
     * phone that has the deployment as upstreams, and restarts Caddy if it changed. Caddy's
     * health checks take phones that stop answering out of rotation in between.
     */
    fun refreshBalancer() = synchronized(LOCK) {
        val balanced = deploys.all().filter { it.balance != null }
        val file = File(paths.caddyDir, Balancer.SNIPPET)
        if (balanced.isEmpty() && !file.exists()) return
        val others = peerDeploys()
        val sites = balanced.map { d ->
            Balancer.Site(d.id, d.balance!!, Balancer.upstreams(d.port, others.mapNotNull { (host, list) ->
                list.firstOrNull { it.optString("id") == d.id }?.let { host to it.optInt("port") }
            }))
        }
        val text = Balancer.snippet(sites)
        var changed = false
        if (!file.isFile || file.readText() != text) {
            paths.caddyDir.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            changed = tmp.renameTo(file)
        }
        if (balanced.isNotEmpty() && paths.caddyfile.isFile) {
            Balancer.withImport(paths.caddyfile.readText())?.let { paths.caddyfile.writeText(it); changed = true }
        }
        if (changed) ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == "caddy" }?.restart()
    }

    /** Each reachable phone's address and its deployments. */
    private fun peerDeploys(): List<Pair<String, List<JSONObject>>> {
        val cluster = Cluster.instance ?: return emptyList()
        return cluster.state.peers().filter { it.address.isNotEmpty() }.mapNotNull { p ->
            try {
                PeerHttp.request(cluster.identity, p.address, p.port, p.fingerprint, "GET", "/api/deploys", readTimeoutMs = 6000).use { res ->
                    if (res.status != 200) return@mapNotNull null
                    val arr = JSONArray(res.text())
                    p.address to (0 until arr.length()).mapNotNull(arr::optJSONObject)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        const val MAX_UPLOAD = 512L shl 20
        const val MAX_UNPACKED = 2048L shl 20
        /** How long a deploy that is waited for may take, including the rollout. */
        const val WAIT_MS = 30 * 60_000L
        private val LOCK = Any()
    }
}

/**
 * The deployment endpoints beyond create/delete, and the deploy hooks for CI under /hooks/,
 * which use a per-deployment token or GitHub webhook signature instead of a session.
 */
class DeployApi(private val ctx: Context, private val paths: Paths) {
    private val deploys = Deploys(ctx, paths)
    private val alpine = Alpine(ctx, paths)
    private val ops = DeployOps(ctx, paths)
    // Separate from the dashboard's: guessing at hooks must not lock the owner out.
    private val hookAuth = AuthSecurity()

    /** /api/deploys/<id>/<action>/<rest>; [peer] is set when another phone in the cluster asks. */
    fun handle(r: Request, id: String, action: String, rest: List<String>, peer: Peer?): Response {
        if (action == "replica") {
            if (peer == null) return Response.error(403, "Only other phones in the cluster can do this")
            if (r.method != "POST") return Response.error(404, "no such endpoint")
            deploys.saveReplica(r.json().put("id", id))
            return Response.ok()
        }
        val d = deploys.get(id) ?: return Response.error(404, "no deployment $id")
        return when {
            r.method == "POST" && action == "redeploy" && rest.isEmpty() -> start(d, r.query["wait"] == "1")
            r.method == "POST" && action == "upload" && rest.isEmpty() -> {
                if (d.source != "upload") return Response.error(409, "${d.name} deploys from Git")
                ops.saveArtifact(r, id) ?: start(d, r.query["wait"] == "1")
            }
            r.method == "POST" && action == "token" && rest.isEmpty() -> {
                if (d.replica) return Response.error(409, "Hooks are set up on the phone ${d.name} comes from")
                if (r.json().optString("kind", "token") == "webhook") {
                    Response.json(JSONObject().put("webhookSecret", deploys.issueWebhookSecret(id)).put("url", "/hooks/github/$id"))
                } else {
                    Response.json(JSONObject().put("token", deploys.issueToken(id)).put("url", "/hooks/deploy/$id"))
                }
            }
            r.method == "POST" && action == "settings" && rest.isEmpty() -> {
                val next = try { deploys.update(id, r.json()) } catch (e: IllegalArgumentException) {
                    return Response.error(400, e.message ?: "invalid settings")
                }
                background { ops.refreshBalancer() }
                Response.json(next.toJson(null))
            }
            r.method == "POST" && action == "pipeline" && rest.isEmpty() -> {
                val steps = try { deploys.pipeline(d, alpine) } catch (e: IllegalArgumentException) {
                    return Response.error(400, e.message ?: "invalid pipeline")
                }
                if (steps.isEmpty()) return Response.error(409, "No pipeline: add .homedroid/pipeline.json to the project or set steps")
                val run = deploys.runs(id).create("manual", steps)
                ServerService.submitJob { ops.manualPipeline(id, run) } ?: return Response.error(503, "The server isn't running")
                Response.json(JSONObject().put("ok", true).put("run", run.getString("id")))
            }
            r.method == "GET" && action == "runs" && rest.isEmpty() ->
                Response.json(JSONObject().put("runs", jsonArray(deploys.runs(id).list())))
            r.method == "GET" && action == "runs" && rest.size == 1 ->
                deploys.runs(id).get(rest[0])?.let { Response.json(it) } ?: Response.error(404, "No such run")
            else -> Response.error(404, "no such endpoint")
        }
    }

    /** POST /hooks/deploy/<id>[/artifact] and /hooks/github/<id>. */
    fun hook(r: Request): Response {
        val seg = r.path.removePrefix("/hooks/").split('/')
        if (r.method != "POST") return Response.error(404, "not found")
        val id = seg.getOrNull(1).orEmpty()
        return when {
            seg[0] == "deploy" && (seg.size == 2 || seg.size == 3 && seg[2] == "artifact") -> {
                val d = deploys.get(id)
                val given = AuthSecurity.bearer(r.headers)
                val result = hookAuth.password(r.client) { d != null && !d.replica && DeployHooksCore.tokenMatches(given, d.tokenHash) }
                if (!result.accepted || d == null) return rejected(result)
                if (seg.size == 2) return start(d, r.query["wait"] == "1")
                if (d.source != "upload") return Response.error(409, "${d.name} deploys from Git: use /hooks/deploy/$id")
                ops.saveArtifact(r, id) ?: start(d, r.query["wait"] == "1")
            }
            seg[0] == "github" && seg.size == 2 -> {
                if (r.length !in 0..(1L shl 20)) return Response.error(413, "payload too large")
                val d = deploys.get(id)
                val body = r.body
                val result = hookAuth.password(r.client) {
                    d != null && !d.replica && DeployHooksCore.githubSignatureValid(d.webhookSecret, body, r.headers["x-hub-signature-256"])
                }
                if (!result.accepted || d == null) return rejected(result)
                when (r.headers["x-github-event"]) {
                    "ping" -> Response.json(JSONObject().put("ok", true).put("pong", true))
                    "push" -> {
                        if (d.source != "git") return Response.error(409, "${d.name} isn't deployed from Git")
                        val branch = DeployHooksCore.pushBranch(JSONObject(String(body)))
                        if (branch != d.branch) Response.json(JSONObject().put("ok", true).put("ignored", "only pushes to ${d.branch} deploy"), 202)
                        else start(d, false)
                    }
                    else -> Response.json(JSONObject().put("ok", true).put("ignored", "only push events deploy"), 202)
                }
            }
            else -> Response.error(404, "not found")
        }
    }

    private fun rejected(result: AuthSecurity.Result): Response = if (result.retrySeconds > 0) {
        Response.json(JSONObject().put("error", "Too many failed attempts. Try again in ${result.retrySeconds} seconds.")
            .put("retryAfter", result.retrySeconds), 429, mapOf("Retry-After" to result.retrySeconds.toString()))
    } else Response.error(401, "Invalid or missing deploy token")

    /** Queues a deploy of [d]; with [wait], answers once it's built (and rolled out). */
    private fun start(d: Deploy, wait: Boolean): Response {
        if (!wait) {
            ServerService.deploy(ctx, d.id)
            return Response.json(JSONObject().put("ok", true).put("queued", true))
        }
        val job = ServerService.deployJob(d.id) ?: return Response.error(503, "The server isn't running")
        val error = try {
            job.get(DeployOps.WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return Response.error(504, "Still deploying; see the dashboard")
        }
        if (error != null) return Response.json(JSONObject().put("ok", false).put("error", error), 500)
        return Response.json(JSONObject().put("ok", true).put("commit", deploys.state(d, alpine).commit ?: JSONObject.NULL))
    }

    private fun background(block: () -> Unit) = Thread({ runCatching(block) }, "deploy-balancer").apply { isDaemon = true; start() }

    companion object {
        val ACTIONS = setOf("redeploy", "upload", "token", "settings", "pipeline", "runs", "replica")
    }
}
