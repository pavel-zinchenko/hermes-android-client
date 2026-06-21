package com.hermes.android.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.SttConfig
import com.hermes.android.ui.AppViewModelProvider
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the server-side speech-recognition (STT) provider page. STT isn't a Hermes
 * toolset, so the repository assembles the choices from the config schema/values and
 * writes `stt.provider` via PUT /api/config; keys go through PUT /api/env. After each
 * successful mutation it re-lists so the selection / key state repaint from the server.
 */
class SttViewModel(
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
            repository.listSttConfig()
                .onSuccess { config ->
                    _state.update {
                        it.copy(
                            loading = false,
                            currentLabel = currentLabel(config),
                            rows = config.providers.map { row ->
                                ProviderRowUi(
                                    id = row.slug,
                                    label = row.label,
                                    badge = if (row.keyEnv != null && row.keySet) "✓ key set" else null,
                                    selected = row.slug == config.currentProvider,
                                    keyEnv = row.keyEnv,
                                    keySet = row.keySet,
                                )
                            },
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
            repository.setSttProvider(id)
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
            repository.saveSttKey(envVar, key)
                .onSuccess {
                    _state.update { it.copy(busyId = null, keyDrafts = it.keyDrafts - id) }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(busyId = null, error = err.toUserMessage()) }
                }
        }
    }

    /** Display the active provider's label (not its bare slug) when we know it. */
    private fun currentLabel(config: SttConfig): String? {
        val slug = config.currentProvider ?: return null
        return config.providers.firstOrNull { it.slug == slug }?.label ?: slug
    }
}

@Composable
fun SttScreen(
    onBack: () -> Unit,
    viewModel: SttViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProviderPickerScreen(
        title = "Speech recognition",
        description = "Which engine Hermes uses to transcribe your voice. " +
            "Applies to new recordings.",
        state = state,
        onBack = onBack,
        onSelect = viewModel::selectProvider,
        onKeyDraftChange = viewModel::onKeyDraftChange,
        onSaveKey = viewModel::saveKey,
    )
}
