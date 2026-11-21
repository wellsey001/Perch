package com.wellsey.perch

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.view.WindowManager
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Perch's own wallpapers (the three from the design) and photos you pick. */
object Wallpapers {
    private class Spec(val stops: IntArray, val orbs: Array<FloatArray>, val orbColors: IntArray)

    private val POS = floatArrayOf(0f, 0.38f, 0.72f, 1f)

    private val specs = linkedMapOf(
        "Aurora" to Spec(
            intArrayOf(0xFF1B1446.toInt(), 0xFF2B1B6B.toInt(), 0xFF12407A.toInt(), 0xFF0C5C6B.toInt()),
            arrayOf(floatArrayOf(60f, 150f, 210f), floatArrayOf(330f, 520f, 190f), floatArrayOf(70f, 750f, 150f)),
            intArrayOf(0xFFFF5FB8.toInt(), 0xFF4F7CFF.toInt(), 0xFF22D3C5.toInt()),
        ),
        "Sunset" to Spec(
            intArrayOf(0xFF3A0F3F.toInt(), 0xFF7A1F4F.toInt(), 0xFFC2410C.toInt(), 0xFFF59E0B.toInt()),
            arrayOf(floatArrayOf(60f, 150f, 210f), floatArrayOf(330f, 520f, 190f), floatArrayOf(70f, 750f, 150f)),
            intArrayOf(0xFFFF7A59.toInt(), 0xFFC026D3.toInt(), 0xFFFBBF24.toInt()),
        ),
        "Ocean" to Spec(
            intArrayOf(0xFF031B34.toInt(), 0xFF063A5E.toInt(), 0xFF0B6E8A.toInt(), 0xFF14B8A6.toInt()),
            arrayOf(floatArrayOf(60f, 150f, 210f), floatArrayOf(330f, 520f, 190f), floatArrayOf(70f, 750f, 150f)),
            intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt(), 0xFF5EEAD4.toInt()),
        ),
    )

    val names: List<String> get() = specs.keys.toList()

    fun render(name: String, w: Int, h: Int): Bitmap {
        val s = specs[name] ?: specs.getValue("Aurora")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // CSS linear-gradient(165deg, …)
        val a = Math.toRadians(165.0)
        val dx = sin(a).toFloat()
        val dy = (-cos(a)).toFloat()
        val len = abs(w * dx) + abs(h * dy)
        val cx = w / 2f
        val cy = h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(
            cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
            s.stops, POS, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // The soft colour "orbs", blurred like the design's filter: blur(46px)
        val sx = w / 390f
        val sy = h / 844f
        for ((i, o) in s.orbs.withIndex()) {
            val ox = o[0] * sx
            val oy = o[1] * sy
            val r = o[2] * sx * 1.35f
            val col = s.orbColors[i]
            p.shader = RadialGradient(
                ox, oy, r,
                intArrayOf(withAlpha(col, 0.85f), withAlpha(col, 0.5f), withAlpha(col, 0f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(ox, oy, r, p)
        }
        return bmp
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    fun screenSize(a: Activity): Pair<Int, Int> {
        val b = a.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Pair(min(b.width(), b.height()), max(b.width(), b.height()))
    }

    /** Decode a picked photo at about screen size and crop it to fill the screen. */
    fun fromPhoto(a: Activity, uri: Uri): Bitmap {
        val (w, h) = screenSize(a)
        val src = ImageDecoder.createSource(a.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val iw = info.size.width
            val ih = info.size.height
            val scale = max(w.toFloat() / iw, h.toFloat() / ih)
            if (scale < 1f) decoder.setTargetSize(max(1, (iw * scale).roundToInt()), max(1, (ih * scale).roundToInt()))
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = max(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((w - decoded.width * scale) / 2f, (h - decoded.height * scale) / 2f)
        c.drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        return out
    }

    /** Set [bmp] as the home screen wallpaper and keep a copy so the glass can frost it. */
    fun apply(a: Activity, bmp: Bitmap, preset: String, done: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                Prefs.wallSetAt = System.currentTimeMillis()
                File(a.filesDir, "wallpaper.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                WallpaperManager.getInstance(a).setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                Prefs.wallSetAt = System.currentTimeMillis()
                Prefs.wallSource = "perch"
                Prefs.wallPreset = preset
                true
            } catch (e: Exception) {
                false
            }
            a.runOnUiThread {
                Backdrop.reload(a)
                done(ok)
            }
        }.start()
    }
}
