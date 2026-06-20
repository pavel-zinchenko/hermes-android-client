package com.hermes.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.model.ChatSession
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val loading: Boolean = true,
    val sessions: List<ChatSession> = emptyList(),
    val error: String? = null,
)

class SessionsViewModel(
    private val repository: HermesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Streaming mode lists over the gateway so the screen doesn't depend
            // on the REST api_server (8642).
            val result = if (repository.streamingEnabled()) {
                repository.listSessionsViaGateway()
            } else {
                repository.listSessions()
            }
            result
                .onSuccess { sessions ->
                    _state.update { it.copy(loading = false, sessions = sessions, error = null) }
                }
                .onFailure { err ->
                    _state.update { it.copy(loading = false, error = err.toUserMessage()) }
                }
        }
    }

    /** Creates a new session and returns its id via [onCreated] for navigation. */
    fun createSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            // In streaming mode the session must be created over the gateway so it
            // inherits the configured model; a REST-created session is stamped with
            // the "hermes-agent" label that the gateway later rejects.
            val result = if (repository.streamingEnabled()) {
                repository.createSessionViaGateway()
            } else {
                repository.createSession()
            }
            result
                .onSuccess { session ->
                    refresh()
                    onCreated(session.id)
                }
                .onFailure { err ->
                    _state.update { it.copy(error = err.toUserMessage()) }
                }
        }
    }

    fun deleteSession(id: String) {
        // Optimistic removal; refresh reconciles with the server.
        _state.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == id }) }
        viewModelScope.launch {
            val result = if (repository.streamingEnabled()) {
                repository.deleteSessionViaGateway(id)
            } else {
                repository.deleteSession(id)
            }
            result
                .onSuccess { refresh() }
                .onFailure { err ->
                    _state.update { it.copy(error = err.toUserMessage()) }
                    refresh()
                }
        }
    }
}
