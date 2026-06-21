package com.hermes.android.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.ModelProviderRow
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pending expensive-model confirmation surfaced by POST /api/model/set. */
data class ExpensiveConfirm(
    val provider: String,
    val model: String,
    val message: String,
)

data class ModelsUiState(
    val loading: Boolean = true,
    val providers: List<ModelProviderRow> = emptyList(),
    val currentModel: String? = null,
    val currentProvider: String? = null,
    /** Which provider card is expanded (only one at a time), or null. */
    val expandedSlug: String? = null,
    /** Per-provider API-key draft text, keyed by slug. */
    val keyDrafts: Map<String, String> = emptyMap(),
    /** Slug of the row running a save/select/disconnect (shows a row spinner). */
    val busySlug: String? = null,
    val error: String? = null,
    val confirm: ExpensiveConfirm? = null,
)

/**
 * Drives the Hermes Models screen: lists providers/models (gateway `model.options`),
 * saves/removes provider keys (`model.save_key` / `model.disconnect`), and selects
 * the active main model (REST `POST /api/model/set`). After every successful
 * mutation it re-lists so `is_current`/auth badges repaint from the source of truth.
 */
class ModelsViewModel(
    private val repository: HermesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.listModelOptions()
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            loading = false,
                            providers = result.providers,
                            currentModel = result.model,
                            currentProvider = result.provider,
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(loading = false, error = err.toUserMessage()) }
                }
        }
    }

    fun toggleExpand(slug: String) {
        _state.update {
            it.copy(expandedSlug = if (it.expandedSlug == slug) null else slug, error = null)
        }
    }

    fun onKeyDraftChange(slug: String, text: String) {
        _state.update { it.copy(keyDrafts = it.keyDrafts + (slug to text)) }
    }

    fun saveKey(slug: String) {
        val key = _state.value.keyDrafts[slug]?.trim().orEmpty()
        if (key.isEmpty()) return
        _state.update { it.copy(busySlug = slug, error = null) }
        viewModelScope.launch {
            repository.saveProviderKey(slug, key)
                .onSuccess {
                    // Clear the draft and repaint from a fresh listing.
                    _state.update { it.copy(busySlug = null, keyDrafts = it.keyDrafts - slug) }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(busySlug = null, error = err.toUserMessage()) }
                }
        }
    }

    fun disconnect(slug: String) {
        _state.update { it.copy(busySlug = slug, error = null) }
        viewModelScope.launch {
            repository.disconnectProvider(slug)
                .onSuccess {
                    _state.update { it.copy(busySlug = null) }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(busySlug = null, error = err.toUserMessage()) }
                }
        }
    }

    fun selectModel(provider: String, model: String) = applyModel(provider, model, confirm = false)

    fun confirmExpensive() {
        val pending = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        applyModel(pending.provider, pending.model, confirm = true)
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirm = null, busySlug = null) }
    }

    private fun applyModel(provider: String, model: String, confirm: Boolean) {
        _state.update { it.copy(busySlug = provider, error = null) }
        viewModelScope.launch {
            repository.setMainModel(provider, model, confirm)
                .onSuccess { resp ->
                    when {
                        resp.confirmRequired -> _state.update {
                            it.copy(
                                busySlug = null,
                                confirm = ExpensiveConfirm(
                                    provider = provider,
                                    model = model,
                                    message = resp.confirmMessage
                                        ?: "This model may be expensive. Use it anyway?",
                                ),
                            )
                        }
                        resp.ok -> {
                            _state.update { it.copy(busySlug = null) }
                            refresh()
                        }
                        else -> _state.update {
                            it.copy(busySlug = null, error = "Couldn't switch model.")
                        }
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(busySlug = null, error = err.toUserMessage()) }
                }
        }
    }
}
