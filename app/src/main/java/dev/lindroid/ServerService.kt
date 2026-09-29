package dev.lindroid

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
        private const val ACTION_RESTART = "dev.lindroid.RESTART"
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

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }
    }

    // All supervisor start/stop work is serialized here, off the main thread.
    private val worker = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lindroid:server")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lindroid:server")
            .apply { setReferenceCounted(false); acquire() }
        worker.execute(::launch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) worker.execute { shutdown(); launch() }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            worker.submit(::shutdown).get(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        worker.shutdown()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun launch() {
        val paths = Paths(this).also { it.ensure() }
        supervisor = Supervisor(paths, Services.enabled(paths, Config(this))).also { it.start() }
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
            .setContentTitle("Lindroid server running")
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
