package com.hermes.android

import android.app.Application
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Minimal manual DI: a single app-scoped repository shared by all ViewModels. */
class HermesApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    val repository: HermesRepository by lazy {
        HermesRepository(SettingsStore(applicationContext), appScope)
    }
}
