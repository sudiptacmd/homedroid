package dev.lindroid

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var paths: Paths
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var battery: Button
    private lateinit var logs: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cfg = Config(this)
        paths = Paths(this).also { it.ensure() }

        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        battery = findViewById(R.id.battery)
        logs = findViewById(R.id.logs)
        val token = findViewById<EditText>(R.id.token)
        val keys = findViewById<EditText>(R.id.keys)

        findViewById<Switch>(R.id.ssh).apply {
            isChecked = cfg.sshEnabled
            setOnCheckedChangeListener { _, on -> cfg.sshEnabled = on }
        }
        findViewById<Switch>(R.id.web).apply {
            isChecked = cfg.webEnabled
            setOnCheckedChangeListener { _, on -> cfg.webEnabled = on }
        }
        findViewById<Switch>(R.id.autostart).apply {
            isChecked = cfg.autostart
            setOnCheckedChangeListener { _, on -> cfg.autostart = on }
        }
        token.setText(cfg.tunnelToken)
        keys.setText(paths.authorizedKeys.readText())

        toggle.setOnClickListener {
            if (ServerService.running) ServerService.stop(this) else ServerService.start(this)
            ui.postDelayed(::refresh, 300)
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            cfg.tunnelToken = token.text.toString().trim()
            paths.authorizedKeys.writeText(keys.text.toString().trim() + "\n")
            if (ServerService.running) ServerService.restart(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        battery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        findViewById<View>(R.id.tip).visibility =
            if (Build.VERSION.SDK_INT >= 31) View.VISIBLE else View.GONE

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val sv = ServerService.supervisor
        val s = StringBuilder()
        s.append(if (sv != null) "● running\n" else "○ stopped\n")
        sv?.daemons?.forEach { d ->
            s.append("  ").append(d.spec.name.padEnd(12)).append(d.state.name.lowercase())
            if (d.restarts > 0) s.append(" (").append(d.restarts).append(" restarts)")
            s.append('\n')
        }
        val ips = localIps()
        val ip = ips.firstOrNull() ?: "<phone-ip>"
        s.append("\nIP    ").append(ips.joinToString().ifEmpty { "none" }).append('\n')
        s.append("SSH   ssh -p ${cfg.sshPort} $ip\n")
        s.append("Web   http://$ip:${cfg.webPort}\n")
        s.append(deviceStats())
        status.text = s

        toggle.setText(if (sv != null) R.string.stop else R.string.start)
        battery.visibility =
            if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) View.GONE
            else View.VISIBLE
        logs.text = sv?.daemons.orEmpty()
            .flatMap { d -> d.log.tail(15).map { "[${d.spec.name}] $it" } }
            .joinToString("\n")
    }

    private fun deviceStats(): String {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val mb = 1024 * 1024
        var out = "Batt  $level%${if (plugged) " ⚡" else ""}  $temp°C\n" +
            "RAM   ${mem.availMem / mb} / ${mem.totalMem / mb} MB free\n"
        if (temp >= 45f) out += "⚠ Battery is hot. Keep the phone ventilated and limit charging to ~80% if possible.\n"
        return out
    }

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
