package dev.homedroid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/**
 * "Open when charging". Android 10+ doesn't let a background service start an activity, so
 * plugging in posts a high-priority notification whose full-screen intent opens Dock mode (the
 * system launches it straight away when the screen is off or locked, and shows a heads-up
 * otherwise). Android 14+ may withhold full-screen intents; then it's a plain tap-to-open
 * notification. Choosing [DockDream] as the screen saver is the more reliable alternative.
 *
 * POWER_CONNECTED can't be received by a manifest receiver, so [ServerService] registers one
 * while it runs: call [register] in onCreate and [unregister] in onDestroy.
 */
object DockLauncher {
    private const val CHANNEL = "dock"
    private const val NOTIFICATION_ID = 4100
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun register(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> onPowerConnected(c)
                    Intent.ACTION_POWER_DISCONNECTED -> cancelNotification(c)
                }
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        registerSystem(ctx.applicationContext, r, filter)
        receiver = r
    }

    @Synchronized
    fun unregister(ctx: Context) {
        val r = receiver ?: return
        receiver = null
        runCatching { ctx.applicationContext.unregisterReceiver(r) }
    }

    /** Offers Dock mode if the user turned on "Open when charging" and it isn't already showing. */
    fun onPowerConnected(ctx: Context) {
        if (!DockPrefs(ctx).openOnCharge || DockActivity.visible) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dock mode", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Opens the bedside clock when the phone is plugged in"
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(ctx, 0, DockActivity.intent(ctx, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Charging")
            .setContentText("Tap to open Dock mode")
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
        if (canUseFullScreen(nm)) builder.setFullScreenIntent(open, true)
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun canUseFullScreen(nm: NotificationManager) = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()

    /** Registers for protected system broadcasts (power, battery), not exported to other apps. */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerSystem(ctx: Context, r: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, filter)
    }

    private const val TIMEOUT_MS = 60_000L
}
