package com.wt.relay.services

import android.content.Context
import androidx.work.*
import com.wt.relay.core.SecureStorage
import com.wt.relay.data.local.AppDatabase
import com.wt.relay.data.remote.IngestNotificationRequest
import com.wt.relay.data.remote.WtRelayApiService
import java.time.Instant
import java.util.concurrent.TimeUnit

class RelaySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val storage = SecureStorage(applicationContext)
        val token = storage.deviceToken ?: return Result.failure()
        val api = WtRelayApiService.create(storage)
        val dao = AppDatabase.get(applicationContext).notificationDao()

        return try {
            // Send Heartbeat
            api.sendHeartbeat(storage.deviceId, token, mapOf("network_type" to "CONNECTED"))

            // Flush Offline Queue
            val pending = dao.getPendingEvents()
            var allDelivered = true
            for (item in pending) {
                val resp = api.ingestNotification(
                    token,
                    IngestNotificationRequest(
                        event_id = item.eventId,
                        package_name = item.packageName,
                        sender_title = item.senderTitle,
                        message_text = item.messageText,
                        received_at = item.receivedAt
                    )
                )
                if (resp.isSuccessful && resp.body()?.success == true) {
                    dao.updateStatus(item.eventId, "DELIVERED")
                    storage.lastSyncTime = Instant.now().toString()
                } else {
                    dao.updateStatus(item.eventId, "FAILED")
                    allDelivered = false
                }
            }
            if (allDelivered) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<RelaySyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("wt_relay_immediate_sync", ExistingWorkPolicy.KEEP, req)
        }
    }
}
