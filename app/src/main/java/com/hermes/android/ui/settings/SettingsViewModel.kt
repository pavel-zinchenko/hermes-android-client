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
    val baseUrl: String = "",
    val apiKey: String = "",
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
                it.copy(baseUrl = current.baseUrl, apiKey = current.apiKey, loaded = true)
            }
        }
    }

    fun onBaseUrlChange(value: String) =
        _state.update { it.copy(baseUrl = value, saved = false, testResult = TestResult.Idle) }

    fun onApiKeyChange(value: String) =
        _state.update { it.copy(apiKey = value, saved = false, testResult = TestResult.Idle) }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            repository.updateSettings(s.baseUrl, s.apiKey)
            _state.update { it.copy(saved = true) }
        }
    }

    /** Persists then probes /health so the test reflects exactly what was entered. */
    fun testConnection() {
        _state.update { it.copy(testResult = TestResult.Testing) }
        viewModelScope.launch {
            val s = _state.value
            repository.updateSettings(s.baseUrl, s.apiKey)
            _state.update { it.copy(saved = true) }
            repository.checkHealth()
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
