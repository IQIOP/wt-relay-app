package com.wt.relay

import android.app.Application
import androidx.work.*
import com.wt.relay.services.RelaySyncWorker
import java.util.concurrent.TimeUnit

class WTApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodicSync = PeriodicWorkRequestBuilder<RelaySyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "wt_relay_periodic_heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSync
        )
    }
}
