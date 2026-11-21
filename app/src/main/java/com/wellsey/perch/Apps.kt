package com.wellsey.perch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Path
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.math.max

class AppInfo(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val isSystem: Boolean,
    val icon: Bitmap,
) {
    val packageName: String get() = component.packageName
}

/** Every launchable app on the phone (and in a work profile), kept up to date as apps come and go. */
class AppRepo(context: Context) {
    private val ctx = context.applicationContext
    val launcherApps: LauncherApps = ctx.getSystemService(LauncherApps::class.java)
    private val userManager: UserManager = ctx.getSystemService(UserManager::class.java)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Bitmap>() // only touched on exec
    private val iconPx = (76 * ctx.resources.displayMetrics.density).toInt()

    var apps: List<AppInfo> = emptyList(); private set
    var byKey: Map<String, AppInfo> = emptyMap(); private set
    var loaded = false; private set
    var onChanged: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = load(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = load(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = load(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = load(null)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = load(null)
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        load(null)
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
    }

    fun load(dropPackage: String?) {
        exec.execute {
            if (dropPackage != null) iconCache.keys.removeAll { it.startsWith("$dropPackage/") }
            val list = query()
            main.post {
                apps = list
                byKey = list.associateBy { it.key }
                loaded = true
                onChanged?.invoke()
            }
        }
    }

    private fun query(): List<AppInfo> {
        val out = ArrayList<AppInfo>()
        val density = ctx.resources.displayMetrics.densityDpi
        val me = Process.myUserHandle()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val activities: List<LauncherActivityInfo> = try {
                launcherApps.getActivityList(null, user)
            } catch (e: Exception) {
                emptyList()
            }
            for (info in activities) {
                val cn = info.componentName
                val key = "${cn.flattenToShortString()}#$serial"
                val sys = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val icon = iconCache.getOrPut(key) { makeIcon(info, density, user == me) }
                out.add(AppInfo(key, info.label?.toString() ?: cn.packageName, cn, user, sys, icon))
            }
        }
        val collator = Collator.getInstance()
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return out
    }

    private fun makeIcon(info: LauncherActivityInfo, density: Int, personal: Boolean): Bitmap {
        val d: Drawable = try {
            if (personal) info.getIcon(density) else info.getBadgedIcon(density)
        } catch (e: Exception) {
            null
        } ?: ctx.packageManager.defaultActivityIcon
        return IconMaker.make(d, iconPx)
    }

    /** The app's own shortcuts (static ones first). Only works once Perch is the default home app. */
    fun shortcuts(app: AppInfo): List<ShortcutInfo> {
        return try {
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            (launcherApps.getShortcuts(q, app.user) ?: emptyList())
                .filter { it.isEnabled }
                .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
                .take(4)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The personal-profile app that handles [intent], e.g. the default dialer. */
    fun appFor(intent: Intent): AppInfo? {
        val pm = ctx.packageManager
        val ri = try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            null
        } ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        if (pkg == "android") return null // the "choose an app" screen
        val me = Process.myUserHandle()
        return apps.firstOrNull { it.packageName == pkg && it.user == me }
    }

    fun appByLabel(label: String): AppInfo? {
        val me = Process.myUserHandle()
        return apps.firstOrNull { it.user == me && it.label.equals(label, ignoreCase = true) }
    }
}

/**
 * Turns any app icon into a glossy, colourful tile: an iOS-style squircle with light falling from
 * the top, a soft sheen across the upper half and a crisp rim, so it reads as a raised object.
 */
object IconMaker {
    const val RADIUS = 0.30f // roughly the squircle's corner, for things drawn around icons

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val atop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shapes = HashMap<Long, Path>()

    /**
     * A superellipse (|x|^5 + |y|^5 = 1), the "continuous corner" shape iOS uses, filling (0,0)-(w,h).
     * Cached by size; callers must not change the returned path.
     */
    @Synchronized
    fun shape(w: Float, h: Float = w): Path = shapes.getOrPut((w.toRawBits().toLong() shl 32) or h.toRawBits().toLong()) {
        val p = Path()
        val n = 5.0
        val steps = 96
        for (i in 0..steps) {
            val t = 2 * Math.PI * i / steps
            val c = Math.cos(t)
            val sn = Math.sin(t)
            val x = Math.signum(c) * Math.pow(Math.abs(c), 2 / n)
            val y = Math.signum(sn) * Math.pow(Math.abs(sn), 2 / n)
            val px = (w / 2f + x * w / 2f).toFloat()
            val py = (h / 2f + y * h / 2f).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        p
    }

    fun make(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val shape = shape(s)
        c.saveLayer(0f, 0f, s, s, null)
        if (d is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp with a 72dp visible centre: draw them 50% larger and centred.
            val inset = size / 4
            d.background?.let { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(c) }
            d.foreground?.let { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(c) }
        } else if (isFullBleed(d)) {
            d.setBounds(0, 0, size, size)
            d.draw(c)
        } else {
            // Round or oddly shaped legacy icons sit on a soft white tile, like iOS.
            atop.xfermode = null
            atop.shader = LinearGradient(0f, 0f, 0f, s, 0xFFFFFFFF.toInt(), 0xFFE9ECF2.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s, s, atop)
            atop.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            val pad = (size * 0.15f).toInt()
            d.setBounds(pad, pad, size - pad, size - pad)
            d.draw(c)
        }
        c.drawPath(shape, maskPaint)

        // Light from above: brighter top, gently darker bottom.
        atop.shader = LinearGradient(
            0f, 0f, 0f, s,
            intArrayOf(0x38FFFFFF, 0x00FFFFFF, 0x00000000, 0x33000000),
            floatArrayOf(0f, 0.42f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, s, s, atop)

        // A curved glossy sheen over the upper half.
        atop.shader = LinearGradient(0f, 0f, 0f, s * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(-s * 0.25f, -s * 0.62f, s * 1.25f, s * 0.5f, atop)

        // Inner bottom shade so the tile looks thick.
        atop.shader = RadialGradient(s / 2f, s * 1.08f, s * 0.62f, 0x26000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, atop)
        atop.shader = null
        c.restore()

        // Rim: bright along the top edge, a faint dark line along the bottom.
        val sw = max(1f, s / 62f * 1.6f)
        rimPaint.strokeWidth = sw
        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, s,
            intArrayOf(0x99FFFFFF.toInt(), 0x1FFFFFFF, 0x00FFFFFF, 0x2E000000),
            floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP,
        )
        c.save()
        c.clipPath(shape)
        c.drawPath(shape, rimPaint) // half the stroke lands inside: a crisp inner rim
        c.restore()
        return bmp
    }

    private fun isFullBleed(d: Drawable): Boolean {
        val n = 40
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, n, n)
        d.draw(c)
        var full = true
        for (x in intArrayOf(4, 35)) for (y in intArrayOf(4, 35)) {
            if (Color.alpha(b.getPixel(x, y)) < 220) full = false
        }
        b.recycle()
        return full
    }
}
