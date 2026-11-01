package com.wt.relay.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wt.relay.data.local.AppDatabase
import com.wt.relay.data.local.QueuedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant

class WTNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val targetPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !targetPackages.contains(sbn.packageName)) return
        val extras = sbn.notification.extras ?: return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
        if (title.isEmpty() || text.isEmpty()) return

        // Deterministic Idempotent Event ID within 10-second bucket
        val timeBucket = sbn.postTime / 10000L
        val rawKey = "${sbn.packageName}|$title|$text|$timeBucket"
        val eventId = "EVT-" + MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
            .uppercase()

        val receivedIso = Instant.ofEpochMilli(sbn.postTime).toString()

        scope.launch {
            val dao = AppDatabase.get(applicationContext).notificationDao()
            val inserted = dao.insertEvent(
                QueuedNotification(
                    eventId = eventId,
                    packageName = sbn.packageName,
                    senderTitle = title,
                    messageText = text,
                    receivedAt = receivedIso,
                    status = "PENDING"
                )
            )
            if (inserted != -1L) {
                RelaySyncWorker.enqueueImmediateSync(applicationContext)
            }
        }
    }
}
