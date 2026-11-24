package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** A web app deployed from a git repository or an uploaded archive. */
class Deploy(
    val id: String,
    val name: String,
    val repo: String,
    val branch: String,
    val port: Int,
    /** "auto" or one of [Runtimes.ALL]. */
    val runtime: String,
    val build: String,
    val start: String,
    val env: Map<String, String>,
    val autoDeploy: Boolean,
    /** "git", or "upload" for a project sent as an archive. */
    val source: String = "git",
    /** Rolled out to every phone in the cluster after it deploys here. */
    val replicated: Boolean = false,
    /** A copy another phone rolled out here: no hooks, no further rollout. */
    val replica: Boolean = false,
    val balance: Balance? = null,
    /** Pipeline steps from the settings; empty to use .homedroid/pipeline.json from the project. */
    val pipeline: List<PipelineStep> = emptyList(),
    /** SHA-256 of the deploy hook token; the token itself is shown once. */
    val tokenHash: String = "",
    /** For GitHub's webhook signatures, so it's kept as is, but never shown after it's issued. */
    val webhookSecret: String = "",
) {
    val service get() = Deploys.serviceName(id)

    fun toJson(d: Supervisor.Daemon?, state: DeployState? = null): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("repo", repo.replace(CREDENTIALS, "://***@"))
        .put("branch", branch)
        .put("port", port)
        .put("runtime", runtime)
        .put("build", build)
        .put("start", start)
        .put("env", JSONObject(env.mapValues { "***" }))
        .put("autoDeploy", autoDeploy)
        .put("source", source)
        .put("replicated", replicated)
        .put("replica", replica)
        .put("balanced", balance?.toJson() ?: JSONObject.NULL)
        .put("pipeline", Pipelines.toJson(pipeline))
        .put("hasToken", tokenHash.isNotEmpty())
        .put("hasWebhookSecret", webhookSecret.isNotEmpty())
        .put("hooks", JSONObject().put("deploy", "/hooks/deploy/$id").put("artifact", "/hooks/deploy/$id/artifact")
            .put("github", "/hooks/github/$id"))
        .put("state", d?.state?.name?.lowercase() ?: "stopped")
        .put("restarts", d?.restarts ?: 0)
        .apply {
            state?.let {
                put("commit", it.commit ?: JSONObject.NULL)
                put("detected", it.runtime ?: JSONObject.NULL)
                put("error", it.error ?: JSONObject.NULL)
            }
        }

    /** Everything, secrets included: for deploys.json and for a phone taking the deployment over. */
    fun toStored(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("repo", repo).put("branch", branch).put("port", port)
        .put("runtime", runtime).put("build", build).put("start", start)
        .put("env", JSONObject(env)).put("autoDeploy", autoDeploy)
        .put("source", source).put("replicated", replicated).put("replica", replica)
        .put("balanced", balance?.toJson() ?: JSONObject.NULL).put("pipeline", Pipelines.toJson(pipeline))
        .put("tokenHash", tokenHash).put("webhookSecret", webhookSecret)

    companion object {
        private val CREDENTIALS = Regex("://[^/@]+@")
    }
}

/** What the last build produced, written by the build script inside Alpine. */
class DeployState(val commit: String?, val runtime: String?, val root: String?, val error: String?)

/**
 * Deployments from git (GitHub or any other host) or uploaded archives: stored in
 * files/deploys.json, built in /srv/<id> inside Alpine, run by the supervisor. Uploaded
 * archives and pipeline runs are kept in files/deploys/<id>/.
 */
class Deploys(private val ctx: Context, private val paths: Paths) {
    private val file = File(paths.root, "deploys.json")

    // One lock for every instance: the dashboard, the hooks and the job queue each hold their own
    // Deploys, and they all read-modify-write the same file.
    fun all(): List<Deploy> = synchronized(LOCK) {
        if (!file.exists()) emptyList()
        else JSONArray(file.readText()).let { arr -> (0 until arr.length()).map { parse(arr.getJSONObject(it)) } }
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    /** Whether a deployment (or its load balancer) listens on [port]. */
    fun usesPort(port: Int) = all().any { it.port == port || it.balance?.port == port }

    /**
     * Adds a deployment from the dashboard's settings, or with [imported] from another phone's
     * stored config (keeping its hook secrets, so CI keeps working after a move).
     */
    fun create(o: JSONObject, imported: Boolean = false): Deploy = synchronized(LOCK) {
        val name = o.optString("name").trim()
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        require(id.isNotEmpty()) { "give the deployment a name" }
        require(get(id) == null) { "a deployment called $id already exists" }
        val d = validate(o, id, name, imported)
        save(all() + d)
        d
    }

    /** Changes the settings in [o] that may change after creation. */
    fun update(id: String, o: JSONObject): Deploy = synchronized(LOCK) {
        val d = get(id) ?: throw IllegalArgumentException("no deployment $id")
        require(!d.replica) { "this is a copy of another phone's deployment: change it there" }
        val merged = d.toStored()
        for (k in EDITABLE) if (o.has(k)) merged.put(k, o.get(k))
        val next = validate(merged, d.id, d.name, imported = true)
        save(all().map { if (it.id == id) next else it })
        next
    }

    /**
     * Saves a copy of another phone's deployment [o] here, replacing an earlier copy; a
     * deployment of this phone's own with the same name is never replaced.
     */
    fun saveReplica(o: JSONObject): Deploy = synchronized(LOCK) {
        val id = o.optString("id")
        require(Regex("[a-z0-9-]{1,64}").matches(id)) { "invalid deployment" }
        val old = get(id)
        require(old == null || old.replica) { "this phone has its own deployment called $id" }
        val d = validate(o, id, o.optString("name").trim().ifEmpty { id }, imported = true, replica = true)
        save(all().filterNot { it.id == id } + d)
        d
    }

    private fun validate(o: JSONObject, id: String, name: String, imported: Boolean, replica: Boolean = false): Deploy {
        val source = o.optString("source", "git").ifEmpty { "git" }
        require(source in setOf("git", "upload")) { "source must be git or upload" }
        val repo = o.optString("repo").trim()
        if (source == "git") require(repo.startsWith("https://") || repo.startsWith("git@")) { "repo must be an https:// or git@ URL" }
        val port = o.optInt("port", 0)
        require(port in 1024..65535) { "port must be between 1024 and 65535" }
        // sshd's port is the user's choice (see Config.sshPort), so the fixed 8022 above isn't the whole story.
        val taken = all().filter { it.id != id }.flatMap { listOfNotNull(it.port, it.balance?.port) }.toSet() + RESERVED_PORTS + Config(ctx).sshPort
        require(port !in taken) { "port $port is already used" }
        val runtime = o.optString("runtime", "auto").ifEmpty { "auto" }
        require(runtime in Runtimes.CHOICES) { "unknown runtime $runtime" }
        val env = o.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }.orEmpty()
        require(env.keys.all { ENV_NAME.matches(it) }) { "environment variable names must be letters, digits and _" }
        // Balancing is done by the phone the deployment comes from.
        val balance = if (replica) null else Balance.parse(o.optJSONObject("balanced"))
        if (balance != null) require(balance.port != port && balance.port !in taken) { "balancer port ${balance.port} is already used" }
        return Deploy(
            id = id,
            name = name,
            repo = if (source == "git") repo else "",
            branch = o.optString("branch").trim().ifEmpty { "main" },
            port = port,
            runtime = runtime,
            build = o.optString("build").trim(),
            start = o.optString("start").trim(),
            env = env,
            autoDeploy = !replica && source == "git" && o.optBoolean("autoDeploy", false),
            source = source,
            replicated = !replica && o.optBoolean("replicated", false),
            replica = replica,
            balance = balance,
            pipeline = Pipelines.parse(o.opt("pipeline")),
            tokenHash = if (imported && !replica) o.optString("tokenHash") else "",
            webhookSecret = when {
                replica -> ""
                imported -> o.optString("webhookSecret")
                else -> DeployHooksCore.newSecret("whsec_")
            },
        )
    }

    /** A new deploy hook token for [id]; only its hash is kept, so this is the one time it's seen. */
    fun issueToken(id: String): String = DeployHooksCore.newSecret("hd_").also { t -> change(id) { it.put("tokenHash", DeployHooksCore.hash(t)) } }

    /** A new GitHub webhook secret for [id], returned this once. */
    fun issueWebhookSecret(id: String): String = DeployHooksCore.newSecret("whsec_").also { s -> change(id) { it.put("webhookSecret", s) } }

    private fun change(id: String, edit: (JSONObject) -> JSONObject) = synchronized(LOCK) {
        val list = all()
        val d = list.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no deployment $id")
        require(!d.replica) { "this is a copy of another phone's deployment: change it there" }
        save(list.map { if (it.id == id) parse(edit(it.toStored())) else it })
    }

    fun remove(id: String) = synchronized(LOCK) { save(all().filterNot { it.id == id }) }

    fun state(d: Deploy, alpine: Alpine): DeployState {
        fun read(name: String) = File(alpine.root, "srv/.homedroid/${d.id}.$name").takeIf { it.exists() }?.readText()?.trim()
        return DeployState(read("commit"), read("runtime"), read("root"), read("error"))
    }

    /** Folder on the phone (outside Alpine) with [id]'s uploaded archive and pipeline runs. */
    fun dataDir(id: String) = File(paths.root, "deploys/$id")

    /** The last archive uploaded for [id]. */
    fun artifact(id: String) = File(dataDir(id), "artifact")

    fun runs(id: String) = PipelineRuns(File(dataDir(id), "runs"))

    /**
     * [d]'s pipeline: from its settings, or the project's .homedroid/pipeline.json. Throws
     * IllegalArgumentException if that file is invalid.
     */
    fun pipeline(d: Deploy, alpine: Alpine): List<PipelineStep> {
        if (d.pipeline.isNotEmpty()) return d.pipeline
        val dir = File(alpine.root, "srv/${d.id}/.homedroid")
        val f = File(dir, "pipeline.json")
        // Read from outside proot: a link in the project could point anywhere on the phone.
        if (Files.isSymbolicLink(dir.toPath()) || Files.isSymbolicLink(f.toPath()) || !f.isFile) return emptyList()
        require(f.length() <= 64 * 1024) { ".homedroid/pipeline.json is too large" }
        return Pipelines.parse(f.readText())
    }

    /**
     * Shell script (run inside Alpine) that fetches and builds [d]. An uploaded project is
     * already unpacked in /srv/<id> (see [DeployOps.stageUpload]).
     */
    fun buildScript(d: Deploy): String {
        val fetch = if (d.source == "upload") """
            |cd "${'$'}dir"
            |echo "upload ${'$'}REV"
            """.trimMargin() else """
            |command -v git >/dev/null || apk add --no-cache git ca-certificates
            |# proot can only fake hard links, which git uses for new objects by default.
            |git config --global core.createObject rename
            |if [ -d "${'$'}dir/.git" ]; then
            |  cd "${'$'}dir"
            |  git remote set-url origin "${'$'}REPO"
            |  git fetch --depth 1 origin "${'$'}BRANCH"
            |  git reset --hard FETCH_HEAD
            |  git clean -fdx -e node_modules -e .venv
            |else
            |  rm -rf "${'$'}dir"
            |  git clone --depth 1 --branch "${'$'}BRANCH" "${'$'}REPO" "${'$'}dir"
            |  cd "${'$'}dir"
            |fi
            |echo "commit ${'$'}(git rev-parse --short HEAD): ${'$'}(git log -1 --format=%s)"
            """.trimMargin()
        val commit = if (d.source == "upload") "echo \"${'$'}REV\"" else "git rev-parse HEAD"
        return listOf(
            """
            |meta=/srv/.homedroid
            |dir=/srv/${d.id}
            |mkdir -p "${'$'}meta"
            |rm -f "${'$'}meta/${d.id}.error"
            |trap 'echo "build failed" > "${'$'}meta/${d.id}.error"' EXIT
            """.trimMargin(),
            fetch,
            "rt=${d.runtime}\nif [ \"${'$'}rt\" = auto ]; then\n${Runtimes.detectScript()}\nfi\necho \"runtime: ${'$'}rt\"",
            Runtimes.installScript(),
            """
            |root=.
            |if [ "${'$'}rt" = static ]; then
            |  for c in dist build public out _site; do [ -f "${'$'}c/index.html" ] && { root=${'$'}c; break; }; done
            |fi
            |echo "${'$'}rt" > "${'$'}meta/${d.id}.runtime"
            |echo "${'$'}root" > "${'$'}meta/${d.id}.root"
            |$commit > "${'$'}meta/${d.id}.commit"
            |trap - EXIT
            """.trimMargin(),
        ).joinToString("\n")
    }

    /** Environment for [buildScript]; [rev] names an uploaded archive. */
    fun buildEnv(d: Deploy, rev: String = "") = d.env + mapOf(
        "REPO" to d.repo, "BRANCH" to d.branch, "BUILD" to d.build, "REV" to rev, "GOFLAGS" to "-buildvcs=false",
    )

    /** Shell script that prints the branch's latest commit, for auto-deploy. */
    fun remoteHeadScript() = """git ls-remote "${'$'}REPO" "refs/heads/${'$'}BRANCH" | cut -f1"""

    /** How the supervisor runs [d] once built, or null if it hasn't been built yet. */
    fun spec(d: Deploy, alpine: Alpine): Spec? {
        val st = state(d, alpine)
        if (st.commit == null || st.runtime == null) return null
        if (st.runtime == "static") {
            val root = File(alpine.root, "srv/${d.id}/${st.root ?: "."}").canonicalFile
            return Spec(
                d.service,
                listOf(paths.exe("caddy"), "file-server", "--root", root.path, "--listen", ":${d.port}"),
            )
        }
        val start = d.start.ifEmpty { Runtimes.defaultStart(st.runtime) }
        return Spec(
            d.service,
            alpine.command(listOf("/bin/sh", "-c", "cd /srv/${d.id} && $start"), runEnv(d)),
            alpine.processEnv(),
        )
    }

    /** Environment [d] runs with, also used for its pipeline steps. */
    fun runEnv(d: Deploy) = d.env + mapOf(
        "PORT" to d.port.toString(),
        "HOST" to "0.0.0.0",
        "NODE_ENV" to "production",
        "GOFLAGS" to "-buildvcs=false",
        "PATH" to "/srv/${d.id}/.venv/bin:/srv/${d.id}/node_modules/.bin:" +
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    fun removeFiles(d: Deploy, alpine: Alpine) {
        alpine.removeGuest("srv/${d.id}", "srv/.homedroid/${d.id}.upload")
        File(alpine.root, "srv/.homedroid").listFiles { f -> f.name.startsWith("${d.id}.") }?.forEach { it.delete() }
        dataDir(d.id).deleteRecursively()
    }

    private fun save(list: List<Deploy>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toStored()) } }.toString(2))
        tmp.renameTo(file)
    }

    private fun parse(o: JSONObject) = Deploy(
        id = o.getString("id"),
        name = o.getString("name"),
        repo = o.optString("repo"),
        branch = o.getString("branch"),
        port = o.getInt("port"),
        runtime = o.optString("runtime", "auto"),
        build = o.optString("build"),
        start = o.optString("start"),
        env = o.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }.orEmpty(),
        autoDeploy = o.optBoolean("autoDeploy"),
        source = o.optString("source", "git").ifEmpty { "git" },
        replicated = o.optBoolean("replicated"),
        replica = o.optBoolean("replica"),
        balance = try { Balance.parse(o.optJSONObject("balanced")) } catch (_: IllegalArgumentException) { null },
        pipeline = try { Pipelines.parse(o.opt("pipeline")) } catch (_: IllegalArgumentException) { emptyList() },
        tokenHash = o.optString("tokenHash"),
        webhookSecret = o.optString("webhookSecret"),
    )

    companion object {
        fun serviceName(id: String) = "deploy-$id"

        private val LOCK = Any()

        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Settings [update] may change. */
        private val EDITABLE = listOf("branch", "build", "start", "runtime", "env", "autoDeploy", "replicated", "balanced", "pipeline")

        /** Ports used by Homedroid and the catalog apps. */
        private val RESERVED_PORTS = setOf(2019, 2283, 5432, 6379, 8022, 8080, 8081, 8090, 8091, 8096, 8123, 8800, 8801)
    }
}
