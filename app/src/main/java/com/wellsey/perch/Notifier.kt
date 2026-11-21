package com.wellsey.perch

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification

/**
 * Counts each app's notifications for the dots on icons. Perch never reads or stores what the
 * notifications say. Being a notification listener also lets At a Glance see what's playing.
 */
class Notifier : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        recount()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        publish(emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val list: Array<StatusBarNotification> = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        val map = HashMap<String, Int>()
        for (sbn in list) {
            if (sbn.isOngoing || !sbn.isClearable) continue
            if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val k = key(sbn.packageName, sbn.user.hashCode())
            map[k] = (map[k] ?: 0) + 1
        }
        publish(map)
    }

    companion object {
        @Volatile
        private var counts: Map<String, Int> = emptyMap()
        @Volatile
        var connected = false
            private set
        private val listeners = ArrayList<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        private fun key(pkg: String, user: Int) = "$pkg|$user"

        private fun publish(m: Map<String, Int>) {
            main.post {
                if (m != counts) {
                    counts = m
                    listeners.toList().forEach { it() }
                }
            }
        }

        fun count(app: AppInfo): Int = counts[key(app.packageName, app.user.hashCode())] ?: 0

        fun addListener(l: () -> Unit) {
            listeners.add(l)
        }

        fun removeListener(l: () -> Unit) {
            listeners.remove(l)
        }

        fun component(c: Context) = ComponentName(c, Notifier::class.java)

        fun isEnabled(c: Context): Boolean {
            val s = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = component(c)
            return s.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
