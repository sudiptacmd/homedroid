package dev.lindroid

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface

/** Health of the phone itself, for the app screen and the dashboard. */
class Device(ctx: Context) {
    val batteryPercent: Int
    val batteryTempC: Float
    val charging: Boolean
    val ramFreeMb: Long
    val ramTotalMb: Long
    val storageFreeMb: Long
    val storageTotalMb: Long
    val ips: List<String> = localIps()
    val uptimeS = SystemClock.elapsedRealtime() / 1000
    val model = "${Build.MANUFACTURER} ${Build.MODEL}"
    val android = Build.VERSION.RELEASE

    init {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryPercent = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        batteryTempC = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val mem = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        ramFreeMb = mem.availMem shr 20
        ramTotalMb = mem.totalMem shr 20
        storageFreeMb = ctx.filesDir.freeSpace shr 20
        storageTotalMb = ctx.filesDir.totalSpace shr 20
    }

    val hot get() = batteryTempC >= 45f

    companion object {
        private fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
