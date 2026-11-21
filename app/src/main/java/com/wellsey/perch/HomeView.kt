package com.wellsey.perch

import android.annotation.SuppressLint
import android.graphics.Insets
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import kotlin.math.abs

/**
 * The home screen: big clock and date, the weather and calendar widgets, paged app grid,
 * page dots, the glass search pill and the glass dock.
 */
@SuppressLint("ViewConstructor")
class HomeView(private val act: LauncherActivity) : LinearLayout(act) {
    val clock: TextView = label(act, 84f, Fonts.light)
    val date: TextView = label(act, 17f, Fonts.semibold, 0.92f)
    private val header = LinearLayout(act)
    val widgetRow = LinearLayout(act)
    val weather = WeatherWidget(act)
    val calendar = CalendarWidget(act)
    val pager = Pager(act)
    val dots = PageDots(act)
    val search = LinearLayout(act)
    val dockBox = FrameLayout(act)
    val dockGrid = AppGrid(act, act, Place.DOCK)

    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var vertical = false
    private var touchOnInteractive = false
    private var vt: VelocityTracker? = null

    init {
        orientation = VERTICAL

        // Clock + date
        header.orientation = VERTICAL
        clock.letterSpacing = -0.05f
        clock.setShadowLayer(18.dpf, 0f, 2.dpf, 0x40000000)
        date.setShadowLayer(18.dpf, 0f, 2.dpf, 0x40000000)
        clock.setOnClickListener { act.openClockApp() }
        date.setOnClickListener { act.openCalendarApp(null) }
        header.addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(date, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Widgets
        widgetRow.orientation = HORIZONTAL
        widgetRow.clipChildren = false
        widgetRow.addView(weather, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        widgetRow.addView(calendar, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginStart = 14.dp })
        weather.setOnClickListener { act.onWeatherTap() }
        calendar.setOnClickListener { act.onCalendarTap(calendar.event) }
        addView(widgetRow, LayoutParams(LayoutParams.MATCH_PARENT, 158.dp).apply { topMargin = 22.dp })

        // App pages
        pager.onScroll = { dots.position = it }
        pager.onPage = { dots.position = it.toFloat() }
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 26.dp })
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, 7.dp).apply { topMargin = 6.dp; bottomMargin = 14.dp })

        // Search pill
        search.orientation = HORIZONTAL
        search.gravity = Gravity.CENTER_VERTICAL
        search.background = Glass(search, 23.dpf, 0x2EFFFFFF, 0x40FFFFFF, highlight = 0x59FFFFFF)
        search.setPadding(16.dp, 0, 6.dp, 0)
        search.isClickable = true
        search.contentDescription = "Search"
        search.setOnClickListener { act.openDrawer(focusSearch = true) }
        search.springPress(0.96f)
        search.addView(GlyphView(act, Glyphs.SEARCH, 18, stroke = 2.2f))
        search.addView(label(act, 15f, Fonts.medium).apply { text = "Search" },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 10.dp })
        val mic = GlyphView(act, Glyphs.MIC, 18, stroke = 2.2f).apply {
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
            contentDescription = "Voice search"
            isClickable = true
            setOnClickListener { act.voiceSearch() }
        }
        search.addView(mic, LayoutParams(40.dp, 40.dp))
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, 46.dp).apply {
            marginStart = 18.dp; marginEnd = 18.dp; bottomMargin = 14.dp
        })

        // Dock
        dockBox.background = Glass(dockBox, 34.dpf, 0x2EFFFFFF, 0x40FFFFFF, border = 0x52FFFFFF, highlight = 0x73FFFFFF)
        dockBox.glassShadow(10)
        dockBox.setPadding(8.dp, 10.dp, 8.dp, 10.dp)
        dockBox.clipChildren = false
        dockGrid.columns = LauncherActivity.DOCK_MAX
        dockGrid.rows = 1
        dockGrid.showLabels = false
        dockGrid.centerRow = true
        dockBox.addView(dockGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 68.dp))
        addView(dockBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp })

        updateClock()
    }

    fun applyInsets(bars: Insets) {
        setPadding(22.dp, bars.top + 18.dp, 22.dp, bars.bottom + 8.dp)
    }

    fun applySettings() {
        widgetRow.visibility = if (Prefs.showWidgets) VISIBLE else GONE
        search.visibility = if (Prefs.showSearch) VISIBLE else GONE
        for (g in pager.pages()) configurePage(g)
    }

    private fun configurePage(g: AppGrid) {
        g.columns = Prefs.columns
        g.rows = Prefs.rows
        g.maxIcon = 62.dp
        g.requestLayout()
    }

    fun updateClock() {
        val now = Date()
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        clock.text = DateFormat.format(pattern, now)
        date.text = DateFormat.format("EEEE d MMMM", now)
        calendar.updateDate()
    }

    fun bind(pages: List<List<String?>>, dock: List<String?>, apps: Map<String, AppInfo>) {
        pager.setPageCount(pages.size) {
            AppGrid(act, act, Place.HOME).also { configurePage(it) }
        }
        for ((i, p) in pages.withIndex()) pager.page(i)?.bind(p, apps)
        dockGrid.bind(dock, apps)
        dots.count = pages.size
        dots.position = pager.current.toFloat()
    }

    fun setEditing(on: Boolean) {
        for (g in pager.pages()) g.setEditing(on)
        dockGrid.setEditing(on)
    }

    fun viewFor(key: String): AppIconView? =
        dockGrid.viewFor(key) ?: pager.pages().firstNotNullOfOrNull { it.viewFor(key) }

    /** The entrance from the design: clock rises, widgets follow, icons pop in one by one. */
    fun entrance() {
        if (Motion.reduced) return
        header.riseIn(0)
        widgetRow.riseIn(120)
        search.riseIn(380)
        dockBox.riseIn(300)
        pager.page(pager.current)?.popIn(240, 55)
    }

    /** A quick settle when coming back from an app. */
    fun settle() {
        if (Motion.reduced) return
        scaleX = 1.06f; scaleY = 1.06f; alpha = 0.4f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(Ease.out).start()
    }

    // ---- drag targets ----

    private fun screenRect(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    fun targetAt(x: Float, y: Float, pages: List<List<String>>, dock: List<String>, dockMax: Int): LauncherActivity.Target? {
        val dr = screenRect(dockBox)
        if (dr.contains(x.toInt(), y.toInt())) {
            val g = screenRect(dockGrid)
            return if (dock.size >= dockMax) {
                LauncherActivity.Target(true, 0, dockGrid.slotAt(x - g.left, dock.size), true)
            } else {
                LauncherActivity.Target(true, 0, dockGrid.slotAt(x - g.left, dock.size + 1), false)
            }
        }
        val pr = screenRect(pager)
        if (pr.contains(x.toInt(), y.toInt())) {
            val page = pager.current
            val grid = pager.page(page) ?: return null
            val g = screenRect(grid)
            val idx = grid.cellAt(x - g.left, y - g.top)
            val size = pages.getOrNull(page)?.size ?: 0
            return LauncherActivity.Target(false, page, idx.coerceIn(0, size), false)
        }
        return null
    }

    fun edgeDir(x: Float, y: Float): Int {
        val r = screenRect(pager)
        if (y < r.top || y > r.bottom) return 0
        return when {
            x < r.left + 26.dp -> -1
            x > r.right - 26.dp -> 1
            else -> 0
        }
    }

    // ---- gestures: swipe up / down, double-tap, long-press on empty space ----

    private val detector = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!act.editing) act.onDoubleTapHome()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (act.editing) act.exitEdit()
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (!act.editing && !vertical) act.showHomeMenu(e.rawX, e.rawY)
        }
    })

    private fun hitsInteractive(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != VISIBLE) return false
        if (v !== this && (v.isClickable || v is AppIconView)) {
            val r = Rect()
            if (v.getGlobalVisibleRect(r) && r.contains(x, y)) {
                return v !is AppIconView || v.app != null
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hitsInteractive(v.getChildAt(i), x, y)) return true
        return false
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOnInteractive = hitsInteractive(this, e.rawX.toInt(), e.rawY.toInt())
            vt?.recycle()
            vt = VelocityTracker.obtain()
        }
        val raw = MotionEvent.obtain(e)
        raw.setLocation(e.rawX, e.rawY)
        vt?.addMovement(raw)
        raw.recycle()
        val handled = super.dispatchTouchEvent(e)
        if (!touchOnInteractive) detector.onTouchEvent(e)
        return handled
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (act.editing) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; vertical = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                    vertical = true
                    act.beginVerticalGesture(dy < 0)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; vertical = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (!vertical && !act.editing && abs(dy) > slop * 1.5f && abs(dy) > abs(e.rawX - downX) * 1.3f) {
                    vertical = true
                    act.beginVerticalGesture(dy < 0)
                }
                if (vertical) act.verticalGestureMove(dy)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (vertical) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    act.verticalGestureEnd(e.rawY - downY, if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0f else vy)
                }
                vertical = false
            }
        }
        return true
    }
}
