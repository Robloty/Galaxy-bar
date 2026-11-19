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
    val intent: PendingIntent?
)

object Bus {
    var notif: ((NotifData) -> Unit)? = null
    var connected: (() -> Unit)? = null
}

class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Bus.connected?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (ex: Exception) {
            null
        }
        Bus.notif?.invoke(NotifData(title, text, icon, n.contentIntent))
    }
}
