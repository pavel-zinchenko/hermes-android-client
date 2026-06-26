package com.hermes.android.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.android.HermesApp
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.audio.CallSession
import com.hermes.android.audio.ContinuousVoiceCapture
import com.hermes.android.audio.DeviceSpeechTranscriber
import com.hermes.android.audio.FullDuplexCallEngine
import com.hermes.android.audio.FullDuplexClipPlayer
import com.hermes.android.audio.FullDuplexTranscriber
import com.hermes.android.audio.HalfDuplexClipPlayer
import com.hermes.android.audio.OnDeviceTts
import com.hermes.android.audio.ServerVadTranscriber
import com.hermes.android.audio.ThinkingSoundPlayer
import com.hermes.android.audio.VoiceRecorder
import com.hermes.android.data.SttEngine
import com.hermes.android.ui.chat.ChatSessionViewModel
import com.hermes.android.ui.models.ModelsViewModel
import com.hermes.android.ui.schedule.ScheduleViewModel
import com.hermes.android.ui.sessions.SessionsViewModel
import com.hermes.android.ui.settings.SettingsViewModel
import com.hermes.android.ui.startup.ConnectionViewModel
import com.hermes.android.ui.voice.SttViewModel
import com.hermes.android.ui.voice.TtsViewModel

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
            TtsViewModel(app.repository, AudioPlayer(app))
        }
        initializer {
            val app = extrasApp(this)
            SttViewModel(app.repository, OnDeviceTts(app))
        }
        initializer {
            val app = extrasApp(this)
            ScheduleViewModel(app.repository, app.reminderScheduler)
        }
        initializer {
            val app = extrasApp(this)
            // Shared with HalfDuplexClipPlayer so call playback and push-to-talk use
            // the same AudioPlayer instance.
            val audioPlayer = AudioPlayer(app)
            ChatSessionViewModel(
                repository = app.repository,
                recorder = VoiceRecorder(app),
                player = audioPlayer,
                thinkingSound = ThinkingSoundPlayer(app),
                onDeviceTts = OnDeviceTts(app),
                callSessionFor = { engine ->
                    when (engine) {
                        SttEngine.ON_DEVICE -> {
                            val t = DeviceSpeechTranscriber(app)
                            CallSession(t, HalfDuplexClipPlayer(audioPlayer, t))
                        }
                        SttEngine.SERVER -> {
                            val t = ServerVadTranscriber(ContinuousVoiceCapture()) { wav ->
                                app.repository.transcribe(wav, "audio/wav")
                            }
                            CallSession(t, HalfDuplexClipPlayer(audioPlayer, t))
                        }
                        SttEngine.FULL_DUPLEX -> {
                            val fdEngine = FullDuplexCallEngine()
                            val t = FullDuplexTranscriber(fdEngine) { wav ->
                                app.repository.transcribe(wav, "audio/wav")
                            }
                            CallSession(t, FullDuplexClipPlayer(fdEngine))
                        }
                    }
                },
                savedStateHandle = createSavedStateHandle(),
                onTurnComplete = { app.syncReminders() },
            )
        }
    }
}
