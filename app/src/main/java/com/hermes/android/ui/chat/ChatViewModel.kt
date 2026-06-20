package com.hermes.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
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
                        is ChatEvent.Delta -> appendToBubble(bubbleId, event.text)
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
                        if (it.id == bubbleId) it.copy(streaming = false) else it
                    },
                )
            }
        }
    }

    /** Inserts the assistant bubble on first activity if it isn't present yet. */
    private fun ensureStreamBubble(bubbleId: String) = _state.update { state ->
        if (state.messages.any { it.id == bubbleId }) {
            state
        } else {
            state.copy(
                messages = state.messages +
                    ChatMessage(id = bubbleId, sender = Sender.ASSISTANT, text = "", streaming = true),
            )
        }
    }

    private fun appendToBubble(bubbleId: String, delta: String) = _state.update { state ->
        val exists = state.messages.any { it.id == bubbleId }
        val base = if (exists) state.messages else state.messages +
            ChatMessage(id = bubbleId, sender = Sender.ASSISTANT, text = "", streaming = true)
        state.copy(
            statusLine = null,
            messages = base.map {
                if (it.id == bubbleId) it.copy(text = it.text + delta, streaming = true) else it
            },
        )
    }

    private fun finishBubble(bubbleId: String, finalText: String) = _state.update { state ->
        val exists = state.messages.any { it.id == bubbleId }
        val messages = if (exists) {
            state.messages.map {
                if (it.id == bubbleId) {
                    it.copy(text = finalText.ifBlank { it.text }.ifBlank { "(empty response)" }, streaming = false)
                } else it
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
            // Drop an empty streaming bubble; keep any partial text.
            messages = state.messages.mapNotNull {
                when {
                    it.id != bubbleId -> it
                    it.text.isBlank() -> null
                    else -> it.copy(streaming = false)
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
