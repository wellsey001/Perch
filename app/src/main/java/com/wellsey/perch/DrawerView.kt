package com.wellsey.perch

import android.annotation.SuppressLint
import android.graphics.Insets
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** The glass app drawer that slides up over the home screen: search, suggested apps, A–Z grid. */
@SuppressLint("ViewConstructor")
class DrawerView(private val act: LauncherActivity) : FrameLayout(act) {
    private val sheet = Sheet()
    private val handle = FrameLayout(act)
    private val searchBox = LinearLayout(act)
    val input = EditText(act)
    private val scroll = ScrollView(act)
    private val content = LinearLayout(act)
    private val suggestedLabel = section("Suggested")
    private val suggestedCard = FrameLayout(act)
    private val suggestedGrid = AppGrid(act, act, Place.SUGGESTED)
    private val allLabel = section("All apps")
    private val allGrid = AppGrid(act, act, Place.DRAWER)
    private val emptyText = label(act, 15f, Fonts.medium, 0.75f)
    private val webRow = LinearLayout(act)
    private val webText = label(act, 15f, Fonts.medium)

    private val tabs = LinearLayout(act)
    private val tabPersonal = tab("Personal")
    private val tabWork = tab("Work")
    private var showWork = false
    private val newLabel = section("New")
    private val newCard = FrameLayout(act)
    private val newGrid = AppGrid(act, act, Place.SUGGESTED)
    private val resultsCard = LinearLayout(act)
    private val listBox = FrameLayout(act)
    private val letters = LetterStrip(act) { letter -> jumpTo(letter) }
    private var shortcuts: List<android.content.pm.ShortcutInfo> = emptyList()

    private var apps: List<AppInfo> = emptyList()
    private var progress = 0f
    private val extra = 80.dp // the sheet runs past the bottom so its overshoot never shows a gap

    init {
        visibility = INVISIBLE // laid out but hidden, so the first open can slide
        clipChildren = false
        // Tapping the blurred home screen above the sheet closes the drawer.
        isClickable = true
        setOnClickListener { act.closeDrawer() }

        sheet.orientation = LinearLayout.VERTICAL
        sheet.background = Glass(sheet, 38.dpf, 0x6B141628, 0xF2161830.toInt(), border = 0x4DFFFFFF, highlight = 0x4DFFFFFF, topOnly = true)
        sheet.setPadding(18.dp, 0, 18.dp, 0)

        // Grab handle
        val pill = View(act).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x99FFFFFF.toInt()); cornerRadius = 3.dpf
            }
        }
        handle.addView(pill, FrameLayout.LayoutParams(44.dp, 5.dp, Gravity.CENTER))
        handle.contentDescription = "Close all apps"
        handle.setOnClickListener { act.closeDrawer() }
        sheet.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 30.dp))

        // Search box
        searchBox.orientation = LinearLayout.HORIZONTAL
        searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.background = Glass(searchBox, 23.dpf, 0x24FFFFFF, 0x2EFFFFFF, border = 0x38FFFFFF, highlight = 0x33FFFFFF)
        searchBox.setPadding(16.dp, 0, 12.dp, 0)
        searchBox.addView(GlyphView(act, Glyphs.SEARCH, 18, stroke = 2.2f))
        input.background = null
        input.hint = "Search apps"
        input.setHintTextColor(0x99FFFFFF.toInt())
        input.setTextColor(0xFFFFFFFF.toInt())
        input.textSize = 15f
        input.typeface = Fonts.medium
        input.isSingleLine = true
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setPadding(10.dp, 0, 0, 0)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                launchFirst(); true
            } else false
        }
        searchBox.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        sheet.addView(searchBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 46.dp).apply {
            topMargin = 4.dp; bottomMargin = 12.dp
        })

        // Scrolling content
        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.setPadding(0, 0, 14.dp, 0) // room for the A–Z strip

        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.background = Glass(tabs, 18.dpf, 0x1FFFFFFF, 0x24FFFFFF, border = 0x33FFFFFF, highlight = 0)
        tabs.setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        tabs.addView(tabPersonal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        tabs.addView(tabWork, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        tabPersonal.setOnClickListener { showWork = false; refresh(); tabPersonal.buzz() }
        tabWork.setOnClickListener { showWork = true; refresh(); tabWork.buzz() }
        tabs.visibility = GONE
        content.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 36.dp).apply { bottomMargin = 16.dp })

        content.addView(suggestedLabel)
        suggestedCard.background = Glass(suggestedCard, 26.dpf, 0x1FFFFFFF, 0x24FFFFFF, border = 0x33FFFFFF, highlight = 0x40FFFFFF)
        suggestedCard.setPadding(6.dp, 12.dp, 6.dp, 8.dp)
        suggestedGrid.columns = 4
        suggestedGrid.rows = 1
        suggestedGrid.maxIcon = 56.dp
        suggestedCard.addView(suggestedGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 90.dp))
        content.addView(suggestedCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 22.dp
        })
        content.addView(newLabel)
        newCard.background = Glass(newCard, 26.dpf, 0x1FFFFFFF, 0x24FFFFFF, border = 0x33FFFFFF, highlight = 0x40FFFFFF)
        newCard.setPadding(6.dp, 12.dp, 6.dp, 8.dp)
        newGrid.columns = 4
        newGrid.rows = 1
        newGrid.maxIcon = 56.dp
        newCard.addView(newGrid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 90.dp))
        content.addView(newCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 22.dp
        })

        resultsCard.orientation = LinearLayout.VERTICAL
        resultsCard.background = Glass(resultsCard, 22.dpf, 0x1FFFFFFF, 0x24FFFFFF, border = 0x33FFFFFF, highlight = 0x33FFFFFF)
        resultsCard.clipToOutline = true
        resultsCard.visibility = GONE

        content.addView(allLabel)
        allGrid.cellHeight = 98.dp
        allGrid.maxIcon = 58.dp
        content.addView(allGrid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        emptyText.gravity = Gravity.CENTER
        emptyText.visibility = GONE
        content.addView(emptyText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60.dp))

        webRow.orientation = LinearLayout.HORIZONTAL
        webRow.gravity = Gravity.CENTER_VERTICAL
        webRow.background = Glass(webRow, 20.dpf, 0x1FFFFFFF, 0x24FFFFFF, border = 0x33FFFFFF, highlight = 0x33FFFFFF)
        webRow.setPadding(16.dp, 0, 16.dp, 0)
        webRow.isClickable = true
        webRow.springPress(0.97f)
        webRow.setOnClickListener { act.webSearch(input.text.toString().trim()) }
        webRow.addView(GlyphView(act, Glyphs.GLOBE, 18))
        webRow.addView(webText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp })
        webRow.visibility = GONE
        content.addView(resultsCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8.dp
        })
        content.addView(webRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52.dp).apply { topMargin = 8.dp })

        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        listBox.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        listBox.addView(letters, FrameLayout.LayoutParams(22.dp, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            marginEnd = (-10).dp
        })
        listBox.clipChildren = false
        sheet.addView(listBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = 52.dp; bottomMargin = -extra
        })
    }

    private fun section(text: String): TextView = label(act, 13f, Fonts.semibold, 0.8f).apply {
        this.text = text
        setPadding(4.dp, 0, 4.dp, 12.dp)
    }

    private fun tab(text: String): TextView = label(act, 13f, Fonts.semibold).apply {
        this.text = text
        gravity = Gravity.CENTER
        isClickable = true
    }

    private fun styleTabs() {
        for ((t, on) in listOf(tabPersonal to !showWork, tabWork to showWork)) {
            t.background = if (on) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 15.dpf; setColor(0x40FFFFFF)
            } else null
            t.alpha = if (on) 1f else 0.7f
        }
    }

    fun applyInsets(bars: Insets, ime: Insets) {
        (sheet.layoutParams as LayoutParams).topMargin = bars.top + 10.dp
        sheet.requestLayout()
        scroll.setPadding(0, 0, 0, max(bars.bottom, ime.bottom) + 24.dp + extra)
        letters.setPadding(0, 6.dp, 0, max(bars.bottom, ime.bottom) + extra + 24.dp)
    }

    fun setApps(list: List<AppInfo>) {
        apps = list
        refresh()
    }

    fun refresh() {
        val q = input.text?.toString()?.trim().orEmpty()
        val hidden = Prefs.hidden
        val byKey = act.repo.byKey
        val hasWork = apps.any { it.isWork }
        if (!hasWork) showWork = false
        if (q.isEmpty()) {
            tabs.visibility = if (hasWork) VISIBLE else GONE
            styleTabs()
            val visible = apps.filter { it.key !in hidden && it.isWork == showWork }
            val sugg = if (showWork) emptyList() else suggested(visible)
            suggestedLabel.visibility = if (sugg.isEmpty()) GONE else VISIBLE
            suggestedCard.visibility = suggestedLabel.visibility
            suggestedGrid.bind(sugg.map { it.key }, byKey)
            val weekAgo = System.currentTimeMillis() - 4 * 24 * 3600_000L
            val fresh = visible.filter { it.installedAt > weekAgo && it.packageName != act.packageName }
                .sortedByDescending { it.installedAt }.take(4)
            newLabel.visibility = if (fresh.isEmpty()) GONE else VISIBLE
            newCard.visibility = newLabel.visibility
            newGrid.bind(fresh.map { it.key }, byKey)
            allLabel.text = if (showWork) "Work apps" else "All apps"
            allLabel.visibility = VISIBLE
            webRow.visibility = GONE
            emptyText.visibility = GONE
            resultsCard.visibility = GONE
            allGrid.columns = Prefs.columns
            allGrid.bind(visible.map { it.key }, byKey)
            letters.visibility = VISIBLE
            letters.setLetters(visible.map { firstLetter(it.label) }.distinct())
        } else {
            tabs.visibility = GONE
            suggestedLabel.visibility = GONE
            suggestedCard.visibility = GONE
            newLabel.visibility = GONE
            newCard.visibility = GONE
            letters.visibility = GONE
            val results = search(q)
            allLabel.visibility = if (results.isEmpty()) GONE else VISIBLE
            allLabel.text = "Apps"
            allGrid.columns = Prefs.columns
            allGrid.bind(results.take(Prefs.columns * 2).map { it.key }, byKey)
            showResults(q)
            emptyText.text = "No apps match “$q”"
            emptyText.visibility = if (results.isEmpty() && resultsCard.visibility != VISIBLE) VISIBLE else GONE
            webText.text = "Search the web for “$q”"
            webRow.visibility = VISIBLE
        }
    }

    private fun firstLetter(label: String): String {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    private fun jumpTo(letter: String) {
        val keys = allGrid.keys
        val i = keys.indexOfFirst { k -> k != null && act.repo.byKey[k]?.let { firstLetter(it.label) } == letter }
        if (i < 0) return
        val row = i / allGrid.columns
        scroll.scrollTo(0, allGrid.top + row * allGrid.cellHeight)
    }

    /** Shortcuts, settings pages, contacts and sums that match. */
    private fun showResults(q: String) {
        val out = ArrayList<SearchResult>()
        Calc.eval(q)?.let { v ->
            val txt = Calc.format(v)
            out.add(SearchResult("= $txt", "Tap to copy", Glyphs.CALC) {
                val cm = act.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Result", txt))
                android.widget.Toast.makeText(act, "Copied $txt", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        val ql = q.lowercase()
        val density = resources.displayMetrics.densityDpi
        for (sc in shortcuts) {
            if (out.size >= 6) break
            val lbl = (sc.shortLabel ?: sc.longLabel ?: continue).toString()
            if (!lbl.lowercase().contains(ql)) continue
            val owner = act.repo.apps.firstOrNull { it.packageName == sc.`package` && it.user == sc.userHandle }
            val icon = try {
                act.repo.launcherApps.getShortcutIconDrawable(sc, density)
            } catch (e: Exception) {
                null
            }
            out.add(SearchResult(lbl, owner?.label ?: "Shortcut", Glyphs.SPARK, icon) {
                try {
                    act.repo.launcherApps.startShortcut(sc, null, null)
                } catch (e: Exception) {
                }
            })
        }
        out += SettingsSearch.find(act, q) { act.startExternal(it) }
        out += ContactsSearch.find(act, q) { act.startExternal(it) }
        if (!ContactsSearch.hasPermission(act) && q.length >= 2 && q.first().isLetter()) {
            out.add(SearchResult("Search your contacts too", "Allow contacts", Glyphs.PERSON) { act.requestContacts() })
        }

        resultsCard.removeAllViews()
        for ((i, r) in out.withIndex()) {
            if (i > 0) resultsCard.addView(View(act).apply { setBackgroundColor(0x24FFFFFF) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = 56.dp })
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            row.minimumHeight = 54.dp
            row.isClickable = true
            row.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x1FFFFFFF))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            if (r.icon != null) row.addView(android.widget.ImageView(act).apply { setImageDrawable(r.icon) },
                LinearLayout.LayoutParams(26.dp, 26.dp).apply { marginEnd = 14.dp })
            else row.addView(GlyphView(act, r.glyph, 22), LinearLayout.LayoutParams(26.dp, 26.dp).apply { marginEnd = 14.dp })
            val texts = LinearLayout(act)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(label(act, 15f, Fonts.medium).apply { text = r.title })
            if (!r.subtitle.isNullOrEmpty()) texts.addView(label(act, 12f, Fonts.regular, 0.7f).apply { text = r.subtitle })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { row.buzz(); r.action() }
            resultsCard.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        resultsCard.visibility = if (out.isEmpty()) GONE else VISIBLE
    }

    /** Load every app's shortcuts in the background so search can find them. */
    private fun loadShortcuts() {
        Thread {
            val list = act.repo.allShortcuts()
            post { shortcuts = list; if (input.text.isNotEmpty()) refresh() }
        }.start()
    }

    private fun suggested(visible: List<AppInfo>): List<AppInfo> {
        val used = visible.filter { Prefs.launchCount(it.key) > 0 }
            .sortedWith(compareByDescending<AppInfo> { Prefs.launchCount(it.key) }.thenByDescending { Prefs.lastLaunch(it.key) })
            .take(4)
        if (used.size >= 4) return used
        val dock = act.dockKeys().toSet()
        return used + visible.filter { it !in used && it.key !in dock }.take(4 - used.size)
    }

    private fun search(q: String): List<AppInfo> {
        val s = q.lowercase()
        fun score(a: AppInfo): Int {
            val l = a.label.lowercase()
            return when {
                l.startsWith(s) -> 0
                l.split(' ', '-', '.').any { it.startsWith(s) } -> 1
                l.contains(s) -> 2
                a.packageName.lowercase().contains(s) -> 3
                else -> 9
            }
        }
        return apps.map { it to score(it) }.filter { it.second < 9 }.sortedBy { it.second }.map { it.first }
    }

    private fun launchFirst() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        val first = search(q).firstOrNull()
        if (first != null) act.launch(first, allGrid.viewFor(first.key)) else act.webSearch(q)
    }

    // ---- opening and closing ----

    fun setProgress(p: Float) {
        progress = p
        visibility = if (p <= 0.001f) INVISIBLE else VISIBLE
        val travel = (if (sheet.height > 0) sheet.height - extra else height).toFloat()
        sheet.translationY = (1f - p) * travel * 1.04f
    }

    /** Reset to the top with an empty search, ready to open. */
    fun prepare(pop: Boolean) {
        if (input.text.isNotEmpty()) input.setText("")
        scroll.scrollTo(0, 0)
        showWork = false
        refresh()
        loadShortcuts()
        if (pop) {
            suggestedGrid.popIn(250, 60)
            allGrid.popIn(420, 28)
        }
    }

    fun reset() {
        hideKeyboard()
        if (input.text.isNotEmpty()) input.setText("")
        scroll.scrollTo(0, 0)
    }

    fun focusSearch() {
        input.requestFocus()
        input.post { input.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }

    fun hideKeyboard() {
        if (input.hasFocus()) {
            input.windowInsetsController?.hide(WindowInsets.Type.ime())
            input.clearFocus()
        }
    }

    /** The sheet itself: drag it down (from the top of the list) to close. */
    private inner class Sheet : LinearLayout(act) {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var vt: VelocityTracker? = null
        private val slop = ViewConfiguration.get(act).scaledTouchSlop

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

        private fun scrollTopOnScreen(): Int {
            val loc = IntArray(2)
            scroll.getLocationOnScreen(loc)
            return loc[1]
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    track(e, true)
                }

                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    val dy = e.rawY - downY
                    val dx = e.rawX - downX
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        hideKeyboard()
                        if (dy > 0 && (scroll.scrollY == 0 || downY < scrollTopOnScreen())) {
                            dragging = true
                            return true
                        }
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    track(e, true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    val dy = e.rawY - downY
                    if (!dragging && dy > slop) dragging = true
                    if (dragging) {
                        val h = max(1, height - extra)
                        act.setDrawerProgress(1f - (dy / h).coerceIn(0f, 1f))
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        track(e)
                        vt?.computeCurrentVelocity(1000)
                        act.endDrawerDrag(vt?.yVelocity ?: 0f)
                    }
                    dragging = false
                    vt?.recycle(); vt = null
                }
            }
            return true
        }
    }
}

/** The A–Z strip down the side of the app drawer: touch or slide to jump. */
@SuppressLint("ViewConstructor")
class LetterStrip(context: android.content.Context, private val jump: (String) -> Unit) : View(context) {
    private var letters: List<String> = emptyList()
    private var active = -1
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = sp(context, 10.5f)
        typeface = Fonts.semibold
    }
    private val bubble = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }

    fun setLetters(l: List<String>) {
        letters = l.sortedWith(compareBy({ it == "#" }, { it }))
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (letters.size < 2) return
        val top = paddingTop.toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val step = kotlin.math.min(h / letters.size, 19.dpf)
        val start = top + (h - step * letters.size) / 2f
        for ((i, l) in letters.withIndex()) {
            val cy = start + step * i + step / 2f
            if (i == active) canvas.drawCircle(width / 2f, cy, 9.dpf, bubble)
            canvas.drawText(l, width / 2f, cy - (paint.descent() + paint.ascent()) / 2f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (letters.size < 2) return false
        val top = paddingTop.toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val step = kotlin.math.min(h / letters.size, 19.dpf)
        val start = top + (h - step * letters.size) / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((e.y - start) / step).toInt().coerceIn(0, letters.size - 1)
                if (i != active) {
                    active = i
                    buzz()
                    jump(letters[i])
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
            }
        }
        return true
    }
}
