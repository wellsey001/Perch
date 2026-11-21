package com.wellsey.perch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class Place { HOME, DOCK, DRAWER, SUGGESTED }

interface IconHost {
    val editing: Boolean
    fun onIconClick(v: AppIconView)
    fun onIconLongPress(v: AppIconView)
    fun onIconDragStart(v: AppIconView, rawX: Float, rawY: Float)
    fun onIconRemove(v: AppIconView)
}

/** One app: rounded icon, soft shadow, label. Handles tap, long-press, drag and the edit-mode jiggle. */
@SuppressLint("ViewConstructor")
class AppIconView(context: Context, private val host: IconHost, val place: Place) : View(context) {
    var app: AppInfo? = null
        set(v) {
            field = v
            shownLabel = null
            contentDescription = v?.label
            invalidate()
        }
    var iconSize = 62.dp
        set(v) {
            if (field != v) {
                field = v; shownLabel = null; invalidate()
            }
        }
    var showLabel = true

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    // Two shadows, like a real object: a wide soft one and a tight contact one.
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(11.dpf, 0f, 7.dpf, 0x52000000)
    }
    private val contact = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(2.dpf, 0f, 1.5f.dpf, 0x47000000)
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(context, 12f)
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
        setShadowLayer(6.dpf, 0f, 1.dpf, 0x73000000)
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6474B5C.toInt() }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.dpf
    }
    private val dst = RectF()
    private var shownLabel: CharSequence? = null
    private val labelGap = 7.dp
    private val labelH: Int = ceil(text.fontMetrics.descent - text.fontMetrics.ascent).toInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(iconSize + 16.dp, widthMeasureSpec),
            resolveSize(blockHeight() + 8.dp, heightMeasureSpec),
        )
    }

    private fun blockHeight() = iconSize + if (showLabel) labelGap + labelH else 0
    fun iconLeft() = (width - iconSize) / 2
    fun iconTop() = max(0, (height - blockHeight()) / 2)

    fun iconRectOnScreen(): Rect {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val l = loc[0] + iconLeft()
        val t = loc[1] + iconTop()
        return Rect(l, t, l + iconSize, t + iconSize)
    }

    private val removable get() = host.editing && (place == Place.HOME || place == Place.DOCK)

    override fun onDraw(canvas: Canvas) {
        val a = app ?: return
        val s = iconSize.toFloat()
        val l = iconLeft().toFloat()
        val t = iconTop().toFloat()
        val shape = IconMaker.shape(s - 4f)
        canvas.save()
        canvas.translate(l + 2f, t + 2f)
        canvas.drawPath(shape, shadow)
        canvas.drawPath(shape, contact)
        canvas.restore()
        dst.set(l, t, l + s, t + s)
        canvas.drawBitmap(a.icon, null, dst, bmpPaint)
        if (showLabel) {
            val lbl = shownLabel ?: TextUtils.ellipsize(a.label, text, (width - 6.dp).toFloat(), TextUtils.TruncateAt.END)
                .also { shownLabel = it }
            canvas.drawText(lbl, 0, lbl.length, width / 2f, t + s + labelGap - text.fontMetrics.ascent, text)
        }
        if (removable) {
            val cx = l + 3.dp
            val cy = t + 3.dp
            val br = 11.dpf
            canvas.drawCircle(cx, cy, br, badgeFill)
            canvas.drawCircle(cx, cy, br, badgeRing)
            Glyphs.draw(canvas, "M 7 12 L 17 12", cx - 8.dpf, cy - 8.dpf, 16.dpf, Color.WHITE, 2.6f)
        }
    }

    private fun inBadge(x: Float, y: Float): Boolean =
        removable && hypot(x - (iconLeft() + 3.dp), y - (iconTop() + 3.dp)) < 20.dp

    // ---- touch ----

    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private var handedOff = false
    private var badgeDown = false
    private val longPress = Runnable {
        longFired = true
        parent?.requestDisallowInterceptTouchEvent(true)
        host.onIconLongPress(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (app == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                longFired = false; handedOff = false; badgeDown = false
                if (inBadge(e.x, e.y)) {
                    badgeDown = true
                    return true
                }
                if (removable) {
                    // In edit mode a touch that moves picks the icon up straight away.
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    press(true)
                    postDelayed(longPress, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (handedOff || badgeDown) return true
                val dist = hypot(e.rawX - downX, e.rawY - downY)
                if (removable) {
                    if (dist > slop) startDrag(e)
                } else if (longFired) {
                    if (dist > 10.dp) startDrag(e)
                } else if (dist > slop) {
                    removeCallbacks(longPress)
                    press(false)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (handedOff) return true
                press(false)
                if (badgeDown) {
                    if (inBadge(e.x, e.y)) host.onIconRemove(this)
                } else if (!longFired && !host.editing && hypot(e.rawX - downX, e.rawY - downY) < slop) {
                    host.onIconClick(this)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                press(false)
                return true
            }
        }
        return true
    }

    private fun startDrag(e: MotionEvent) {
        handedOff = true
        resetPress()
        buzz(HapticFeedbackConstants.LONG_PRESS)
        host.onIconDragStart(this, e.rawX, e.rawY)
    }

    private fun press(down: Boolean) {
        if (Motion.reduced) return
        if (down) animate().scaleX(0.86f).scaleY(0.86f).setDuration(280).setInterpolator(Ease.spring).start()
        else animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Ease.spring).start()
    }

    fun resetPress() {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
    }

    /** The `.pop` entrance: scale up from .4 with a springy overshoot. */
    fun popIn(delay: Long) {
        if (Motion.reduced) return
        alpha = 0f
        scaleX = 0.4f; scaleY = 0.4f
        translationY = 14.dpf
        animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(delay).setDuration(650).setInterpolator(Ease.spring).start()
    }

    // ---- edit-mode jiggle ----

    private var jiggle: ValueAnimator? = null

    fun setEditing(on: Boolean) {
        jiggle?.cancel()
        jiggle = null
        rotation = 0f
        if (on && !Motion.reduced && (place == Place.HOME || place == Place.DOCK)) {
            jiggle = ValueAnimator.ofFloat(-2.6f, 2.6f).apply {
                duration = 150
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                startDelay = Random.nextLong(0, 150)
                addUpdateListener { rotation = it.animatedValue as Float }
                start()
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        jiggle?.cancel()
        jiggle = null
        removeCallbacks(longPress)
    }

    companion object {
        const val LONG_PRESS_MS = 450L
    }
}

/**
 * A grid of app icons: a home page (fixed rows), the dock (one centred row) or the drawer (as many rows
 * as needed). Icons glide to their new spots when the order changes.
 */
@SuppressLint("ViewConstructor")
class AppGrid(context: Context, private val host: IconHost, val place: Place) : ViewGroup(context) {
    var columns = 4
    var rows = 0 // 0 = as many as needed, each cellHeight tall
    var cellHeight = 96.dp
    var maxIcon = 62.dp
    var showLabels = true
    var centerRow = false // dock: centre a short row
    var keys: List<String?> = emptyList()
        private set
    private val views = LinkedHashMap<String, AppIconView>()
    private var cellW = 1
    private var cellH = 1

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun bind(newKeys: List<String?>, apps: Map<String, AppInfo>) {
        keys = newKeys
        val wanted = newKeys.filterNotNull().toHashSet()
        val iter = views.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in wanted) {
                removeView(e.value)
                iter.remove()
            }
        }
        for (k in newKeys) {
            if (k == null) continue
            val v = views[k] ?: AppIconView(context, host, place).also { nv ->
                views[k] = nv
                addView(nv)
                nv.setEditing(host.editing)
            }
            if (v.app !== apps[k]) v.app = apps[k]
        }
        requestLayout()
    }

    fun viewFor(key: String): AppIconView? = views[key]
    fun iconViews(): Collection<AppIconView> = views.values

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cellW = max(1, w / columns)
        val h: Int
        if (rows > 0) {
            h = MeasureSpec.getSize(heightMeasureSpec)
            cellH = max(1, h / rows)
        } else {
            val n = (keys.size + columns - 1) / columns
            cellH = cellHeight
            h = n * cellH
        }
        val labelSpace = if (showLabels) 30.dp else 6.dp
        val icon = max(24.dp, min(maxIcon, min(cellW - 14.dp, cellH - labelSpace)))
        val ws = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
        for (v in views.values) {
            v.iconSize = icon
            v.showLabel = showLabels
            v.measure(ws, hs)
        }
        setMeasuredDimension(w, h)
    }

    private fun rowOffset(count: Int): Int =
        if (centerRow && count < columns) (columns - count) * cellW / 2 else 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val off = rowOffset(keys.size)
        for ((i, k) in keys.withIndex()) {
            if (k == null) continue
            val v = views[k] ?: continue
            val left = off + (i % columns) * cellW
            val top = (i / columns) * cellH
            val had = v.isLaidOut
            val oldL = v.left
            val oldT = v.top
            v.layout(left, top, left + cellW, top + cellH)
            if (had && (oldL != left || oldT != top) && !Motion.reduced) {
                v.translationX += (oldL - left).toFloat()
                v.translationY += (oldT - top).toFloat()
                v.animate().translationX(0f).translationY(0f).setDuration(240).setInterpolator(Ease.out).start()
            }
        }
    }

    /** Which cell (row-major index) a point in this view falls in. */
    fun cellAt(x: Float, y: Float): Int {
        val col = (x / cellW).toInt().coerceIn(0, columns - 1)
        val maxRow = if (rows > 0) rows - 1 else max(0, (keys.size - 1) / columns)
        val row = (y / cellH).toInt().coerceIn(0, maxRow)
        return row * columns + col
    }

    /** For a centred single row holding [count] slots: which slot is under x. */
    fun slotAt(x: Float, count: Int): Int {
        if (count <= 0) return 0
        val off = if (centerRow && count < columns) (columns - count) * cellW / 2f else 0f
        return ((x - off) / cellW).toInt().coerceIn(0, count - 1)
    }

    fun setEditing(on: Boolean) {
        for (v in views.values) v.setEditing(on)
    }

    fun popIn(base: Long, step: Long, limit: Int = 28) {
        var n = 0
        for (k in keys) {
            if (k == null) continue
            if (n >= limit) break
            views[k]?.popIn(base + n * step)
            n++
        }
    }
}

/** Home screen pages, side by side, with swipe-and-snap. */
class Pager(context: Context) : ViewGroup(context) {
    var current = 0
        private set
    var onPage: ((Int) -> Unit)? = null
    var onScroll: ((Float) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var vt: android.view.VelocityTracker? = null
    private var anim: ValueAnimator? = null

    val pageCount get() = childCount
    fun page(i: Int): AppGrid? = getChildAt(i) as? AppGrid
    fun pages(): List<AppGrid> = (0 until childCount).mapNotNull { getChildAt(it) as? AppGrid }

    fun setPageCount(n: Int, make: () -> AppGrid) {
        while (childCount < n) addView(make())
        while (childCount > n) removeViewAt(childCount - 1)
        if (current > n - 1) setCurrent(max(0, n - 1))
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (!dragging && anim?.isRunning != true) scrollTo(current * w, 0)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x
                dragging = false
                if (anim?.isRunning == true) {
                    anim?.cancel()
                    dragging = true
                    track(e, true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (childCount > 1 && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                    dragging = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    track(e, true)
                    return true
                }
            }
        }
        return dragging
    }

    private fun track(e: MotionEvent, reset: Boolean = false) {
        if (reset) {
            vt?.recycle(); vt = null
        }
        val t = vt ?: android.view.VelocityTracker.obtain().also { vt = it }
        t.addMovement(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x
                track(e, true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                track(e)
                if (!dragging) {
                    if (childCount > 1 && kotlin.math.abs(e.x - downX) > slop) {
                        dragging = true
                        lastX = e.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else return true
                }
                val dx = lastX - e.x
                lastX = e.x
                val maxX = (childCount - 1) * width
                var nx = scrollX + dx
                if (nx < 0 || nx > maxX) nx = scrollX + dx * 0.35f // rubber band
                scrollTo(nx.toInt(), 0)
                onScroll?.invoke(scrollX.toFloat() / max(1, width))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    track(e)
                    val t = vt
                    t?.computeCurrentVelocity(1000)
                    val v = t?.xVelocity ?: 0f
                    val pos = scrollX.toFloat() / max(1, width)
                    val target = when {
                        v < -700 -> current + 1
                        v > 700 -> current - 1
                        else -> Math.round(pos)
                    }
                    dragging = false
                    snapTo(target.coerceIn(0, max(0, childCount - 1)))
                }
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    fun snapTo(i: Int, animate: Boolean = true) {
        anim?.cancel()
        val target = i * width
        setCurrent(i)
        if (!animate || Motion.reduced || width == 0) {
            scrollTo(target, 0)
            onScroll?.invoke(i.toFloat())
            return
        }
        anim = ValueAnimator.ofInt(scrollX, target).apply {
            duration = 380
            interpolator = Ease.out
            addUpdateListener {
                scrollTo(it.animatedValue as Int, 0)
                onScroll?.invoke(scrollX.toFloat() / max(1, width))
            }
            start()
        }
    }

    private fun setCurrent(i: Int) {
        if (i != current) {
            current = i
            onPage?.invoke(i)
        }
    }
}

/** The little page indicator dots. */
class PageDots(context: Context) : View(context) {
    var count = 1
        set(v) { field = v; invalidate() }
    var position = 0f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val d = 7.dpf
        val gap = 7.dpf
        val total = count * d + (count - 1) * gap
        var x = (width - total) / 2f + d / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            paint.color = Color.argb((115 + 140 * near).toInt(), 255, 255, 255)
            canvas.drawCircle(x, cy, d / 2f, paint)
            x += d + gap
        }
    }
}

/** An icon floating above everything: the lifted icon in the long-press menu, or one being dragged. */
@SuppressLint("ViewConstructor")
class FloatIcon(context: Context, private val bmp: Bitmap, val iconPx: Int) : View(context) {
    val pad = 28.dp
    val size get() = iconPx + 2 * pad
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        setShadowLayer(18.dpf, 0f, 14.dpf, 0x66000000)
    }
    private val dst = RectF()

    /** Put the icon (not the padded view) at this screen spot. */
    fun placeAt(r: Rect) {
        x = (r.left - pad).toFloat()
        y = (r.top - pad).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val s = iconPx.toFloat()
        val p = pad.toFloat()
        canvas.save()
        canvas.translate(p + 2f, p + 2f)
        canvas.drawPath(IconMaker.shape(s - 4f), shadow)
        canvas.restore()
        dst.set(p, p, p + s, p + s)
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}
