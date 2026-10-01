package dev.homedroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the server back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val cfg = Config(ctx)
        val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && cfg.resumeAfterUpdate
        if (updated) cfg.resumeAfterUpdate = false
        if (cfg.autostart || updated) ServerService.start(ctx)
    }
}
