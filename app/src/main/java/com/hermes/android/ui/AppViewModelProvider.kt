package com.hermes.android.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.android.HermesApp
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.audio.ThinkingSoundPlayer
import com.hermes.android.audio.VoiceRecorder
import com.hermes.android.ui.chat.ChatViewModel
import com.hermes.android.ui.sessions.SessionsViewModel
import com.hermes.android.ui.settings.SettingsViewModel
import com.hermes.android.ui.startup.ConnectionViewModel
import com.hermes.android.ui.voice.VoiceViewModel

private fun extrasApp(
    extras: androidx.lifecycle.viewmodel.CreationExtras,
): HermesApp = (extras[APPLICATION_KEY] as HermesApp)

/** Factories that pull the shared repository off the [HermesApp] application. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { ConnectionViewModel(extrasApp(this).repository) }
        initializer { SessionsViewModel(extrasApp(this).repository) }
        initializer { SettingsViewModel(extrasApp(this).repository) }
        initializer {
            ChatViewModel(
                repository = extrasApp(this).repository,
                savedStateHandle = createSavedStateHandle(),
            )
        }
        initializer {
            val app = extrasApp(this)
            VoiceViewModel(
                repository = app.repository,
                recorder = VoiceRecorder(app),
                player = AudioPlayer(app),
                thinkingSound = ThinkingSoundPlayer(app),
                savedStateHandle = createSavedStateHandle(),
            )
        }
    }
}
