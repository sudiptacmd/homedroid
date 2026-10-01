package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A web app deployed from a git repository. */
class Deploy(
    val id: String,
    val name: String,
    val repo: String,
    val branch: String,
    val port: Int,
    /** "auto", "node", "python" or "static". */
    val runtime: String,
    val build: String,
    val start: String,
    val env: Map<String, String>,
    val autoDeploy: Boolean,
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
        .put("state", d?.state?.name?.lowercase() ?: "stopped")
        .put("restarts", d?.restarts ?: 0)
        .apply {
            state?.let {
                put("commit", it.commit ?: JSONObject.NULL)
                put("detected", it.runtime ?: JSONObject.NULL)
                put("error", it.error ?: JSONObject.NULL)
            }
        }

    fun toStored(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("repo", repo).put("branch", branch).put("port", port)
        .put("runtime", runtime).put("build", build).put("start", start)
        .put("env", JSONObject(env)).put("autoDeploy", autoDeploy)

    companion object {
        private val CREDENTIALS = Regex("://[^/@]+@")
    }
}

/** What the last build produced, written by the build script inside Alpine. */
class DeployState(val commit: String?, val runtime: String?, val root: String?, val error: String?)

/**
 * Deployments from git (GitHub or any other host): stored in files/deploys.json, checked out
 * and built in /srv/<id> inside Alpine, run by the supervisor.
 */
class Deploys(private val ctx: Context, private val paths: Paths) {
    private val file = File(paths.root, "deploys.json")

    @Synchronized
    fun all(): List<Deploy> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    @Synchronized
    fun create(o: JSONObject): Deploy {
        val name = o.optString("name").trim()
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        require(id.isNotEmpty()) { "give the deployment a name" }
        require(get(id) == null) { "a deployment called $id already exists" }
        val repo = o.optString("repo").trim()
        require(repo.startsWith("https://") || repo.startsWith("git@")) { "repo must be an https:// or git@ URL" }
        val port = o.optInt("port", 0)
        require(port in 1024..65535) { "port must be between 1024 and 65535" }
        require(all().none { it.port == port } && port !in RESERVED_PORTS) { "port $port is already used" }
        val runtime = o.optString("runtime", "auto").ifEmpty { "auto" }
        require(runtime in setOf("auto", "node", "python", "static")) { "unknown runtime $runtime" }
        val env = o.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }.orEmpty()
        require(env.keys.all { ENV_NAME.matches(it) }) { "environment variable names must be letters, digits and _" }
        val d = Deploy(
            id = id,
            name = name,
            repo = repo,
            branch = o.optString("branch").trim().ifEmpty { "main" },
            port = port,
            runtime = runtime,
            build = o.optString("build").trim(),
            start = o.optString("start").trim(),
            env = env,
            autoDeploy = o.optBoolean("autoDeploy", false),
        )
        save(all() + d)
        return d
    }

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    fun state(d: Deploy, alpine: Alpine): DeployState {
        fun read(name: String) = File(alpine.root, "srv/.homedroid/${d.id}.$name").takeIf { it.exists() }?.readText()?.trim()
        return DeployState(read("commit"), read("runtime"), read("root"), read("error"))
    }

    /** Shell script (run inside Alpine) that fetches and builds [d]. */
    fun buildScript(d: Deploy): String = """
        |meta=/srv/.homedroid
        |dir=/srv/${d.id}
        |mkdir -p "${'$'}meta"
        |rm -f "${'$'}meta/${d.id}.error"
        |trap 'echo "build failed" > "${'$'}meta/${d.id}.error"' EXIT
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
        |rt=${d.runtime}
        |if [ "${'$'}rt" = auto ]; then
        |  if [ -f package.json ]; then rt=node
        |  elif [ -f requirements.txt ] || [ -f pyproject.toml ]; then rt=python
        |  else rt=static; fi
        |fi
        |echo "runtime: ${'$'}rt"
        |case ${'$'}rt in
        |node)
        |  command -v npm >/dev/null || apk add --no-cache nodejs npm
        |  if [ -f package-lock.json ]; then npm ci; else npm install; fi ;;
        |python)
        |  command -v python3 >/dev/null || apk add --no-cache python3
        |  [ -d .venv ] || python3 -m venv .venv
        |  if [ -f requirements.txt ]; then .venv/bin/pip install -r requirements.txt
        |  elif [ -f pyproject.toml ]; then .venv/bin/pip install .; fi ;;
        |esac
        |if [ -n "${'$'}BUILD" ]; then sh -c "${'$'}BUILD"
        |elif [ "${'$'}rt" = node ]; then npm run build --if-present; fi
        |root=.
        |if [ "${'$'}rt" = static ]; then
        |  for c in dist build public out _site; do [ -f "${'$'}c/index.html" ] && { root=${'$'}c; break; }; done
        |fi
        |echo "${'$'}rt" > "${'$'}meta/${d.id}.runtime"
        |echo "${'$'}root" > "${'$'}meta/${d.id}.root"
        |git rev-parse HEAD > "${'$'}meta/${d.id}.commit"
        |trap - EXIT
        """.trimMargin()

    /** Environment for [buildScript]. */
    fun buildEnv(d: Deploy) = d.env + mapOf("REPO" to d.repo, "BRANCH" to d.branch, "BUILD" to d.build)

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
        val start = d.start.ifEmpty {
            when (st.runtime) {
                "node" -> "npm start"
                else -> "for f in main.py app.py server.py; do [ -f \$f ] && exec .venv/bin/python \$f; done; " +
                    "echo 'Set a start command for this Python app' >&2; exit 1"
            }
        }
        val env = d.env + mapOf(
            "PORT" to d.port.toString(),
            "HOST" to "0.0.0.0",
            "NODE_ENV" to "production",
            "PATH" to "/srv/${d.id}/.venv/bin:/srv/${d.id}/node_modules/.bin:" +
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        return Spec(
            d.service,
            alpine.command(listOf("/bin/sh", "-c", "cd /srv/${d.id} && $start"), env),
            alpine.processEnv(),
        )
    }

    fun removeFiles(d: Deploy, alpine: Alpine) {
        alpine.removeGuest("srv/${d.id}")
        File(alpine.root, "srv/.homedroid").listFiles { f -> f.name.startsWith("${d.id}.") }?.forEach { it.delete() }
    }

    private fun save(list: List<Deploy>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toStored()) } }.toString(2))
        tmp.renameTo(file)
    }

    private fun parse(o: JSONObject) = Deploy(
        id = o.getString("id"),
        name = o.getString("name"),
        repo = o.getString("repo"),
        branch = o.getString("branch"),
        port = o.getInt("port"),
        runtime = o.optString("runtime", "auto"),
        build = o.optString("build"),
        start = o.optString("start"),
        env = o.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }.orEmpty(),
        autoDeploy = o.optBoolean("autoDeploy"),
    )

    companion object {
        fun serviceName(id: String) = "deploy-$id"

        private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Ports used by Homedroid and the catalog apps. */
        private val RESERVED_PORTS = setOf(2019, 2283, 5432, 6379, 8022, 8080, 8081, 8090, 8091, 8096, 8123, 8800, 8801)
    }
}
