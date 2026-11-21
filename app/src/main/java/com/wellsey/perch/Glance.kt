package com.wellsey.perch

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import java.util.Date

/**
 * The "At a Glance" line under the date: what's playing, charging, or your next alarm,
 * in that order of importance.
 */
class Glance(private val ctx: Context, private val show: (Item?) -> Unit) {
    class Item(val glyph: String, val text: String, val tap: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var msm: MediaSessionManager? = null
    private var running = false

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list ?: emptyList())
        refresh()
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() {
            controller = null
            refresh()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun start() {
        if (running) return
        running = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        connectMedia()
        refresh()
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        try {
            msm?.removeOnActiveSessionsChangedListener(sessions)
        } catch (e: Exception) {
        }
        controller?.unregisterCallback(mediaCallback)
        controller = null
    }

    /** Media sessions are only visible once Perch's notification access is on. */
    fun connectMedia() {
        if (!Notifier.isEnabled(ctx)) return
        try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            msm = m
            m.addOnActiveSessionsChangedListener(sessions, Notifier.component(ctx), main)
            pickController(m.getActiveSessions(Notifier.component(ctx)))
        } catch (e: Exception) {
        }
    }

    private fun pickController(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(mediaCallback)
        controller = best
        best?.registerCallback(mediaCallback, main)
    }

    fun refresh() {
        if (!Prefs.glance) {
            show(null); return
        }
        show(media() ?: charging() ?: alarm())
    }

    private fun media(): Item? {
        val c = controller ?: return null
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val text = if (artist.isNullOrBlank()) title else "$title · $artist"
        return Item(MUSIC, text) {
            try {
                val pi = c.sessionActivity
                if (pi != null) pi.send()
                else ctx.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun charging(): Item? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (plugged == 0) return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val text = when {
            pct >= 100 -> "Charged"
            pct >= 0 -> "Charging · $pct%"
            else -> "Charging"
        }
        return Item(BOLT, text) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
    }

    private fun alarm(): Item? {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = am.nextAlarmClock ?: return null
        val inMs = next.triggerTime - System.currentTimeMillis()
        if (inMs <= 0 || inMs > 12 * 3600_000L) return null
        val t = DateFormat.getTimeFormat(ctx).format(Date(next.triggerTime))
        return Item(Glyphs.BELL, "Alarm $t") {
            try {
                next.showIntent?.send()
            } catch (e: Exception) {
            }
        }
    }

    companion object {
        const val MUSIC = "M 9 18 L 9 5 L 20 3 L 20 16 O 6 18 3 O 17 16 3"
        const val BOLT = "M 13 2 L 4 14 L 12 14 L 11 22 L 20 10 L 12 10 Z"
    }
}
