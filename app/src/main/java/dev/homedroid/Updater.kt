package dev.homedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app from the web dashboard: downloads the APK for this phone's CPU from the
 * latest GitHub release (or takes one uploaded from the browser), checks it is Homedroid
 * signed with the same key, and hands it to PackageInstaller.
 *
 * Android asks for confirmation on the phone (a notification opens the prompt), except on
 * Android 12 and later once Homedroid itself installed the version being replaced.
 */
object Updater {
    private const val REPO = "sudiptacmd/homedroid"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    /** idle, downloading, installing (handed to Android), confirm (waiting on the phone), failed. */
    @Volatile var state = "idle"; private set
    @Volatile private var confirmSince = 0L
    @Volatile var error: String? = null; private set
    @Volatile private var done = 0L
    @Volatile private var total = 0L
    @Volatile private var latest: JSONObject? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var checkError: String? = null

    fun json(ctx: Context, refresh: Boolean): JSONObject {
        if (refresh || System.currentTimeMillis() - checkedAt > CHECK_EVERY_MS) check(ctx)
        // Dismissing Android's prompt doesn't always report back; don't wait on it forever.
        if (state == "confirm" && System.currentTimeMillis() - confirmSince > 10 * 60_000L) state = "idle"
        val current = installed(ctx)
        val l = latest
        return JSONObject()
            .put("version", current.versionName)
            .put("versionCode", current.longVersionCode)
            .put("latest", l ?: JSONObject.NULL)
            .put("available", l != null && newer(l.getString("version"), current.versionName ?: ""))
            .put("checkError", checkError ?: JSONObject.NULL)
            .put("checkedAt", checkedAt / 1000)
            .put("state", state)
            .put("error", error ?: JSONObject.NULL)
            .put("done", done)
            .put("total", total)
            // Without "Install unknown apps", Android shows a settings shortcut in the prompt instead.
            .put("allowed", ctx.packageManager.canRequestPackageInstalls())
            .put("silent", Build.VERSION.SDK_INT >= 31 &&
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName == ctx.packageName)
    }

    /** Reads the newest release from GitHub and picks the APK for this phone. */
    private fun check(ctx: Context) {
        checkedAt = System.currentTimeMillis()
        try {
            // Not /releases/latest: GitHub leaves pre-releases out of it, and every beta is one.
            val conn = open("https://api.github.com/repos/$REPO/releases?per_page=20", ctx)
            val rel = newest(JSONArray(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }))
                ?: throw IOException("no release with an APK yet")
            val assets = rel.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
            val abi = when (Oci.archFor(Paths(ctx).libDir)) { "arm64" -> "arm64-v8a"; "arm" -> "armeabi-v7a"; else -> "x86_64" }
            val apk = apks.firstOrNull { it.getString("name").endsWith("-$abi.apk") }
                ?: apks.firstOrNull { it.getString("name").endsWith("-universal.apk") } ?: apks.firstOrNull()
            latest = JSONObject()
                .put("version", rel.getString("tag_name").removePrefix("v"))
                .put("name", rel.optString("name"))
                .put("notes", rel.optString("body"))
                .put("url", rel.optString("html_url"))
                .put("published", rel.optString("published_at"))
                .put("apk", apk?.getString("browser_download_url") ?: JSONObject.NULL)
                .put("apkName", apk?.getString("name") ?: JSONObject.NULL)
                .put("size", apk?.optLong("size") ?: 0)
            checkError = null
        } catch (e: Exception) {
            checkError = "Couldn't check GitHub for updates: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private val busy get() = state == "downloading" || state == "installing"

    /** Downloads the latest release's APK and installs it; runs in the background. */
    fun installLatest(ctx: Context): String? {
        val url = latest?.optString("apk")?.takeIf { it.startsWith("https://") } ?: return "No APK in the latest release"
        return startJob(ctx) { apk ->
            val conn = open(url, ctx)
            total = conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                    }
                }
            }
        }
    }

    /** Installs an APK uploaded from the browser. */
    fun installUpload(ctx: Context, r: Request): String? {
        if (r.length <= 0 || r.length > 512L shl 20) return "Upload an APK of up to 512 MB"
        total = r.length
        return startJob(ctx, async = false) { apk ->
            apk.outputStream().use { if (!r.bodyTo(it)) throw IOException("upload interrupted") }
            done = r.length
        }
    }

    private fun startJob(ctx: Context, async: Boolean = true, fetch: (File) -> Unit): String? {
        synchronized(this) {
            if (busy) return "An update is already in progress"
            state = "downloading"; error = null; done = 0
        }
        val work = {
            try {
                val apk = File(ctx.cacheDir, "update.apk").also { it.delete() }
                fetch(apk)
                state = "installing"
                install(ctx, apk)
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
        if (async) Thread(work, "updater").apply { isDaemon = true; start() } else work()
        return null
    }

    private fun fail(message: String) {
        error = message
        state = "failed"
    }

    private fun install(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        // Android 10 leaves signingInfo empty for archives unless GET_SIGNATURES is asked for too.
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
            ?: throw IOException("That file isn't an Android app")
        if (info.packageName != ctx.packageName) throw IOException("That APK is ${info.packageName}, not Homedroid")
        val mine = installed(ctx)
        if (info.longVersionCode < mine.longVersionCode) {
            throw IOException("That APK is an older version (${info.versionName}); Android won't downgrade")
        }
        @Suppress("DEPRECATION")
        val theirs = (info.signingInfo?.apkContentsSigners ?: info.signatures).orEmpty().map { it.toCharsString() }.toSet()
        val ours = mine.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        if (theirs != ours) throw IOException("That APK is signed with a different key (a debug build?), so it can't update this install")

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("homedroid.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The server comes back after the update even if it doesn't start on boot.
            Config(ctx).resumeAfterUpdate = ServerService.running
            val done = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(done.intentSender)
        }
        apk.delete()
    }

    private fun installed(ctx: Context): PackageInfo =
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun open(url: String, ctx: Context): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Homedroid/${installed(ctx).versionName}")
        conn.setRequestProperty("Accept", if ("api.github.com" in url) "application/vnd.github+json" else "application/octet-stream")
        if (conn.responseCode != 200) throw IOException("${conn.responseCode} from ${URL(url).host}")
        return conn
    }

    /** The release with the highest version that isn't a draft and has an APK, pre-releases included. */
    fun newest(releases: JSONArray): JSONObject? =
        (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { r ->
                !r.optBoolean("draft") && r.optString("tag_name").isNotEmpty() &&
                    r.optJSONArray("assets")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("name").endsWith(".apk") } } == true
            }
            .fold(null as JSONObject?) { best, r ->
                if (best == null || newer(r.getString("tag_name").removePrefix("v"), best.getString("tag_name").removePrefix("v"))) r else best
            }

    /** "0.8.10" > "0.8.9"; a pre-release suffix only breaks ties. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val (x, y) = parts(a) to parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return '-' in b && '-' !in a
    }

    /** Result of a PackageInstaller session. */
    class UpdateReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    state = "confirm"
                    confirmSince = System.currentTimeMillis()
                    askOnPhone(ctx, confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> state = "idle"
                else -> {
                    Config(ctx).resumeAfterUpdate = false
                    ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android didn't install the update")
                }
            }
        }

        /**
         * Android won't let a background app open the prompt itself, so a notification does;
         * if the Homedroid screen is open, the prompt appears straight away.
         */
        private fun askOnPhone(ctx: Context, confirm: Intent) {
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(confirm)
            } catch (_: Exception) {
            }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH))
            val tap = PendingIntent.getActivity(ctx, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                NOTIFICATION_ID,
                Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Homedroid update ready")
                    .setContentText("Tap to install it")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
