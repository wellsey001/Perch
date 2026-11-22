package com.wellsey.perch

import android.content.Context
import android.content.SharedPreferences

/** Everything Perch remembers. Home pages are stored as lines of app keys separated by "|". */
object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var counts: SharedPreferences

    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.applicationContext.getSharedPreferences("perch", Context.MODE_PRIVATE)
        counts = c.applicationContext.getSharedPreferences("perch_counts", Context.MODE_PRIVATE)
    }

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun setS(k: String, v: String) = sp.edit().putString(k, v).apply()

    // Switches
    var showSearch: Boolean
        get() = b("search", true)
        set(v) = setB("search", v)
    var showWidgets: Boolean
        get() = b("widgets", true)
        set(v) = setB("widgets", v)
    var glass: Boolean
        get() = b("glass", true)
        set(v) = setB("glass", v)
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = setB("haptics", v)

    // Grid
    var columns: Int
        get() = sp.getInt("columns", 4)
        set(v) = sp.edit().putInt("columns", v).apply()
    var rows: Int
        get() = sp.getInt("rows", 4)
        set(v) = sp.edit().putInt("rows", v).apply()

    // Gestures: "drawer" | "search" | "none"; "notifications" | "search" | "none"; "lock" | "none"
    var swipeUp: String
        get() = s("swipe_up", "drawer")
        set(v) = setS("swipe_up", v)
    var swipeDown: String
        get() = s("swipe_down", "notifications")
        set(v) = setS("swipe_down", v)
    var doubleTap: String
        get() = s("double_tap", "lock")
        set(v) = setS("double_tap", v)

    // Hidden apps
    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    // Home layout
    fun hasHome() = sp.contains("home")

    fun loadHome(): MutableList<MutableList<String>> {
        val raw = sp.getString("home", null) ?: return mutableListOf()
        return raw.split("\n").map { line -> line.split("|").filter { it.isNotEmpty() }.toMutableList() }.toMutableList()
    }

    fun saveHome(pages: List<List<String>>) = setS("home", pages.joinToString("\n") { it.joinToString("|") })

    fun loadDock(): MutableList<String> = s("dock", "").split("|").filter { it.isNotEmpty() }.toMutableList()

    fun saveDock(d: List<String>) = setS("dock", d.joinToString("|"))

    // Launch counts, for "Suggested" in the app drawer
    fun launchCount(key: String) = counts.getInt(key, 0)

    fun bumpLaunch(key: String) {
        val slot = "h${hourSlot()}:$key"
        counts.edit().putInt(key, counts.getInt(key, 0) + 1).putLong("t:$key", System.currentTimeMillis())
            .putInt(slot, counts.getInt(slot, 0) + 1).apply()
    }

    /** Launches of [key] in this part of the day (six 4-hour slots). */
    fun launchesNow(key: String) = counts.getInt("h${hourSlot()}:$key", 0)

    private fun hourSlot() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) / 4

    fun lastLaunch(key: String) = counts.getLong("t:$key", 0L)

    // Wallpaper: "system" (whatever the phone has) or "perch" (one Perch set and saved a copy of)
    var wallSource: String
        get() = s("wall_source", "system")
        set(v) = setS("wall_source", v)
    var wallPreset: String
        get() = s("wall_preset", "")
        set(v) = setS("wall_preset", v)
    var wallSetAt: Long
        get() = sp.getLong("wall_set_at", 0L)
        set(v) = sp.edit().putLong("wall_set_at", v).apply()

    var promptDismissed: Boolean
        get() = b("prompt_dismissed", false)
        set(v) = setB("prompt_dismissed", v)

    // Look
    var themedIcons: Boolean
        get() = b("themed_icons", false)
        set(v) = setB("themed_icons", v)
    var showLabels: Boolean
        get() = b("labels", true)
        set(v) = setB("labels", v)
    var glassTone: String // "auto" | "light" | "dark"
        get() = s("glass_tone", "auto")
        set(v) = setS("glass_tone", v)
    var parallax: Boolean
        get() = b("parallax", true)
        set(v) = setB("parallax", v)

    var tiltLight: Boolean
        get() = b("tilt_light", true)
        set(v) = setB("tilt_light", v)
    var mood: Boolean
        get() = b("mood", true)
        set(v) = setB("mood", v)
    var suggestRow: Boolean
        get() = b("suggest_row", true)
        set(v) = setB("suggest_row", v)

    // Photo frame widget: "immich" or "phone"
    var photoSource: String
        get() = s("photo_source", "immich")
        set(v) = setS("photo_source", v)
    var immichUrl: String
        get() = s("immich_url", "")
        set(v) = setS("immich_url", v.trim().trimEnd('/'))
    var immichKey: String
        get() = s("immich_key", "")
        set(v) = setS("immich_key", v.trim())

    /** The current accent, saved so Clack can match it. */
    var accent: Int
        get() = sp.getInt("accent", 0xFF7DD3FC.toInt())
        set(v) = sp.edit().putInt("accent", v).apply()

    // Home extras
    var glance: Boolean
        get() = b("glance", true)
        set(v) = setB("glance", v)
    var dots: Boolean
        get() = b("dots", true)
        set(v) = setB("dots", v)

    // More gestures
    var twoFingerDown: String // "search" | "notifications" | "quick_settings" | "none"
        get() = s("two_finger_down", "quick_settings")
        set(v) = setS("two_finger_down", v)
    var iconSwipeUp: String // "drawer" | "shortcuts"
        get() = s("icon_swipe_up", "drawer")
        set(v) = setS("icon_swipe_up", v)
    var pinchToEdit: Boolean
        get() = b("pinch_edit", true)
        set(v) = setB("pinch_edit", v)

    // Folders: one per line, "id<TAB>name<TAB>key|key|key"
    fun loadFolders(): MutableMap<String, Folder> {
        val out = LinkedHashMap<String, Folder>()
        for (line in s("folders", "").split("\n")) {
            val p = line.split("\t")
            if (p.size < 3 || p[0].isEmpty()) continue
            val f = Folder(p[0], p[1], p[2].split("|").filter { it.isNotEmpty() }.toMutableList())
            out[f.key] = f
        }
        return out
    }

    fun saveFolders(folders: Collection<Folder>) = setS("folders", folders.joinToString("\n") {
        "${it.id}\t${it.name.replace("\t", " ").replace("\n", " ")}\t${it.items.joinToString("|")}"
    })

    // Widgets in the widget panel: "id:heightDp,id:heightDp"
    fun loadWidgets(): MutableList<Pair<Int, Int>> = s("widgets", "").split(",").mapNotNull {
        val p = it.split(":")
        if (p.size == 2) p[0].toIntOrNull()?.let { id -> Pair(id, p[1].toIntOrNull() ?: 180) } else null
    }.toMutableList()

    fun saveWidgets(list: List<Pair<Int, Int>>) = setS("widgets", list.joinToString(",") { "${it.first}:${it.second}" })

    /** Bumped when a backup is restored, so the home screen reloads its layout. */
    var rev: Long
        get() = sp.getLong("rev", 0L)
        set(v) = sp.edit().putLong("rev", v).apply()

    val raw: SharedPreferences get() = sp

    var weather: String?
        get() = sp.getString("weather", null)
        set(v) = sp.edit().putString("weather", v).apply()
}

/** A home screen folder. Its key on the home screen is "folder:<id>". */
class Folder(val id: String, var name: String, val items: MutableList<String>) {
    val key: String get() = KEY_PREFIX + id

    companion object {
        const val KEY_PREFIX = "folder:"
        fun isFolder(key: String?) = key != null && key.startsWith(KEY_PREFIX)
    }
}
