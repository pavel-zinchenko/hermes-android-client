package com.hermes.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.audio.AudioDecoder
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.audio.OnDeviceTts
import com.hermes.android.audio.SentenceChunker
import com.hermes.android.audio.SpeechSanitizer
import com.hermes.android.audio.ThinkingSoundPlayer
import com.hermes.android.audio.VoiceRecorder
import com.hermes.android.data.DecodedAudio
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.VoiceEngine
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.gateway.InteractiveRequest
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.data.model.TurnPart
import com.hermes.android.data.model.TurnParts
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

/** Phases of one push-to-talk voice turn. */
enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING, RESPONDING }

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
 * Single state for one chat session, shared by the text-chat and voice screens.
 * [messages] is the one source of truth both screens render and append to, so a
 * voice turn shows up instantly in text chat and vice versa. [sending]/[statusLine]/
 * [pendingRequests] drive the text screen; [phase] drives the voice screen.
 */
data class ChatSessionUiState(
    val loadingHistory: Boolean = true,
    val sending: Boolean = false,
    val statusLine: String? = null,
    val error: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    /**
     * Interactive requests the agent is blocked on, oldest first. The agent blocks
     * on one at a time per session; the UI shows only the head.
     */
    val pendingRequests: List<InteractiveRequest> = emptyList(),
    val phase: VoicePhase = VoicePhase.IDLE,
)

/**
 * Backs both the text-chat and voice screens for a single session. Merging the two
 * means one in-memory [ChatSessionUiState.messages] list is shared live across the
 * screens (scoped to the chat/voice nav subgraph), so switching modes shows the same
 * conversation without re-fetching. Persistence remains the server's job.
 *
 * Text and voice each run on their own job ([streamJob] / [turnJob]). They're
 * different screens, but the user can switch screens mid-turn, so each entry point
 * first [cancelActiveTurn]s any in-flight turn of *either* mode (interrupting the
 * server when needed) before starting — the single shared session must never have
 * two turns racing on it. The assistant turn streams identically in both modes
 * (thinking/tool/text parts via the shared [TurnParts] reducer); voice additionally
 * speaks the answer sentence by sentence — each fragment sanitized of Markdown/emoji,
 * synthesized, and played while the next streams in.
 */
class ChatSessionViewModel(
    private val repository: HermesRepository,
    private val recorder: VoiceRecorder,
    private val player: AudioPlayer,
    private val thinkingSound: ThinkingSoundPlayer,
    private val onDeviceTts: OnDeviceTts,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) {
        "ChatSessionViewModel requires a sessionId nav argument"
    }

    private val _state = MutableStateFlow(ChatSessionUiState())
    val state: StateFlow<ChatSessionUiState> = _state.asStateFlow()

    /** The in-flight text streaming turn, so [stop] can cancel it. */
    private var streamJob: Job? = null

    /** The in-flight voice transcribe → respond turn, so a new press can interrupt it. */
    private var turnJob: Job? = null

    /**
     * True only while a server turn is genuinely in flight — between `prompt.submit`
     * and its terminal `message.complete`/`error`. Gates the server-side interrupt on
     * barge-in: interrupting an *idle* session sets a sticky `_interrupt_requested`
     * flag that the agent never clears, poisoning the next turn into an empty reply.
     * Note the local playback of an already-finished answer is NOT a server turn.
     */
    private var serverTurnActive = false

    init {
        loadHistory()
    }

    fun loadHistory() {
        _state.update { it.copy(loadingHistory = true, error = null) }
        viewModelScope.launch {
            repository.getMessages(sessionId)
                .onSuccess { history ->
                    _state.update { it.copy(loadingHistory = false, messages = history) }
                }
                .onFailure { err ->
                    _state.update { it.copy(loadingHistory = false, error = err.toUserMessage()) }
                }
        }
    }

    // --- Text chat ---------------------------------------------------------

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        // A voice turn may still be running if the user switched screens mid-turn;
        // cancel it (and interrupt the server) so the two never race on one session.
        cancelActiveTurn()

        val pending = ChatMessage(
            id = "local_${System.currentTimeMillis()}",
            sender = Sender.USER,
            text = trimmed,
        )
        _state.update {
            it.copy(
                messages = it.messages + pending,
                sending = true,
                statusLine = null,
                error = null,
                phase = VoicePhase.IDLE,
            )
        }
        sendStreaming(trimmed)
    }

    private fun sendStreaming(text: String) {
        val bubbleId = "stream_${System.currentTimeMillis()}"
        streamJob = viewModelScope.launch {
            repository.submitPromptStreaming(sessionId, text)
                .collect { event ->
                    when (event) {
                        ChatEvent.Start -> ensureBubble(bubbleId)
                        is ChatEvent.Delta -> appendText(bubbleId, event.text)
                        is ChatEvent.Thinking -> appendThinking(bubbleId, event.text)
                        is ChatEvent.ToolStart -> addTool(bubbleId, event)
                        is ChatEvent.ToolComplete -> completeTool(bubbleId, event)
                        is ChatEvent.Status ->
                            _state.update { it.copy(statusLine = event.text.ifBlank { null }) }
                        is ChatEvent.Interactive ->
                            _state.update { it.copy(pendingRequests = it.pendingRequests + event.request) }
                        is ChatEvent.Complete -> finishBubble(bubbleId, event.text)
                        is ChatEvent.Failure -> failStreamTurn(bubbleId, event.message)
                    }
                }
        }
        streamJob?.invokeOnCompletion { cause ->
            // Cancellation (Stop) leaves whatever streamed so far; just clear flags.
            // Only Stop calls session.interrupt, which clears pending prompts
            // server-side, so only then drop any unanswered request dialog to match.
            _state.update { state ->
                state.copy(
                    sending = false,
                    statusLine = null,
                    pendingRequests = if (cause != null) emptyList() else state.pendingRequests,
                    messages = state.messages.map {
                        if (it.id == bubbleId) {
                            it.copy(parts = TurnParts.collapseThinking(it.parts), streaming = false)
                        } else it
                    },
                )
            }
        }
    }

    /** Interrupts the in-flight text streaming turn. */
    fun stop() {
        if (!_state.value.sending) return
        viewModelScope.launch { repository.interrupt(sessionId) }
        streamJob?.cancel()
    }

    private fun failStreamTurn(bubbleId: String, message: String) = _state.update { state ->
        state.copy(
            sending = false,
            statusLine = null,
            error = message,
            // Drop a bubble with nothing to show; keep any partial text/tools.
            messages = state.messages.mapNotNull {
                when {
                    it.id != bubbleId -> it
                    it.text.isBlank() && it.parts.isEmpty() -> null
                    else -> it.copy(parts = TurnParts.collapseThinking(it.parts), streaming = false)
                }
            },
        )
    }

    // --- Interactive requests (text chat) ----------------------------------

    /**
     * Answers [head] (the current queue head) via [rpc]. Dequeues [head] synchronously
     * *before* launching the RPC — and only if it is still the head — so a stray second
     * dispatch for an already-answered request is a no-op rather than resolving the
     * next queued one. Always dequeues so a broken request can't wedge the dialog host.
     */
    private fun respond(head: InteractiveRequest, rpc: suspend () -> Result<Unit>) {
        if (_state.value.pendingRequests.firstOrNull() !== head) return
        _state.update { it.copy(pendingRequests = it.pendingRequests.drop(1)) }
        viewModelScope.launch {
            rpc().onFailure { err -> _state.update { it.copy(error = err.toUserMessage()) } }
        }
    }

    /** Approval choice: "once", "session", or "deny". */
    fun respondApproval(choice: String) {
        val req = _state.value.pendingRequests.firstOrNull() as? InteractiveRequest.Approval ?: return
        respond(req) { repository.respondApproval(sessionId, choice) }
    }

    fun respondClarify(answer: String) {
        val req = _state.value.pendingRequests.firstOrNull() as? InteractiveRequest.Clarify ?: return
        respond(req) { repository.respondClarify(req.requestId, answer) }
    }

    fun respondSudo(password: String) {
        val req = _state.value.pendingRequests.firstOrNull() as? InteractiveRequest.Sudo ?: return
        respond(req) { repository.respondSudo(req.requestId, password) }
    }

    fun respondSecret(value: String) {
        val req = _state.value.pendingRequests.firstOrNull() as? InteractiveRequest.Secret ?: return
        respond(req) { repository.respondSecret(req.requestId, value) }
    }

    // --- Voice -------------------------------------------------------------

    /**
     * Called when the user presses the talk button. The button is always live: a press
     * while Hermes is transcribing, responding, or speaking interrupts that turn
     * (cancels the in-flight work, stops playback and the thinking sound) and
     * immediately starts a fresh recording.
     */
    fun startRecording() {
        if (_state.value.phase == VoicePhase.RECORDING) return
        // Interrupt whatever Hermes was doing before we grab the mic — a prior voice
        // turn or an in-flight text stream — so two turns never race on one session.
        cancelActiveTurn()
        try {
            recorder.start()
            _state.update { it.copy(phase = VoicePhase.RECORDING, error = null) }
        } catch (_: Exception) {
            _state.update { it.copy(phase = VoicePhase.IDLE, error = "Couldn't start recording.") }
        }
    }

    /**
     * Cancels any in-flight turn of *either* mode and interrupts the server if one was
     * genuinely running, so a fresh turn never races an existing one on the single
     * shared session. Cancelling the local job only stops *our* side; the server agent
     * keeps running unless we also interrupt it — but interrupting an *idle* session
     * sets a sticky flag the agent never clears, poisoning the next turn into an empty
     * reply, so we only interrupt when a server turn is actually live ([serverTurnActive]
     * for voice, [ChatSessionUiState.sending] for text). [streamJob]'s completion handler
     * clears the text flags; voice phase is reset by whichever caller starts next.
     */
    private fun cancelActiveTurn() {
        val interruptServer = serverTurnActive || _state.value.sending
        serverTurnActive = false
        streamJob?.cancel()
        streamJob = null
        turnJob?.cancel()
        turnJob = null
        player.stop()
        thinkingSound.release()
        if (interruptServer) viewModelScope.launch { repository.interrupt(sessionId) }
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
            failVoiceTurn(err.toUserMessage())
            return
        }
        // If this turn was interrupted (the user started a new recording), bail before
        // touching state so we don't clobber the fresh RECORDING phase.
        if (!currentCoroutineContext().isActive) return
        // Whisper hallucinates filler words on near-silence; treat those (and a blank
        // result) as "nothing said" so we never append a bogus bubble or call Hermes.
        if (transcript.isBlank() || isLikelyNoise(transcript)) {
            failVoiceTurn("Didn't catch that — try again.")
            return
        }
        append(Sender.USER, transcript)

        _state.update { it.copy(phase = VoicePhase.RESPONDING) }
        // Pick the synthesizer once for the whole turn so a mid-turn settings change
        // can't split one reply across two engines.
        val engine = repository.voiceEngine()
        // The filler sound covers every gap where nothing is playing but the turn is
        // still going. Prepare it once for the whole turn so it resumes instantly on
        // each gap; start it now to cover the wait for the first sentence.
        val fillerUri = repository.thinkingSoundUri()
        thinkingSound.prepare(fillerUri)
        thinkingSound.resume()

        val bubbleId = "assistant_${System.currentTimeMillis()}"
        val chunker = SentenceChunker()
        // Whether any fragment was enqueued for speech, and whether any actually
        // synthesized — so the end-of-turn check can tell a wholly-failed TTS turn
        // from a normal one.
        var spoke = false
        var synthesizedAny = false
        try {
            coroutineScope {
                // Completed text fragments awaiting synthesis; UNLIMITED so feeding it
                // from the event collector never blocks.
                val sentences = Channel<String>(Channel.UNLIMITED)
                // Synthesized clips awaiting playback. A small buffer keeps synthesis
                // just ahead of playback while keeping `tryReceive` deterministic.
                val audios = Channel<DecodedAudio>(capacity = 3)

                val synth = launch {
                    for (fragment in sentences) {
                        // Strip Markdown/emoji so TTS never reads syntax or pictographs
                        // aloud. A fragment that is pure decoration cleans to blank — skip it.
                        val speakable = SpeechSanitizer.clean(fragment)
                        if (speakable.isBlank()) continue
                        synthesize(engine, speakable)
                            .onSuccess { clip ->
                                synthesizedAny = true
                                audios.send(tightened(clip))
                            }
                            .onFailure { /* skip this fragment; keep the rest flowing */ }
                    }
                    audios.close()
                }
                val playback = launch {
                    try {
                        while (true) {
                            // Play a ready clip immediately. If none is ready, resume the
                            // (already-prepared) filler to bridge the gap and wait for the
                            // next; a closed-and-drained channel ends playback.
                            var clip = audios.tryReceive().getOrNull()
                            if (clip == null) {
                                thinkingSound.resume()
                                clip = audios.receiveCatching().getOrNull()
                                if (clip == null) break
                            }
                            // Pause the filler only while a real clip plays; the next
                            // iteration resumes it if the following clip isn't ready.
                            thinkingSound.pause()
                            try {
                                player.playAndAwait(clip.bytes, clip.mime)
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Exception) {
                                // A bad clip shouldn't wedge the turn — skip it.
                            }
                        }
                    } finally {
                        thinkingSound.release()
                    }
                }

                // The server turn is now in flight (prompt.submit happens inside the
                // collect); a barge-in until a terminal event should interrupt it.
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
                        // Voice has no UI to answer an interactive request, and the agent
                        // would block server-side forever — end the turn and point the
                        // user at text chat.
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
            failVoiceTurn(abort.userMessage)
        } catch (c: CancellationException) {
            // A new recording interrupted this turn — startRecording owns cleanup.
            throw c
        } catch (_: Exception) {
            // Anything unexpected ends the turn cleanly rather than leaving the UI
            // stuck on "Hermes is responding…".
            player.stop()
            failVoiceTurn("Hermes ran into a problem while responding.")
        } finally {
            // Make sure the looping filler is never left running once the turn ends.
            thinkingSound.release()
        }
    }

    /**
     * Synthesizes [text] with the [engine] chosen for this turn: the on-device
     * [TextToSpeech][OnDeviceTts] (offline) or Hermes server TTS. Both return the same
     * [DecodedAudio], so the surrounding pipeline (trim → play) is identical.
     */
    private suspend fun synthesize(engine: VoiceEngine, text: String): Result<DecodedAudio> =
        if (engine == VoiceEngine.ON_DEVICE) onDeviceTts.synthesize(text)
        else repository.speak(text)

    /**
     * Trims the leading/trailing silence off a synthesized [clip] so sentences play
     * back-to-back instead of padded with dead air. Decoding is CPU work, so it runs
     * off the main thread; on any failure the original clip plays untrimmed.
     */
    private suspend fun tightened(clip: DecodedAudio): DecodedAudio =
        withContext(Dispatchers.Default) {
            AudioDecoder.toTrimmedWav(clip.bytes, clip.mime)
                ?.let { DecodedAudio(bytes = it, mime = "audio/wav") } ?: clip
        }

    /**
     * Moves the voice turn to IDLE with [message], unless this turn was already
     * interrupted — in which case a newer turn owns the phase and we stay quiet.
     */
    private suspend fun failVoiceTurn(message: String) {
        if (!currentCoroutineContext().isActive) return
        _state.update { it.copy(phase = VoicePhase.IDLE, error = message) }
    }

    private fun append(sender: Sender, text: String) {
        val message = ChatMessage(
            id = "${sender}_${System.currentTimeMillis()}",
            sender = sender,
            text = text,
        )
        _state.update { it.copy(messages = it.messages + message) }
    }

    // --- Shared streamed-turn assembly (text + voice) ----------------------

    /** Toggles the thinking trace on [messageId] (collapsed after the answer begins). */
    fun toggleThinking(messageId: String) = _state.update { state ->
        state.copy(
            messages = state.messages.map { m ->
                if (m.id != messageId) m else m.copy(parts = TurnParts.toggleThinking(m.parts))
            },
        )
    }

    /**
     * Mutates the [bubbleId] assistant message's [parts], creating the bubble if it
     * isn't present yet. Any transient status line is cleared once real content arrives.
     */
    private fun updateParts(
        bubbleId: String,
        transform: (List<TurnPart>) -> List<TurnPart>,
    ) = _state.update { state ->
        val exists = state.messages.any { it.id == bubbleId }
        val base = if (exists) state.messages else state.messages +
            ChatMessage(id = bubbleId, sender = Sender.ASSISTANT, text = "", streaming = true)
        state.copy(
            statusLine = null,
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

    /** Finalizes the [bubbleId] bubble's content (collapses thinking, settles text). */
    private fun finishBubble(bubbleId: String, finalText: String) = _state.update { state ->
        val exists = state.messages.any { it.id == bubbleId }
        val messages = if (exists) {
            state.messages.map { m ->
                if (m.id != bubbleId) {
                    m
                } else {
                    var parts = TurnParts.collapseThinking(m.parts)
                    // If the turn produced parts but no text segment, append the final
                    // answer so it isn't lost (e.g. tools-only streaming).
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

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
        thinkingSound.release()
        onDeviceTts.shutdown()
    }
}
