package com.wellsey.perch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Perch settings, in the same glass style as the home screen. */
class SettingsActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var list: LinearLayout
    private var sheet: View? = null
    private var insets: Insets = Insets.NONE
    private var repo: AppRepo? = null
    private val swatchCache = HashMap<String, android.graphics.Bitmap>()

    private val backdropListener: () -> Unit = { root.invalidateDeep(); if (::list.isInitialized) build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Ui.init(this)
        window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this)
        val bg = View(this)
        bg.background = Glass(bg, 0f, 0x470A0C1E, 0xF0101326.toInt(), border = 0, highlight = 0)
        root.addView(bg, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.clipChildren = false
        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, wi ->
            insets = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            list.setPadding(16.dp, insets.top + 6.dp, 16.dp, insets.bottom + 28.dp)
            wi
        }

        Backdrop.addListener(backdropListener)
        Backdrop.reload(this)
        repo = AppRepo(this).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Backdrop.removeListener(backdropListener)
        repo?.stop()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet != null) closeSheet() else finish()
    }

    // ------------------------------------------------------------ layout

    private fun build() {
        list.removeAllViews()

        // Header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = GlyphView(this, Glyphs.BACK, 22, stroke = 2.2f).apply {
            setPadding(11.dp, 11.dp, 11.dp, 11.dp)
            isClickable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(44.dp, 44.dp))
        header.addView(label(this, 30f, Fonts.bold).apply { text = "Perch"; letterSpacing = -0.02f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6.dp })
        list.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp))

        // Default home app
        if (!isDefaultHome()) {
            val c = card()
            c.setPadding(18.dp, 18.dp, 18.dp, 16.dp)
            c.addView(label(this, 17f, Fonts.semibold).apply { text = "Perch isn't your home screen yet" })
            c.addView(body("Set it as the default home app so the Home button opens Perch, and long-press shows app shortcuts."),
                lp().apply { topMargin = 6.dp })
            c.addView(pillButton("Set as default home app") { LauncherActivity.requestDefaultHome(this) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp).apply { topMargin = 14.dp })
            list.addView(c, cardLp())
        }

        // Wallpaper
        val wc = card()
        wc.setPadding(16.dp, 16.dp, 16.dp, 8.dp)
        wc.addView(label(this, 15f, Fonts.semibold).apply { text = "Wallpaper" })
        val swatches = LinearLayout(this)
        swatches.orientation = LinearLayout.HORIZONTAL
        for ((i, name) in Wallpapers.names.withIndex()) {
            swatches.addView(swatch(name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = 12.dp
            })
        }
        wc.addView(swatches, lp().apply { topMargin = 14.dp; bottomMargin = 6.dp })
        wc.addView(row(Glyphs.IMAGE, 0xFFEC4899.toInt(), "Choose a photo", "", divider = false) { pickPhoto() })
        wc.addView(row(Glyphs.HEX, 0xFF8B5CF6.toInt(), "Frost my current wallpaper", frostStatus()) { useCurrentWallpaper() })
        list.addView(wc, cardLp())

        // Switches
        val tc = card()
        tc.addView(toggle(Glyphs.SEARCH, 0xFF3B82F6.toInt(), "Search bar on home", Prefs.showSearch, false) { Prefs.showSearch = it })
        tc.addView(toggle(Glyphs.CLOUD, 0xFFF59E0B.toInt(), "Weather and calendar", Prefs.showWidgets) { Prefs.showWidgets = it })
        tc.addView(toggle(Glyphs.HEX, 0xFF8B5CF6.toInt(), "Glass effects", Prefs.glass) {
            Prefs.glass = it
            root.invalidateDeep()
        })
        tc.addView(toggle(Glyphs.HAPTIC, 0xFF10B981.toInt(), "Haptic feedback", Prefs.haptics) { Prefs.haptics = it })
        list.addView(tc, cardLp())

        // Layout and gestures
        val gc = card()
        gc.addView(row(Glyphs.GRID, 0xFF64748B.toInt(), "Grid size", "${Prefs.columns} × ${Prefs.rows}", divider = false) { pickGrid() })
        gc.addView(row(Glyphs.ARROW_UP, 0xFF0EA5E9.toInt(), "Swipe up", gestureName(Prefs.swipeUp)) {
            choose("Swipe up on home", listOf("drawer" to "All apps", "search" to "Search", "none" to "Nothing"), Prefs.swipeUp) {
                Prefs.swipeUp = it
            }
        })
        gc.addView(row(Glyphs.ARROW_DOWN, 0xFF14B8A6.toInt(), "Swipe down", gestureName(Prefs.swipeDown)) {
            choose("Swipe down on home", listOf("notifications" to "Notifications", "search" to "Search", "none" to "Nothing"), Prefs.swipeDown) {
                Prefs.swipeDown = it
            }
        })
        gc.addView(row(Glyphs.LOCK, 0xFFEF4444.toInt(), "Double-tap", gestureName(Prefs.doubleTap)) {
            choose("Double-tap on home", listOf("lock" to "Lock screen", "none" to "Nothing"), Prefs.doubleTap) {
                Prefs.doubleTap = it
                if (it == "lock" && !LockService.isEnabled(this)) explainLock()
            }
        })
        val hiddenCount = Prefs.hidden.size
        gc.addView(row(Glyphs.HIDE, 0xFF6366F1.toInt(), "Hidden apps", if (hiddenCount == 0) "None" else "$hiddenCount hidden") { showHidden() })
        list.addView(gc, cardLp())

        // About
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        list.addView(label(this, 12f, Fonts.regular, 0.6f).apply {
            text = "Perch $version"
            gravity = Gravity.CENTER
        }, lp().apply { topMargin = 18.dp })
    }

    private fun gestureName(v: String) = when (v) {
        "drawer" -> "All apps"
        "search" -> "Search"
        "notifications" -> "Notifications"
        "lock" -> if (LockService.isEnabled(this)) "Lock screen" else "Lock screen (off)"
        else -> "Nothing"
    }

    private fun frostStatus(): String = when (Backdrop.source) {
        Backdrop.Source.SYSTEM -> "On"
        Backdrop.Source.PERCH -> "Using Perch wallpaper"
        Backdrop.Source.LIVE -> "Live wallpaper"
        else -> if (Environment.isExternalStorageManager()) "Loading" else "Needs access"
    }

    private fun isDefaultHome(): Boolean = try {
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    } catch (e: Exception) {
        true
    }

    // ------------------------------------------------------------ building blocks

    private fun lp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun cardLp() = lp().apply { topMargin = 14.dp }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass(this, 26.dpf, 0x29FFFFFF, 0x1FFFFFFF, border = 0x47FFFFFF, highlight = 0x59FFFFFF)
        clipToOutline = true
    }

    private fun body(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 14f
        typeface = Fonts.regular
        setTextColor(Color.WHITE)
        alpha = 0.85f
        setLineSpacing(2.dpf, 1f)
    }

    private fun pillButton(text: String, click: () -> Unit): TextView =
        label(this, 14f, Fonts.semibold, 1f, 0xFF1B1446.toInt()).apply {
            this.text = text
            gravity = Gravity.CENTER
            setPadding(20.dp, 0, 20.dp, 0)
            background = GradientDrawable().apply { cornerRadius = 21.dpf; setColor(Color.WHITE) }
            isClickable = true
            springPress(0.95f)
            setOnClickListener { click() }
        }

    private fun badge(glyph: String, color: Int): View = GlyphView(this, glyph, 30, stroke = 2.2f).apply {
        setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        background = GradientDrawable().apply { cornerRadius = 9.dpf; setColor(color) }
    }

    private fun hairline(): View = View(this).apply { setBackgroundColor(0x24FFFFFF) }

    private fun row(glyph: String, color: Int, title: String, value: String, divider: Boolean = true, click: () -> Unit): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        if (divider) wrap.addView(hairline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = 58.dp })
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(16.dp, 0, 14.dp, 0)
        r.minimumHeight = 56.dp
        r.isClickable = true
        r.background = pressed()
        r.contentDescription = "$title. $value"
        r.addView(badge(glyph, color), LinearLayout.LayoutParams(30.dp, 30.dp))
        r.addView(label(this, 15f, Fonts.regular).apply { text = title },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp })
        r.addView(label(this, 14f, Fonts.regular, 0.75f).apply { text = value },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 6.dp })
        r.addView(GlyphView(this, Glyphs.CHEVRON, 16, 0x99FFFFFF.toInt(), 2.4f))
        r.setOnClickListener { r.buzz(); click() }
        wrap.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp))
        return wrap
    }

    private fun toggle(glyph: String, color: Int, title: String, on: Boolean, divider: Boolean = true, change: (Boolean) -> Unit): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        if (divider) wrap.addView(hairline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = 58.dp })
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(16.dp, 0, 14.dp, 0)
        r.addView(badge(glyph, color), LinearLayout.LayoutParams(30.dp, 30.dp))
        r.addView(label(this, 15f, Fonts.regular).apply { text = title },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 12.dp })
        val sw = GlassSwitch(this, on) { v -> r.buzz(); change(v) }
        sw.contentDescription = title
        r.addView(sw, LinearLayout.LayoutParams(52.dp, 32.dp))
        r.isClickable = true
        r.setOnClickListener { sw.toggle() }
        wrap.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp))
        return wrap
    }

    private fun pressed() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0x1FFFFFFF))
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun swatch(name: String): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val selected = Prefs.wallSource == "perch" && Prefs.wallPreset == name
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setImageBitmap(swatchCache.getOrPut(name) { Wallpapers.render(name, 180, 300) })
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 18.dpf)
            }
        }
        img.clipToOutline = true
        val frame = FrameLayout(this)
        frame.setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        frame.background = GradientDrawable().apply {
            cornerRadius = 21.dpf
            if (selected) setStroke(3.dp, Color.WHITE) else setStroke(1.dp, 0x4DFFFFFF)
        }
        frame.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 96.dp))
        col.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(label(this, 13f, Fonts.medium).apply { text = name; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        col.isClickable = true
        col.contentDescription = "$name wallpaper" + if (selected) ", selected" else ""
        col.springPress(0.94f)
        col.setOnClickListener { setPreset(name) }
        return col
    }

    // ------------------------------------------------------------ actions

    private fun setPreset(name: String) {
        val (w, h) = Wallpapers.screenSize(this)
        toast("Setting wallpaper…")
        Thread {
            val bmp = Wallpapers.render(name, w, h)
            runOnUiThread {
                Wallpapers.apply(this, bmp, name) { ok ->
                    toast(if (ok) "$name wallpaper set" else "Couldn't set the wallpaper")
                    build()
                }
            }
        }.start()
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(i, REQ_PHOTO)
        } catch (e: Exception) {
            toast("No photo picker found")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        toast("Setting wallpaper…")
        Thread {
            val bmp = try {
                Wallpapers.fromPhoto(this, uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bmp == null) {
                    toast("Couldn't open that photo")
                } else {
                    Wallpapers.apply(this, bmp, "") { ok ->
                        toast(if (ok) "Wallpaper set" else "Couldn't set the wallpaper")
                        build()
                    }
                }
            }
        }.start()
    }

    private fun useCurrentWallpaper() {
        if (Backdrop.source == Backdrop.Source.LIVE) {
            info(
                "Live wallpapers can't be frosted",
                "Perch can only blur a still image. Pick one of Perch's wallpapers or a photo, or set a still wallpaper in your phone's settings.",
            )
            return
        }
        if (!Environment.isExternalStorageManager()) {
            info(
                "Let Perch see your wallpaper",
                "To frost the wallpaper you already have, Android needs you to give Perch “All files access”. " +
                    "Perch only uses it to read the wallpaper image. You can skip this and pick a Perch wallpaper or a photo instead.",
                "Allow access",
            ) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            return
        }
        Prefs.wallSource = "system"
        Prefs.wallPreset = ""
        Backdrop.reload(this)
        toast("Frosting your wallpaper")
    }

    private fun pickGrid() {
        val options = listOf("4x4" to "4 × 4", "4x5" to "4 × 5", "4x6" to "4 × 6", "5x5" to "5 × 5", "5x6" to "5 × 6")
        choose("Grid size", options, "${Prefs.columns}x${Prefs.rows}") {
            val p = it.split("x")
            Prefs.columns = p[0].toInt()
            Prefs.rows = p[1].toInt()
        }
    }

    private fun explainLock() {
        info(
            "Turn on double-tap to lock",
            "Android only lets apps lock the screen through an accessibility service. In Accessibility settings, open " +
                "“Perch double-tap to lock” and switch it on. If it's greyed out, open Perch's App info, tap ⋮ and choose " +
                "“Allow restricted settings” first.",
            "Open settings",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun showHidden() {
        val hidden = Prefs.hidden
        if (hidden.isEmpty()) {
            info("No hidden apps", "Long-press any app and choose Hide app to keep it out of the app drawer.")
            return
        }
        val byKey = repo?.byKey ?: emptyMap()
        val options = hidden.map { it to (byKey[it]?.label ?: it.substringBefore('/')) }.sortedBy { it.second.lowercase() }
        choose("Tap an app to unhide it", options, null) { key ->
            Prefs.hidden = Prefs.hidden - key
            toast("${byKey[key]?.label ?: "App"} is back in the app drawer")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ glass bottom sheets

    private fun choose(title: String, options: List<Pair<String, String>>, selected: String?, pick: (String) -> Unit) {
        val c = sheetCard(title)
        for ((i, o) in options.withIndex()) {
            if (i > 0) c.addView(hairline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(20.dp, 0, 18.dp, 0)
            r.isClickable = true
            r.background = pressed()
            r.addView(label(this, 16f, Fonts.regular).apply { text = o.second },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (o.first == selected) r.addView(GlyphView(this, Glyphs.CHECK, 20, 0xFF7DD3FC.toInt(), 2.6f))
            r.setOnClickListener {
                r.buzz()
                pick(o.first)
                closeSheet()
                build()
            }
            c.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 54.dp))
        }
        openSheet(c)
    }

    private fun info(title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val c = sheetCard(title)
        c.addView(body(text), lp().apply { marginStart = 20.dp; marginEnd = 20.dp; bottomMargin = 16.dp })
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.END
        buttons.setPadding(20.dp, 0, 20.dp, 18.dp)
        val close = label(this, 14f, Fonts.semibold).apply {
            this.text = if (action == null) "OK" else "Not now"
            gravity = Gravity.CENTER
            setPadding(18.dp, 0, 18.dp, 0)
            background = GradientDrawable().apply { cornerRadius = 21.dpf; setColor(0x2EFFFFFF) }
            isClickable = true
            setOnClickListener { closeSheet() }
        }
        buttons.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp))
        if (action != null && onAction != null) {
            buttons.addView(pillButton(action) { closeSheet(); onAction() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 42.dp).apply { marginStart = 10.dp })
        }
        c.addView(buttons, lp())
        openSheet(c)
    }

    private fun sheetCard(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = Glass(c, 28.dpf, 0x40FFFFFF, 0xF2262A40.toInt(), border = 0x52FFFFFF, highlight = 0x73FFFFFF)
        c.clipToOutline = true
        c.glassShadow(16)
        c.isClickable = true
        c.addView(label(this, 17f, Fonts.semibold).apply {
            text = title
            setPadding(20.dp, 20.dp, 20.dp, 14.dp)
            isSingleLine = false
        })
        return c
    }

    private fun openSheet(card: LinearLayout) {
        closeSheet(false)
        val holder = FrameLayout(this)
        val scrim = View(this)
        scrim.setBackgroundColor(0x66000000)
        scrim.setOnClickListener { closeSheet() }
        holder.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        holder.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(12.dp, 0, 12.dp, insets.bottom + 12.dp)
        })
        root.addView(holder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        sheet = holder
        if (!Motion.reduced) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(250).start()
            card.translationY = 400.dpf
            card.animate().translationY(0f).setDuration(450).setInterpolator(Ease.sheet).start()
        }
    }

    private fun closeSheet(animate: Boolean = true) {
        val s = sheet ?: return
        sheet = null
        if (!animate || Motion.reduced) {
            root.removeView(s); return
        }
        s.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(s) }.start()
    }

    companion object {
        private const val REQ_PHOTO = 21
    }
}

/** The iOS-style switch from the design: a green track and a springy white knob. */
@SuppressLint("ViewConstructor")
class GlassSwitch(context: Context, on: Boolean, private val changed: (Boolean) -> Unit) : View(context) {
    var on = on
        private set
    private var pos = if (on) 1f else 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(3.dpf, 0f, 1.dpf, 0x4D000000)
    }
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener { toggle() }
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = this@GlassSwitch.on
            }
        }
    }

    fun toggle() {
        on = !on
        changed(on)
        anim?.cancel()
        if (Motion.reduced) {
            pos = if (on) 1f else 0f; invalidate(); return
        }
        anim = ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 350
            interpolator = Ease.spring
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = pos.coerceIn(0f, 1f)
        val off = Color.argb(64, 255, 255, 255)
        val onC = 0xFF34D399.toInt()
        track.color = blend(off, onC, t)
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, track)
        val r = h / 2f - 3.dpf
        val cx = 3.dpf + r + (w - 6.dpf - 2 * r) * pos
        canvas.drawCircle(cx, h / 2f, r, knob)
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
