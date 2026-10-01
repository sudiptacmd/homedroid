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
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray

/** What the home-screen widget shows, worked out apart from Android so it can be tested. */
object WidgetModel {
    /** One line: a coloured dot, a name, and a detail on the right. */
    data class Row(val name: String, val detail: String, val color: Int)

    const val OK = 0xFF4ADE80.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val BAD = 0xFFF87171.toInt()
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
        val s = if (services.size <= room) services else services.take(maxOf(0, room - 1)) + Row("+${services.size - room + 1} more", "", OFF)
        return p to s
    }

    private val CORE = mapOf("sshd" to "SSH & SFTP", "caddy" to "Web hosting", "cloudflared" to "Cloudflare Tunnel",
        "tailscaled" to "Tailscale", Ai.SERVICE to "AI model")
}

/**
 * The home-screen widget: every phone in the cluster and the services running on this one.
 * The server refreshes it every minute (see [ServerService]); tapping it opens the dashboard.
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(context)

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        Thread({ try { refresh(context) } finally { pending.finish() } }, "widget").start()
    }

    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))

        /** Redraws every placed widget; does nothing (and asks no phones) when none is placed. Slow: not on the main thread. */
        fun refresh(context: Context) {
            val ctx = context.applicationContext
            val ids = ids(ctx)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(ctx)
            val running = ServerService.running
            val dev = Device(ctx)
            val cluster = Cluster.instance
            val phones = mutableListOf(WidgetModel.phone(cluster?.name ?: Build.MODEL, true, dev.batteryPercent, dev.batteryTempC.toDouble(), dev.charging))
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
            val services = WidgetModel.services(ServerService.supervisor?.daemons.orEmpty()
                .map { Triple(WidgetModel.label(it.spec.name, apps, deploys), it.state.name.lowercase(), it.restarts) })
            val url = dev.ips.firstOrNull()?.let { "http://$it:${Config(ctx).dashboardPort}" }

            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
                // Header and labels take ~70 dp; a row is 22 dp.
                val (p, s) = WidgetModel.fit(phones, services, maxOf(2, (heightDp - 70) / 22))
                val v = RemoteViews(ctx.packageName, R.layout.widget_status)
                v.setTextViewText(R.id.widget_summary, when {
                    !running -> "server stopped"
                    else -> "${phones.size} phone${if (phones.size == 1) "" else "s"} · ${services.count { it.color == WidgetModel.OK }}/${services.size} running"
                })
                v.removeAllViews(R.id.widget_phones)
                p.forEach { v.addView(R.id.widget_phones, row(ctx, it)) }
                v.removeAllViews(R.id.widget_services)
                s.forEach { v.addView(R.id.widget_services, row(ctx, it)) }
                val noServices = !running || services.isEmpty()
                v.setViewVisibility(R.id.widget_services_label, if (noServices) View.GONE else View.VISIBLE)
                // A single phone needs no "Phones" heading.
                v.setViewVisibility(R.id.widget_phones_label, if (phones.size > 1) View.VISIBLE else View.GONE)
                v.setViewVisibility(R.id.widget_message, if (noServices) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.widget_message, if (!running) "Tap to open Homedroid and start the server." else "Nothing is running. Turn on a module in the dashboard.")
                val app = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                val open = if (running && url != null) PendingIntent.getActivity(ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE) else app
                v.setOnClickPendingIntent(R.id.widget_root, open)
                v.setOnClickPendingIntent(R.id.widget_header, app)
                manager.updateAppWidget(id, v)
            }
        }

        private fun row(ctx: Context, r: WidgetModel.Row) = RemoteViews(ctx.packageName, R.layout.widget_row).apply {
            setTextViewText(R.id.row_name, r.name)
            setTextViewText(R.id.row_detail, r.detail)
            setTextColor(R.id.row_dot, r.color)
        }
    }
}
