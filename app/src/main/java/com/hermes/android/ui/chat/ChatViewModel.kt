package com.hermes.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.gateway.InteractiveRequest
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.data.model.TurnPart
import com.hermes.android.data.model.TurnParts
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val loadingHistory: Boolean = true,
    val sending: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val statusLine: String? = null,
    val error: String? = null,
    /**
     * Interactive requests the agent is blocked on, oldest first. The agent
     * blocks on one at a time per session, but a queue keeps things serialized;
     * the UI shows only the head.
     */
    val pendingRequests: List<InteractiveRequest> = emptyList(),
)

/** Collapses every thinking part in the turn (used once the answer begins). */
private fun List<TurnPart>.collapseThinking(): List<TurnPart> = TurnParts.collapseThinking(this)

class ChatViewModel(
    private val repository: HermesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) {
        "ChatViewModel requires a sessionId nav argument"
    }

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** The in-flight streaming turn, so [stop] can cancel it. */
    private var streamJob: Job? = null

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

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return

        val pending = ChatMessage(
            id = "local_${System.currentTimeMillis()}",
            sender = Sender.USER,
            text = trimmed,
        )
        _state.update {
            it.copy(messages = it.messages + pending, sending = true, statusLine = null, error = null)
        }

        sendStreaming(trimmed)
    }

    private fun sendStreaming(text: String) {
        val bubbleId = "stream_${System.currentTimeMillis()}"
        streamJob = viewModelScope.launch {
            repository.submitPromptStreaming(sessionId, text)
                .collect { event ->
                    when (event) {
                        ChatEvent.Start -> ensureStreamBubble(bubbleId)
                        is ChatEvent.Delta -> appendText(bubbleId, event.text)
                        is ChatEvent.Thinking -> appendThinking(bubbleId, event.text)
                        is ChatEvent.ToolStart -> addTool(bubbleId, event)
                        is ChatEvent.ToolComplete -> completeTool(bubbleId, event)
                        is ChatEvent.Status ->
                            _state.update { it.copy(statusLine = event.text.ifBlank { null }) }
                        is ChatEvent.Interactive ->
                            _state.update { it.copy(pendingRequests = it.pendingRequests + event.request) }
                        is ChatEvent.Complete -> finishBubble(bubbleId, event.text)
                        is ChatEvent.Failure -> failTurn(bubbleId, event.message)
                    }
                }
        }
        streamJob?.invokeOnCompletion { cause ->
            // Cancellation (Stop) leaves whatever streamed so far; just clear flags.
            // Only Stop calls session.interrupt, which clears pending prompts
            // server-side, so only then drop any unanswered request dialog to
            // match. A normal/error close leaves the queue alone — hiding a dialog
            // the agent is still blocked on would strand the turn server-side.
            _state.update { state ->
                state.copy(
                    sending = false,
                    statusLine = null,
                    pendingRequests = if (cause != null) emptyList() else state.pendingRequests,
                    messages = state.messages.map {
                        if (it.id == bubbleId) {
                            it.copy(parts = it.parts.collapseThinking(), streaming = false)
                        } else it
                    },
                )
            }
        }
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

    /** First turn activity (`message.start`): make sure the streaming bubble exists. */
    private fun ensureStreamBubble(bubbleId: String) = updateParts(bubbleId) { it }

    // The visible answer beginning (the first answer delta, not Start — `message.start`
    // fires before any thinking/answer text) collapses the thinking trace; that and
    // the tool/thinking folding live in the shared [TurnParts] reducer.
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
                    var parts = m.parts.collapseThinking()
                    // If the turn produced parts but no text segment, append the
                    // final answer so it isn't lost (e.g. tools-only streaming).
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
        state.copy(sending = false, statusLine = null, messages = messages)
    }

    private fun failTurn(bubbleId: String, message: String) = _state.update { state ->
        state.copy(
            sending = false,
            statusLine = null,
            error = message,
            // Drop a bubble with nothing to show; keep any partial text/tools.
            messages = state.messages.mapNotNull {
                when {
                    it.id != bubbleId -> it
                    it.text.isBlank() && it.parts.isEmpty() -> null
                    else -> it.copy(parts = it.parts.collapseThinking(), streaming = false)
                }
            },
        )
    }

    /** Toggles the thinking trace on [messageId] (collapsed after the answer begins). */
    fun toggleThinking(messageId: String) = _state.update { state ->
        state.copy(
            messages = state.messages.map { m ->
                if (m.id != messageId) {
                    m
                } else {
                    m.copy(parts = TurnParts.toggleThinking(m.parts))
                }
            },
        )
    }

    /** Interrupts the in-flight streaming turn. */
    fun stop() {
        if (!_state.value.sending) return
        viewModelScope.launch { repository.interrupt(sessionId) }
        streamJob?.cancel()
    }

    /**
     * Answers [head] (which must be the current queue head) via [rpc]. Dequeues
     * [head] synchronously *before* launching the RPC — and only if it is still
     * the head — so a stray second dispatch for an already-answered request is a
     * no-op rather than resolving the next queued one. Always dequeues so a
     * broken request can't wedge the dialog host.
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

    fun clearError() = _state.update { it.copy(error = null) }
}
