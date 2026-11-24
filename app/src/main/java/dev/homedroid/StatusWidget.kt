package dev.homedroid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import kotlin.math.roundToInt

/** What the home-screen widgets show, worked out apart from Android so it can be tested. */
object WidgetModel {
    /** One line: a coloured dot, a name, and a detail on the right. */
    data class Row(val name: String, val detail: String, val color: Int)

    // Mid-tones that read on both the light and the dark widget surface.
    const val OK = 0xFF34C27A.toInt()
    const val WARN = 0xFFF2A93B.toInt()
    const val BAD = 0xFFEF5350.toInt()
    const val OFF = 0xFF8A92A3.toInt()

    /** A phone: online with battery and temperature, or offline. */
    fun phone(name: String, online: Boolean, battery: Int, tempC: Double?, charging: Boolean): Row = if (!online) Row(name, "offline", BAD)
        else Row(name, "$battery%${if (charging) "⚡" else ""}${tempC?.let { " · %.0f °C".format(it) }.orEmpty()}",
            if ((tempC ?: 0.0) >= 45 || battery in 0..15 && !charging) WARN else OK)

    /** A service: crashing ones first, then starting, then running, each group by name. */
    fun services(daemons: List<Triple<String, String, Int>>): List<Row> = daemons
        .map { (label, state, restarts) ->
            val color = when (state) { "running" -> if (restarts > 0) WARN else OK; "starting" -> WARN; "backoff" -> BAD; else -> OFF }
            Row(label, when (state) { "backoff" -> "crashing"; "running" -> if (restarts > 0) "running · $restarts restarts" else "running"; else -> state }, color)
        }
        .sortedWith(compareBy({ listOf(BAD, WARN, OK, OFF).indexOf(it.color) }, { it.name.lowercase() }))

    /** A readable name for a supervised process (sshd, jellyfin, immich-server, deploy-blog…). */
    fun label(name: String, apps: List<AppDef>, deploys: List<Deploy>): String {
        CORE[name]?.let { return it }
        if (name.startsWith("deploy-")) deploys.firstOrNull { it.service == name }?.let { return it.name }
        for (a in apps) {
            if (a.services.isEmpty() && name == a.id) return a.name
            a.services.firstOrNull { "${a.id}-${it.name}" == name }?.let { return "${a.name} · ${it.name}" }
        }
        return name
    }

    /**
     * How many rows fit: phones first (at most 4), services in the rest; anything left over
     * becomes a "+N more" line. [rows] is the widget's height in rows.
     */
    fun fit(phones: List<Row>, services: List<Row>, rows: Int): Pair<List<Row>, List<Row>> {
        val p = phones.take(minOf(4, maxOf(1, rows / 2)))
        val room = maxOf(0, rows - p.size)
        val s = when {
            services.size <= room -> services
            room == 0 -> emptyList()
            else -> services.take(room - 1) + Row("+${services.size - room + 1} more", "", OFF)
        }
        return p to s
    }

    /** The status pill: Online, Needs attention, Starting or Stopped. */
    data class Status(val label: String, val color: Int)

    fun status(running: Boolean, active: Boolean, services: List<Row>): Status = when {
        running && services.any { it.color == BAD } -> Status("Needs attention", BAD)
        running -> Status("Online", OK)
        active -> Status("Starting", WARN)
        else -> Status("Stopped", OFF)
    }

    /** The line under the metrics, e.g. "5 of 6 services running". */
    fun summary(running: Boolean, services: List<Row>): String {
        if (!running) return "Server stopped"
        if (services.isEmpty()) return "No services turned on"
        val up = services.count { it.detail.startsWith("running") }
        return if (up == services.size) "All ${services.size} service${if (services.size == 1) "" else "s"} running"
            else "$up of ${services.size} services running"
    }

    /** Big-number formatting: "84%", "31°", "—" when unknown. */
    fun percent(v: Int): String = if (v < 0) "—" else "$v%"
    fun percent(v: Double?): String = if (v == null || v.isNaN() || v < 0) "—" else "${v.roundToInt()}%"
    fun degrees(c: Double?): String = if (c == null || c.isNaN() || c <= 0) "—" else "${c.roundToInt()}°"
    fun ramUsed(freeMb: Long, totalMb: Long): Int = if (totalMb <= 0) -1 else ((totalMb - freeMb).coerceAtLeast(0) * 100 / totalMb).toInt()

    /** The caption under the battery figure. */
    fun batteryLabel(percent: Int, charging: Boolean): String = when {
        charging && percent >= 100 -> "Full"
        charging -> "Charging"
        else -> "Battery"
    }

    /** The clock widget's status line: "Online · 84% charging". */
    fun clockLine(status: Status, battery: Int, charging: Boolean): String =
        status.label + if (battery < 0) "" else " · $battery%" + if (charging) " charging" else ""

    /**
     * The layouts, smallest first, with the least size (dp) each needs. Android 12+ gets them all
     * as a size map; older versions pick one with [size].
     * Small: status and battery. Compact: + header and metrics. Medium: + summary and action.
     * Large: + metrics strip, service rows and two buttons (~215 dp before any row).
     */
    enum class Size(val minWidth: Int, val minHeight: Int) { SMALL(0, 0), COMPACT(180, 90), MEDIUM(180, 140), LARGE(250, 250) }

    fun size(widthDp: Int, heightDp: Int): Size = Size.entries.last { widthDp >= it.minWidth && heightDp >= it.minHeight }

    /** Service rows that fit a large widget [heightDp] tall. */
    fun largeRows(heightDp: Int): Int = maxOf(1, (heightDp - 215) / 26)

    /** The Dock clock widget: a one-line strip below 180×110 dp, the stacked clock above. */
    fun clockFull(widthDp: Int, heightDp: Int): Boolean = widthDp >= 180 && heightDp >= 110

    private val CORE = mapOf("sshd" to "SSH & SFTP", "caddy" to "Web hosting", "cloudflared" to "Cloudflare Tunnel",
        "tailscaled" to "Tailscale", Ai.SERVICE to "AI model")
}

/**
 * The home-screen widget: this phone's status pill, key metrics, services (and other cluster
 * phones), and an action. Three layouts: 2×1, 4×2 and 4×3+; Android 12+ picks between them
 * as the widget is resized. The server refreshes it every minute (see [ServerService]).
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(this, context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(this, context)

    /** Everything both widgets show, gathered once per refresh. */
    private class Data(ctx: Context) {
        val running = ServerService.running
        val dev = Device(ctx)
        val metrics = runCatching { Metrics.sample(ctx) }.getOrNull()
        val phones = mutableListOf<WidgetModel.Row>()
        val services: List<WidgetModel.Row>
        val status: WidgetModel.Status
        val url: String?
        val name: String

        init {
            val cluster = Cluster.instance
            name = cluster?.name ?: Build.MODEL
            val members = if (running && cluster != null) try { cluster.membersJson() } catch (_: Exception) { JSONArray() } else JSONArray()
            for (i in 0 until members.length()) {
                val m = members.getJSONObject(i)
                val s = m.optJSONObject("status")
                val temp = s?.optJSONObject("metrics")?.optDouble("cpuTemp")?.takeIf { !it.isNaN() } ?: s?.optDouble("batteryTemp")
                phones += WidgetModel.phone(m.optString("name"), m.optBoolean("online"), s?.optInt("battery") ?: -1, temp, s?.optBoolean("charging") == true)
            }
            val paths = Paths(ctx)
            val apps = Apps(ctx, paths).catalog
            val deploys = try { Deploys(ctx, paths).all() } catch (_: Exception) { emptyList() }
            services = WidgetModel.services(ServerService.supervisor?.daemons.orEmpty()
                .map { Triple(WidgetModel.label(it.spec.name, apps, deploys), it.state.name.lowercase(), it.restarts) })
            status = WidgetModel.status(running, ServerService.active, services)
            url = dev.ips.firstOrNull()?.let { "http://$it:${Config(ctx).dashboardPort}" }
        }
    }

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))

        internal fun refreshAsync(receiver: AppWidgetProvider, context: Context) {
            val pending = receiver.goAsync()
            Thread({ try { refresh(context) } finally { pending.finish() } }, "widget").start()
        }

        /**
         * Redraws every placed status and Dock clock widget; does nothing (and asks no phones)
         * when none is placed. Slow: not on the main thread.
         */
        fun refresh(context: Context) {
            val ctx = context.applicationContext
            val ids = ids(ctx)
            val clockIds = DockClockWidget.ids(ctx)
            if (ids.isEmpty() && clockIds.isEmpty()) return
            val manager = AppWidgetManager.getInstance(ctx)
            val d = Data(ctx)
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val views = if (Build.VERSION.SDK_INT >= 31) {
                    val rows = WidgetModel.largeRows(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300))
                    RemoteViews(WidgetModel.Size.entries.associate { SizeF(it.minWidth.toFloat(), it.minHeight.toFloat()) to layout(ctx, d, it, rows) })
                } else {
                    // Before Android 12: portrait uses min width × max height, landscape max width × min height.
                    val (land, port) = orientations(options)
                    RemoteViews(layout(ctx, d, WidgetModel.size(land.first, land.second), WidgetModel.largeRows(land.second)),
                        layout(ctx, d, WidgetModel.size(port.first, port.second), WidgetModel.largeRows(port.second)))
                }
                manager.updateAppWidget(id, views)
            }
            for (id in clockIds) manager.updateAppWidget(id, DockClockWidget.views(ctx, manager.getAppWidgetOptions(id), d.status, d.dev.batteryPercent, d.dev.charging))
        }

        /** (width, height) in landscape and in portrait, from the widget's options. */
        internal fun orientations(o: Bundle): Pair<Pair<Int, Int>, Pair<Int, Int>> {
            val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val maxW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
            val minH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)
            return (maxW to minH) to (minW to maxH)
        }

        private fun layout(ctx: Context, d: Data, size: WidgetModel.Size, rows: Int) = when (size) {
            WidgetModel.Size.SMALL -> small(ctx, d)
            WidgetModel.Size.COMPACT -> medium(ctx, d, R.layout.widget_status_compact)
            WidgetModel.Size.MEDIUM -> medium(ctx, d, R.layout.widget_status)
            WidgetModel.Size.LARGE -> large(ctx, d, rows)
        }

        private fun pill(v: RemoteViews, s: WidgetModel.Status) {
            v.setTextViewText(R.id.status_label, s.label)
            v.setInt(R.id.status_dot, "setColorFilter", s.color)
        }

        private fun appIntent(ctx: Context) =
            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

        /** Running: open the dashboard in the browser. Stopped: start the server. */
        private fun action(ctx: Context, v: RemoteViews, d: Data) {
            val running = d.running && d.url != null
            v.setTextViewText(R.id.widget_action, if (d.running) "Dashboard" else if (ServerService.active) "Starting…" else "Start server")
            val intent = when {
                running -> PendingIntent.getActivity(ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse(d.url)), PendingIntent.FLAG_IMMUTABLE)
                d.running -> appIntent(ctx)
                else -> PendingIntent.getForegroundService(ctx, 2, Intent(ctx, ServerService::class.java), PendingIntent.FLAG_IMMUTABLE)
            }
            v.setOnClickPendingIntent(R.id.widget_action, intent)
        }

        private fun metrics(v: RemoteViews, d: Data, ram: Boolean) {
            v.setTextViewText(R.id.m_battery, WidgetModel.percent(d.dev.batteryPercent))
            v.setTextViewText(R.id.m_battery_label, WidgetModel.batteryLabel(d.dev.batteryPercent, d.dev.charging))
            val cpuTemp = d.metrics?.optDouble("cpuTemp")?.takeIf { !it.isNaN() }
            v.setTextViewText(R.id.m_temp, WidgetModel.degrees(cpuTemp ?: d.dev.batteryTempC.toDouble()))
            v.setTextViewText(R.id.m_cpu, if (d.running) WidgetModel.percent(d.metrics?.optDouble("cpu")) else "—")
            if (ram) v.setTextViewText(R.id.m_ram, WidgetModel.percent(WidgetModel.ramUsed(d.dev.ramFreeMb, d.dev.ramTotalMb)))
        }

        private fun small(ctx: Context, d: Data) = RemoteViews(ctx.packageName, R.layout.widget_status_small).apply {
            pill(this, d.status)
            setTextViewText(R.id.widget_name, d.name)
            setTextViewText(R.id.m_battery, WidgetModel.percent(d.dev.batteryPercent))
            setTextViewText(R.id.m_battery_label, if (d.dev.charging) "⚡ ${WidgetModel.degrees(d.dev.batteryTempC.toDouble())}" else WidgetModel.degrees(d.dev.batteryTempC.toDouble()))
            setOnClickPendingIntent(android.R.id.background, appIntent(ctx))
        }

        private fun medium(ctx: Context, d: Data, layout: Int) = RemoteViews(ctx.packageName, layout).apply {
            pill(this, d.status)
            setTextViewText(R.id.widget_name, d.name)
            metrics(this, d, false)
            if (layout == R.layout.widget_status) {
                setTextViewText(R.id.widget_summary, WidgetModel.summary(d.running, d.services))
                action(ctx, this, d)
            }
            setOnClickPendingIntent(android.R.id.background, appIntent(ctx))
        }

        private fun large(ctx: Context, d: Data, rows: Int) = RemoteViews(ctx.packageName, R.layout.widget_status_large).apply {
            pill(this, d.status)
            setTextViewText(R.id.widget_name, d.name)
            metrics(this, d, true)
            // Other cluster phones get rows too; this phone is already the header.
            val others = d.phones.filter { it.name != d.name }
            val (p, s) = WidgetModel.fit(others, d.services, rows)
            removeAllViews(R.id.widget_services)
            (p + s).forEach { addView(R.id.widget_services, row(ctx, it)) }
            val empty = !d.running || d.services.isEmpty()
            setTextViewText(R.id.widget_services_label, if (p.isNotEmpty()) "Cluster & services" else "Services")
            setViewVisibility(R.id.widget_services_label, if (p.isEmpty() && empty) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_message, if (empty) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_message, if (!d.running) "The server is stopped. Start it to bring your apps online."
                else "Nothing is running yet. Turn on a module in the dashboard.")
            action(ctx, this, d)
            setOnClickPendingIntent(R.id.widget_dock, DockClockWidget.dockIntent(ctx))
            setOnClickPendingIntent(android.R.id.background, appIntent(ctx))
        }

        private fun row(ctx: Context, r: WidgetModel.Row) = RemoteViews(ctx.packageName, R.layout.widget_row).apply {
            setTextViewText(R.id.row_name, r.name)
            setTextViewText(R.id.row_detail, r.detail)
            setInt(R.id.row_dot, "setColorFilter", r.color)
        }
    }
}

/** A mini Dock clock for the home screen: TextClock ticks on its own; the status line comes from [StatusWidget.refresh]. */
class DockClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = StatusWidget.refreshAsync(this, context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = StatusWidget.refreshAsync(this, context)

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, DockClockWidget::class.java))

        fun dockIntent(ctx: Context): PendingIntent =
            PendingIntent.getActivity(ctx, 3, DockActivity.intent(ctx, false), PendingIntent.FLAG_IMMUTABLE)

        internal fun views(ctx: Context, options: Bundle, status: WidgetModel.Status, battery: Int, charging: Boolean): RemoteViews {
            fun build(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
                setTextViewText(R.id.clock_status, WidgetModel.clockLine(status, battery, charging))
                setInt(R.id.status_dot, "setColorFilter", status.color)
                setOnClickPendingIntent(android.R.id.background, dockIntent(ctx))
            }
            fun pick(size: Pair<Int, Int>) = build(if (WidgetModel.clockFull(size.first, size.second)) R.layout.widget_clock else R.layout.widget_clock_small)
            if (Build.VERSION.SDK_INT >= 31) return RemoteViews(mapOf(
                SizeF(0f, 0f) to build(R.layout.widget_clock_small),
                SizeF(180f, 110f) to build(R.layout.widget_clock),
            ))
            val (land, port) = StatusWidget.orientations(options)
            return RemoteViews(pick(land), pick(port))
        }
    }
}
