package com.wellsey.perch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

object Ui {
    var density = 3f
    fun init(c: Context) {
        density = c.resources.displayMetrics.density
    }
}

val Int.dp: Int get() = (this * Ui.density).roundToInt()
val Int.dpf: Float get() = this * Ui.density
val Float.dpf: Float get() = this * Ui.density

/** The curves from the design canvas. */
object Ease {
    /** cubic-bezier(.34,1.56,.64,1): the springy pop used for icons and menus. */
    val spring = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
    /** cubic-bezier(.2,.9,.25,1): smooth settle. */
    val out = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
    /** cubic-bezier(.2,.95,.25,1.04): the app drawer sheet, with a tiny overshoot. */
    val sheet = PathInterpolator(0.2f, 0.95f, 0.25f, 1.04f)
}

object Motion {
    /** True when the user turned animations off (Settings › Accessibility › Remove animations). */
    val reduced: Boolean get() = !ValueAnimator.areAnimatorsEnabled()
}

object Fonts {
    val light: Typeface = Typeface.create(Typeface.SANS_SERIF, 300, false)
    val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)
    val medium: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    val semibold: Typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
    val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, 700, false)
}

fun View.buzz(type: Int = HapticFeedbackConstants.CLOCK_TICK) {
    if (Prefs.haptics) performHapticFeedback(type)
}

fun sp(c: Context, v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.resources.displayMetrics)

fun label(c: Context, sizeSp: Float, face: Typeface, alpha: Float = 1f, color: Int = Color.WHITE): TextView =
    TextView(c).apply {
        textSize = sizeSp
        typeface = face
        setTextColor(color)
        this.alpha = alpha
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

/** Shrinks a view a little while it's held, then springs back (the `.tile:active` effect). */
@SuppressLint("ClickableViewAccessibility")
fun View.springPress(scale: Float = 0.94f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!Motion.reduced) v.animate().scaleX(scale).scaleY(scale)
                .setDuration(180).setInterpolator(Ease.out).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!Motion.reduced) v.animate().scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(Ease.spring).start()
        }
        false
    }
}

/** Fades and rises a view in (the `.rise` effect). */
fun View.riseIn(delay: Long) {
    if (Motion.reduced) return
    alpha = 0f
    translationY = 30.dpf
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(800)
        .setInterpolator(Ease.out).withEndAction { }.start()
}

/** An end listener that doesn't fire when the animation was cancelled. */
fun onEnd(block: () -> Unit): Animator.AnimatorListener = object : AnimatorListenerAdapter() {
    private var cancelled = false
    override fun onAnimationCancel(animation: Animator) {
        cancelled = true
    }

    override fun onAnimationEnd(animation: Animator) {
        if (!cancelled) block()
    }
}

/** Run [block] once, just before the next frame is drawn (after layout). */
fun View.onNextPreDraw(block: () -> Unit) {
    val vto = viewTreeObserver
    vto.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (vto.isAlive) vto.removeOnPreDrawListener(this) else viewTreeObserver.removeOnPreDrawListener(this)
            block()
            return true
        }
    })
    invalidate()
}

fun View.invalidateDeep() {
    invalidate()
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).invalidateDeep()
}

/** Line icons drawn on a 24×24 grid, stroked like the design's SVGs. "O x y r" is a circle. */
object Glyphs {
    const val SEARCH = "O 11 11 6 M 20 20 L 15.5 15.5"
    const val MIC = "M 9 6 L 9 11 C 9 15 15 15 15 11 L 15 6 C 15 2 9 2 9 6 Z M 5 11 C 5 20.3 19 20.3 19 11 M 12 18 L 12 21"
    const val GRID = "M 4 4 L 10 4 L 10 10 L 4 10 Z M 14 4 L 20 4 L 20 10 L 14 10 Z M 4 14 L 10 14 L 10 20 L 4 20 Z M 14 14 L 20 14 L 20 20 L 14 20 Z"
    const val HIDE = "M 3 3 L 21 21 M 10.6 6.1 C 11 6 11.5 6 12 6 C 17 6 21 12 21 12 C 20.2 13.3 19.2 14.5 18 15.5 M 6.6 6.6 C 4.3 8.2 3 12 3 12 C 3 12 7 18 12 18 C 13.6 18 15.1 17.6 16.4 16.9"
    const val EYE = "M 2 12 C 2 12 6 5 12 5 C 18 5 22 12 22 12 C 22 12 18 19 12 19 C 6 19 2 12 2 12 Z O 12 12 3"
    const val INFO = "O 12 12 9 M 12 11 L 12 16 M 12 8 L 12.01 8"
    const val TRASH = "M 4 7 L 20 7 M 9 7 L 9 4 L 15 4 L 15 7 M 6 7 L 7 20 L 17 20 L 18 7 M 10 11 L 10 16 M 14 11 L 14 16"
    const val PLUS = "M 12 5 L 12 19 M 5 12 L 19 12"
    const val MINUS_CIRCLE = "O 12 12 9 M 8 12 L 16 12"
    const val CHEVRON = "M 9 5 L 16 12 L 9 19"
    const val BACK = "M 15 5 L 8 12 L 15 19"
    const val CHECK = "M 5 12.5 L 10 17 L 19 7"
    const val LOCK = "M 7 11 L 7 8 C 7 1.5 17 1.5 17 8 L 17 11 M 6 11 L 18 11 L 18 20 L 6 20 Z"
    const val ARROW_UP = "M 12 19 L 12 5 M 6 11 L 12 5 L 18 11"
    const val ARROW_DOWN = "M 12 5 L 12 19 M 6 13 L 12 19 L 18 13"
    const val TAP = "O 12 12 3 O 12 12 7.5"
    const val IMAGE = "M 5 5 L 19 5 C 20.1 5 21 5.9 21 7 L 21 17 C 21 18.1 20.1 19 19 19 L 5 19 C 3.9 19 3 18.1 3 17 L 3 7 C 3 5.9 3.9 5 5 5 Z O 9 10 1.5 M 21 17 L 16 12 L 7 19"
    const val GEAR = "O 12 12 3 M 12 2 L 12 5 M 12 19 L 12 22 M 2 12 L 5 12 M 19 12 L 22 12 M 4.9 4.9 L 7 7 M 17 17 L 19.1 19.1 M 4.9 19.1 L 7 17 M 17 7 L 19.1 4.9"
    const val HEX = "M 12 3 L 19 7 L 19 17 L 12 21 L 5 17 L 5 7 Z"
    const val HAPTIC = "M 8 4 L 16 4 L 16 20 L 8 20 Z M 4 9 L 4 15 M 20 9 L 20 15"
    const val GLOBE = "O 12 12 9 M 3 12 L 21 12 M 12 3 C 16.5 7 16.5 17 12 21 M 12 3 C 7.5 7 7.5 17 12 21"
    const val HOME = "M 4 11 L 12 4 L 20 11 M 6 9.5 L 6 20 L 18 20 L 18 9.5"
    const val SPARK = "M 12 3 L 13.8 10.2 L 21 12 L 13.8 13.8 L 12 21 L 10.2 13.8 L 3 12 L 10.2 10.2 Z"
    const val WIDGETS = "M 4 4 L 11 4 L 11 11 L 4 11 Z M 13 13 L 20 13 L 20 20 L 13 20 Z O 16.5 7.5 3.5 O 7.5 16.5 3.5"
    const val SHARE = "M 12 3 L 12 15 M 7 8 L 12 3 L 17 8 M 5 13 L 5 19 C 5 20.1 5.9 21 7 21 L 17 21 C 18.1 21 19 20.1 19 19 L 19 13"

    const val BELL = "M 6 16 L 6 11 C 6 7.7 8.7 5 12 5 C 15.3 5 18 7.7 18 11 L 18 16 L 20 18 L 4 18 Z M 10 21 L 14 21"
    const val CLOCK = "O 12 12 9 M 12 7 L 12 12 L 15.5 14"
    const val PEN = "M 4 20 L 8 19 L 19 8 L 16 5 L 5 16 Z M 14 7 L 17 10"
    const val FOLDER = "M 3 7 C 3 5.9 3.9 5 5 5 L 9 5 L 11 7 L 19 7 C 20.1 7 21 7.9 21 9 L 21 17 C 21 18.1 20.1 19 19 19 L 5 19 C 3.9 19 3 18.1 3 17 Z"
    const val UNGROUP = "M 4 4 L 10 4 L 10 10 L 4 10 Z M 14 14 L 20 14 L 20 20 L 14 20 Z M 14 4 L 20 4 L 20 10 L 14 10 Z"
    const val PERSON = "O 12 8 4 M 4 20 C 5.5 16 8.5 14 12 14 C 15.5 14 18.5 16 20 20"
    const val CALC = "M 7 3 L 17 3 C 18.1 3 19 3.9 19 5 L 19 19 C 19 20.1 18.1 21 17 21 L 7 21 C 5.9 21 5 20.1 5 19 L 5 5 C 5 3.9 5.9 3 7 3 Z M 8 7 L 16 7 M 8 12 L 8.01 12 M 12 12 L 12.01 12 M 16 12 L 16.01 12 M 8 16 L 8.01 16 M 12 16 L 12.01 16 M 16 16 L 16.01 16"
    const val CHEVRON_UP = "M 5 15 L 12 8 L 19 15"
    const val CHEVRON_DOWN = "M 5 9 L 12 16 L 19 9"
    const val BRIEFCASE = "M 4 8 L 20 8 L 20 19 L 4 19 Z M 9 8 L 9 5 L 15 5 L 15 8 M 4 13 L 20 13"
    const val SAVE = "M 12 4 L 12 15 M 7 10 L 12 15 L 17 10 M 5 19 L 19 19"
    const val RESTORE = "M 12 20 L 12 9 M 7 14 L 12 9 L 17 14 M 5 5 L 19 5"
    const val TEXT = "M 5 6 L 19 6 M 12 6 L 12 19 M 9 19 L 15 19"
    const val MOVE = "M 12 3 L 12 21 M 3 12 L 21 12 M 9 6 L 12 3 L 15 6 M 9 18 L 12 21 L 15 18 M 6 9 L 3 12 L 6 15 M 18 9 L 21 12 L 18 15"
    const val PINCH = "M 6 6 L 10 10 M 6 6 L 6 9 M 6 6 L 9 6 M 18 18 L 14 14 M 18 18 L 18 15 M 18 18 L 15 18"
    const val TWO_FINGER = "M 8 4 L 8 14 M 14 4 L 14 14 M 5 17 L 11 20 L 17 17"

    // Weather
    const val SUN = "O 12 12 4 M 12 2 L 12 4 M 12 20 L 12 22 M 2 12 L 4 12 M 20 12 L 22 12 M 4.9 4.9 L 6.3 6.3 M 17.7 17.7 L 19.1 19.1 M 4.9 19.1 L 6.3 17.7 M 17.7 6.3 L 19.1 4.9"
    const val MOON = "M 20 14.5 C 18.8 15.1 17.4 15.5 16 15.5 C 11 15.5 8.5 12.5 8.5 8 C 8.5 6.6 8.9 5.2 9.5 4 C 5.8 5 3.5 8.3 3.5 12 C 3.5 16.7 7.3 20.5 12 20.5 C 15.7 20.5 19 18.2 20 14.5 Z"
    const val CLOUD = "M 7 18 C 4.8 18 3 16.2 3 14 C 3 11.8 4.8 10 7 10 C 7.6 7.1 10.1 5 13 5 C 15.6 5 17.9 6.7 18.6 9.2 C 20.6 9.9 22 11.8 22 14 C 22 16.2 20.2 18 18 18 Z"
    private const val CLOUD_HIGH = "M 7 15 C 4.8 15 3 13.2 3 11 C 3 8.8 4.8 7 7 7 C 7.6 4.1 10.1 2 13 2 C 15.6 2 17.9 3.7 18.6 6.2 C 20.6 6.9 22 8.8 22 11 C 22 13.2 20.2 15 18 15 Z"
    const val RAIN = "$CLOUD_HIGH M 8 18 L 7 21 M 12 18 L 11 21 M 16 18 L 15 21"
    const val SNOW = "$CLOUD_HIGH O 8 19.5 0.6 O 12 20.5 0.6 O 16 19.5 0.6"
    const val STORM = "$CLOUD_HIGH M 13 16 L 10.5 19.5 L 14 19.5 L 11.5 23"
    const val FOG = "M 4 8 L 20 8 M 3 12 L 21 12 M 5 16 L 19 16"
    const val PARTLY = "O 8 8 3 M 8 2 L 8 3 M 2 8 L 3 8 M 3.8 3.8 L 4.5 4.5 M 12.2 3.8 L 11.5 4.5 M 9 20 C 7.1 20 5.5 18.4 5.5 16.5 C 5.5 14.6 7.1 13 9 13 C 9.6 10.7 11.6 9 14 9 C 16.2 9 18 10.4 18.7 12.4 C 20.6 12.9 22 14.6 22 16.5 C 22 18.4 20.4 20 18.5 20 Z"

    private val cache = HashMap<String, Path>()

    fun path(spec: String): Path = cache.getOrPut(spec) { parse(spec) }

    private fun parse(spec: String): Path {
        val p = Path()
        val t = spec.trim().split(Regex("\\s+"))
        var i = 0
        fun n(): Float = t[i++].toFloat()
        while (i < t.size) {
            when (t[i++]) {
                "M" -> { val x = n(); val y = n(); p.moveTo(x, y) }
                "L" -> { val x = n(); val y = n(); p.lineTo(x, y) }
                "C" -> {
                    val a = n(); val b = n(); val c = n(); val d = n(); val e = n(); val f = n()
                    p.cubicTo(a, b, c, d, e, f)
                }
                "Q" -> { val a = n(); val b = n(); val c = n(); val d = n(); p.quadTo(a, b, c, d) }
                "Z" -> p.close()
                "O" -> { val x = n(); val y = n(); val r = n(); p.addCircle(x, y, r, Path.Direction.CW) }
            }
        }
        return p
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(c: Canvas, spec: String, x: Float, y: Float, size: Float, color: Int, stroke: Float = 2f) {
        paint.color = color
        paint.strokeWidth = stroke
        c.save()
        c.translate(x, y)
        c.scale(size / 24f, size / 24f)
        c.drawPath(path(spec), paint)
        c.restore()
    }
}

class GlyphView(context: Context, glyph: String, private val sizeDp: Int, color: Int = Color.WHITE, private val stroke: Float = 2f) :
    View(context) {
    var glyph: String = glyph
        set(v) { field = v; invalidate() }
    var color: Int = color
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(sizeDp.dp, widthMeasureSpec), resolveSize(sizeDp.dp, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        val x = paddingLeft + (width - paddingLeft - paddingRight - s) / 2f
        val y = paddingTop + (height - paddingTop - paddingBottom - s) / 2f
        Glyphs.draw(canvas, glyph, x, y, s, color, stroke)
    }
}
