package com.hermes.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val loadingHistory: Boolean = true,
    val sending: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
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
        _state.update { it.copy(messages = it.messages + pending, sending = true, error = null) }

        viewModelScope.launch {
            repository.sendMessage(sessionId, trimmed)
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
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
