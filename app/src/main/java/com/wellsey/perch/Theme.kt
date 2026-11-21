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
        val hintsDark = colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        bright = Backdrop.luminance > 0.6f || (Backdrop.luminance < 0f && hintsDark)
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
