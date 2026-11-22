package com.wellsey.perch

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Colours that follow the wallpaper (like Material You) and the glass tone that keeps white text
 * readable: darker glass over bright wallpapers and at night.
 */
object Theme {
    var accent = 0xFF7DD3FC.toInt()
        private set
    var bright = false
        private set
    var night = false
        private set

    fun update(ctx: Context) {
        night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors: WallpaperColors? = try {
            WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            null
        }
        if (colors != null) accent = vivid(colors.primaryColor.toArgb())
        if (Prefs.accent != accent) Prefs.accent = accent
        val hintsDark = colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        bright = Backdrop.luminance > 0.6f || (Backdrop.luminance < 0f && hintsDark)
    }

    /**
     * Time-of-day mood on the glass: a cool lift in the morning, nothing in the day, a warm glow in the
     * evening and a deep dim late at night.
     */
    val mood: Int
        get() {
            if (!Prefs.mood) return 0
            val h = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) + it.get(java.util.Calendar.MINUTE) / 60f }
            return when {
                h < 5f -> 0x2A0A0A28
                h < 6.5f -> blend(0x2A0A0A28, 0x0DCFE8FF, (h - 5f) / 1.5f)
                h < 9f -> blend(0x0DCFE8FF, 0, (h - 6.5f) / 2.5f)
                h < 17f -> 0
                h < 19.5f -> blend(0, 0x1AFF9A3C, (h - 17f) / 2.5f)
                h < 22f -> blend(0x1AFF9A3C, 0x2A0A0A28, (h - 19.5f) / 2.5f)
                else -> 0x2A0A0A28
            }
        }

    /** How much to dim the wallpaper itself at night (0..1). */
    val wallpaperDim: Float
        get() {
            if (!Prefs.mood) return 0f
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return when (h) {
                in 22..23, in 0..4 -> 0.22f
                21, 5 -> 0.11f
                else -> 0f
            }
        }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }

    /** An extra darkening layer drawn on glass panels. */
    val shade: Int
        get() = when (Prefs.glassTone) {
            "light" -> 0
            "dark" -> 0x4D000000
            else -> when {
                bright -> 0x40000000
                night -> 0x1F000000
                else -> 0
            }
        }

    /** Stronger label shadows over bright wallpapers. */
    val labelShadow: Int get() = if (bright) 0xB3000000.toInt() else 0x73000000

    /** Themed icons: a deep tile and a light symbol, both in the wallpaper's hue. */
    fun iconColors(): Pair<Int, Int> {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        val bg = Color.HSVToColor(floatArrayOf(hsv[0], min(hsv[1], 0.6f), 0.26f))
        val fg = Color.HSVToColor(floatArrayOf(hsv[0], min(hsv[1], 0.28f), 1f))
        return Pair(bg, fg)
    }

    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = max(hsv[1], 0.45f)
        hsv[2] = max(hsv[2], 0.88f)
        return Color.HSVToColor(hsv)
    }
}
