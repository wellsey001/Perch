package com.wellsey.perch

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Perch's own widgets live in the widget panel with negative ids, next to Android widgets. */
object PerchWidgets {
    const val CLOCK = -1000
    const val PHOTO = -2000

    fun isPerch(id: Int) = id < 0
    fun kind(id: Int) = if (id <= PHOTO) PHOTO else CLOCK

    fun newId(kind: Int, existing: List<Int>): Int {
        var id = kind - 1
        while (id in existing) id--
        return id
    }
}

/** An analogue clock in glass, with the digital time and date beside it. */
@SuppressLint("ViewConstructor")
class ClockWidgetView(context: Context) : View(context) {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x59FFFFFF }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val second = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Fonts.light }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt(); typeface = Fonts.semibold }
    private val tickRunnable = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, if (Motion.reduced) 60_000L - System.currentTimeMillis() % 60_000L else 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    init {
        contentDescription = "Clock"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tickRunnable)
    }

    override fun onDraw(c: Canvas) {
        val pad = 18.dpf
        val d = min(height - 2 * pad, width * 0.5f)
        val r = d / 2f
        val cx = pad + r
        val cy = height / 2f
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
        val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val s = cal.get(Calendar.SECOND).toFloat()

        c.drawCircle(cx, cy, r, face)
        rim.strokeWidth = 1.5f.dpf
        c.drawCircle(cx, cy, r, rim)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val major = i % 3 == 0
            tick.strokeWidth = if (major) 3.dpf else 1.6f.dpf
            val r1 = r * (if (major) 0.78f else 0.84f)
            val r2 = r * 0.9f
            c.drawLine(cx + (r1 * sin(a)).toFloat(), cy - (r1 * cos(a)).toFloat(), cx + (r2 * sin(a)).toFloat(), cy - (r2 * cos(a)).toFloat(), tick)
        }
        fun handAt(turns: Float, len: Float, w: Float, p: Paint) {
            val a = Math.toRadians(turns * 360.0)
            p.strokeWidth = w
            c.drawLine(cx - (len * 0.14f * sin(a)).toFloat(), cy + (len * 0.14f * cos(a)).toFloat(),
                cx + (len * sin(a)).toFloat(), cy - (len * cos(a)).toFloat(), p)
        }
        hand.setShadowLayer(4.dpf, 0f, 1.dpf, 0x55000000)
        handAt(h / 12f, r * 0.5f, 5.dpf, hand)
        handAt(m / 60f, r * 0.72f, 3.5f.dpf, hand)
        if (!Motion.reduced) {
            second.color = Theme.accent
            handAt(s / 60f, r * 0.8f, 1.6f.dpf, second)
        }
        c.drawCircle(cx, cy, 4.dpf, hand)

        // Digital time and date to the right.
        val tx = cx + r + 20.dpf
        if (width - tx > 60.dpf) {
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            big.textSize = min(44.dpf, (width - tx) / 3.2f)
            small.textSize = 14.dpf
            val now = Date()
            c.drawText(DateFormat.format(pattern, now).toString(), tx, cy + big.textSize * 0.15f, big)
            c.drawText(DateFormat.format("EEEE d MMM", now).toString(), tx, cy + big.textSize * 0.15f + 24.dpf, small)
        }
    }
}

/** A photo from your Immich library (or your phone), refreshed now and then, with when it was taken. */
@SuppressLint("ViewConstructor")
class PhotoFrameView(private val act: LauncherActivity) : FrameLayout(act) {
    private val image = ImageView(act)
    private val shade = View(act)
    private val caption = label(act, 13f, Fonts.semibold)
    private val message = label(act, 14f, Fonts.medium, 0.85f)
    private val refresher = Runnable { next() }

    init {
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shade.background = object : android.graphics.drawable.Drawable() {
            private val p = Paint()
            override fun draw(c: Canvas) {
                val b = bounds
                p.shader = LinearGradient(0f, b.height() * 0.55f, 0f, b.height().toFloat(), 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(b, p)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        caption.setShadowLayer(6.dpf, 0f, 1.dpf, 0x80000000.toInt())
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(16.dp, 0, 16.dp, 14.dp)
        })
        message.isSingleLine = false
        message.gravity = Gravity.CENTER
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(20.dp, 0, 20.dp, 0)
        })
        contentDescription = "Photo frame"
        isClickable = true
        setOnClickListener { act.openPhotoApp() }
        PhotoSource.cached?.let { show(it) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (PhotoSource.cached == null || PhotoSource.stale()) next() else scheduleNext()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(refresher)
    }

    private fun scheduleNext() {
        removeCallbacks(refresher)
        postDelayed(refresher, 20 * 60_000L)
    }

    fun next() {
        PhotoSource.next(act) { photo, error ->
            if (photo != null) show(photo)
            else if (PhotoSource.cached == null) {
                image.setImageDrawable(null)
                caption.text = ""
                message.text = error ?: "No photos yet"
                message.visibility = VISIBLE
            }
            scheduleNext()
        }
    }

    private fun show(p: PhotoSource.Photo) {
        message.visibility = GONE
        if (Motion.reduced || image.drawable == null) {
            image.setImageBitmap(p.bitmap)
        } else {
            image.animate().alpha(0f).setDuration(250).withEndAction {
                image.setImageBitmap(p.bitmap)
                image.animate().alpha(1f).setDuration(450).start()
            }.start()
        }
        caption.text = p.caption
    }
}

/** Where the photo frame's pictures come from. */
object PhotoSource {
    class Photo(val bitmap: Bitmap, val caption: String, val at: Long)

    @Volatile
    var cached: Photo? = null
        private set
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun stale() = (cached?.let { System.currentTimeMillis() - it.at > 20 * 60_000L }) ?: true

    fun hasPhonePermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    fun next(c: Context, done: (Photo?, String?) -> Unit) {
        val app = c.applicationContext
        exec.execute {
            var error: String? = null
            val p = try {
                if (Prefs.photoSource == "phone") fromPhone(app) else fromImmich()
            } catch (e: SetupNeeded) {
                error = e.message
                null
            } catch (e: Exception) {
                error = "Couldn't load a photo. Check the photo frame in Perch settings."
                null
            }
            if (p != null) cached = p
            main.post { done(p, error) }
        }
    }

    private class SetupNeeded(msg: String) : Exception(msg)

    private fun fromImmich(): Photo? {
        val base = Prefs.immichUrl
        val key = Prefs.immichKey
        if (base.isEmpty() || key.isEmpty()) throw SetupNeeded("Add your Immich server in Perch settings › Photo frame.")
        var asset: JSONObject? = null
        try {
            val body = request("$base/api/search/random", key, "POST", JSONObject().put("size", 1).put("type", "IMAGE").toString())
            asset = JSONArray(body).optJSONObject(0)
        } catch (e: Exception) {
        }
        if (asset == null) {
            // Older Immich servers.
            asset = JSONArray(request("$base/api/assets/random?count=1", key, "GET", null)).optJSONObject(0)
        }
        val a = asset ?: return null
        val id = a.getString("id")
        val conn = open("$base/api/assets/$id/thumbnail?size=preview", key, "GET")
        val bmp = try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        } ?: return null
        val taken = a.optString("localDateTime").ifEmpty { a.optString("fileCreatedAt") }
        return Photo(bmp, captionFor(taken), System.currentTimeMillis())
    }

    private fun captionFor(iso: String): String = try {
        val d = OffsetDateTime.parse(iso).toLocalDate()
        val years = LocalDate.now().year - d.year
        val date = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
        when {
            years <= 0 -> date
            years == 1 -> "1 year ago · $date"
            else -> "$years years ago · $date"
        }
    } catch (e: Exception) {
        ""
    }

    private fun open(url: String, key: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun request(url: String, key: String, method: String, json: String?): String {
        val c = open(url, key, method)
        try {
            if (json != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun fromPhone(c: Context): Photo? {
        if (!hasPhonePermission(c)) throw SetupNeeded("Allow photos in Perch settings › Photo frame.")
        val ids = ArrayList<Pair<Long, Long>>()
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) ids.add(Pair(cur.getLong(0), cur.getLong(1)))
        }
        if (ids.isEmpty()) throw SetupNeeded("No photos on this phone yet.")
        val (id, taken) = ids.random()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bmp = c.contentResolver.loadThumbnail(uri, Size(1080, 1080), null)
        val caption = if (taken > 0) {
            val d = java.time.Instant.ofEpochMilli(taken).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
            captionFor(d)
        } else ""
        return Photo(bmp, caption, System.currentTimeMillis())
    }
}
