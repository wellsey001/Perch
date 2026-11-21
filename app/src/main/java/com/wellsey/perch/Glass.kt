package com.wellsey.perch

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * A frosted copy of the phone's wallpaper. Glass panels draw the part of it that sits behind them,
 * so they look like blurred glass over the real wallpaper, and stay right as they move.
 */
object Backdrop {
    enum class Source { NONE, SYSTEM, PERCH, LIVE, NO_ACCESS }

    @Volatile
    var blurred: Bitmap? = null
        private set
    var source = Source.NONE
        private set
    var screenW = 1080
        private set
    var screenH = 2400
        private set

    private val listeners = ArrayList<() -> Unit>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    private const val SCALE = 8 // blur at 1/8 size, then scale up smoothly
    private const val RADIUS = 10

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** [context] should be an Activity (it's used to measure the screen). */
    fun reload(context: Context) {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        screenW = min(bounds.width(), bounds.height())
        screenH = max(bounds.width(), bounds.height())
        val w = screenW
        val h = screenH
        val app = context.applicationContext
        val gen = ++generation
        exec.execute {
            val (bmp, src) = loadWallpaper(app)
            val result = try {
                bmp?.let { process(it, w, h) }
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (gen == generation) {
                    blurred = result
                    source = if (result == null && src != Source.LIVE) Source.NO_ACCESS else src
                    listeners.toList().forEach { it() }
                }
            }
        }
    }

    private fun loadWallpaper(c: Context): Pair<Bitmap?, Source> {
        val wm = WallpaperManager.getInstance(c)
        try {
            if (wm.wallpaperInfo != null) return Pair(null, Source.LIVE)
        } catch (e: Throwable) {
        }
        if (Prefs.wallSource == "perch") {
            val f = File(c.filesDir, "wallpaper.png")
            if (f.exists()) {
                val b = try {
                    BitmapFactory.decodeFile(f.path)
                } catch (e: Throwable) {
                    null
                }
                if (b != null) return Pair(b, Source.PERCH)
            }
        }
        // Reading someone else's wallpaper needs "All files access" on Android 13+.
        try {
            val d = wm.drawable
            if (d != null) return Pair(toBitmap(d), Source.SYSTEM)
        } catch (e: Throwable) {
        }
        return Pair(null, Source.NO_ACCESS)
    }

    private fun toBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) {
            val b = d.bitmap
            return if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        }
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else screenW
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else screenH
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    /** Centre-crop to the screen (how the system shows it), shrink, saturate and blur. */
    private fun process(src: Bitmap, w: Int, h: Int): Bitmap {
        val sw = max(1, w / SCALE)
        val sh = max(1, h / SCALE)
        var cur = src
        while (cur.width > sw * 4 && cur.height > sh * 4) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = max(sw.toFloat() / cur.width, sh.toFloat() / cur.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((sw - cur.width * scale) / 2f, (sh - cur.height * scale) / 2f)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.8f) })
        c.drawBitmap(cur, m, p)
        if (cur !== src) cur.recycle()

        val px = IntArray(sw * sh)
        out.getPixels(px, 0, sw, 0, 0, sw, sh)
        val tmp = IntArray(px.size)
        repeat(3) {
            blurH(px, tmp, sw, sh, RADIUS)
            blurV(tmp, px, sw, sh, RADIUS)
        }
        out.setPixels(px, 0, sw, 0, 0, sw, sh)
        return out
    }

    private fun blurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun blurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }
}

/**
 * The iOS-style glass panel: the frosted wallpaper behind the view, a translucent tint,
 * a hairline border and a bright top edge. With glass effects off (or no wallpaper to frost)
 * it falls back to the [solid] colour.
 */
class Glass(
    private val host: View,
    var radius: Float,
    var glassTint: Int,
    var solid: Int,
    var border: Int = 0x4DFFFFFF,
    var highlight: Int = 0x66FFFFFF,
    private val topOnly: Boolean = false,
) : Drawable() {
    private val loc = IntArray(2)
    private val last = IntArray(2)
    private val rect = RectF()
    private val path = Path()
    private val radii = FloatArray(8)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()

    // When the panel (or anything above it) moves, redraw so the frosted wallpaper stays put.
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (Backdrop.blurred != null && Prefs.glass) {
            host.getLocationOnScreen(loc)
            if (loc[0] != last[0] || loc[1] != last[1]) invalidateSelf()
        }
        true
    }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
            }
        })
        if (host.isAttachedToWindow) host.viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        hi.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + max(radius, bounds.height() * 0.25f),
            highlight, highlight and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    private fun buildPath(inset: Float) {
        rect.set(bounds)
        rect.inset(inset, inset)
        val r = max(0f, radius - inset)
        path.reset()
        if (topOnly) {
            radii[0] = r; radii[1] = r; radii[2] = r; radii[3] = r
            radii[4] = 0f; radii[5] = 0f; radii[6] = 0f; radii[7] = 0f
            path.addRoundRect(rect, radii, Path.Direction.CW)
        } else {
            path.addRoundRect(rect, r, r, Path.Direction.CW)
        }
    }

    override fun draw(c: Canvas) {
        buildPath(0f)
        val blur = if (Prefs.glass) Backdrop.blurred else null
        c.save()
        c.clipPath(path)
        if (blur != null) {
            host.getLocationOnScreen(loc)
            last[0] = loc[0]
            last[1] = loc[1]
            matrix.setScale(Backdrop.screenW.toFloat() / blur.width, Backdrop.screenH.toFloat() / blur.height)
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            c.drawBitmap(blur, matrix, bmpPaint)
            fill.color = glassTint
        } else {
            fill.color = solid
        }
        c.drawRect(rect, fill)
        c.restore()

        val sw = max(1f, 1.dpf)
        if (Color.alpha(border) > 0) {
            buildPath(sw / 2f)
            stroke.strokeWidth = sw
            stroke.color = border
            c.drawPath(path, stroke)
        }
        if (Color.alpha(highlight) > 0) {
            buildPath(sw / 2f)
            hi.strokeWidth = sw * 1.2f
            c.drawPath(path, hi)
        }
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        if (topOnly) outline.setRoundRect(b.left, b.top, b.right, b.bottom + radius.toInt(), radius)
        else outline.setRoundRect(b, radius)
        outline.alpha = 0.5f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Shadows for glass panels, like `0 12px 32px rgba(0,0,0,.22)`. */
fun View.glassShadow(elevationDp: Int = 8) {
    elevation = elevationDp.dpf
    outlineAmbientShadowColor = 0x33000000
    outlineSpotShadowColor = 0x55000000
}
