package com.hermes.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.VoiceEngine
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
    val voiceEngine: VoiceEngine = VoiceEngine.SERVER,
    val loaded: Boolean = false,
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
                    voiceEngine = current.voiceEngine,
                    loaded = true,
                )
            }
        }
    }

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value, testResult = TestResult.Idle) }
        persistServer()
    }

    fun onApiKeyChange(value: String) {
        _state.update { it.copy(apiKey = value, testResult = TestResult.Idle) }
        persistServer()
    }

    /**
     * Writes the current URL + token straight to settings. Called on every edit so the
     * server config saves automatically (no Save button). DataStore serializes writes,
     * so overlapping keystroke saves can't corrupt each other — the last value wins.
     */
    private fun persistServer() {
        val s = _state.value
        viewModelScope.launch { repository.updateSettings(s.serverUrl, s.apiKey) }
    }

    /** Persists the picked thinking-sound URI immediately (it carries a permission). */
    fun setThinkingSound(uri: String) {
        _state.update { it.copy(thinkingSoundUri = uri) }
        viewModelScope.launch { repository.updateThinkingSound(uri) }
    }

    fun clearThinkingSound() = setThinkingSound("")

    /** Persists the chosen voice engine immediately (a simple toggle, no Save needed). */
    fun setVoiceEngine(engine: VoiceEngine) {
        _state.update { it.copy(voiceEngine = engine) }
        viewModelScope.launch { repository.updateVoiceEngine(engine) }
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
