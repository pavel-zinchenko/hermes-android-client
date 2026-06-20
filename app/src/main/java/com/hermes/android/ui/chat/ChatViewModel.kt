package com.hermes.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.data.model.ToolState
import com.hermes.android.data.model.TurnPart
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
)

/** Collapses every thinking part in the turn (used once the answer begins). */
private fun List<TurnPart>.collapseThinking(): List<TurnPart> =
    map { if (it is TurnPart.Thinking) it.copy(expanded = false) else it }

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
            val streaming = repository.streamingEnabled()
            val result = if (streaming) {
                repository.getMessagesViaGateway(sessionId)
            } else {
                repository.getMessages(sessionId)
            }
            result
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

        viewModelScope.launch {
            if (repository.streamingEnabled()) {
                sendStreaming(trimmed)
            } else {
                sendBlocking(trimmed)
            }
        }
    }

    private suspend fun sendBlocking(text: String) {
        repository.sendMessage(sessionId, text)
            .onSuccess { reply ->
                val assistant = ChatMessage(
                    id = "reply_${System.currentTimeMillis()}",
                    sender = Sender.ASSISTANT,
                    text = reply.ifBlank { "(empty response)" },
                )
                _state.update { it.copy(messages = it.messages + assistant, sending = false) }
            }
            .onFailure { err ->
                _state.update { it.copy(sending = false, error = err.toUserMessage()) }
            }
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
                        is ChatEvent.Complete -> finishBubble(bubbleId, event.text)
                        is ChatEvent.Failure -> failTurn(bubbleId, event.message)
                    }
                }
        }
        streamJob?.invokeOnCompletion { cause ->
            // Cancellation (Stop) leaves whatever streamed so far; just clear flags.
            _state.update { state ->
                state.copy(
                    sending = false,
                    statusLine = null,
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

    private fun appendText(bubbleId: String, delta: String) = updateParts(bubbleId) { parts ->
        // The visible answer has begun → collapse the thinking trace shown while it
        // streamed. `message.start` fires before any thinking/answer text, so this
        // (the first answer delta), not Start, is when "the answer begins".
        val collapsed = parts.collapseThinking()
        val last = collapsed.lastOrNull()
        if (last is TurnPart.Text) {
            collapsed.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            // A tool/thinking part interleaved before this text → start a new segment.
            collapsed + TurnPart.Text(delta)
        }
    }

    private fun appendThinking(bubbleId: String, delta: String) = updateParts(bubbleId) { parts ->
        val last = parts.lastOrNull()
        if (last is TurnPart.Thinking) {
            parts.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            parts + TurnPart.Thinking(delta)
        }
    }

    private fun addTool(bubbleId: String, e: ChatEvent.ToolStart) = updateParts(bubbleId) { parts ->
        if (parts.any { it is TurnPart.Tool && it.toolId == e.toolId }) {
            parts // idempotent: ignore a duplicate start for the same tool id
        } else {
            parts + TurnPart.Tool(
                toolId = e.toolId,
                name = e.name,
                context = e.context,
                state = ToolState.RUNNING,
            )
        }
    }

    private fun completeTool(bubbleId: String, e: ChatEvent.ToolComplete) = updateParts(bubbleId) { parts ->
        parts.map {
            if (it is TurnPart.Tool && it.toolId == e.toolId) {
                it.copy(
                    state = ToolState.DONE,
                    summary = e.summary ?: it.summary,
                    resultText = e.resultText ?: it.resultText,
                    durationS = e.durationS ?: it.durationS,
                )
            } else it
        }
    }

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
                    val anyExpanded = m.parts.any { it is TurnPart.Thinking && it.expanded }
                    m.copy(
                        parts = m.parts.map {
                            if (it is TurnPart.Thinking) it.copy(expanded = !anyExpanded) else it
                        },
                    )
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

    fun clearError() = _state.update { it.copy(error = null) }
}
