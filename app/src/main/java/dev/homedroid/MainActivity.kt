package dev.homedroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
        findViewById<Button>(R.id.apps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
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
        val width = (sv?.daemons.orEmpty().maxOfOrNull { it.spec.name.length } ?: 0) + 2
        sv?.daemons?.forEach { d ->
            s.append("  ").append(d.spec.name.padEnd(width)).append(d.state.name.lowercase())
            if (d.restarts > 0) s.append(" (").append(d.restarts).append(" restarts)")
            s.append('\n')
        }
        val dev = Device(this)
        val ip = dev.ips.firstOrNull() ?: "<phone-ip>"
        s.append("\nIP    ").append(dev.ips.joinToString().ifEmpty { "none" }).append('\n')
        if (sv != null) {
            s.append("Panel http://$ip:${cfg.dashboardPort}  password ${cfg.dashboardPassword}\n")
        }
        if (cfg.sshEnabled) s.append("SSH   ssh -p ${cfg.sshPort} $ip\n")
        if (cfg.webEnabled) s.append("Web   http://$ip:${cfg.webPort}\n")
        s.append("Batt  ${dev.batteryPercent}%${if (dev.charging) " ⚡" else ""}  ${dev.batteryTempC}°C\n")
        s.append("RAM   ${dev.ramFreeMb} / ${dev.ramTotalMb} MB free\n")
        s.append("Disk  ${dev.storageFreeMb / 1024} / ${dev.storageTotalMb / 1024} GB free\n")
        if (dev.hot) s.append("⚠ Battery is hot. Keep the phone ventilated and limit charging to ~80% if possible.\n")
        status.setTextIfChanged(s)

        toggle.setTextIfChanged(getString(if (sv != null) R.string.stop else R.string.start))
        battery.setVisibleIfChanged(
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        )
        logs.setTextIfChanged(
            sv?.daemons.orEmpty()
                .flatMap { d -> d.log.tail(15).map { "[${d.spec.name}] $it" } }
                .joinToString("\n")
        )
    }
}
