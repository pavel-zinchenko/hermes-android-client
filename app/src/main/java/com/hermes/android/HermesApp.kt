package com.hermes.android

import android.app.Application
import android.util.Log
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.SettingsStore
import com.hermes.android.local.AppAction
import com.hermes.android.local.LocalApiServer
import com.hermes.android.schedule.Notifications
import com.hermes.android.schedule.ReminderScheduler
import com.hermes.android.schedule.ScheduleSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Minimal manual DI: a single app-scoped repository shared by all ViewModels. */
class HermesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    val repository: HermesRepository by lazy {
        HermesRepository(SettingsStore(applicationContext), applicationContext, appScope)
    }

    /** Mirrors Hermes cron jobs into local Android alarms (reminders). */
    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(applicationContext)
    }

    /**
     * In-app actions pushed by [LocalApiServer] when Hermes calls the loopback API.
     * Collected by `MainActivity` to drive the UI. Buffered + drop-tolerant: a
     * non-suspending `tryEmit` from the server thread never blocks request handling.
     */
    val actionFlow = MutableSharedFlow<AppAction>(extraBufferCapacity = 16)

    /** Loopback HTTP bridge Hermes uses for UI/SDK actions Termux can't perform. */
    private val localApiServer by lazy {
        LocalApiServer { action -> actionFlow.tryEmit(action) }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        ScheduleSyncWorker.schedulePeriodic(this)
        syncReminders()
        startLocalApiServer()
    }

    private fun startLocalApiServer() {
        try {
            localApiServer.start()
        } catch (e: Exception) {
            Log.e("HermesApp", "Local API server failed to start", e)
        }
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
