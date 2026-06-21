package com.hermes.android.ui.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.audio.SentenceChunker
import com.hermes.android.audio.ThinkingSoundPlayer
import com.hermes.android.audio.VoiceRecorder
import com.hermes.android.data.DecodedAudio
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.data.model.TurnPart
import com.hermes.android.data.model.TurnParts
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Phases of one push-to-talk turn. */
enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING, RESPONDING }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
)

/** Ends a voice turn early with a user-facing message (e.g. an interactive request). */
private class TurnAbort(val userMessage: String) : RuntimeException(userMessage)

/** Common filler that cloud STT (Whisper) emits for near-silent audio, normalized. */
private val NOISE_PHRASES = setOf(
    "you", "thank you", "thanks", "thanks for watching",
    "thank you for watching", "bye", "bye bye", "you're welcome",
    "please subscribe", "subscribe",
)

/**
 * True if [transcript] looks like a silence-hallucination rather than real speech.
 * Normalizes (trim, lowercase, strip surrounding punctuation) before matching so
 * "Thank you." and "you" are caught.
 */
internal fun isLikelyNoise(transcript: String): Boolean {
    val normalized = transcript.trim().lowercase().trim('.', '!', '?', ',', '…', ' ')
    return normalized in NOISE_PHRASES
}

/**
 * Drives the dedicated voice mode for a chat session. Holds the wheel for the
 * record → transcribe → respond loop. The assistant turn streams just like text
 * chat: thinking traces and tool activity show inline (built into [ChatMessage.parts]
 * via the shared [TurnParts] reducer), while the answer text is spoken sentence by
 * sentence — each fragment synthesized and played while the next streams in, so
 * audio starts on the first sentence rather than after the whole answer.
 *
 * Persistence is the server's job (the same session the chat screen reads), so the
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

    /** The in-flight transcribe → respond turn, if any, so a new press can interrupt it. */
    private var turnJob: Job? = null

    /**
     * True only while a server turn is genuinely in flight — between `prompt.submit`
     * and its terminal `message.complete`/`error`. Gates the server-side interrupt on
     * barge-in: interrupting an *idle* session sets a sticky `_interrupt_requested`
     * flag that the agent never clears, poisoning the next turn into an empty reply.
     * Note the local playback of an already-finished answer is NOT a server turn.
     */
    private var serverTurnActive = false

    /**
     * Called when the user presses the talk button. The button is always live: a
     * press while Hermes is transcribing, responding, or speaking interrupts that
     * turn (cancels the in-flight work, stops playback and the thinking sound) and
     * immediately starts a fresh recording.
     */
    fun startRecording() {
        if (_state.value.phase == VoicePhase.RECORDING) return
        // Interrupt whatever Hermes was doing before we grab the mic. Cancelling the
        // local job stops *our* side; the server agent keeps running unless we also
        // interrupt it, which would otherwise leave the session busy and reject the
        // next prompt.submit with error 4009. But only interrupt when a server turn is
        // actually in flight — interrupting an idle session poisons the next turn into
        // an empty reply (see [serverTurnActive]).
        val interruptServer = serverTurnActive
        serverTurnActive = false
        turnJob?.cancel()
        turnJob = null
        player.stop()
        thinkingSound.stop()
        if (interruptServer) viewModelScope.launch { repository.interrupt(sessionId) }
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
        // Whisper hallucinates filler words on near-silence; treat those (and a blank
        // result) as "nothing said" so we never append a bogus bubble or call Hermes.
        if (transcript.isBlank() || isLikelyNoise(transcript)) {
            failTurn("Didn't catch that — try again.")
            return
        }
        append(Sender.USER, transcript)

        _state.update { it.copy(phase = VoicePhase.RESPONDING) }
        // The filler sound covers every gap where nothing is playing but the turn is
        // still going: before the first sentence, and between sentences when the
        // next clip isn't synthesized in time. Read the URI once for the whole turn.
        val fillerUri = repository.thinkingSoundUri()
        thinkingSound.start(fillerUri)

        val bubbleId = "assistant_${System.currentTimeMillis()}"
        val chunker = SentenceChunker()
        // Whether any fragment was enqueued for speech, and whether any actually
        // synthesized. Hoisted out of the coroutineScope so the end-of-turn check
        // below can tell a wholly-failed TTS turn from a normal one.
        var spoke = false
        var synthesizedAny = false
        try {
            coroutineScope {
                // Completed text fragments awaiting synthesis; UNLIMITED so feeding
                // it from the event collector never blocks.
                val sentences = Channel<String>(Channel.UNLIMITED)
                // Synthesized clips awaiting playback. A small buffer keeps synthesis
                // just ahead of playback (the next clips are prepared while the
                // current one plays) while keeping `tryReceive` deterministic.
                val audios = Channel<DecodedAudio>(capacity = 3)

                val synth = launch {
                    for (fragment in sentences) {
                        repository.speak(fragment)
                            .onSuccess { synthesizedAny = true; audios.send(it) }
                            .onFailure { /* skip this fragment; keep the rest flowing */ }
                    }
                    audios.close()
                }
                val playback = launch {
                    try {
                        while (true) {
                            // Play a ready clip immediately (no gap → no filler). If
                            // none is ready, bridge with the filler and wait for the
                            // next; a closed-and-drained channel ends playback.
                            var clip = audios.tryReceive().getOrNull()
                            if (clip == null) {
                                thinkingSound.start(fillerUri)
                                clip = audios.receiveCatching().getOrNull()
                                thinkingSound.stop()
                                if (clip == null) break
                            }
                            try {
                                player.playAndAwait(clip.bytes, clip.mime)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Exception) {
                                // A bad clip shouldn't wedge the turn — skip it and
                                // play the rest.
                            }
                        }
                    } finally {
                        thinkingSound.stop()
                    }
                }

                // The server turn is now in flight (prompt.submit happens inside the
                // collect); a barge-in from here until a terminal event should
                // interrupt it server-side.
                serverTurnActive = true
                repository.submitPromptStreaming(sessionId, transcript).collect { event ->
                    when (event) {
                        ChatEvent.Start -> ensureBubble(bubbleId)
                        is ChatEvent.Delta -> {
                            appendText(bubbleId, event.text)
                            for (fragment in chunker.push(event.text)) {
                                sentences.send(fragment)
                                spoke = true
                            }
                        }
                        is ChatEvent.Thinking -> appendThinking(bubbleId, event.text)
                        is ChatEvent.ToolStart -> addTool(bubbleId, event)
                        is ChatEvent.ToolComplete -> completeTool(bubbleId, event)
                        is ChatEvent.Status -> {}
                        is ChatEvent.Complete -> {
                            // Server turn finished — the rest (playback) is local, so a
                            // later press must NOT interrupt an idle session.
                            serverTurnActive = false
                            finishBubble(bubbleId, event.text)
                            chunker.drain()?.let { sentences.send(it); spoke = true }
                            // Nothing was streamed as deltas (rare) → speak the
                            // authoritative final text so the turn isn't silent.
                            if (!spoke && event.text.isNotBlank()) {
                                sentences.send(event.text)
                                spoke = true
                            }
                        }
                        // Voice has no UI to answer an interactive request, and the
                        // agent would block server-side forever waiting — end the turn
                        // and point the user at text chat.
                        is ChatEvent.Interactive -> throw TurnAbort(
                            "Hermes needs interactive input, which voice mode can't provide. " +
                                "Use text chat for this request.",
                        )
                        is ChatEvent.Failure -> {
                            // Server turn ended (errored) — no running turn to interrupt.
                            serverTurnActive = false
                            throw TurnAbort(event.message)
                        }
                    }
                }
                // The collector finished normally (message.complete). Let the queued
                // speech drain and finish playing before we go idle.
                sentences.close()
                synth.join()
                playback.join()
            }
            if (currentCoroutineContext().isActive) {
                // The reply text is on screen; if we had speech to play but every
                // synthesis failed, say so rather than going quietly idle.
                val ttsFailed = spoke && !synthesizedAny
                _state.update {
                    it.copy(
                        phase = VoicePhase.IDLE,
                        error = if (ttsFailed) "Couldn't play the reply audio." else it.error,
                    )
                }
            }
        } catch (abort: TurnAbort) {
            player.stop()
            failTurn(abort.userMessage)
        } catch (c: CancellationException) {
            // A new recording interrupted this turn — startRecording owns cleanup.
            throw c
        } catch (_: Exception) {
            // Anything unexpected (synthesis/playback/stream failure) ends the turn
            // cleanly rather than leaving the UI stuck on "Hermes is responding…".
            player.stop()
            failTurn("Hermes ran into a problem while responding.")
        } finally {
            // Make sure the looping filler is never left running once the turn ends
            // or is cancelled. No-op if playback already stopped it.
            thinkingSound.stop()
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

    /** Toggles the thinking trace on [messageId] (collapsed after the answer begins). */
    fun toggleThinking(messageId: String) = _state.update { state ->
        state.copy(
            messages = state.messages.map { m ->
                if (m.id != messageId) m else m.copy(parts = TurnParts.toggleThinking(m.parts))
            },
        )
    }

    private fun append(sender: Sender, text: String) {
        val message = ChatMessage(
            id = "${sender}_${System.currentTimeMillis()}",
            sender = sender,
            text = text,
        )
        _state.update { it.copy(messages = it.messages + message) }
    }

    /**
     * Mutates the streaming assistant message's [parts], creating it if needed.
     * Mirrors the text-chat assembly so voice shows the same live thinking/tools.
     */
    private fun updateParts(bubbleId: String, transform: (List<TurnPart>) -> List<TurnPart>) =
        _state.update { state ->
            val exists = state.messages.any { it.id == bubbleId }
            val base = if (exists) {
                state.messages
            } else {
                state.messages + ChatMessage(id = bubbleId, sender = Sender.ASSISTANT, text = "", streaming = true)
            }
            state.copy(
                messages = base.map {
                    if (it.id == bubbleId) it.copy(parts = transform(it.parts), streaming = true) else it
                },
            )
        }

    private fun ensureBubble(bubbleId: String) = updateParts(bubbleId) { it }

    private fun appendText(bubbleId: String, delta: String) =
        updateParts(bubbleId) { TurnParts.appendText(it, delta) }

    private fun appendThinking(bubbleId: String, delta: String) =
        updateParts(bubbleId) { TurnParts.appendThinking(it, delta) }

    private fun addTool(bubbleId: String, e: ChatEvent.ToolStart) =
        updateParts(bubbleId) { TurnParts.addTool(it, e.toolId, e.name, e.context) }

    private fun completeTool(bubbleId: String, e: ChatEvent.ToolComplete) =
        updateParts(bubbleId) { TurnParts.completeTool(it, e.toolId, e.summary, e.resultText, e.durationS) }

    private fun finishBubble(bubbleId: String, finalText: String) = _state.update { state ->
        val exists = state.messages.any { it.id == bubbleId }
        val messages = if (exists) {
            state.messages.map { m ->
                if (m.id != bubbleId) {
                    m
                } else {
                    var parts = TurnParts.collapseThinking(m.parts)
                    // Tools-only stream with no text segment → append the final answer
                    // so it isn't lost.
                    if (m.parts.isNotEmpty() &&
                        m.parts.none { it is TurnPart.Text } &&
                        finalText.isNotBlank()
                    ) {
                        parts = parts + TurnPart.Text(finalText)
                    }
                    m.copy(
                        text = finalText.ifBlank { m.text }.ifBlank { "(empty response)" },
                        parts = parts,
                        streaming = false,
                    )
                }
            }
        } else {
            state.messages + ChatMessage(
                id = bubbleId,
                sender = Sender.ASSISTANT,
                text = finalText.ifBlank { "(empty response)" },
            )
        }
        state.copy(messages = messages)
    }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
        thinkingSound.stop()
    }
}
