package com.hermes.android

import android.app.Application
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.SettingsStore
import com.hermes.android.schedule.Notifications
import com.hermes.android.schedule.ReminderScheduler
import com.hermes.android.schedule.ScheduleSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Minimal manual DI: a single app-scoped repository shared by all ViewModels. */
class HermesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    val repository: HermesRepository by lazy {
        HermesRepository(SettingsStore(applicationContext), appScope)
    }

    /** Mirrors Hermes cron jobs into local Android alarms (reminders). */
    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        ScheduleSyncWorker.schedulePeriodic(this)
        syncReminders()
    }

    /**
     * Pulls the active local cron jobs and reconciles the mirrored alarms. Called
     * on launch and after each completed chat turn (so a just-created reminder is
     * armed immediately); the periodic [ScheduleSyncWorker] is the background net.
     */
    fun syncReminders() {
        appScope.launch {
            repository.listScheduledTasks().onSuccess { reminderScheduler.reconcile(it) }
        }
    }
}
