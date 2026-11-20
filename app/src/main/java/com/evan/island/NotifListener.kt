package com.evan.island

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class NotifData(
    val title: String,
    val text: String,
    val icon: Drawable?,
    val intent: PendingIntent?,
    val badge: String? = null
)

data class TimerInfo(
    val key: String,
    val title: String,
    val text: String,
    val base: Long,
    val chrono: Boolean,
    val countDown: Boolean,
    val icon: Drawable?,
    val intent: PendingIntent?
)

object Bus {
    var notif: ((NotifData) -> Unit)? = null
    var connected: (() -> Unit)? = null
    var cmd: ((String) -> Unit)? = null
    var preview: ((Cfg) -> Unit)? = null
    var timer: ((TimerInfo) -> Unit)? = null
    var timerGone: ((String) -> Unit)? = null
}

class NotifListener : NotificationListenerService() {

    companion object {
        var inst: NotifListener? = null
    }

    private fun isClock(pkg: String) = pkg.contains("clock", true)

    private fun iconOf(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (ex: Exception) {
        null
    }

    private fun handleClock(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        val e = n.extras
        Bus.timer?.invoke(
            TimerInfo(
                key = sbn.key,
                title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
                text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
                base = n.`when`,
                chrono = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
                countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
                icon = iconOf(sbn.packageName),
                intent = n.contentIntent
            )
        )
        return true
    }

    fun scan() {
        try {
            activeNotifications?.forEach {
                if (isClock(it.packageName)) handleClock(it)
            }
        } catch (e: Exception) {
        }
    }

    override fun onListenerConnected() {
        inst = this
        Bus.connected?.invoke()
    }

    override fun onListenerDisconnected() {
        inst = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isClock(sbn.packageName)) Bus.timerGone?.invoke(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (isClock(sbn.packageName) && handleClock(sbn)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        Bus.notif?.invoke(NotifData(title, text, iconOf(sbn.packageName), n.contentIntent))
    }
}
