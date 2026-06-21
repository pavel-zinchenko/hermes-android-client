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
            repository.listSessions()
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
            repository.createSession()
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
        // Optimistic removal; refresh reconciles with the server on success.
        val removed = _state.value.sessions.firstOrNull { it.id == id }
        _state.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == id }) }
        viewModelScope.launch {
            repository.deleteSession(id)
                .onSuccess { refresh() }
                .onFailure { err ->
                    // Re-add just the deleted row and keep the error visible. (refresh()
                    // would clear the error and re-add the still-present row with no
                    // explanation — the "it reappears silently" bug.) Restoring only this
                    // row, rather than a stale whole-list snapshot, preserves any rows
                    // added/removed concurrently while the delete was in flight.
                    _state.update { st ->
                        val sessions = if (removed != null && st.sessions.none { it.id == id }) {
                            st.sessions + removed
                        } else {
                            st.sessions
                        }
                        st.copy(sessions = sessions, error = err.toUserMessage())
                    }
                }
        }
    }
}
