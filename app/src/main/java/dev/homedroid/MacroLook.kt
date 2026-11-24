package dev.homedroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Turns a resolved [MacroLookCore.Look] into Typefaces, backdrops and decoded icon bitmaps. */
internal class MacroLook(private val ctx: Context, private val assets: MacroAssets) {
    private val bitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val faces = HashMap<String, Typeface>()

    fun typeface(look: MacroLookCore.Look): Typeface {
        val key = "${look.family}/${look.customFont}/${look.weight}"
        return faces.getOrPut(key) {
            val base = when (look.family) {
                "sans" -> Typeface.create("sans-serif", Typeface.NORMAL)
                "serif" -> Typeface.create("serif", Typeface.NORMAL)
                "mono" -> Typeface.create("monospace", Typeface.NORMAL)
                "rounded" -> Typeface.create("sans-serif-rounded", Typeface.NORMAL).takeIf { it != Typeface.DEFAULT } ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                "custom" -> look.customFont?.let { assets.file(it) }?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
                else -> Typeface.DEFAULT
            }
            Typeface.create(base, look.weight, false)
        }
    }

    /** Decoded asset image, longest side at most [max] px; null if missing or unreadable. */
    fun bitmap(id: String, max: Int): Bitmap? = bitmaps.get("$id@$max") ?: assets.file(id)?.let { f ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
            var sample = 1; while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { bitmaps.put("$id@$max", it) }
    }

    /** Average luminance 0..1 of an image, used to pick readable header colors. */
    fun luminance(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true); var sum = 0f
        for (x in 0 until 8) for (y in 0 until 8) small.getPixel(x, y).let { sum += 0.2126f * Color.red(it) + 0.7152f * Color.green(it) + 0.0722f * Color.blue(it) }
        return sum / 64 / 255f
    }

    /** The deck background: colour, gradient (CSS-style angles), OLED black, or a blurred and dimmed image. Null keeps the app's own. */
    fun backdrop(look: MacroLookCore.Look): Drawable? = when (look.bgType) {
        "color" -> ColorDrawable(look.bgColor)
        "oled" -> ColorDrawable(Color.BLACK)
        "gradient" -> GradientDrawable(orientation(look.bgAngle), look.bgColors.toIntArray())
        "image" -> {
            val src = look.bgImage?.let { bitmap(it, 1280) }
            if (src == null) ColorDrawable(Color.BLACK) else ImageBackdrop(blur(src, look.blur), look.dim)
        }
        else -> null
    }
    private fun orientation(angle: Int) = when (angle) {
        0 -> GradientDrawable.Orientation.BOTTOM_TOP; 45 -> GradientDrawable.Orientation.BL_TR; 90 -> GradientDrawable.Orientation.LEFT_RIGHT
        135 -> GradientDrawable.Orientation.TL_BR; 180 -> GradientDrawable.Orientation.TOP_BOTTOM; 225 -> GradientDrawable.Orientation.TR_BL
        270 -> GradientDrawable.Orientation.RIGHT_LEFT; else -> GradientDrawable.Orientation.BR_TL
    }

    /** Cheap blur: shrink, run two box-blur passes, and let the bilinear upscale smooth the rest. */
    private fun blur(src: Bitmap, amount: Int): Bitmap {
        if (amount <= 0) return src
        val scale = 160f / max(src.width, src.height)
        val w = max(8, (src.width * scale).toInt()); val h = max(8, (src.height * scale).toInt())
        val px = IntArray(w * h).also { Bitmap.createScaledBitmap(src, w, h, true).getPixels(it, 0, w, 0, 0, w, h) }
        val radius = max(1, amount * 8 / 25)
        repeat(2) { boxPass(px, w, h, radius, true); boxPass(px, w, h, radius, false) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    private fun boxPass(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h; val lines = if (horizontal) h else w
        val out = IntArray(len)
        for (line in 0 until lines) {
            fun at(i: Int) = px[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) at(i).let { a += Color.alpha(it); rr += Color.red(it); g += Color.green(it); b += Color.blue(it) }
            val n = 2 * r + 1
            for (i in 0 until len) {
                out[i] = Color.argb(a / n, rr / n, g / n, b / n)
                val add = at(i + r + 1); val sub = at(i - r)
                a += Color.alpha(add) - Color.alpha(sub); rr += Color.red(add) - Color.red(sub); g += Color.green(add) - Color.green(sub); b += Color.blue(add) - Color.blue(sub)
            }
            for (i in 0 until len) px[if (horizontal) line * w + i else i * w + line] = out[i]
        }
    }

    /** Draws [bitmap] centre-cropped to the bounds, with a black scrim of [dim] percent. */
    class ImageBackdrop(private val bitmap: Bitmap, dim: Int) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val scrim = Paint().apply { color = Color.argb(dim * 255 / 100, 0, 0, 0) }
        override fun draw(canvas: Canvas) {
            val b = bounds; if (b.isEmpty) return
            val s = max(b.width() / bitmap.width.toFloat(), b.height() / bitmap.height.toFloat())
            val m = Matrix().apply { postScale(s, s); postTranslate(b.left + (b.width() - bitmap.width * s) / 2, b.top + (b.height() - bitmap.height * s) / 2) }
            canvas.save(); canvas.clipRect(b); canvas.drawBitmap(bitmap, m, paint); canvas.drawRect(b, scrim); canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    companion object {
        /** Reads a picked image, scales it (and optionally centre-crops to a square) and re-encodes within the 1 MB asset cap. */
        fun readImage(ctx: Context, uri: Uri, square: Boolean): ByteArray {
            val limit = if (square) 384 else 1600
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IllegalArgumentException("Could not open that image")
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not an image" }
            var sample = 1; while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalArgumentException("Could not decode that image")
            if (square) {
                val side = min(bmp.width, bmp.height)
                bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                if (side > 256) bmp = Bitmap.createScaledBitmap(bmp, 256, 256, true)
            } else if (max(bmp.width, bmp.height) > limit) {
                val f = limit / max(bmp.width, bmp.height).toFloat(); bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            }
            var quality = 88
            while (true) {
                val out = ByteArrayOutputStream()
                if (square) bmp.compress(Bitmap.CompressFormat.PNG, 100, out) else bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= 900_000) return out.toByteArray()
                if (quality <= 40) { bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 3 / 4, bmp.height * 3 / 4, true); quality = 80 } else quality -= 12
            }
        }
        fun readFont(ctx: Context, uri: Uri): ByteArray {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
                while (out.size() <= MacroAssetsCore.MAX_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            } ?: throw IllegalArgumentException("Could not open that file")
            require(bytes.size <= MacroAssetsCore.MAX_BYTES) { "Fonts can be at most 1 MB" }
            return bytes
        }
    }
}
