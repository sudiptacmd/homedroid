package dev.homedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the [Supervisor]. It holds a partial wakelock and a Wi-Fi lock
 * so the CPU and radio stay up with the screen off.
 */
class ServerService : Service() {
    companion object {
        private const val ACTION_RESTART = "dev.homedroid.RESTART"
        private const val ACTION_INSTALL = "dev.homedroid.INSTALL"
        private const val ACTION_UNINSTALL = "dev.homedroid.UNINSTALL"
        private const val ACTION_CLEAR = "dev.homedroid.CLEAR"
        private const val ACTION_DEPLOY = "dev.homedroid.DEPLOY"
        private const val ACTION_UNDEPLOY = "dev.homedroid.UNDEPLOY"
        private const val EXTRA_APP = "app"
        private const val EXTRA_DELETE_DATA = "deleteData"
        private const val EXTRA_DELETE_LIBRARY = "deleteLibrary"
        private const val EXTRA_DEPLOY = "deploy"
        private const val AUTO_DEPLOY_MINUTES = 5L
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var supervisor: Supervisor? = null
            private set

        val running get() = supervisor != null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        }

        fun restart(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ServerService::class.java).setAction(ACTION_RESTART))
        }

        fun install(ctx: Context, app: AppDef) = startJob(ctx, ACTION_INSTALL, app)

        /** Removes [app]; with [deleteData] also its settings and database, with [deleteLibrary] its library. */
        fun uninstall(ctx: Context, app: AppDef, deleteData: Boolean = false, deleteLibrary: Boolean = false) =
            startJob(ctx, ACTION_UNINSTALL, app, deleteData, deleteLibrary)

        /** Resets [app] to a fresh install: settings and database go; the library only with [deleteLibrary]. */
        fun clearData(ctx: Context, app: AppDef, deleteLibrary: Boolean) =
            startJob(ctx, ACTION_CLEAR, app, true, deleteLibrary)

        private fun startJob(
            ctx: Context, action: String, app: AppDef, deleteData: Boolean = false, deleteLibrary: Boolean = false,
        ) {
            ctx.startForegroundService(
                Intent(ctx, ServerService::class.java).setAction(action).putExtra(EXTRA_APP, app.id)
                    .putExtra(EXTRA_DELETE_DATA, deleteData).putExtra(EXTRA_DELETE_LIBRARY, deleteLibrary)
            )
        }

        fun deploy(ctx: Context, id: String) = startDeployJob(ctx, ACTION_DEPLOY, id)

        fun undeploy(ctx: Context, id: String) = startDeployJob(ctx, ACTION_UNDEPLOY, id)

        private fun startDeployJob(ctx: Context, action: String, id: String) {
            ctx.startForegroundService(
                Intent(ctx, ServerService::class.java).setAction(action).putExtra(EXTRA_DEPLOY, id)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }
    }

    // All supervisor start/stop work is serialized here, off the main thread.
    private val worker = Executors.newSingleThreadExecutor()

    // App installs can take many minutes; they run beside the supervisor, one at a time.
    private val installer = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var dashboard: Dashboard? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homedroid:server")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "homedroid:server")
            .apply { setReferenceCounted(false); acquire() }
        worker.execute(::launch)
        dashboard = Dashboard(this).also {
            try {
                it.start()
            } catch (e: java.io.IOException) {
                Jobs.line("dashboard could not start: ${e.message}")
            }
        }
        scheduler.scheduleWithFixedDelay(::checkAutoDeploys, AUTO_DEPLOY_MINUTES, AUTO_DEPLOY_MINUTES, TimeUnit.MINUTES)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART -> worker.execute { shutdown(); launch() }
            ACTION_INSTALL, ACTION_UNINSTALL, ACTION_CLEAR -> {
                val id = intent.getStringExtra(EXTRA_APP)
                val action = intent.action!!
                val deleteData = intent.getBooleanExtra(EXTRA_DELETE_DATA, false)
                val deleteLibrary = intent.getBooleanExtra(EXTRA_DELETE_LIBRARY, false)
                installer.execute { runJob(id, action, deleteData, deleteLibrary) }
            }
            ACTION_DEPLOY, ACTION_UNDEPLOY -> {
                val id = intent.getStringExtra(EXTRA_DEPLOY)
                val deploy = intent.action == ACTION_DEPLOY
                installer.execute { runDeployJob(id, deploy) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            worker.submit(::shutdown).get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        worker.shutdown()
        installer.shutdownNow()
        scheduler.shutdownNow()
        CameraService.stop(this)
        dashboard?.stop()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun launch() {
        val paths = Paths(this).also { it.ensure() }
        val alpine = Alpine(this, paths)
        if (alpine.installed) {
            alpine.refresh()
            val apps = Apps(this, paths)
            for (app in apps.installed()) for (key in app.images.keys) {
                apps.imageDir(app, key).takeIf { it.isDirectory }?.let(alpine::prepareRootfs)
            }
        }
        if (alpine.installed) paths.writeShellRc(alpine.shellCommand(), alpine.shellPrefix())
        else paths.writeShellRc(null, null)
        supervisor = Supervisor(paths, Services.enabled(this, paths, Config(this))).also { it.start() }
    }

    private fun runDeployJob(id: String?, deploy: Boolean) {
        val paths = Paths(this)
        val deploys = Deploys(this, paths)
        val d = deploys.all().firstOrNull { it.id == id } ?: return
        val alpine = Alpine(this, paths)
        Jobs.begin((if (deploy) "Deploying " else "Deleting ") + d.name)
        val error = try {
            if (deploy) {
                alpine.ensure(Jobs::line)
                val rc = alpine.run(deploys.buildScript(d), Jobs::line, deploys.buildEnv(d))
                if (rc == 0) null else "build exited with $rc"
            } else {
                deploys.remove(d.id)
                worker.submit { shutdown(); launch() }.get()
                deploys.removeFiles(d, alpine)
                null
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        Jobs.finish(error)
        // A failed build keeps the previous version running.
        if (deploy && error == null) {
            val running = supervisor?.daemons?.firstOrNull { it.spec.name == d.service }
            if (running != null) running.restart() else worker.execute { shutdown(); launch() }
        }
    }

    /** Redeploys auto-deploy apps whose branch has new commits. */
    private fun checkAutoDeploys() {
        if (Jobs.running) return
        val paths = Paths(this)
        val alpine = Alpine(this, paths)
        if (!alpine.installed) return
        val deploys = Deploys(this, paths)
        for (d in deploys.all().filter { it.autoDeploy }) {
            val head = StringBuilder()
            val rc = try {
                alpine.run(deploys.remoteHeadScript(), { head.append(it.trim()) }, deploys.buildEnv(d))
            } catch (_: Exception) {
                continue
            }
            val current = deploys.state(d, alpine).commit
            if (rc == 0 && head.length == 40 && head.toString() != current) {
                installer.execute { runDeployJob(d.id, true) }
            }
        }
    }

    private fun runJob(id: String?, action: String, deleteData: Boolean, deleteLibrary: Boolean) {
        val paths = Paths(this)
        val apps = Apps(this, paths)
        val app = apps.catalog.firstOrNull { it.id == id } ?: return
        val alpine = Alpine(this, paths)
        val install = action == ACTION_INSTALL
        Jobs.begin(
            when (action) {
                ACTION_INSTALL -> "Installing "
                ACTION_CLEAR -> "Clearing data of "
                else -> "Removing "
            } + app.name
        )
        val error = try {
            if (action == ACTION_CLEAR) {
                if (!apps.isInstalled(app)) throw java.io.IOException("${app.name} isn't installed")
                apps.markInstalled(app, false)
                worker.submit { shutdown(); launch() }.get()
                wipe(app, apps, alpine, deleteLibrary)
                // Recreates the default settings the install script writes.
                val rc = alpine.run(app.install, Jobs::line)
                apps.markInstalled(app, true)
                if (rc == 0) null else "install script exited with $rc"
            } else if (install) {
                val arch = Oci.archFor(paths.libDir)
                if (app.arches.isNotEmpty() && arch !in app.arches) {
                    throw java.io.IOException("${app.name} isn't available for this phone's CPU ($arch)")
                }
                alpine.ensure(Jobs::line)
                for ((key, ref) in app.images) {
                    val dir = apps.imageDir(app, key)
                    Oci(arch).pull(ref, dir, Jobs::line)
                    alpine.prepareRootfs(dir)
                }
                val rc = alpine.run(app.install, Jobs::line)
                if (rc == 0) apps.markInstalled(app, true)
                if (rc == 0) null else "install script exited with $rc"
            } else {
                // Stop it before deleting its files.
                apps.markInstalled(app, false)
                worker.submit { shutdown(); launch() }.get()
                val rc = alpine.run(app.uninstall, Jobs::line)
                // Images are just programs; the app's data lives in appdata/ and is kept.
                for (key in app.images.keys) alpine.removeGuestHost(apps.imageDir(app, key))
                if (deleteData) wipe(app, apps, alpine, deleteLibrary)
                if (rc == 0) null else "uninstall script exited with $rc"
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        Jobs.finish(error)
        if ((install || action == ACTION_CLEAR) && apps.isInstalled(app)) worker.execute { shutdown(); launch() }
    }

    /**
     * Deletes [app]'s settings and database (in Alpine and in appdata/) and, with
     * [deleteLibrary], the contents of its library folder. The library folder itself stays, as
     * it may be one the user picked.
     */
    private fun wipe(app: AppDef, apps: Apps, alpine: Alpine, deleteLibrary: Boolean) {
        val library = apps.libraryDir(app, Config(this), alpine)?.canonicalFile
        for (p in app.data) {
            Jobs.line("Deleting $p")
            alpine.removeGuest(p)
        }
        apps.dataDir(app).listFiles().orEmpty()
            .filter { library == null || it.canonicalFile != library }
            .forEach {
                Jobs.line("Deleting ${it.name}")
                alpine.removeGuestHost(it)
            }
        if (deleteLibrary && library != null && library.isDirectory) {
            Jobs.line("Deleting everything in the library")
            library.listFiles().orEmpty().forEach(alpine::removeGuestHost)
        }
    }

    private fun shutdown() {
        supervisor?.stop()
        supervisor = null
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Homedroid server running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
