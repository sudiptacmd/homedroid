package dev.homedroid

import android.content.Context
import android.net.TrafficStats
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File

/**
 * Live load figures for the dashboard, computed as rates between two calls (it polls every
 * few seconds).
 *
 * Android hides system-wide CPU figures (/proc/stat, /proc/loadavg) from apps, so CPU load is
 * that of Homedroid's own processes, which are all the servers. It is a share of all cores:
 * 100% means every core is busy. Network speeds are the whole phone's.
 */
object Metrics {
    private val clockTicks = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    private val cores = Runtime.getRuntime().availableProcessors()

    private var lastAt = 0L
    private var lastCpu = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var cpu = 0.0
    private var rx = 0L
    private var tx = 0L

    @Synchronized
    fun sample(ctx: Context): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val ticks = cpuTicks()
        val rxTotal = TrafficStats.getTotalRxBytes()
        val txTotal = TrafficStats.getTotalTxBytes()
        val dt = now - lastAt
        // Several browsers polling at once: keep the last rates rather than divide tiny deltas.
        if (lastAt != 0L && dt >= 1000) {
            cpu = ((ticks - lastCpu).coerceAtLeast(0) * 1000.0 / clockTicks / dt / cores * 100).coerceIn(0.0, 100.0)
            rx = if (rxTotal >= lastRx) (rxTotal - lastRx) * 1000 / dt else 0
            tx = if (txTotal >= lastTx) (txTotal - lastTx) * 1000 / dt else 0
        }
        if (lastAt == 0L || dt >= 1000) {
            lastAt = now
            lastCpu = ticks
            lastRx = rxTotal
            lastTx = txTotal
        }
        return JSONObject()
            .put("cpu", Math.round(cpu * 10) / 10.0)
            .put("cores", cores)
            .put("rxBps", rx)
            .put("txBps", tx)
            .put("cpuTemp", cpuTempC() ?: JSONObject.NULL)
            .put("thermal", thermalStatus(ctx))
    }

    /**
     * CPU time of every process this app can see (on Android, only its own), including
     * finished children, so short build steps count too.
     */
    private fun cpuTicks(): Long {
        var sum = 0L
        for (dir in File("/proc").listFiles().orEmpty()) {
            if (!dir.name[0].isDigit()) continue
            val stat = try {
                File(dir, "stat").readText()
            } catch (_: Exception) {
                continue
            }
            // Fields after "(comm) ": state is [0]; utime, stime, cutime, cstime are [11..14].
            val f = stat.substringAfterLast(") ").split(' ')
            if (f.size > 14) sum += (11..14).sumOf { f[it].toLongOrNull() ?: 0L }
        }
        return sum
    }

    /** The hottest CPU sensor, on phones that let apps read them; null otherwise. */
    private fun cpuTempC(): Double? {
        val zones = File("/sys/class/thermal").listFiles().orEmpty().filter { it.name.startsWith("thermal_zone") }
        val temps = zones.mapNotNull { z ->
            try {
                val type = File(z, "type").readText().trim().lowercase()
                if (CPU_SENSOR.none { it in type }) return@mapNotNull null
                val raw = File(z, "temp").readText().trim().toDouble()
                (if (raw > 1000) raw / 1000 else raw).takeIf { it in 5.0..130.0 }
            } catch (_: Exception) {
                null
            }
        }
        return temps.maxOrNull()?.let { Math.round(it * 10) / 10.0 }
    }

    private fun thermalStatus(ctx: Context) = when (ctx.getSystemService(PowerManager::class.java).currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "warm"
        PowerManager.THERMAL_STATUS_MODERATE -> "hot"
        PowerManager.THERMAL_STATUS_SEVERE -> "throttling"
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
        else -> "unknown"
    }

    private val CPU_SENSOR = listOf("cpu", "tsens", "soc", "big", "little", "mtktscpu", "exynos")
}
