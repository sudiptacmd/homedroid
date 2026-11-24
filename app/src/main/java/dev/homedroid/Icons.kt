package dev.homedroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line icons drawn from paths on a 24-unit grid (1.75-unit rounded strokes), so they stay crisp at
 * every density and need no image assets. A few have a filled part ([fill]).
 */
enum class Icon {
    Home, Apps, Tune, Settings, Power, Battery, Bolt, Thermometer, Cpu, Memory, Storage, Network,
    Wifi, Globe, Copy, Share, OpenExternal, ChevronRight, ChevronDown, Check, Plus, Close, Download,
    Refresh, Trash, Lock, Fingerprint, Key, Terminal, Shield, Cloud, Camera, Logs, Folder, Sparkle,
    Layers, Phone, Contrast, Clock, Dock, Keypad, Info, Warning, Alert, Server, Plug, Stop, Play,
    Eye, Bell, Nodes, Swatch, Mic, MicOff, VolumeUp, VolumeDown, VolumeMute, Pause, Next, Previous,
    Screenshot, Paste, Undo, Redo, Light, Mouse, Keyboard, Music, Heart, Search, Minus;

    private val parts: Pair<Path, Path?> by lazy { build(this) }
    val stroke: Path get() = parts.first
    val fill: Path? get() = parts.second

    companion object {
        private fun oval(l: Float, t: Float, r: Float, b: Float) = RectF(l, t, r, b)

        private fun build(icon: Icon): Pair<Path, Path?> {
            val p = Path()
            var f: Path? = null
            fun line(vararg xy: Float) {
                p.moveTo(xy[0], xy[1])
                var i = 2
                while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
            }
            fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) = p.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW)
            fun circle(x: Float, y: Float, rad: Float) = p.addCircle(x, y, rad, Path.Direction.CW)
            fun dot(x: Float, y: Float, rad: Float = 1.1f) {
                (f ?: Path().also { f = it }).addCircle(x, y, rad, Path.Direction.CW)
            }
            when (icon) {
                Mic, MicOff -> {
                    rr(9f, 3f, 15f, 14f, 3f); p.addArc(oval(6f, 8f, 18f, 18f), 0f, 180f)
                    line(12f, 18f, 12f, 21f); line(9f, 21f, 15f, 21f)
                    if (icon == MicOff) line(3f, 3f, 21f, 21f)
                }
                VolumeUp, VolumeDown, VolumeMute -> {
                    line(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f); p.close()
                    if (icon == VolumeMute) { line(16f, 9f, 21f, 15f); line(21f, 9f, 16f, 15f) }
                    else { p.addArc(oval(10f, 8f, 18f, 16f), -65f, 130f); if (icon == VolumeUp) p.addArc(oval(8f, 4f, 22f, 20f), -65f, 130f) }
                }
                Pause -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
                Next -> { line(5f, 5f, 16f, 12f, 5f, 19f); p.close(); line(19f, 5f, 19f, 19f) }
                Previous -> { line(19f, 5f, 8f, 12f, 19f, 19f); p.close(); line(5f, 5f, 5f, 19f) }
                Screenshot -> { line(3f, 8f, 3f, 3f, 8f, 3f); line(16f, 3f, 21f, 3f, 21f, 8f); line(3f, 16f, 3f, 21f, 8f, 21f); line(16f, 21f, 21f, 21f, 21f, 16f); rr(7f, 7f, 17f, 17f, 2f) }
                Paste -> { rr(5f, 5f, 19f, 21f, 2f); rr(9f, 3f, 15f, 7f, 1f); line(9f, 12f, 15f, 12f); line(9f, 16f, 15f, 16f) }
                Undo -> { line(8f, 4f, 3f, 9f, 8f, 14f); p.moveTo(3f, 9f); p.lineTo(14f, 9f); p.cubicTo(23f, 9f, 23f, 20f, 14f, 20f) }
                Redo -> { line(16f, 4f, 21f, 9f, 16f, 14f); p.moveTo(21f, 9f); p.lineTo(10f, 9f); p.cubicTo(1f, 9f, 1f, 20f, 10f, 20f) }
                Light -> { p.addArc(oval(6f, 2f, 18f, 15f), 145f, 250f); line(7f, 12f, 9f, 17f, 15f, 17f, 17f, 12f); line(9f, 20f, 15f, 20f); line(11f, 22f, 13f, 22f) }
                Mouse -> { rr(6f, 2f, 18f, 22f, 6f); line(12f, 3f, 12f, 10f); line(6f, 10f, 18f, 10f) }
                Keyboard -> { rr(2f, 5f, 22f, 19f, 2f); for (x in listOf(6f, 10f, 14f, 18f)) { dot(x, 9f, 0.5f); dot(x, 12f, 0.5f) }; line(7f, 16f, 17f, 16f) }
                Music -> { line(10f, 18f, 10f, 5f, 20f, 3f, 20f, 16f); p.addOval(oval(4f, 16f, 10f, 21f), Path.Direction.CW); p.addOval(oval(14f, 14f, 20f, 19f), Path.Direction.CW) }
                Heart -> { p.moveTo(12f, 21f); p.cubicTo(-5f, 9f, 6f, -2f, 12f, 7f); p.cubicTo(18f, -2f, 29f, 9f, 12f, 21f) }
                Search -> { circle(10f, 10f, 7f); line(15f, 15f, 21f, 21f) }
                Minus -> line(5f, 12f, 19f, 12f)
                Home -> { line(3f, 11f, 12f, 3.5f, 21f, 11f); line(5.5f, 9.5f, 5.5f, 20.5f, 18.5f, 20.5f, 18.5f, 9.5f); line(10f, 20.5f, 10f, 14.5f, 14f, 14.5f, 14f, 20.5f) }
                Apps -> for (x in listOf(3.5f, 13.5f)) for (y in listOf(3.5f, 13.5f)) rr(x, y, x + 7f, y + 7f, 2f)
                Tune -> {
                    for (y in listOf(6f, 12f, 18f)) line(3.5f, y, 20.5f, y)
                    dot(9f, 6f, 2.4f); dot(15f, 12f, 2.4f); dot(8f, 18f, 2.4f)
                }
                Settings -> {
                    circle(12f, 12f, 3f)
                    circle(12f, 12f, 6.5f)
                    for (k in 0 until 8) {
                        val a = Math.toRadians(k * 45.0)
                        line(12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat(), 12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat())
                    }
                }
                Power -> { p.addArc(oval(4f, 5f, 20f, 21f), -60f, 300f); line(12f, 3f, 12f, 12f) }
                Battery -> { rr(3f, 7f, 18.5f, 17f, 2.5f); line(21f, 10.5f, 21f, 13.5f) }
                Bolt -> { line(13f, 2.5f, 5f, 13.5f, 11.5f, 13.5f, 10.5f, 21.5f, 19f, 10f, 12.5f, 10f); p.close() }
                Thermometer -> {
                    p.moveTo(14f, 13.54f); p.arcTo(oval(8f, 13f, 16f, 21f), -60f, 300f)
                    p.lineTo(10f, 5f); p.arcTo(oval(10f, 3f, 14f, 7f), 180f, 180f); p.close()
                    dot(12f, 17f, 1.8f)
                }
                Cpu -> {
                    rr(6f, 6f, 18f, 18f, 2f); rr(9.5f, 9.5f, 14.5f, 14.5f, 1f)
                    for (v in listOf(9f, 12f, 15f)) { line(v, 3f, v, 6f); line(v, 18f, v, 21f); line(3f, v, 6f, v); line(18f, v, 21f, v) }
                }
                Memory -> {
                    rr(3f, 6f, 21f, 15f, 1.5f)
                    for (x in listOf(6f, 10.5f, 15f)) rr(x, 9f, x + 3f, 12f, 0.5f)
                    for (x in listOf(6f, 9f, 12f, 15f, 18f)) line(x, 15f, x, 18f)
                }
                Storage -> {
                    p.addOval(oval(5f, 3f, 19f, 8f), Path.Direction.CW)
                    line(5f, 5.5f, 5f, 18.5f); line(19f, 5.5f, 19f, 18.5f)
                    p.addArc(oval(5f, 16f, 19f, 21f), 0f, 180f); p.addArc(oval(5f, 9.5f, 19f, 14.5f), 0f, 180f)
                }
                Network -> { line(8f, 20f, 8f, 4f); line(4.5f, 7.5f, 8f, 4f, 11.5f, 7.5f); line(16f, 4f, 16f, 20f); line(12.5f, 16.5f, 16f, 20f, 19.5f, 16.5f) }
                Wifi -> {
                    for (rad in listOf(4f, 8f, 12f)) p.addArc(oval(12f - rad, 19f - rad, 12f + rad, 19f + rad), 225f, 90f)
                    dot(12f, 19f, 1.3f)
                }
                Globe -> { circle(12f, 12f, 9f); p.addOval(oval(8f, 3f, 16f, 21f), Path.Direction.CW); line(3f, 12f, 21f, 12f) }
                Copy -> {
                    rr(8f, 8f, 20f, 20f, 2f)
                    p.moveTo(16f, 8f); p.lineTo(16f, 5.5f); p.quadTo(16f, 4f, 14.5f, 4f); p.lineTo(5.5f, 4f)
                    p.quadTo(4f, 4f, 4f, 5.5f); p.lineTo(4f, 14.5f); p.quadTo(4f, 16f, 5.5f, 16f); p.lineTo(8f, 16f)
                }
                Share -> {
                    circle(18f, 5f, 2.5f); circle(6f, 12f, 2.5f); circle(18f, 19f, 2.5f)
                    line(8.2f, 10.8f, 15.8f, 6.2f); line(8.2f, 13.2f, 15.8f, 17.8f)
                }
                OpenExternal -> {
                    p.moveTo(19f, 13.5f); p.lineTo(19f, 18.5f); p.quadTo(19f, 20f, 17.5f, 20f); p.lineTo(5.5f, 20f)
                    p.quadTo(4f, 20f, 4f, 18.5f); p.lineTo(4f, 6.5f); p.quadTo(4f, 5f, 5.5f, 5f); p.lineTo(10.5f, 5f)
                    line(14f, 4f, 20f, 4f, 20f, 10f); line(20f, 4f, 11f, 13f)
                }
                ChevronRight -> line(9f, 5.5f, 15.5f, 12f, 9f, 18.5f)
                ChevronDown -> line(5.5f, 9f, 12f, 15.5f, 18.5f, 9f)
                Check -> line(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 7f)
                Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                Download -> { line(12f, 4f, 12f, 15f); line(7f, 10f, 12f, 15f, 17f, 10f); line(5f, 19.5f, 19f, 19.5f) }
                Refresh -> {
                    p.addArc(oval(5f, 5f, 19f, 19f), 30f, 300f)
                    line(18.6f, 4.2f, 18.06f, 8.5f, 13.9f, 7.6f)
                }
                Trash -> {
                    line(4f, 7f, 20f, 7f)
                    p.moveTo(9f, 7f); p.lineTo(9f, 5f); p.quadTo(9f, 4f, 10f, 4f); p.lineTo(14f, 4f); p.quadTo(15f, 4f, 15f, 5f); p.lineTo(15f, 7f)
                    p.moveTo(6f, 7f); p.lineTo(7f, 19f); p.quadTo(7.2f, 20.5f, 8.5f, 20.5f); p.lineTo(15.5f, 20.5f); p.quadTo(16.8f, 20.5f, 17f, 19f); p.lineTo(18f, 7f)
                    line(10f, 11f, 10f, 16.5f); line(14f, 11f, 14f, 16.5f)
                }
                Lock -> {
                    rr(5f, 10.5f, 19f, 20.5f, 2.5f)
                    p.moveTo(8f, 10.5f); p.lineTo(8f, 8f); p.arcTo(oval(8f, 4f, 16f, 12f), 180f, 180f); p.lineTo(16f, 10.5f)
                    line(12f, 14.5f, 12f, 16.5f)
                }
                Fingerprint -> {
                    p.addArc(oval(3f, 3f, 21f, 21f), 200f, 140f)
                    p.addArc(oval(6f, 6f, 18f, 18f), 170f, 220f)
                    p.addArc(oval(9f, 9f, 15f, 15f), 150f, 260f)
                    line(12f, 12f, 12f, 20f); line(6.3f, 14f, 7.5f, 19f); line(17.8f, 14f, 16.8f, 19f)
                }
                Key -> { circle(8f, 15f, 4f); line(10.9f, 12.1f, 20f, 3f); line(17f, 6f, 19.5f, 8.5f); line(14.5f, 8.5f, 16.5f, 10.5f) }
                Terminal -> { rr(3f, 4f, 21f, 20f, 2.5f); line(7f, 9f, 10f, 12f, 7f, 15f); line(12.5f, 15f, 17f, 15f) }
                Shield -> {
                    p.moveTo(12f, 3f); p.lineTo(19f, 6f); p.lineTo(19f, 11.5f); p.cubicTo(19f, 16f, 16f, 19.5f, 12f, 21f)
                    p.cubicTo(8f, 19.5f, 5f, 16f, 5f, 11.5f); p.lineTo(5f, 6f); p.close()
                    line(9f, 12f, 11.2f, 14.2f, 15.5f, 9.8f)
                }
                Cloud -> {
                    p.moveTo(7f, 18.5f); p.cubicTo(4.2f, 18.5f, 3f, 16.5f, 3f, 14.6f); p.cubicTo(3f, 12.6f, 4.6f, 11.1f, 6.5f, 11f)
                    p.cubicTo(7.2f, 7.8f, 9.6f, 5.5f, 12.5f, 5.5f); p.cubicTo(15.7f, 5.5f, 18.2f, 7.9f, 18.4f, 11.2f)
                    p.cubicTo(20f, 11.6f, 21f, 13f, 21f, 14.8f); p.cubicTo(21f, 16.9f, 19.4f, 18.5f, 17.5f, 18.5f); p.close()
                }
                Camera -> {
                    rr(3f, 7f, 21f, 19.5f, 2.5f); line(8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f); circle(12f, 13.2f, 3.5f)
                }
                Logs -> { for (y in listOf(6f, 12f, 18f)) { line(9f, y, 20.5f, y); dot(4.8f, y) } }
                Folder -> {
                    p.moveTo(3f, 7f); p.quadTo(3f, 5f, 5f, 5f); p.lineTo(9.5f, 5f); p.lineTo(11.5f, 7f); p.lineTo(19f, 7f)
                    p.quadTo(21f, 7f, 21f, 9f); p.lineTo(21f, 17.5f); p.quadTo(21f, 19.5f, 19f, 19.5f); p.lineTo(5f, 19.5f)
                    p.quadTo(3f, 19.5f, 3f, 17.5f); p.close(); line(3f, 10f, 21f, 10f)
                }
                Sparkle -> {
                    p.moveTo(11f, 3.5f); p.cubicTo(11.6f, 8.5f, 14f, 10.9f, 19f, 11.5f); p.cubicTo(14f, 12.1f, 11.6f, 14.5f, 11f, 19.5f)
                    p.cubicTo(10.4f, 14.5f, 8f, 12.1f, 3f, 11.5f); p.cubicTo(8f, 10.9f, 10.4f, 8.5f, 11f, 3.5f); p.close()
                    line(18.5f, 16f, 18.5f, 21f); line(16f, 18.5f, 21f, 18.5f)
                }
                Layers -> { line(12f, 3f, 21f, 8f, 12f, 13f, 3f, 8f); p.close(); line(3f, 12.5f, 12f, 17.5f, 21f, 12.5f); line(3f, 16.5f, 12f, 21.5f, 21f, 16.5f) }
                Phone -> { rr(6f, 2.5f, 18f, 21.5f, 2.5f); line(10.5f, 18.5f, 13.5f, 18.5f) }
                Contrast -> {
                    circle(12f, 12f, 8.5f)
                    f = Path().apply { moveTo(12f, 3.5f); arcTo(oval(3.5f, 3.5f, 20.5f, 20.5f), -90f, 180f); close() }
                }
                Clock -> { circle(12f, 12f, 9f); line(12f, 7f, 12f, 12f, 15.5f, 14f) }
                Dock -> { rr(3f, 4f, 21f, 16f, 2.5f); line(12f, 16f, 12f, 20f); line(8.5f, 20f, 15.5f, 20f); line(12f, 7f, 12f, 10f, 14.5f, 11.5f) }
                Keypad -> for (x in listOf(3f, 9.5f, 16f)) for (y in listOf(5f, 13f)) rr(x, y, x + 5f, y + 6f, 1.5f)
                Info -> { circle(12f, 12f, 9f); line(12f, 11f, 12f, 16.5f); dot(12f, 7.8f) }
                Warning -> {
                    p.moveTo(12f, 3.5f); p.lineTo(21.5f, 20f); p.lineTo(2.5f, 20f); p.close()
                    line(12f, 9.5f, 12f, 14f); dot(12f, 17f)
                }
                Alert -> { circle(12f, 12f, 9f); line(12f, 7.5f, 12f, 13f); dot(12f, 16.4f) }
                Server -> { rr(3f, 4f, 21f, 10.5f, 2f); rr(3f, 13.5f, 21f, 20f, 2f); dot(7f, 7.25f); dot(7f, 16.75f); line(11f, 7.25f, 17f, 7.25f); line(11f, 16.75f, 17f, 16.75f) }
                Plug -> {
                    line(9f, 3f, 9f, 7f); line(15f, 3f, 15f, 7f)
                    p.moveTo(6f, 7f); p.lineTo(18f, 7f); p.lineTo(18f, 11f); p.quadTo(18f, 16f, 12f, 16f); p.quadTo(6f, 16f, 6f, 11f); p.close()
                    line(12f, 16f, 12f, 21f)
                }
                Stop -> rr(6f, 6f, 18f, 18f, 2.5f)
                Play -> { line(8f, 5f, 19f, 12f, 8f, 19f); p.close() }
                Eye -> {
                    p.moveTo(2.5f, 12f); p.cubicTo(5f, 7f, 8.5f, 5f, 12f, 5f); p.cubicTo(15.5f, 5f, 19f, 7f, 21.5f, 12f)
                    p.cubicTo(19f, 17f, 15.5f, 19f, 12f, 19f); p.cubicTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); p.close()
                    circle(12f, 12f, 3f)
                }
                Bell -> {
                    p.moveTo(6f, 16.5f); p.lineTo(6f, 11f); p.cubicTo(6f, 7.5f, 8.7f, 5f, 12f, 5f); p.cubicTo(15.3f, 5f, 18f, 7.5f, 18f, 11f)
                    p.lineTo(18f, 16.5f); p.lineTo(19.5f, 18f); p.lineTo(4.5f, 18f); p.close(); line(10f, 21f, 14f, 21f); line(12f, 3f, 12f, 5f)
                }
                Nodes -> {
                    circle(12f, 5f, 2.2f); circle(5f, 18f, 2.2f); circle(19f, 18f, 2.2f)
                    line(10.9f, 6.9f, 6.1f, 16.1f); line(13.1f, 6.9f, 17.9f, 16.1f); line(7.2f, 18f, 16.8f, 18f)
                }
                Swatch -> {
                    p.moveTo(12f, 3f); p.cubicTo(7f, 3f, 3f, 7f, 3f, 12f); p.cubicTo(3f, 17f, 7f, 21f, 12f, 21f)
                    p.cubicTo(13.2f, 21f, 13.8f, 20.2f, 13.5f, 19.2f); p.cubicTo(13.1f, 17.8f, 14f, 16.5f, 15.5f, 16.5f)
                    p.lineTo(17f, 16.5f); p.cubicTo(19.2f, 16.5f, 21f, 14.7f, 21f, 12.5f); p.cubicTo(21f, 7.3f, 17f, 3f, 12f, 3f); p.close()
                    dot(7.5f, 12f, 1.3f); dot(9.5f, 7.8f, 1.3f); dot(14.5f, 7.8f, 1.3f); dot(17f, 11.5f, 1.3f)
                }
            }
            return p to f
        }
    }
}

/** Draws an [Icon] scaled to its bounds, in one colour. */
class IconDrawable(private val icon: Icon, color: Int, private val sizePx: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.75f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    var color: Int
        get() = pen.color
        set(v) { if (pen.color != v) { pen.color = v; brush.color = v; invalidateSelf() } }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        canvas.drawPath(icon.stroke, pen)
        icon.fill?.let { canvas.drawPath(it, brush) }
        canvas.restore()
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { pen.alpha = alpha; brush.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter = filter; brush.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A decorative icon view; give its parent the content description. */
@android.annotation.SuppressLint("ViewConstructor")
class IconView(ctx: Context, icon: Icon, color: Int) : View(ctx) {
    private var drawable = IconDrawable(icon, color, 0)
    private var current = icon
    var color: Int
        get() = drawable.color
        set(v) { if (drawable.color != v) { drawable.color = v; invalidate() } }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setIcon(icon: Icon) {
        if (icon == current) return
        current = icon; drawable = IconDrawable(icon, drawable.color, 0); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawable.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        drawable.draw(canvas)
    }
}
