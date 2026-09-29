package dev.homedroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the server back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Config(ctx).autostart) ServerService.start(ctx)
    }
}
