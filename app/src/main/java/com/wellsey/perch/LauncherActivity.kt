package com.wellsey.perch

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.app.WallpaperManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetHost
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
    private var folders: MutableMap<String, Folder> = LinkedHashMap()
    private var modelLoaded = false
    private var loadedRev = 0L

    private var folderView: FolderOverlay? = null
    private var sheet: GlassSheet? = null
    private var lastLaunchKey: String? = null
    private var iconMode: Pair<Int, Int>? = null
    private lateinit var glance: Glance

    // Android widgets
    lateinit var widgetHost: AppWidgetHost
        private set
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetPanel: WidgetPanel
    var widgets: MutableList<Pair<Int, Int>> = mutableListOf()
        private set
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
    var widgetProgress = 0f
        private set
    private var widgetAnim: ValueAnimator? = null
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
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        widgets = Prefs.loadWidgets()
        home = HomeView(this)
        widgetPanel = WidgetPanel(this)
        drawer = DrawerView(this)
        overlay = FrameLayout(this)
        stage.addView(home, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        stage.addView(widgetPanel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
            widgetPanel.applyInsets(bars)
            positionOverlays()
            wi
        }

        home.applySettings()
        home.pager.onPull = { pull -> if (editing) false else { setWidgetProgress((pull / (home.width * 0.7f)).coerceIn(0f, 1f)); true } }
        home.pager.onPullEnd = { _, vx -> endWidgetDrag(vx, false) }
        home.pager.onScroll = { pos ->
            home.dots.position = pos
            panWallpaper(pos)
        }
        widgetPanel.rebuild()

        Theme.update(this)
        iconMode = if (Prefs.themedIcons) Theme.iconColors() else null
        repo.themeColors = iconMode
        repo.onChanged = { onAppsChanged() }
        repo.start()
        loadedRev = Prefs.rev

        glance = Glance(this) { home.showGlance(it) }
        Notifier.addListener(notifListener)

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
        Notifier.removeListener(notifListener)
        try {
            WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener)
            unregisterReceiver(timeReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
        }
        glance.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.applySettings()
        // A restored backup: reload everything from settings.
        if (Prefs.rev != loadedRev) {
            loadedRev = Prefs.rev
            modelLoaded = false
            widgets = Prefs.loadWidgets()
            widgetPanel.rebuild()
            if (repo.loaded) onAppsChanged()
        }
        // Themed icons switched on or off, or the wallpaper colour changed.
        val mode = if (Prefs.themedIcons) Theme.iconColors() else null
        if (mode != iconMode) {
            iconMode = mode
            repo.themeColors = mode
            repo.load(null)
        }
        if (modelLoaded) {
            normalize()
            bindAll()
        }
        glance.connectMedia()
        glance.refresh()
        returnToIcon()
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
        folderView?.close(false)
        sheet?.close()
        if (drawer.isShown || drawerProgress > 0f) closeDrawer(false)
        if (widgetProgress > 0f) closeWidgets(false)
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
        }
        glance.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            val wasHere = resumed
            when {
                sheet != null -> sheet?.close()
                menu != null -> menu?.dismiss(wasHere)
                folderView != null -> folderView?.close(wasHere)
                drawerProgress > 0.01f -> closeDrawer(wasHere)
                widgetProgress > 0.01f -> closeWidgets(wasHere)
                editing -> exitEdit()
                wasHere -> home.pager.snapTo(0)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheet != null -> sheet?.close()
            prompt != null -> prompt?.dismiss()
            menu != null -> menu?.dismiss(true)
            folderView != null -> folderView?.close(true)
            drawerProgress > 0.01f -> closeDrawer()
            widgetProgress > 0.01f -> closeWidgets()
            editing -> exitEdit()
            else -> {} // Home stays put
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Theme.update(this) // dark mode changed: retune the glass
        root.invalidateDeep()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setWallpaperOffsets()
    }

    private fun setWallpaperOffsets() {
        panWallpaper(home.pager.current.toFloat())
    }

    /** Glide the wallpaper as the pages move (if it's wider than the screen). */
    private fun panWallpaper(pos: Float) {
        val token = window.decorView.windowToken ?: return
        val n = pages.size
        val x = if (Prefs.parallax && n > 1) (pos / (n - 1)).coerceIn(0f, 1f) else 0.5f
        try {
            val wm = WallpaperManager.getInstance(this)
            wm.setWallpaperOffsetSteps(if (n > 1) 1f / (n - 1) else 0f, 0f)
            wm.setWallpaperOffsets(token, x, 0.5f)
        } catch (e: Exception) {
        }
        Backdrop.setPan(x)
    }

    /** Coming back from an app: its icon settles back into place. */
    private fun returnToIcon() {
        val key = lastLaunchKey
        lastLaunchKey = null
        if (!cameFromApp || firstShow || Motion.reduced) return
        val v = key?.let { home.viewFor(it) } ?: return
        v.scaleX = 1.35f
        v.scaleY = 1.35f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(Ease.spring).start()
    }

    // ------------------------------------------------------------ wallpaper and time

    private val backdropListener: () -> Unit = {
        root.invalidateDeep()
        val mode = if (Prefs.themedIcons) Theme.iconColors() else null
        if (mode != iconMode) {
            iconMode = mode
            repo.themeColors = mode
            repo.load(null)
        }
    }
    private val notifListener: () -> Unit = { home.invalidateDeep(); drawer.invalidateDeep() }

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
            folders = Prefs.loadFolders()
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
        for (f in folders.values) f.items.removeAll { it !in known || it in hidden }
        fun gone(k: String) = if (Folder.isFolder(k)) folders[k] == null else k !in known || k in hidden
        for (p in pages) p.removeAll { gone(it) }
        dock.removeAll { gone(it) }
        tidyFolders()
    }

    /** Folders with one app left turn back into that app; empty ones disappear. */
    private fun tidyFolders() {
        for (f in folders.values.toList()) {
            if (f.items.size >= 2) continue
            val replacement = f.items.firstOrNull()
            fun swap(list: MutableList<String>) {
                val i = list.indexOf(f.key)
                if (i >= 0) {
                    if (replacement != null && !isOnHome(replacement)) list[i] = replacement else list.removeAt(i)
                }
            }
            swap(dock)
            for (p in pages) swap(p)
            folders.remove(f.key)
        }
        // Folders nothing points at any more.
        val used = (dock + pages.flatten()).toSet()
        folders.keys.retainAll(used)
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
        Prefs.saveFolders(folders.values)
    }

    private fun bindAll() {
        home.bind(pages, dock, repo.byKey, folders)
    }

    fun dockKeys(): List<String> = dock

    private fun isOnHome(key: String) = key in dock || pages.any { key in it } || folders.values.any { key in it.items }

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
        for (f in folders.values) f.items.remove(key)
        folders.remove(key)
        tidyFolders()
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
            lastLaunchKey = icon?.takeIf { it.place == Place.HOME || it.place == Place.DOCK }?.let { app.key }
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

    fun startExternal(i: Intent) {
        if (!startSafely(i)) toast("Couldn't open that")
    }

    fun requestContacts() {
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
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
        if (v.folder != null) showFolderMenu(v) else showAppMenu(v)
    }

    override fun onIconRemove(v: AppIconView) {
        val key = v.itemKey ?: return
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
        if (v.place == Place.FOLDER) {
            actions += MenuEntry("Remove from folder", Glyphs.UNGROUP) { removeFromFolder(app.key) }
        } else if (v.place == Place.HOME || v.place == Place.DOCK) {
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

    private fun showFolderMenu(v: AppIconView) {
        val f = v.folder ?: return
        menu?.dismiss(false)
        val items = listOf(
            MenuEntry("Rename folder", Glyphs.PEN) { openFolder(v, rename = true) },
            MenuEntry("Edit home screen", Glyphs.GRID) { enterEdit() },
            MenuEntry("Ungroup", Glyphs.UNGROUP) { ungroup(f) },
        )
        val m = MenuOverlay(this, v, 0f, 0f, listOf(items))
        menu = m
        overlay.addView(m, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        m.show()
    }

    fun showHomeMenu(x: Float, y: Float) {
        if (menu != null || drawerProgress > 0.01f) return
        home.buzz(HapticFeedbackConstants.LONG_PRESS)
        val items = listOf(
            MenuEntry("Edit home screen", Glyphs.GRID) { enterEdit() },
            MenuEntry("Widgets", Glyphs.WIDGETS) { openWidgets() },
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

    fun setStageBlur(amount: Float) {
        // An open folder keeps the home screen blurred underneath a menu.
        val f = if (folderView != null && menu != null) 1f else amount
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
        if (requestCode == REQ_CONTACTS) drawer.refresh() else refreshWidgets(true)
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
        val originFolder: Folder? = null,
    ) {
        var target: Target? = null
        var edgeDir = 0
        var x = 0f
        var y = 0f
        var mergeCandidate: AppIconView? = null
        var merging = false
    }

    private var drag: Drag? = null
    private var cancelSent = false

    override fun onIconDragStart(v: AppIconView, rawX: Float, rawY: Float) {
        val key = v.itemKey ?: return
        val r = v.iconRectOnScreen()
        val bmp = v.snapshot()
        menu?.dismiss(false)
        prompt?.dismiss()

        // Take the item out of the layout, remembering where it was.
        var originDock = false
        var originPage = -1
        var originFolder: Folder? = null
        var originIndex = -1
        if (v.place == Place.FOLDER) {
            originFolder = folderView?.folder
            originIndex = originFolder?.items?.indexOf(key) ?: -1
            originFolder?.items?.remove(key)
            // Close it after this touch event: removing the view mid-dispatch is unsafe.
            val fv = folderView
            fv?.alpha = 0f
            root.post { fv?.close(false) }
        } else {
            originIndex = dock.indexOf(key)
            if (originIndex >= 0) {
                originDock = true
                dock.removeAt(originIndex)
            } else {
                for ((pi, p) in pages.withIndex()) {
                    val i = p.indexOf(key)
                    if (i >= 0) {
                        originPage = pi; originIndex = i; p.removeAt(i); break
                    }
                }
            }
        }

        val f = FloatIcon(this, bmp, r.width())
        overlay.addView(f, FrameLayout.LayoutParams(f.size, f.size))
        f.placeAt(r)
        if (!Motion.reduced) f.animate().scaleX(1.12f).scaleY(1.12f).setDuration(300).setInterpolator(Ease.spring).start()
        val d = Drag(key, f, rawX - f.x, rawY - f.y, originDock, originPage, originIndex, originFolder)
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

    private val mergeArm = Runnable {
        val d = drag
        val c = d?.mergeCandidate
        if (d != null && c != null) {
            d.merging = true
            c.mergeHover = true
            home.buzz(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun clearMerge(d: Drag) {
        handler.removeCallbacks(mergeArm)
        d.mergeCandidate?.mergeHover = false
        d.mergeCandidate = null
        d.merging = false
    }

    private fun updateTarget(x: Float, y: Float) {
        val d = drag ?: return
        d.x = x; d.y = y
        // Hovering right over another icon: after a moment, dropping makes (or adds to) a folder.
        if (!Folder.isFolder(d.key)) {
            val hover = home.iconNearCenter(x, y)
            if (hover != null && hover.itemKey != d.key) {
                if (d.mergeCandidate !== hover) {
                    clearMerge(d)
                    d.mergeCandidate = hover
                    handler.postDelayed(mergeArm, 380)
                }
                return
            }
            if (d.mergeCandidate != null) clearMerge(d)
        }
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
        home.bind(pv, dv, repo.byKey, folders)
    }

    private fun drop(commit: Boolean) {
        val d = drag ?: return
        drag = null
        handler.removeCallbacks(edgeFlip)
        val mergeInto = if (commit && d.merging) d.mergeCandidate?.itemKey else null
        clearMerge(d)
        val t = if (commit) d.target else null
        var placed = true
        var landKey = d.key
        when {
            mergeInto != null -> landKey = mergeInto(mergeInto, d.key)
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
        tidyFolders()
        normalize()
        save()
        bindAll()
        drawer.refresh()
        home.buzz(HapticFeedbackConstants.VIRTUAL_KEY)

        val f = d.floater
        root.onNextPreDraw {
            val v = if (placed) home.viewFor(landKey) else null
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
        val of = d.originFolder
        when {
            of != null && folders.containsKey(of.key) && key == d.key -> of.items.add(min(d.originIndex.coerceAtLeast(0), of.items.size), key)
            of != null -> pages[home.pager.current.coerceIn(0, pages.size - 1)].add(key) // its folder dissolved
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

    // ------------------------------------------------------------ folders

    /** Drop [dragged] onto [target]: make a folder of the two, or add to the folder. Returns the folder's key. */
    private fun mergeInto(target: String, dragged: String): String {
        val existing = folders[target]
        if (existing != null) {
            if (dragged !in existing.items) existing.items.add(dragged)
            return existing.key
        }
        val id = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val f = Folder(id, folderName(target, dragged), mutableListOf(target, dragged))
        folders[f.key] = f
        val di = dock.indexOf(target)
        if (di >= 0) dock[di] = f.key
        else for (p in pages) {
            val i = p.indexOf(target)
            if (i >= 0) {
                p[i] = f.key; break
            }
        }
        return f.key
    }

    /** A sensible first name from what the two apps are (Games, Social…). */
    private fun folderName(a: String, b: String): String {
        fun cat(k: String): Int {
            val app = repo.byKey[k] ?: return -1
            return try {
                packageManager.getApplicationInfo(app.packageName, 0).category
            } catch (e: Exception) {
                -1
            }
        }
        val c = cat(a)
        if (c >= 0 && c == cat(b)) {
            val t = android.content.pm.ApplicationInfo.getCategoryTitle(this, c)
            if (!t.isNullOrBlank()) return t.toString()
        }
        return "Folder"
    }

    override fun onFolderClick(v: AppIconView) {
        if (editing) return
        v.buzz(HapticFeedbackConstants.VIRTUAL_KEY)
        openFolder(v, rename = false)
    }

    private fun openFolder(v: AppIconView, rename: Boolean) {
        val f = v.folder ?: return
        folderView?.close(false)
        val o = FolderOverlay(this, f, v.iconRectOnScreen(), repo.byKey, rename)
        folderView = o
        overlay.addView(o, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        o.show()
    }

    fun onFolderClosed(o: FolderOverlay) {
        overlay.removeView(o)
        if (folderView === o) folderView = null
        tidyFolders()
        save()
        bindAll()
    }

    fun renameFolder(f: Folder, name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n != f.name) {
            f.name = n
            save()
        }
    }

    private fun ungroup(f: Folder) {
        val key = f.key
        fun spill(list: MutableList<String>): Boolean {
            val i = list.indexOf(key)
            if (i < 0) return false
            list.removeAt(i)
            list.addAll(i, f.items.filter { !isOnHomeExcept(it, key) })
            return true
        }
        if (!spill(dock)) for (p in pages) if (spill(p)) break
        folders.remove(key)
        normalize()
        save()
        bindAll()
    }

    private fun isOnHomeExcept(k: String, folderKey: String) =
        k in dock || pages.any { k in it } || folders.values.any { it.key != folderKey && k in it.items }

    private fun removeFromFolder(key: String) {
        val f = folders.values.firstOrNull { key in it.items } ?: return
        f.items.remove(key)
        // The app goes back on the home screen right after the folder.
        val p = pages.firstOrNull { f.key in it }
        if (p != null) p.add(p.indexOf(f.key) + 1, key) else addToHome(key)
        tidyFolders()
        normalize()
        save()
        bindAll()
    }

    // ------------------------------------------------------------ two-finger swipe

    fun onTwoFingerSwipeDown() {
        home.buzz()
        when (Prefs.twoFingerDown) {
            "search" -> openDrawer(focusSearch = true)
            "notifications" -> expandNotifications()
            "quick_settings" -> expandQuickSettings()
        }
    }

    private fun expandQuickSettings() {
        try {
            val sbs = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(sbs)
        } catch (e: Throwable) {
            expandNotifications()
        }
    }

    // ------------------------------------------------------------ Android widgets

    fun createWidgetView(id: Int): AppWidgetHostView? {
        val info = widgetManager.getAppWidgetInfo(id) ?: return null
        return try {
            widgetHost.createView(this, id, info).also { it.setPadding(0, 0, 0, 0) }
        } catch (e: Exception) {
            null
        }
    }

    fun setWidgetProgress(p: Float) {
        widgetProgress = p
        widgetPanel.setProgress(p)
        if (drawerProgress > 0.01f) return
        val q = p.coerceIn(0f, 1f)
        val s = 1f - 0.08f * q
        home.scaleX = s
        home.scaleY = s
        home.alpha = 1f - 0.6f * q
        home.translationX = 40.dpf * q
        val r = 12.dpf * q
        if (Prefs.glass && r > 0.5f) home.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        else home.setRenderEffect(null)
    }

    private fun animateWidgets(to: Float) {
        widgetAnim?.cancel()
        if (Motion.reduced) {
            setWidgetProgress(to); return
        }
        widgetAnim = ValueAnimator.ofFloat(widgetProgress, to).apply {
            duration = if (to > widgetProgress) 520 else 380
            interpolator = if (to > widgetProgress) Ease.sheet else Ease.out
            addUpdateListener { setWidgetProgress(it.animatedValue as Float) }
            start()
        }
    }

    fun openWidgets() {
        if (editing) exitEdit()
        widgetPanel.rebuild()
        animateWidgets(1f)
    }

    fun closeWidgets(animate: Boolean = true) {
        if (animate) animateWidgets(0f) else {
            widgetAnim?.cancel(); setWidgetProgress(0f)
        }
    }

    /** [fromPanel]: the drag started on the open panel (closing) rather than on home (opening). */
    fun endWidgetDrag(vx: Float, fromPanel: Boolean) {
        val open = if (fromPanel) !(vx < -700 || widgetProgress < 0.7f) else (vx > 700 || widgetProgress > 0.3f)
        if (open && widgetProgress < 1f && !fromPanel) widgetPanel.rebuild()
        animateWidgets(if (open) 1f else 0f)
    }

    fun pickWidget() {
        val providers = try {
            widgetManager.installedProviders
        } catch (e: Exception) {
            emptyList()
        }
        val density = resources.displayMetrics.densityDpi
        val pm = packageManager
        val s = openSheet("Add a widget")
        if (providers.isEmpty()) s.message("No apps on this phone offer widgets.")
        val appName = { p: AppWidgetProviderInfo ->
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString()
            } catch (e: Exception) {
                p.provider.packageName
            }
        }
        for (p in providers.sortedWith(compareBy({ appName(it).lowercase() }, { it.loadLabel(pm).lowercase() }))) {
            val icon = try {
                p.loadIcon(this, density)
            } catch (e: Exception) {
                null
            }
            val dm = resources.displayMetrics.density
            val size = "${(p.minWidth / dm).toInt()} × ${(p.minHeight / dm).toInt()}"
            s.row(Glyphs.WIDGETS, icon, p.loadLabel(pm), "${appName(p)} · $size") { addWidget(p) }
        }
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = Pair(id, info)
        val bound = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            configureOrAdd(id, info)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            try {
                startActivityForResult(i, REQ_BIND)
            } catch (e: Exception) {
                widgetHost.deleteAppWidgetId(id)
                toast("Couldn't add that widget")
            }
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        val optional = (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !optional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
            }
        }
        finishAddWidget(id, info)
    }

    private fun finishAddWidget(id: Int, info: AppWidgetProviderInfo) {
        pendingWidget = null
        val dm = resources.displayMetrics.density
        val h = (info.minHeight / dm).toInt().coerceIn(110, 360) + 16
        widgets.add(Pair(id, h))
        Prefs.saveWidgets(widgets)
        widgetPanel.rebuild()
        if (widgetProgress < 0.5f) openWidgets()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val p = pendingWidget ?: return
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureOrAdd(p.first, p.second) else {
                widgetHost.deleteAppWidgetId(p.first); pendingWidget = null
            }
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) finishAddWidget(p.first, p.second) else {
                widgetHost.deleteAppWidgetId(p.first); pendingWidget = null
            }
        }
    }

    fun showWidgetMenu(frame: WidgetFrame) {
        val i = widgets.indexOfFirst { it.first == frame.widgetId }
        if (i < 0) return
        val r = Rect()
        frame.getGlobalVisibleRect(r)
        fun resize(by: Int) {
            val (id, h) = widgets[i]
            widgets[i] = Pair(id, (h + by).coerceIn(90, 560))
            Prefs.saveWidgets(widgets)
            widgetPanel.rebuild()
        }
        fun move(by: Int) {
            val j = (i + by).coerceIn(0, widgets.size - 1)
            if (j == i) return
            val w = widgets.removeAt(i)
            widgets.add(j, w)
            Prefs.saveWidgets(widgets)
            widgetPanel.rebuild()
        }
        val items = ArrayList<MenuEntry>()
        items += MenuEntry("Taller", Glyphs.CHEVRON_DOWN) { resize(60) }
        items += MenuEntry("Shorter", Glyphs.CHEVRON_UP) { resize(-60) }
        if (i > 0) items += MenuEntry("Move up", Glyphs.ARROW_UP) { move(-1) }
        if (i < widgets.size - 1) items += MenuEntry("Move down", Glyphs.ARROW_DOWN) { move(1) }
        items += MenuEntry("Remove widget", Glyphs.TRASH, danger = true) {
            widgetHost.deleteAppWidgetId(frame.widgetId)
            widgets.removeAt(i)
            Prefs.saveWidgets(widgets)
            widgetPanel.rebuild()
        }
        menu?.dismiss(false)
        val m = MenuOverlay(this, null, r.exactCenterX(), r.exactCenterY(), listOf(items))
        menu = m
        overlay.addView(m, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        m.show()
    }

    // ------------------------------------------------------------ sheets

    fun openSheet(title: String): GlassSheet {
        sheet?.let { overlay.removeView(it) }
        val s = GlassSheet(this, title, insets.bottom) { gone ->
            overlay.removeView(gone)
            if (sheet === gone) sheet = null
        }
        sheet = s
        overlay.addView(s, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        s.show()
        return s
    }

    companion object {
        const val DOCK_MAX = 4
        private const val WIDGET_HOST_ID = 2048
        private const val REQ_BIND = 31
        private const val REQ_CONFIGURE = 32
        private const val REQ_LOCATION = 11
        private const val REQ_CALENDAR = 12
        private const val REQ_CONTACTS = 13

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
