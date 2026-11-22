package com.wellsey.perch

import org.json.JSONArray
import org.json.JSONObject

/** Saves Perch's layout and settings to a JSON file, and puts them back. */
object Backup {
    private val skip = setOf("weather", "wall_source", "wall_set_at", "rev")

    fun export(): String {
        val all = JSONObject()
        for ((k, v) in Prefs.raw.all) {
            if (k in skip || v == null) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            all.put(k, o)
        }
        return JSONObject().put("app", "Perch").put("version", 1).put("prefs", all).toString(2)
    }

    fun import(text: String) {
        val root = JSONObject(text)
        require(root.optString("app") == "Perch")
        val prefs = root.getJSONObject("prefs")
        val e = Prefs.raw.edit()
        val keys = prefs.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in skip) continue
            val o = prefs.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                "set" -> {
                    val a = o.getJSONArray("v")
                    e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        // Android widget ids belong to this install of Perch, so those need adding again;
        // Perch's own widgets (negative ids) come back as they were.
        val w = prefs.optJSONObject("widgets")?.optString("v").orEmpty()
        e.putString("widgets", w.split(",").filter { it.startsWith("-") }.joinToString(","))
        e.apply()
        Prefs.rev = System.currentTimeMillis()
    }
}
