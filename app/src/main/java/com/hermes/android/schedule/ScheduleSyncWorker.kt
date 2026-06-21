package com.hermes.android.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hermes.android.HermesApp
import java.util.concurrent.TimeUnit

/**
 * Periodic safety-net sync: re-pulls the active local cron jobs and reconciles
 * the local alarms. Catches jobs created/changed on other clients while the app
 * was closed, and re-arms recurring jobs' next occurrence even if the user never
 * reopens the app. Needs only the app process (not Termux) to wake briefly, and
 * WorkManager survives Doze/reboot.
 */
class ScheduleSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? HermesApp ?: return Result.success()
        return app.repository.listScheduledTasks().fold(
            onSuccess = { tasks ->
                app.reminderScheduler.reconcile(tasks)
                Result.success()
            },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        private const val UNIQUE_NAME = "schedule-sync"

        /** Enqueues the 15-minute periodic sync (idempotent — keeps any existing one). */
        fun schedulePeriodic(context: Context) {
            // No network constraint: Hermes runs on-device (127.0.0.1, Termux), so
            // it's reachable over loopback even with no Wi-Fi/cell — and loopback
            // isn't an "active network", so NetworkType.CONNECTED would wrongly
            // block the sync while offline. Unreachable servers are handled by the
            // Result.retry() in doWork().
            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
