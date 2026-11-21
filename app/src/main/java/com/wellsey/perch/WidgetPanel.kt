package com.wellsey.perch

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.graphics.Insets
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Your Android widgets (Spotify, Keep, Keepsake…), each in a glass frame, on a frosted panel that
 * slides in when you swipe right on the first home page.
 */
@SuppressLint("ViewConstructor")
class WidgetPanel(private val act: LauncherActivity) : FrameLayout(act) {
    private val bg = View(act)
    private val scroll = ScrollView(act)
    private val list = LinearLayout(act)
    private val views = HashMap<Int, AppWidgetHostView>()
    private var insets: Insets = Insets.NONE
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        visibility = INVISIBLE
        bg.background = Glass(bg, 0f, 0x590A0C1E, 0xF20F1224.toInt(), border = 0, highlight = 0)
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun applyInsets(bars: Insets) {
        insets = bars
        list.setPadding(16.dp, bars.top + 10.dp, 16.dp, bars.bottom + 28.dp)
    }

    fun setProgress(p: Float) {
        visibility = if (p <= 0.001f) INVISIBLE else VISIBLE
        val w = if (width > 0) width else resources.displayMetrics.widthPixels
        translationX = -(1f - p) * w
        bg.alpha = p.coerceIn(0f, 1f)
    }

    fun rebuild() {
        list.removeAllViews()
        val header = LinearLayout(act)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(label(act, 30f, Fonts.bold).apply { text = "Widgets"; letterSpacing = -0.02f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 4.dp })
        val close = GlyphView(act, Glyphs.CHEVRON, 22, stroke = 2.4f).apply {
            setPadding(11.dp, 11.dp, 11.dp, 11.dp)
            isClickable = true
            contentDescription = "Back to home"
            setOnClickListener { act.closeWidgets() }
        }
        header.addView(close, LinearLayout.LayoutParams(44.dp, 44.dp))
        list.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp).apply { bottomMargin = 8.dp })

        val ids = act.widgets.map { it.first }.toSet()
        views.keys.filter { it !in ids }.forEach { views.remove(it) }
        for ((id, hDp) in act.widgets) {
            val hv = views[id] ?: act.createWidgetView(id)?.also { views[id] = it } ?: continue
            (hv.parent as? ViewGroup)?.removeView(hv)
            val frame = WidgetFrame(act, id, hv)
            list.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hDp.dp).apply { bottomMargin = 14.dp })
        }

        if (act.widgets.isEmpty()) {
            list.addView(label(act, 15f, Fonts.regular, 0.8f).apply {
                text = "Add widgets from any app: music controls, notes, photos and more."
                isSingleLine = false
                gravity = Gravity.CENTER
                setPadding(20.dp, 30.dp, 20.dp, 20.dp)
            })
        }

        val add = LinearLayout(act)
        add.orientation = LinearLayout.HORIZONTAL
        add.gravity = Gravity.CENTER
        add.background = Glass(add, 24.dpf, 0x33FFFFFF, 0x40FFFFFF, border = 0x52FFFFFF)
        add.isClickable = true
        add.contentDescription = "Add widget"
        add.springPress(0.96f)
        add.setOnClickListener { act.pickWidget() }
        add.addView(GlyphView(act, Glyphs.PLUS, 18, stroke = 2.4f))
        add.addView(label(act, 15f, Fonts.semibold).apply { text = "Add widget" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dp })
        list.addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dp).apply { topMargin = 4.dp })
    }

    // Swipe left to go back home.
    private fun track(e: MotionEvent, reset: Boolean = false) {
        if (reset) {
            vt?.recycle(); vt = null
        }
        val t = vt ?: VelocityTracker.obtain().also { vt = it }
        val raw = MotionEvent.obtain(e)
        raw.setLocation(e.rawX, e.rawY)
        t.addMovement(raw)
        raw.recycle()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; track(e, true)
            }

            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dx = e.rawX - downX
                if (dx < -slop && abs(dx) > abs(e.rawY - downY) * 1.3f) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                if (!dragging && dx < -slop) dragging = true
                if (dragging) act.setWidgetProgress(1f - (-dx / max(1, width)).coerceIn(0f, 1f))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    act.endWidgetDrag(vt?.xVelocity ?: 0f, true)
                }
                dragging = false
            }
        }
        return true
    }
}

/** A glass frame around one widget. Long-press it to resize, move or remove it. */
@SuppressLint("ViewConstructor")
class WidgetFrame(private val act: LauncherActivity, val widgetId: Int, private val hostView: AppWidgetHostView) : FrameLayout(act) {
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private val longPress = Runnable {
        fired = true
        buzz(android.view.HapticFeedbackConstants.LONG_PRESS)
        act.showWidgetMenu(this)
    }

    init {
        background = Glass(this, 24.dpf, 0x29FFFFFF, 0x33FFFFFF, border = 0x47FFFFFF, highlight = 0x59FFFFFF)
        clipToOutline = true
        glassShadow(8)
        addView(hostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        try {
            hostView.updateAppWidgetSize(Bundle(), listOf(SizeF(w / d, h / d)))
        } catch (e: Exception) {
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; fired = false
                postDelayed(longPress, 500)
            }

            MotionEvent.ACTION_MOVE -> if (hypot(e.rawX - downX, e.rawY - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return fired
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPress)
            fired = false
        }
        return true
    }
}
