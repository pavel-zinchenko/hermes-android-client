package com.hermes.android.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Checking : ConnectionState
    data object Connected : ConnectionState
    data class Unreachable(val message: String) : ConnectionState
}

class ConnectionViewModel(
    private val repository: HermesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Checking)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    init {
        probe()
    }

    fun probe() {
        _state.value = ConnectionState.Checking
        viewModelScope.launch {
            repository.checkHealth()
                .onSuccess { ok ->
                    _state.value = if (ok) {
                        ConnectionState.Connected
                    } else {
                        ConnectionState.Unreachable("Hermes responded but is not healthy.")
                    }
                }
                .onFailure {
                    _state.value = ConnectionState.Unreachable(it.toUserMessage())
                }
        }
    }
}
