package com.wellsey.perch

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

class LauncherActivity : Activity(), IconHost {
    lateinit var repo: AppRepo
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var home: HomeView
    private lateinit var drawer: DrawerView
    private lateinit var overlay: FrameLayout
    private val handler = Handler(Looper.getMainLooper())

    private var menu: MenuOverlay? = null
    private var prompt: PromptCard? = null
    private var doneButton: TextView? = null

    override var editing = false
        private set
    var insets: Insets = Insets.NONE
        private set

    // The home layout: pages of app keys, and the dock.
    private var pages: MutableList<MutableList<String>> = mutableListOf()
    private var dock: MutableList<String> = mutableListOf()
    private var modelLoaded = false
    private val capacity get() = Prefs.columns * Prefs.rows

    private var resumed = false
    private var firstShow = true
    private var cameFromApp = false
    private var lastFilesAccess: Boolean? = null
    private var ticks = 0

    data class Target(val dock: Boolean, val page: Int, val index: Int, val swap: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Ui.init(this)
        window.setDecorFitsSystemWindows(false)

        repo = AppRepo(this)
        root = FrameLayout(this)
        stage = FrameLayout(this)
        home = HomeView(this)
        drawer = DrawerView(this)
        overlay = FrameLayout(this)
        stage.addView(home, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        stage.addView(drawer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(stage, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, wi ->
            val bars = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = wi.getInsets(WindowInsets.Type.ime())
            insets = bars
            home.applyInsets(bars)
            drawer.applyInsets(bars, ime)
            positionOverlays()
            wi
        }

        home.applySettings()
        repo.onChanged = { onAppsChanged() }
        repo.start()

        Backdrop.addListener(backdropListener)
        Backdrop.reload(this)
        WallpaperManager.getInstance(this).addOnColorsChangedListener(colorsListener, handler)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        registerReceiver(timeReceiver, f, Context.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.stop()
        Backdrop.removeListener(backdropListener)
        try {
            WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener)
            unregisterReceiver(timeReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.applySettings()
        if (modelLoaded) {
            normalize()
            bindAll()
        }
        drawer.refresh()
        home.updateClock()
        refreshWidgets(false)
        setWallpaperOffsets()
        val access = Environment.isExternalStorageManager()
        if (lastFilesAccess != null && lastFilesAccess != access) Backdrop.reload(this)
        lastFilesAccess = access
        home.invalidateDeep()
        if (cameFromApp && !firstShow) home.settle()
        cameFromApp = false
        handler.postDelayed({ maybePromptDefault() }, 900)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        cameFromApp = true
        menu?.dismiss(false)
        if (drawer.isShown || drawerProgress > 0f) closeDrawer(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val wasHere = resumed
            when {
                menu != null -> menu?.dismiss(wasHere)
                drawerProgress > 0.01f -> closeDrawer(wasHere)
                editing -> exitEdit()
                wasHere -> home.pager.snapTo(0)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            prompt != null -> prompt?.dismiss()
            menu != null -> menu?.dismiss(true)
            drawerProgress > 0.01f -> closeDrawer()
            editing -> exitEdit()
            else -> {} // Home stays put
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setWallpaperOffsets()
    }

    private fun setWallpaperOffsets() {
        val token = window.decorView.windowToken ?: return
        try {
            WallpaperManager.getInstance(this).setWallpaperOffsets(token, 0.5f, 0.5f)
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ wallpaper and time

    private val backdropListener: () -> Unit = { root.invalidateDeep() }

    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
            // A wallpaper Perch didn't set: go back to frosting the system one.
            if (System.currentTimeMillis() - Prefs.wallSetAt > 15_000) Prefs.wallSource = "system"
            Backdrop.reload(this)
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            home.updateClock()
            if (++ticks % 15 == 0 || intent.action != Intent.ACTION_TIME_TICK) refreshWidgets(false)
        }
    }

    // ------------------------------------------------------------ apps and the home layout

    private fun onAppsChanged() {
        if (!modelLoaded) {
            if (Prefs.hasHome()) {
                pages = Prefs.loadHome()
                dock = Prefs.loadDock()
            } else {
                createDefaultLayout()
            }
            modelLoaded = true
        }
        if (drag == null) {
            prune()
            normalize()
            save()
            bindAll()
        }
        drawer.setApps(repo.apps)
        if (firstShow) {
            firstShow = false
            home.entrance()
        }
    }

    private fun createDefaultLayout() {
        val picked = LinkedHashSet<String>()
        fun add(a: AppInfo?, into: MutableList<String>) {
            if (a != null && picked.add(a.key)) into.add(a.key)
        }
        dock = mutableListOf()
        add(repo.appFor(Intent(Intent.ACTION_DIAL)), dock)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)), dock)
        add(repo.appFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))), dock)
        add(repo.appFor(Intent(MediaStore.ACTION_IMAGE_CAPTURE)), dock)

        val first = mutableListOf<String>()
        for (name in listOf("Glimpse", "Burrow", "Folio", "Keepsake")) add(repo.appByLabel(name), first)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)), first)
        add(repo.appFor(Intent(AlarmClock.ACTION_SHOW_ALARMS)), first)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)), first)
        add(repo.appFor(Intent(Settings.ACTION_SETTINGS)), first)
        for (name in listOf("Snippy", "Tinto", "Clack", "Notes")) add(repo.appByLabel(name), first)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)), first)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)), first)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)), first)
        add(repo.appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)), first)
        for (a in repo.apps) {
            if (first.size >= 8) break
            if (a.packageName != packageName) add(a, first)
        }
        pages = mutableListOf(first.take(capacity).toMutableList())
    }

    private fun prune() {
        if (repo.apps.isEmpty()) return
        val known = repo.byKey.keys
        val hidden = Prefs.hidden
        for (p in pages) p.removeAll { it !in known || it in hidden }
        dock.removeAll { it !in known || it in hidden }
    }

    private fun normalize() {
        while (dock.size > DOCK_MAX) {
            val k = dock.removeAt(dock.size - 1)
            if (pages.isEmpty()) pages.add(mutableListOf())
            pages.last().add(k)
        }
        val cap = capacity
        var i = 0
        while (i < pages.size) {
            val p = pages[i]
            while (p.size > cap) {
                val k = p.removeAt(p.size - 1)
                if (i + 1 >= pages.size) pages.add(mutableListOf())
                pages[i + 1].add(0, k)
            }
            i++
        }
        if (pages.isEmpty()) pages.add(mutableListOf())
    }

    private fun removeEmptyPages() {
        pages.removeAll { it.isEmpty() }
        if (pages.isEmpty()) pages.add(mutableListOf())
    }

    private fun save() {
        Prefs.saveHome(pages)
        Prefs.saveDock(dock)
    }

    private fun bindAll() {
        home.bind(pages, dock, repo.byKey)
    }

    fun dockKeys(): List<String> = dock

    private fun isOnHome(key: String) = key in dock || pages.any { key in it }

    private fun addToHome(key: String) {
        if (isOnHome(key)) return
        val cap = capacity
        var target = pages.indexOfFirst { it.size < cap }
        if (target < 0) {
            pages.add(mutableListOf()); target = pages.size - 1
        }
        pages[target].add(key)
        save()
        bindAll()
        toast("Added to home screen")
    }

    private fun removeFromHome(key: String) {
        dock.remove(key)
        for (p in pages) p.remove(key)
        save()
        bindAll()
    }

    private fun hideApp(app: AppInfo) {
        Prefs.hidden = Prefs.hidden + app.key
        removeFromHome(app.key)
        drawer.refresh()
        toast("${app.label} hidden. Find it by searching, or unhide it in Perch settings.")
    }

    // ------------------------------------------------------------ launching

    fun launch(app: AppInfo, v: View?) {
        val icon = v as? AppIconView
        val bounds = icon?.iconRectOnScreen()
        val opts = icon?.let {
            ActivityOptions.makeScaleUpAnimation(it, it.iconLeft(), it.iconTop(), it.iconSize, it.iconSize).toBundle()
        }
        try {
            repo.launcherApps.startMainActivity(app.component, app.user, bounds, opts)
            Prefs.bumpLaunch(app.key)
        } catch (e: Exception) {
            toast("Couldn't open ${app.label}")
        }
    }

    private fun startSafely(intent: Intent, v: View? = null): Boolean {
        return try {
            val opts = v?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openClockApp() {
        home.clock.buzz()
        if (!startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS), home.clock)) toast("No clock app found")
    }

    fun openCalendarApp(v: View?) {
        startSafely(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), v ?: home.date)
    }

    fun voiceSearch() {
        if (!startSafely(Intent(RecognizerIntent.ACTION_WEB_SEARCH))) openDrawer(focusSearch = true)
    }

    fun webSearch(q: String) {
        if (q.isEmpty()) return
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (!startSafely(i)) startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ IconHost

    override fun onIconClick(v: AppIconView) {
        val app = v.app ?: return
        if (editing) return
        v.buzz(HapticFeedbackConstants.VIRTUAL_KEY)
        launch(app, v)
    }

    override fun onIconLongPress(v: AppIconView) {
        if (editing && (v.place == Place.HOME || v.place == Place.DOCK)) return
        v.resetPress()
        v.buzz(HapticFeedbackConstants.LONG_PRESS)
        showAppMenu(v)
    }

    override fun onIconRemove(v: AppIconView) {
        val key = v.app?.key ?: return
        v.buzz()
        removeFromHome(key)
    }

    // ------------------------------------------------------------ menus

    private fun showAppMenu(v: AppIconView) {
        val app = v.app ?: return
        menu?.dismiss(false)
        prompt?.dismiss()
        val density = resources.displayMetrics.densityDpi
        val shortcuts = repo.shortcuts(app).map { s ->
            val icon = try {
                repo.launcherApps.getShortcutIconDrawable(s, density)
            } catch (e: Exception) {
                null
            }
            MenuEntry((s.shortLabel ?: s.longLabel ?: "").toString(), Glyphs.SPARK, icon) { startShortcut(s, v) }
        }
        val actions = ArrayList<MenuEntry>()
        actions += MenuEntry("Edit home screen", Glyphs.GRID) { enterEdit() }
        if (v.place == Place.HOME || v.place == Place.DOCK) {
            actions += MenuEntry("Remove from home", Glyphs.MINUS_CIRCLE) { removeFromHome(app.key) }
        } else if (!isOnHome(app.key)) {
            actions += MenuEntry("Add to home screen", Glyphs.PLUS) { addToHome(app.key) }
        }
        actions += MenuEntry("Hide app", Glyphs.HIDE) { hideApp(app) }
        actions += MenuEntry("App info", Glyphs.INFO) { appInfo(app, v) }
        if (!app.isSystem) actions += MenuEntry("Uninstall", Glyphs.TRASH, danger = true) { uninstall(app) }
        val m = MenuOverlay(this, v, 0f, 0f, listOf(shortcuts, actions).filter { it.isNotEmpty() })
        menu = m
        overlay.addView(m, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        m.show()
    }

    fun showHomeMenu(x: Float, y: Float) {
        if (menu != null || drawerProgress > 0.01f) return
        home.buzz(HapticFeedbackConstants.LONG_PRESS)
        val items = listOf(
            MenuEntry("Edit home screen", Glyphs.GRID) { enterEdit() },
            MenuEntry("Wallpaper", Glyphs.IMAGE) { openSettings() },
            MenuEntry("Perch settings", Glyphs.GEAR) { openSettings() },
        )
        val m = MenuOverlay(this, null, x, y, listOf(items))
        menu = m
        overlay.addView(m, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        m.show()
    }

    fun onMenuClosed(m: MenuOverlay) {
        overlay.removeView(m)
        if (menu === m) menu = null
    }

    fun setStageBlur(f: Float) {
        if (Prefs.glass && f > 0.01f) {
            val r = 16.dpf * f
            stage.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        } else {
            stage.setRenderEffect(null)
        }
    }

    private fun startShortcut(s: ShortcutInfo, v: View) {
        try {
            repo.launcherApps.startShortcut(s, (v as? AppIconView)?.iconRectOnScreen(), null)
        } catch (e: Exception) {
            toast("Couldn't open that shortcut")
        }
    }

    private fun appInfo(app: AppInfo, v: AppIconView) {
        try {
            repo.launcherApps.startAppDetailsActivity(app.component, app.user, v.iconRectOnScreen(), null)
        } catch (e: Exception) {
            toast("Couldn't open app info")
        }
    }

    private fun uninstall(app: AppInfo) {
        val i = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
        if (!startSafely(i)) toast("Couldn't uninstall ${app.label}")
    }

    fun openSettings() {
        startSafely(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------ app drawer

    var drawerProgress = 0f
        private set
    private var drawerAnim: ValueAnimator? = null
    private var gestureUp = false

    fun setDrawerProgress(p: Float) {
        drawerProgress = p
        drawer.setProgress(p)
        val q = p.coerceIn(0f, 1f)
        val s = 1f - 0.1f * q
        home.scaleX = s
        home.scaleY = s
        home.alpha = 1f - 0.5f * q
        val r = 10.dpf * q
        if (Prefs.glass && r > 0.5f) home.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        else home.setRenderEffect(null)
    }

    fun openDrawer(focusSearch: Boolean = false) {
        if (editing) exitEdit()
        menu?.dismiss(false)
        prompt?.dismiss()
        drawer.prepare(pop = true)
        animateDrawer(1f)
        if (focusSearch) drawer.focusSearch()
    }

    fun closeDrawer(animate: Boolean = true) {
        drawer.hideKeyboard()
        if (animate) {
            animateDrawer(0f)
        } else {
            drawerAnim?.cancel()
            setDrawerProgress(0f)
            drawer.reset()
        }
    }

    private fun animateDrawer(to: Float) {
        drawerAnim?.cancel()
        val from = drawerProgress
        if (Motion.reduced) {
            setDrawerProgress(to)
            if (to == 0f) drawer.reset()
            return
        }
        drawerAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 600 else 420
            interpolator = if (to > from) Ease.sheet else Ease.out
            addUpdateListener { setDrawerProgress(it.animatedValue as Float) }
            addListener(onEnd { if (to == 0f) drawer.reset() })
            start()
        }
    }

    fun endDrawerDrag(vy: Float) {
        if (vy > 900 || drawerProgress < 0.72f) closeDrawer() else animateDrawer(1f)
    }

    fun beginVerticalGesture(up: Boolean) {
        gestureUp = up
        if (up && Prefs.swipeUp == "drawer") {
            drawerAnim?.cancel()
            drawer.prepare(pop = false)
        }
    }

    fun verticalGestureMove(dy: Float) {
        if (gestureUp && Prefs.swipeUp == "drawer") {
            setDrawerProgress((-dy / (home.height * 0.55f)).coerceIn(0f, 1f))
        }
    }

    fun verticalGestureEnd(dy: Float, vy: Float) {
        if (gestureUp) {
            when (Prefs.swipeUp) {
                "drawer" -> if (vy < -700 || drawerProgress > 0.3f) animateDrawer(1f) else animateDrawer(0f)
                "search" -> if (dy < -48.dp || vy < -700) openDrawer(focusSearch = true)
            }
        } else if (dy > 56.dp || vy > 900) {
            when (Prefs.swipeDown) {
                "notifications" -> expandNotifications()
                "search" -> openDrawer(focusSearch = true)
            }
        }
    }

    private fun expandNotifications() {
        try {
            val sbs = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbs)
        } catch (e: Throwable) {
        }
    }

    // ------------------------------------------------------------ double-tap to lock

    fun onDoubleTapHome() {
        if (Prefs.doubleTap != "lock") return
        if (LockService.lock()) return
        showPrompt(
            "Turn on double-tap to lock",
            "Android only lets apps lock the screen through an accessibility service. In Settings › Accessibility, " +
                "open Perch double-tap to lock and switch it on. If it's greyed out, open Perch's App info, tap ⋮ and " +
                "choose Allow restricted settings first.",
            "Open settings", { startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            "Not now", null,
        )
    }

    // ------------------------------------------------------------ widgets

    fun refreshWidgets(force: Boolean) {
        val wOk = WeatherSource.hasPermission(this)
        home.weather.show(WeatherSource.cached(), wOk)
        if (Prefs.showWidgets && wOk) WeatherSource.refresh(this, force) { w -> home.weather.show(w, true) }
        val cOk = CalendarSource.hasPermission(this)
        if (Prefs.showWidgets && cOk) CalendarSource.next(this) { e -> home.calendar.show(e, true) }
        else home.calendar.show(null, cOk)
    }

    fun onWeatherTap() {
        if (!WeatherSource.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val weatherApp = repo.apps.firstOrNull { it.label.contains("weather", ignoreCase = true) }
        if (weatherApp != null) launch(weatherApp, null) else refreshWidgets(true)
    }

    fun onCalendarTap(e: NextEvent?) {
        if (!CalendarSource.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
            return
        }
        if (e != null) {
            val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
            if (startSafely(i, home.calendar)) return
        }
        openCalendarApp(home.calendar)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshWidgets(true)
    }

    // ------------------------------------------------------------ default home prompt

    fun isDefaultHome(): Boolean = try {
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    } catch (e: Exception) {
        true
    }

    private fun maybePromptDefault() {
        if (!resumed || prompt != null || Prefs.promptDismissed || isDefaultHome()) return
        showPrompt(
            "Make Perch your home screen",
            "Then the Home button and gesture always bring you back here, and app shortcuts appear when you long-press an icon.",
            "Set as default", { requestDefaultHome(this) },
            "Not now", { Prefs.promptDismissed = true },
        )
    }

    private fun showPrompt(title: String, body: String, primary: String, onPrimary: () -> Unit, secondary: String?, onSecondary: (() -> Unit)?) {
        prompt?.let { overlay.removeView(it) }
        val p = PromptCard(this, title, body, primary, onPrimary, secondary, onSecondary) { gone ->
            overlay.removeView(gone)
            if (prompt === gone) prompt = null
        }
        prompt = p
        overlay.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        positionOverlays()
        p.show()
    }

    private fun positionOverlays() {
        prompt?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(16.dp, 0, 16.dp, insets.bottom + 120.dp)
            it.layoutParams = lp
        }
        doneButton?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            lp.setMargins(0, insets.top + 10.dp, 18.dp, 0)
            it.layoutParams = lp
        }
    }

    // ------------------------------------------------------------ edit mode and drag-and-drop

    fun enterEdit() {
        if (editing) return
        editing = true
        if (drawerProgress > 0.01f) closeDrawer()
        home.setEditing(true)
        home.buzz(HapticFeedbackConstants.LONG_PRESS)
        val b = label(this, 14f, Fonts.semibold).apply {
            text = "Done"
            gravity = Gravity.CENTER
            setPadding(20.dp, 0, 20.dp, 0)
            isClickable = true
            contentDescription = "Done editing"
            setOnClickListener { exitEdit() }
        }
        b.background = Glass(b, 18.dpf, 0x40FFFFFF, 0x66FFFFFF, border = 0x59FFFFFF)
        b.springPress(0.94f)
        doneButton = b
        overlay.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 36.dp, Gravity.TOP or Gravity.END))
        positionOverlays()
        if (!Motion.reduced) {
            b.alpha = 0f
            b.animate().alpha(1f).setDuration(300).start()
        }
    }

    fun exitEdit() {
        if (!editing) return
        editing = false
        removeEmptyPages()
        normalize()
        save()
        home.setEditing(false)
        bindAll()
        home.pager.snapTo(min(home.pager.current, pages.size - 1))
        doneButton?.let { b ->
            doneButton = null
            if (Motion.reduced) overlay.removeView(b)
            else b.animate().alpha(0f).setDuration(200).withEndAction { overlay.removeView(b) }.start()
        }
    }

    private class Drag(
        val key: String,
        val floater: FloatIcon,
        val offX: Float,
        val offY: Float,
        val originDock: Boolean,
        val originPage: Int,
        val originIndex: Int,
    ) {
        var target: Target? = null
        var edgeDir = 0
        var x = 0f
        var y = 0f
    }

    private var drag: Drag? = null
    private var cancelSent = false

    override fun onIconDragStart(v: AppIconView, rawX: Float, rawY: Float) {
        val app = v.app ?: return
        menu?.dismiss(false)
        prompt?.dismiss()

        // Take the app out of the layout, remembering where it was.
        var originDock = false
        var originPage = -1
        var originIndex = dock.indexOf(app.key)
        if (originIndex >= 0) {
            originDock = true
            dock.removeAt(originIndex)
        } else {
            for ((pi, p) in pages.withIndex()) {
                val i = p.indexOf(app.key)
                if (i >= 0) {
                    originPage = pi; originIndex = i; p.removeAt(i); break
                }
            }
        }

        val r = v.iconRectOnScreen()
        val f = FloatIcon(this, app.icon, r.width())
        overlay.addView(f, FrameLayout.LayoutParams(f.size, f.size))
        f.placeAt(r)
        if (!Motion.reduced) f.animate().scaleX(1.12f).scaleY(1.12f).setDuration(300).setInterpolator(Ease.spring).start()
        val d = Drag(app.key, f, rawX - f.x, rawY - f.y, originDock, originPage, originIndex)
        d.x = rawX; d.y = rawY
        drag = d
        cancelSent = false

        if (v.place == Place.DRAWER || v.place == Place.SUGGESTED) closeDrawer()
        if (!editing) enterEdit()
        bindAll()
        updateTarget(rawX, rawY)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val d = drag ?: return super.dispatchTouchEvent(ev)
        if (!cancelSent) {
            cancelSent = true
            val c = MotionEvent.obtain(ev)
            c.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c)
            c.recycle()
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                d.floater.x = ev.rawX - d.offX
                d.floater.y = ev.rawY - d.offY
                updateTarget(ev.rawX, ev.rawY)
            }

            MotionEvent.ACTION_UP -> drop(true)
            MotionEvent.ACTION_CANCEL -> drop(false)
        }
        return true
    }

    private fun updateTarget(x: Float, y: Float) {
        val d = drag ?: return
        d.x = x; d.y = y
        val dir = home.edgeDir(x, y)
        if (dir != d.edgeDir) {
            d.edgeDir = dir
            handler.removeCallbacks(edgeFlip)
            if (dir != 0) handler.postDelayed(edgeFlip, 600)
        }
        val t = if (dir != 0) null else home.targetAt(x, y, pages, dock, DOCK_MAX)
        if (t != d.target) {
            d.target = t
            showPreview()
            if (t != null) home.buzz()
        }
    }

    private val edgeFlip = object : Runnable {
        override fun run() {
            val d = drag ?: return
            val next = home.pager.current + d.edgeDir
            if (next < 0) return
            if (next >= pages.size) {
                if (pages.last().isEmpty()) return
                pages.add(mutableListOf())
                showPreview()
            }
            home.pager.snapTo(next)
            home.buzz()
            handler.postDelayed(this, 900)
        }
    }

    private fun showPreview() {
        val t = drag?.target
        val pv: List<List<String?>> = pages.mapIndexed { i, p ->
            if (t != null && !t.dock && t.page == i) p.toMutableList<String?>().also { it.add(min(t.index, it.size), null) }
            else p
        }
        val dv: List<String?> =
            if (t != null && t.dock && !t.swap) dock.toMutableList<String?>().also { it.add(min(t.index, it.size), null) }
            else dock
        home.bind(pv, dv, repo.byKey)
    }

    private fun drop(commit: Boolean) {
        val d = drag ?: return
        drag = null
        handler.removeCallbacks(edgeFlip)
        val t = if (commit) d.target else null
        var placed = true
        when {
            t == null -> placed = restore(d.key, d)
            t.dock && t.swap -> {
                val i = t.index.coerceIn(0, dock.size - 1)
                val displaced = dock[i]
                dock[i] = d.key
                restore(displaced, d, fallbackPage = home.pager.current)
            }
            t.dock -> dock.add(min(t.index, dock.size), d.key)
            else -> {
                while (pages.size <= t.page) pages.add(mutableListOf())
                val p = pages[t.page]
                p.add(min(t.index, p.size), d.key)
            }
        }
        normalize()
        save()
        bindAll()
        home.buzz(HapticFeedbackConstants.VIRTUAL_KEY)

        val f = d.floater
        root.onNextPreDraw {
            val v = if (placed) home.viewFor(d.key) else null
            if (v == null || Motion.reduced) {
                if (v == null && !Motion.reduced) {
                    f.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(200)
                        .withEndAction { overlay.removeView(f) }.start()
                } else overlay.removeView(f)
                return@onNextPreDraw
            }
            v.alpha = 0f
            // Where the icon will settle (ignoring the glide it may be doing to get there).
            val r = v.iconRectOnScreen()
            r.offset(-v.translationX.toInt(), -v.translationY.toInt())
            val s = r.width().toFloat() / f.iconPx
            f.animate()
                .x(r.exactCenterX() - f.size / 2f)
                .y(r.exactCenterY() - f.size / 2f)
                .scaleX(s).scaleY(s)
                .setDuration(280).setInterpolator(Ease.out)
                .withEndAction {
                    v.alpha = 1f
                    overlay.removeView(f)
                }.start()
        }
    }

    /** Put [key] back where the dragged app came from. Returns false if it came from the drawer. */
    private fun restore(key: String, d: Drag, fallbackPage: Int = -1): Boolean {
        when {
            d.originDock -> dock.add(min(d.originIndex, dock.size), key)
            d.originPage >= 0 -> {
                val p = pages.getOrNull(d.originPage) ?: pages.last()
                p.add(min(d.originIndex, p.size), key)
            }
            fallbackPage >= 0 -> {
                while (pages.size <= fallbackPage) pages.add(mutableListOf())
                pages[fallbackPage].add(key)
            }
            else -> return false
        }
        return true
    }

    companion object {
        const val DOCK_MAX = 4
        private const val REQ_LOCATION = 11
        private const val REQ_CALENDAR = 12

        fun requestDefaultHome(a: Activity) {
            val rm = a.getSystemService(RoleManager::class.java)
            try {
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 41)
                    return
                }
            } catch (e: Exception) {
            }
            try {
                a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                a.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
