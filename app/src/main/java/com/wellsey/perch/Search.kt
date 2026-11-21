package com.wellsey.perch

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** One non-app search result: a shortcut, contact, settings page or sum. */
class SearchResult(
    val title: String,
    val subtitle: String?,
    val glyph: String,
    val icon: android.graphics.drawable.Drawable? = null,
    val action: () -> Unit,
)

/** Settings pages you can jump to by name. */
object SettingsSearch {
    private class Page(val name: String, val words: String, val action: String)

    private val pages = listOf(
        Page("Wi-Fi", "wifi wireless internet network", Settings.ACTION_WIFI_SETTINGS),
        Page("Bluetooth", "bluetooth headphones pair", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page("Mobile network", "mobile data sim cellular roaming", Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        Page("Hotspot", "hotspot tethering share", "android.settings.TETHER_SETTINGS"),
        Page("Display", "display brightness screen dark mode", Settings.ACTION_DISPLAY_SETTINGS),
        Page("Sound and vibration", "sound volume ringtone vibration", Settings.ACTION_SOUND_SETTINGS),
        Page("Notifications", "notifications alerts", "android.settings.NOTIFICATION_SETTINGS"),
        Page("Do Not Disturb", "do not disturb dnd quiet focus", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        Page("Battery", "battery power saver charging", Intent.ACTION_POWER_USAGE_SUMMARY),
        Page("Apps", "apps applications manage default", Settings.ACTION_APPLICATION_SETTINGS),
        Page("Default apps", "default home browser apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Page("Storage", "storage space files", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page("Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page("Security", "security lock screen fingerprint face", Settings.ACTION_SECURITY_SETTINGS),
        Page("Privacy", "privacy permissions", Settings.ACTION_PRIVACY_SETTINGS),
        Page("Accessibility", "accessibility talkback magnify", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page("Date and time", "date time clock timezone", Settings.ACTION_DATE_SETTINGS),
        Page("Language and keyboard", "language keyboard input clack", Settings.ACTION_INPUT_METHOD_SETTINGS),
        Page("Wallpaper", "wallpaper background", Intent.ACTION_SET_WALLPAPER),
        Page("Developer options", "developer usb debugging", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Page("About phone", "about phone version android model", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page("NFC", "nfc tap pay contactless", Settings.ACTION_NFC_SETTINGS),
        Page("Airplane mode", "airplane flight mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Page("Settings", "settings system", Settings.ACTION_SETTINGS),
    )

    fun find(ctx: Context, q: String, start: (Intent) -> Unit): List<SearchResult> {
        val s = q.lowercase()
        if (s.length < 2) return emptyList()
        return pages.filter { p ->
            p.name.lowercase().startsWith(s) || p.words.split(' ').any { it.startsWith(s) }
        }.take(4).map { p ->
            SearchResult(p.name, "Settings", Glyphs.GEAR) { start(Intent(p.action)) }
        } + if ("perch".startsWith(s) || "launcher".startsWith(s) || "home".startsWith(s)) {
            listOf(SearchResult("Perch settings", "Perch", Glyphs.GEAR) { start(Intent(ctx, SettingsActivity::class.java)) })
        } else emptyList()
    }
}

/** People from your contacts, once you allow it. */
object ContactsSearch {
    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(ctx: Context, q: String, start: (Intent) -> Unit): List<SearchResult> {
        if (q.length < 2 || !hasPermission(ctx)) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        val out = ArrayList<SearchResult>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && out.size < 4) {
                    val id = c.getLong(0)
                    val lookup = c.getString(1)
                    val name = c.getString(2) ?: continue
                    val contactUri = ContactsContract.Contacts.getLookupUri(id, lookup)
                        ?: ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
                    out.add(SearchResult(name, "Contact", Glyphs.PERSON) { start(Intent(Intent.ACTION_VIEW, contactUri)) })
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}

/** Quick sums typed into search: 12*4, (3+4)/2, 2^10, 15% of 80. */
object Calc {
    fun eval(input: String): Double? {
        val s0 = input.replace('×', '*').replace('÷', '/').replace(',', '.').replace(" ", "").lowercase()
        // "15%of80" → 15/100*80
        val s = Regex("([0-9.]+)%of([0-9.]+)").replace(s0) { "(${it.groupValues[1]}/100*${it.groupValues[2]})" }
        if (s.isEmpty() || s.none { it in "+-*/^%" } || s.any { it !in "0123456789.+-*/^%()" }) return null
        return try {
            val p = Parser(s)
            val v = p.expr()
            if (p.pos != s.length || v.isNaN() || v.isInfinite()) null else v
        } catch (e: Exception) {
            null
        }
    }

    fun format(v: Double): String =
        if (abs(v - floor(v)) < 1e-9 && abs(v) < 1e15) floor(v).toLong().toString()
        else String.format(java.util.Locale.getDefault(), "%.6f", v).trimEnd('0').trimEnd('.', ',')

    private class Parser(val s: String) {
        var pos = 0
        private fun peek() = if (pos < s.length) s[pos] else '\u0000'

        fun expr(): Double {
            var v = term()
            while (true) {
                v = when (peek()) {
                    '+' -> { pos++; v + term() }
                    '-' -> { pos++; v - term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                v = when (peek()) {
                    '*' -> { pos++; v * power() }
                    '/' -> { pos++; v / power() }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val b = unary()
            if (peek() == '^') {
                pos++
                return b.pow(power())
            }
            return b
        }

        private fun unary(): Double {
            if (peek() == '-') {
                pos++; return -unary()
            }
            if (peek() == '+') {
                pos++; return unary()
            }
            var v = atom()
            if (peek() == '%') {
                pos++; v /= 100.0
            }
            return v
        }

        private fun atom(): Double {
            if (peek() == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException()
                pos++
                return v
            }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            if (start == pos) throw IllegalArgumentException()
            return s.substring(start, pos).toDouble()
        }
    }
}
