package com.wellsey.perch

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// ---------------------------------------------------------------- weather

data class Weather(
    val temp: Double, val code: Int, val isDay: Boolean,
    val hi: Double, val lo: Double, val place: String, val at: Long,
) {
    fun encode() = listOf(temp, code, if (isDay) 1 else 0, hi, lo, place.replace("|", " "), at).joinToString("|")

    companion object {
        fun decode(s: String?): Weather? = try {
            val p = s!!.split("|")
            Weather(p[0].toDouble(), p[1].toInt(), p[2] == "1", p[3].toDouble(), p[4].toDouble(), p[5], p[6].toLong())
        } catch (e: Exception) {
            null
        }

        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67 -> "Rain"
            in 71..77 -> "Snow"
            in 80..82 -> "Showers"
            85, 86 -> "Snow showers"
            in 95..99 -> "Thunderstorm"
            else -> "—"
        }

        fun glyph(code: Int, day: Boolean): String = when (code) {
            0, 1 -> if (day) Glyphs.SUN else Glyphs.MOON
            2 -> Glyphs.PARTLY
            45, 48 -> Glyphs.FOG
            in 51..67, in 80..82 -> Glyphs.RAIN
            in 71..77, 85, 86 -> Glyphs.SNOW
            in 95..99 -> Glyphs.STORM
            else -> Glyphs.CLOUD
        }
    }
}

/** Weather from Open-Meteo (free, no account) for your rough location. Cached for 30 minutes. */
object WeatherSource {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    fun cached(): Weather? = Weather.decode(Prefs.weather)

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refresh(ctx: Context, force: Boolean, done: (Weather?) -> Unit) {
        val c = cached()
        if (!force && c != null && System.currentTimeMillis() - c.at < 30 * 60_000L) {
            done(c); return
        }
        if (busy || !hasPermission(ctx)) {
            done(c); return
        }
        busy = true
        val app = ctx.applicationContext
        locate(app) { loc ->
            if (loc == null) {
                busy = false
                done(c)
            } else {
                exec.execute {
                    val w = try {
                        fetch(app, loc.latitude, loc.longitude)
                    } catch (e: Exception) {
                        null
                    }
                    if (w != null) Prefs.weather = w.encode()
                    main.post {
                        busy = false
                        done(w ?: c)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun locate(ctx: Context, cb: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(
            LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER,
        )
        val best = providers.mapNotNull {
            try {
                lm.getLastKnownLocation(it)
            } catch (e: Exception) {
                null
            }
        }.maxByOrNull { it.time }
        if (best != null && System.currentTimeMillis() - best.time < 3 * 3600_000L) {
            cb(best); return
        }
        val provider = providers.dropLast(1).firstOrNull {
            try {
                lm.isProviderEnabled(it)
            } catch (e: Exception) {
                false
            }
        }
        if (provider == null) {
            cb(best); return
        }
        try {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { l -> cb(l ?: best) }
        } catch (e: Exception) {
            cb(best)
        }
    }

    private fun fetch(ctx: Context, lat: Double, lon: Double): Weather {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                lat, lon,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val j = JSONObject(body)
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        return Weather(
            temp = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            hi = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lo = daily.getJSONArray("temperature_2m_min").getDouble(0),
            place = placeName(ctx, lat, lon),
            at = System.currentTimeMillis(),
        )
    }

    private fun placeName(ctx: Context, lat: Double, lon: Double): String = try {
        @Suppress("DEPRECATION")
        val list = Geocoder(ctx).getFromLocation(lat, lon, 1)
        list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea } ?: ""
    } catch (e: Exception) {
        ""
    }
}

// ---------------------------------------------------------------- calendar

data class NextEvent(
    val id: Long, val title: String, val begin: Long, val end: Long,
    val allDay: Boolean, val color: Int,
)

/** The next thing in your calendars over the coming week. */
object CalendarSource {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val DAY = 24 * 3600_000L

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun next(ctx: Context, done: (NextEvent?) -> Unit) {
        if (!hasPermission(ctx)) {
            done(null); return
        }
        val app = ctx.applicationContext
        exec.execute {
            val e = try {
                query(app)
            } catch (ex: Exception) {
                null
            }
            main.post { done(e) }
        }
    }

    private fun query(ctx: Context): NextEvent? {
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - DAY)
        ContentUris.appendId(b, now + 7 * DAY)
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val today = LocalDate.now()
        ctx.contentResolver.query(
            b.build(), proj, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            var allDayPick: NextEvent? = null
            while (c.moveToNext()) {
                if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(4) == 1
                val begin = c.getLong(2)
                val end = c.getLong(3)
                val ev = NextEvent(c.getLong(0), c.getString(1) ?: "(No title)", begin, end, allDay, c.getInt(5))
                if (allDay) {
                    // All-day times are midnight UTC: compare dates, not instants.
                    val lastDay = Instant.ofEpochMilli(end - 1).atZone(ZoneOffset.UTC).toLocalDate()
                    if (lastDay.isBefore(today)) continue
                    if (allDayPick == null) allDayPick = ev
                } else {
                    if (end <= now) continue
                    // Prefer a timed event today or later; keep an all-day one only if it comes first by date.
                    val pick = allDayPick
                    if (pick != null) {
                        val pickDay = Instant.ofEpochMilli(pick.begin).atZone(ZoneOffset.UTC).toLocalDate()
                        val evDay = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate()
                        return if (pickDay.isBefore(evDay)) pick else ev
                    }
                    return ev
                }
            }
            return allDayPick
        }
        return null
    }

    fun whenText(ctx: Context, e: NextEvent): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val timeFmt = DateFormat.getTimeFormat(ctx)
        fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        if (e.allDay) {
            val d = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
            return when {
                !d.isAfter(today) -> "All day"
                d == today.plusDays(1) -> "All day tomorrow"
                else -> "All day ${dayName(d)}"
            }
        }
        val d = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
        val t = timeFmt.format(Date(e.begin))
        return when {
            e.begin <= now -> "Now · until ${timeFmt.format(Date(e.end))}"
            d == today -> t
            d == today.plusDays(1) -> "$t tomorrow"
            else -> "${dayName(d)} $t"
        }
    }
}

// ---------------------------------------------------------------- views

/** A glass widget card (158dp tall in the design). */
open class WidgetCard(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        background = Glass(this, 28.dpf, 0x29FFFFFF, 0x38FFFFFF, depth = 0)
        glassShadow(8)
        setPadding(16.dp, 16.dp, 16.dp, 15.dp)
        isClickable = true
        springPress(0.95f)
    }

    protected fun spacer() = View(context).also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)) }
}

class WeatherWidget(context: Context) : WidgetCard(context) {
    private val place = label(context, 13f, Fonts.semibold, 0.9f)
    private val temp = label(context, 46f, Fonts.light)
    private val glyph = GlyphView(context, Glyphs.CLOUD, 24)
    private val cond = label(context, 13f, Fonts.semibold)
    private val hilo = label(context, 12f, Fonts.regular, 0.85f)

    init {
        temp.letterSpacing = -0.04f
        addView(place)
        addView(temp, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp })
        spacer()
        addView(glyph, LayoutParams(24.dp, 24.dp))
        addView(cond, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })
        addView(hilo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp })
        show(WeatherSource.cached(), true)
    }

    fun show(w: Weather?, permitted: Boolean) {
        if (w == null) {
            place.text = "Weather"
            temp.text = "--°"
            glyph.glyph = Glyphs.CLOUD
            cond.text = if (permitted) "Updating…" else "Tap to set up"
            hilo.text = if (permitted) " " else "Uses rough location"
            contentDescription = "Weather. ${cond.text}"
            return
        }
        place.text = w.place.ifEmpty { "Weather" }
        temp.text = "${w.temp.roundToInt()}°"
        glyph.glyph = Weather.glyph(w.code, w.isDay)
        cond.text = Weather.describe(w.code)
        hilo.text = "H:${w.hi.roundToInt()}° L:${w.lo.roundToInt()}°"
        contentDescription = "${place.text}, ${temp.text}, ${cond.text}, ${hilo.text}"
    }
}

class CalendarWidget(context: Context) : WidgetCard(context) {
    private val day = label(context, 12f, Fonts.bold, 1f, 0xFFFFB4AB.toInt())
    private val num = label(context, 40f, Fonts.light)
    private val row = LinearLayout(context)
    private val dot = Dot(context)
    private val title = label(context, 13f, Fonts.semibold)
    private val time = label(context, 12f, Fonts.regular, 0.85f)
    var event: NextEvent? = null
        private set

    init {
        day.letterSpacing = 0.05f
        num.letterSpacing = -0.02f
        addView(day)
        addView(num, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })
        spacer()
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(dot, LayoutParams(8.dp, 8.dp).apply { marginEnd = 8.dp })
        row.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row)
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 2.dp; marginStart = 16.dp
        })
        show(null, true)
    }

    fun updateDate() {
        val d = LocalDate.now()
        day.text = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase(Locale.getDefault())
        num.text = d.dayOfMonth.toString()
    }

    fun show(e: NextEvent?, permitted: Boolean) {
        event = e
        updateDate()
        when {
            !permitted -> {
                dot.visibility = GONE; title.text = "Your events"; time.text = "Tap to show them"
            }
            e == null -> {
                dot.visibility = GONE; title.text = "No events"; time.text = "Next 7 days"
            }
            else -> {
                dot.visibility = VISIBLE
                dot.color = if (e.color != 0) e.color or 0xFF000000.toInt() else 0xFF7DD3FC.toInt()
                title.text = e.title
                time.text = CalendarSource.whenText(context, e)
            }
        }
        (time.layoutParams as? LayoutParams)?.marginStart = if (dot.visibility == VISIBLE) 16.dp else 0
        time.requestLayout()
        contentDescription = "${day.text} ${num.text}. ${title.text}, ${time.text}"
    }

    class Dot(context: Context) : View(context) {
        var color = 0xFF7DD3FC.toInt()
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            p.color = color
            canvas.drawCircle(width / 2f, height / 2f, width / 2f, p)
        }
    }
}
