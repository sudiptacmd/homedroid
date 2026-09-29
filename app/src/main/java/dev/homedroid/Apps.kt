package dev.homedroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One process of a multi-service app. It runs in the pulled [image] (a key of
 * [AppDef.images]) or, without one, in Alpine. [run] replaces the image's own command;
 * [args] are appended to it. [binds] map "@data/<dir>" (kept in files/appdata/<app>/<dir>) or
 * "@storage" (the app's data location) onto guest paths. Env values may use {{secret}}, a
 * random per-app password.
 */
class ServiceDef(
    val name: String,
    val image: String?,
    val run: List<String>?,
    val args: List<String>,
    val env: Map<String, String>,
    val binds: List<Pair<String, String>>,
    val sysvipc: Boolean,
)

/**
 * An installable app from assets/apps.json. Scripts and [run] execute inside Alpine.
 * [storagePath] is the directory in Alpine holding the app's bulk data, which the user can move
 * to another volume; it is described to them as [storageLabel].
 */
class AppDef(
    val id: String,
    val name: String,
    val description: String,
    val port: Int,
    val size: String,
    val install: String,
    val uninstall: String,
    /** Paths in Alpine with the app's settings and database, wiped by "clear data". */
    val data: List<String>,
    val run: List<String>,
    val env: Map<String, String>,
    val storagePath: String?,
    val storageLabel: String?,
    /** Apps with the same value share one data folder, e.g. Jellyfin and qBittorrent. */
    val storageShared: String?,
    /** A log line worth surfacing (like a generated password): regex plus text with $1. */
    val noticePattern: Regex?,
    val noticeText: String?,
    /** Container images to pull, by key, for [services]. */
    val images: Map<String, String>,
    /** Processes to run; empty means one process: [run] in Alpine. */
    val services: List<ServiceDef>,
    /** Registry architectures the app supports; empty means all. */
    val arches: List<String>,
) {
    /** Supervisor names of this app's processes. */
    val serviceNames get() = if (services.isEmpty()) listOf(id) else services.map { "$id-${it.name}" }

    /** Settings key and folder name for this app's data location. */
    val storageKey get() = storageShared ?: id
    val storageFolder get() = storageShared ?: name
}

/** The app directory: what can be installed, and what is. */
class Apps(ctx: Context, private val paths: Paths) {
    val catalog: List<AppDef> = ctx.assets.open("apps.json").bufferedReader().use { parse(it.readText()) }

    private val dir = File(paths.root, "apps").also { it.mkdirs() }

    /** Where [app]'s image [key] is unpacked. */
    fun imageDir(app: AppDef, key: String) = File(paths.root, "images/${app.id}-$key")

    /** Persistent data of [app] that lives outside Alpine and its images. */
    fun dataDir(app: AppDef) = File(paths.root, "appdata/${app.id}")

    /** A random password for [app], created on first use. */
    fun secret(app: AppDef): String {
        val f = File(dataDir(app).also { it.mkdirs() }, ".secret")
        if (!f.exists()) {
            val rnd = java.security.SecureRandom()
            val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            f.writeText((1..24).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString(""))
        }
        return f.readText().trim()
    }

    /** The folder with [app]'s library (media, photos), wherever the user put it; null if it has none. */
    fun libraryDir(app: AppDef, cfg: Config, alpine: Alpine): File? {
        val path = app.storagePath ?: return null
        return cfg.storageDir(app)?.let(::File)
            ?: if (app.services.isEmpty()) File(alpine.root, path.trimStart('/')) else File(dataDir(app), "storage")
    }

    /** Other installed apps using the same library as [app]. */
    fun sharing(app: AppDef) = installed().filter { it.id != app.id && app.storageShared != null && it.storageKey == app.storageKey }

    fun isInstalled(app: AppDef) = File(dir, app.id).exists()

    fun installed() = catalog.filter(::isInstalled)

    fun markInstalled(app: AppDef, on: Boolean) {
        val marker = File(dir, app.id)
        if (on) marker.writeText("") else marker.delete()
    }

    private fun parse(json: String): List<AppDef> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppDef(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.getString("description"),
                port = o.getInt("port"),
                size = o.optString("size"),
                install = o.getString("install"),
                uninstall = o.optString("uninstall", "true"),
                data = o.optJSONArray("data")?.strings().orEmpty(),
                run = o.getJSONArray("run").let { a -> (0 until a.length()).map(a::getString) },
                env = o.optJSONObject("env")?.toMap().orEmpty(),
                storagePath = o.optJSONObject("storage")?.getString("path"),
                storageLabel = o.optJSONObject("storage")?.optString("label"),
                storageShared = o.optJSONObject("storage")?.optString("shared")?.ifEmpty { null },
                noticePattern = o.optJSONObject("notice")?.getString("pattern")?.let(::Regex),
                noticeText = o.optJSONObject("notice")?.getString("text"),
                images = o.optJSONObject("images")?.toMap().orEmpty(),
                services = o.optJSONArray("services")?.let { a -> (0 until a.length()).map { service(a.getJSONObject(it)) } }.orEmpty(),
                arches = o.optJSONArray("arches")?.strings().orEmpty(),
            )
        }
    }

    private fun service(o: JSONObject) = ServiceDef(
        name = o.getString("name"),
        image = o.optString("image").ifEmpty { null },
        run = o.optJSONArray("run")?.strings(),
        args = o.optJSONArray("args")?.strings().orEmpty(),
        env = o.optJSONObject("env")?.toMap().orEmpty(),
        binds = o.optJSONArray("binds")?.let { a ->
            (0 until a.length()).map { a.getJSONArray(it).let { b -> b.getString(0) to b.getString(1) } }
        }.orEmpty(),
        sysvipc = o.optBoolean("sysvipc"),
    )

    private fun JSONArray.strings() = (0 until length()).map(::getString)

    private fun JSONObject.toMap(): Map<String, String> = keys().asSequence().associateWith(::getString)
}
