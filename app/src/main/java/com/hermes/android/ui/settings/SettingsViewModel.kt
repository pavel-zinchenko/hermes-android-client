package com.hermes.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestResult {
    data object Idle : TestResult
    data object Testing : TestResult
    data object Success : TestResult
    data class Failure(val message: String) : TestResult
}

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val thinkingSoundUri: String = "",
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val testResult: TestResult = TestResult.Idle,
)

class SettingsViewModel(
    private val repository: HermesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Seed the editable fields once from persisted settings.
        viewModelScope.launch {
            val current = repository.currentSettings()
            _state.update {
                it.copy(
                    serverUrl = current.serverUrl,
                    apiKey = current.apiKey,
                    thinkingSoundUri = current.thinkingSoundUri,
                    loaded = true,
                )
            }
        }
    }

    fun onServerUrlChange(value: String) =
        _state.update { it.copy(serverUrl = value, saved = false, testResult = TestResult.Idle) }

    fun onApiKeyChange(value: String) =
        _state.update { it.copy(apiKey = value, saved = false, testResult = TestResult.Idle) }

    /** Persists the picked thinking-sound URI immediately (it carries a permission). */
    fun setThinkingSound(uri: String) {
        _state.update { it.copy(thinkingSoundUri = uri) }
        viewModelScope.launch { repository.updateThinkingSound(uri) }
    }

    fun clearThinkingSound() = setThinkingSound("")

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            repository.updateSettings(s.serverUrl, s.apiKey)
            _state.update { it.copy(saved = true) }
        }
    }

    /**
     * Persists then probes the gateway WebSocket so the test reflects exactly what
     * was entered — and exactly what the app depends on. (The dashboard serves no
     * REST `/health`, so probing the gateway handshake is the real reachability
     * check, matching the connection gate.)
     */
    fun testConnection() {
        _state.update { it.copy(testResult = TestResult.Testing) }
        viewModelScope.launch {
            val s = _state.value
            repository.updateSettings(s.serverUrl, s.apiKey)
            _state.update { it.copy(saved = true) }
            repository.checkHealthViaGateway()
                .onSuccess { ok ->
                    _state.update {
                        it.copy(
                            testResult = if (ok) TestResult.Success
                            else TestResult.Failure("Reached the server, but it is not healthy."),
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(testResult = TestResult.Failure(err.toUserMessage())) }
                }
        }
    }
}
