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
        counts.edit().putInt(key, counts.getInt(key, 0) + 1).putLong("t:$key", System.currentTimeMillis()).apply()
    }

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

    var weather: String?
        get() = sp.getString("weather", null)
        set(v) = sp.edit().putString("weather", v).apply()
}
