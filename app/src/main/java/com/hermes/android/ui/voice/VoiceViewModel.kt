package com.hermes.android.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.audio.ThinkingSoundPlayer
import com.hermes.android.audio.VoiceRecorder
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Phases of one push-to-talk turn. */
enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING, THINKING, SPEAKING }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
)

/**
 * Drives the dedicated voice mode for a chat session. Holds the wheel for the
 * record → transcribe → chat → speak → play loop. Persistence is the server's job
 * (the same session the chat screen reads), so history stays text-only; the
 * in-memory [VoiceUiState.messages] list is just for on-screen feedback.
 */
class VoiceViewModel(
    private val repository: HermesRepository,
    private val recorder: VoiceRecorder,
    private val player: AudioPlayer,
    private val thinkingSound: ThinkingSoundPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) {
        "VoiceViewModel requires a sessionId nav argument"
    }

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** The in-flight transcribe → chat → speak turn, if any, so a new press can interrupt it. */
    private var turnJob: Job? = null

    /**
     * Called when the user presses the talk button. The button is always live: a
     * press while Hermes is transcribing, thinking, or speaking interrupts that
     * turn (cancels the in-flight work, stops playback and the thinking sound) and
     * immediately starts a fresh recording.
     */
    fun startRecording() {
        if (_state.value.phase == VoicePhase.RECORDING) return
        // Interrupt whatever Hermes was doing before we grab the mic.
        turnJob?.cancel()
        turnJob = null
        player.stop()
        thinkingSound.stop()
        try {
            recorder.start()
            _state.update { it.copy(phase = VoicePhase.RECORDING, error = null) }
        } catch (_: Exception) {
            _state.update { it.copy(phase = VoicePhase.IDLE, error = "Couldn't start recording.") }
        }
    }

    /** Called when the user releases the talk button. */
    fun stopAndSend() {
        if (_state.value.phase != VoicePhase.RECORDING) return
        val audio = recorder.stop()
        if (audio == null || audio.isEmpty()) {
            _state.update { it.copy(phase = VoicePhase.IDLE) }
            return
        }
        turnJob = viewModelScope.launch { runTurn(audio) }
    }

    private suspend fun runTurn(audio: ByteArray) {
        _state.update { it.copy(phase = VoicePhase.TRANSCRIBING, error = null) }
        val transcript = repository.transcribe(audio, recorder.mimeType).getOrElse { err ->
            failTurn(err.toUserMessage())
            return
        }
        // If this turn was interrupted (the user started a new recording), bail
        // before touching state so we don't clobber the fresh RECORDING phase.
        if (!currentCoroutineContext().isActive) return
        if (transcript.isBlank()) {
            failTurn("Didn't catch that — try again.")
            return
        }
        append(Sender.USER, transcript)

        _state.update { it.copy(phase = VoicePhase.THINKING) }
        thinkingSound.start(repository.thinkingSoundUri())
        val reply = repository.sendMessage(sessionId, transcript).getOrElse { err ->
            thinkingSound.stop()
            failTurn(err.toUserMessage())
            return
        }
        if (!currentCoroutineContext().isActive) {
            thinkingSound.stop()
            return
        }
        val replyText = reply.ifBlank { "(empty response)" }
        append(Sender.ASSISTANT, replyText)

        // Stay in THINKING (with the thinking sound looping) through TTS synthesis,
        // which is its own gap, then hand off to playback the moment audio is ready.
        repository.speak(replyText)
            .onSuccess { out ->
                thinkingSound.stop()
                if (!currentCoroutineContext().isActive) return@onSuccess
                _state.update { it.copy(phase = VoicePhase.SPEAKING) }
                player.play(out.bytes, out.mime) {
                    _state.update { it.copy(phase = VoicePhase.IDLE) }
                }
            }
            .onFailure {
                thinkingSound.stop()
                // The reply text is already shown; only playback failed.
                failTurn("Couldn't play the reply audio.")
            }
    }

    /**
     * Moves the turn to IDLE with [message], unless this turn was already
     * interrupted — in which case a newer turn owns the phase and we stay quiet.
     */
    private suspend fun failTurn(message: String) {
        if (!currentCoroutineContext().isActive) return
        _state.update { it.copy(phase = VoicePhase.IDLE, error = message) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun append(sender: Sender, text: String) {
        val message = ChatMessage(
            id = "${sender}_${System.currentTimeMillis()}",
            sender = sender,
            text = text,
        )
        _state.update { it.copy(messages = it.messages + message) }
    }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
        thinkingSound.stop()
    }
}
