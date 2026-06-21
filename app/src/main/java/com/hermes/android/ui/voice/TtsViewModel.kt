package com.hermes.android.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.dto.ToolsetProviderDto
import com.hermes.android.ui.AppViewModelProvider
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the server-side TTS provider page. TTS is a Hermes "toolset", so this lists
 * providers + key status (GET .../toolsets/tts/config), selects the active provider
 * (PUT .../provider), and saves provider keys (PUT .../env). After every successful
 * mutation it re-lists so the active flag / key state repaint from the source of truth.
 */
class TtsViewModel(
    private val repository: HermesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProviderPickerUiState())
    val state: StateFlow<ProviderPickerUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.listTtsConfig()
                .onSuccess { config ->
                    _state.update {
                        it.copy(
                            loading = false,
                            currentLabel = config.activeProvider,
                            rows = config.providers.map(::toRow),
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(loading = false, error = err.toUserMessage()) }
                }
        }
    }

    fun onKeyDraftChange(id: String, text: String) {
        _state.update { it.copy(keyDrafts = it.keyDrafts + (id to text)) }
    }

    fun selectProvider(id: String) {
        _state.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            repository.setTtsProvider(id)
                .onSuccess { _state.update { it.copy(busyId = null) }; refresh() }
                .onFailure { err ->
                    _state.update { it.copy(busyId = null, error = err.toUserMessage()) }
                }
        }
    }

    fun saveKey(id: String) {
        val envVar = _state.value.rows.firstOrNull { it.id == id }?.keyEnv ?: return
        val key = _state.value.keyDrafts[id]?.trim().orEmpty()
        if (key.isEmpty()) return
        _state.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            repository.saveTtsKeys(mapOf(envVar to key))
                .onSuccess {
                    _state.update { it.copy(busyId = null, keyDrafts = it.keyDrafts - id) }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(busyId = null, error = err.toUserMessage()) }
                }
        }
    }

    private fun toRow(provider: ToolsetProviderDto): ProviderRowUi {
        val needsKey = provider.envVars.isNotEmpty()
        val unsetEnv = provider.envVars.firstOrNull { !it.isSet }?.key
        val allSet = needsKey && unsetEnv == null
        return ProviderRowUi(
            id = provider.name,
            label = provider.name,
            badge = if (allSet) "✓ key set" else null,
            selected = provider.isActive,
            keyEnv = if (provider.requiresNousAuth) null else unsetEnv,
            keySet = allSet,
            externalSetup = provider.requiresNousAuth,
        )
    }
}

@Composable
fun TtsScreen(
    onBack: () -> Unit,
    viewModel: TtsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProviderPickerScreen(
        title = "Speech (TTS)",
        description = "Which engine Hermes uses to speak. Applies to new replies.",
        state = state,
        onBack = onBack,
        onSelect = viewModel::selectProvider,
        onKeyDraftChange = viewModel::onKeyDraftChange,
        onSaveKey = viewModel::saveKey,
    )
}
