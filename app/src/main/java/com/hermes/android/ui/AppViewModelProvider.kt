package com.hermes.android.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.android.HermesApp
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.audio.OnDeviceTts
import com.hermes.android.audio.ThinkingSoundPlayer
import com.hermes.android.audio.VoiceRecorder
import com.hermes.android.ui.chat.ChatSessionViewModel
import com.hermes.android.ui.models.ModelsViewModel
import com.hermes.android.ui.schedule.ScheduleViewModel
import com.hermes.android.ui.sessions.SessionsViewModel
import com.hermes.android.ui.settings.SettingsViewModel
import com.hermes.android.ui.startup.ConnectionViewModel

private fun extrasApp(
    extras: androidx.lifecycle.viewmodel.CreationExtras,
): HermesApp = (extras[APPLICATION_KEY] as HermesApp)

/** Factories that pull the shared repository off the [HermesApp] application. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { ConnectionViewModel(extrasApp(this).repository) }
        initializer { SessionsViewModel(extrasApp(this).repository) }
        initializer { SettingsViewModel(extrasApp(this).repository) }
        initializer { ModelsViewModel(extrasApp(this).repository) }
        initializer {
            val app = extrasApp(this)
            ScheduleViewModel(app.repository, app.reminderScheduler)
        }
        initializer {
            val app = extrasApp(this)
            ChatSessionViewModel(
                repository = app.repository,
                recorder = VoiceRecorder(app),
                player = AudioPlayer(app),
                thinkingSound = ThinkingSoundPlayer(app),
                onDeviceTts = OnDeviceTts(app),
                savedStateHandle = createSavedStateHandle(),
                onTurnComplete = { app.syncReminders() },
            )
        }
    }
}
