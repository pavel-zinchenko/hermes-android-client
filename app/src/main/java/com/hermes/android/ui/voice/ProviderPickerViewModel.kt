package com.hermes.android.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.ui.providers.ProviderDetailUi
import com.hermes.android.ui.providers.ProviderListRowUi
import com.hermes.android.ui.providers.ProviderTestResult
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state shared by the TTS and STT picker ViewModels. */
data class ProviderPickerUiState(
    val loading: Boolean = true,
    val currentLabel: String? = null,
    val rows: List<ProviderListRowUi> = emptyList(),
    /** Detail screen model per provider id, built at refresh time. */
    val details: Map<String, ProviderDetailUi> = emptyMap(),
    /** Provider id whose detail screen is open, or null. */
    val detailId: String? = null,
    /** Per-row API-key draft text, keyed by provider id. */
    val keyDrafts: Map<String, String> = emptyMap(),
    /** Id of the row running a select/save (shows a spinner, disables input). */
    val busyId: String? = null,
    val error: String? = null,
    val testResult: ProviderTestResult = ProviderTestResult.Idle,
)

/**
 * Base for the server-side voice provider pickers (TTS, STT). They differ only in
 * their backend — a toolset vs. plain config — so the load/activate/persist steps
 * are abstract and everything else (state plumbing, detail open, key check) is shared.
 * After every successful mutation it re-lists so selection/key state repaint from
 * the source of truth.
 */
abstract class ProviderPickerViewModel(
    protected val repository: HermesRepository,
) : ViewModel() {

    protected val _state = MutableStateFlow(ProviderPickerUiState())
    val state: StateFlow<ProviderPickerUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            load()
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            loading = false,
                            currentLabel = snapshot.currentLabel,
                            rows = snapshot.rows,
                            details = snapshot.details,
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(loading = false, error = err.toUserMessage()) }
                }
        }
    }

    fun openDetail(id: String) {
        _state.update { it.copy(detailId = id, testResult = ProviderTestResult.Idle, error = null) }
    }

    fun onKeyDraftChange(id: String, text: String) {
        _state.update { it.copy(keyDrafts = it.keyDrafts + (id to text)) }
    }

    /** Activates a provider in place (no model needed for voice providers). */
    fun selectProvider(id: String) {
        _state.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            activate(id)
                .onSuccess { _state.update { it.copy(busyId = null) }; refresh() }
                .onFailure { err ->
                    _state.update { it.copy(busyId = null, error = err.toUserMessage()) }
                }
        }
    }

    fun saveKey(id: String) {
        val envVar = _state.value.details[id]?.keyEnv ?: return
        val key = _state.value.keyDrafts[id]?.trim().orEmpty()
        if (key.isEmpty()) return
        _state.update { it.copy(busyId = id, error = null) }
        viewModelScope.launch {
            persistKey(envVar, key)
                .onSuccess {
                    _state.update { it.copy(busyId = null, keyDrafts = it.keyDrafts - id) }
                    refresh()
                }
                .onFailure { err ->
                    _state.update { it.copy(busyId = null, error = err.toUserMessage()) }
                }
        }
    }

    /**
     * Real round-trip test: the audio endpoints have no per-provider override, so we
     * first [activate] the opened provider (making it the active one), repaint from a
     * fresh listing, then run the type-specific [runTest] (speak / synthesize+transcribe).
     * If the test fails we roll back to whichever provider was active before, so a probe
     * never strands the user on a provider that doesn't work.
     */
    fun testProvider(id: String) {
        val previousActive = _state.value.rows.firstOrNull { it.selected }?.id
        _state.update { it.copy(testResult = ProviderTestResult.Testing, busyId = id, error = null) }
        viewModelScope.launch {
            val activated = activate(id)
            if (activated.isFailure) {
                _state.update {
                    it.copy(
                        busyId = null,
                        testResult = ProviderTestResult.Failure(
                            activated.exceptionOrNull()?.toUserMessage() ?: "Couldn't select provider.",
                        ),
                    )
                }
                return@launch
            }
            // Repaint selection/key state from the source of truth before testing.
            repaintFromLoad()
            val result = runCatching { runTest(id) }
                .getOrElse { ProviderTestResult.Failure(it.toUserMessage()) }
            // A failed probe shouldn't leave the user switched to a broken provider:
            // restore the one that was active before and repaint the rollback.
            if (result is ProviderTestResult.Failure && previousActive != null && previousActive != id) {
                activate(previousActive)
                repaintFromLoad()
            }
            _state.update { it.copy(busyId = null, testResult = result) }
        }
    }

    /** Re-lists providers and repaints selection/key state from the source of truth. */
    private suspend fun repaintFromLoad() {
        load().onSuccess { snapshot ->
            _state.update {
                it.copy(
                    currentLabel = snapshot.currentLabel,
                    rows = snapshot.rows,
                    details = snapshot.details,
                )
            }
        }
    }

    /** Type-specific round trip after the provider is active (TTS speak, STT transcribe). */
    protected abstract suspend fun runTest(id: String): ProviderTestResult

    /** Lists providers + key status, mapped to rows and per-provider detail models. */
    protected abstract suspend fun load(): Result<Snapshot>

    /** Selects [id] as the active provider. */
    protected abstract suspend fun activate(id: String): Result<Unit>

    /** Saves an API key for the active provider's env var. */
    protected abstract suspend fun persistKey(envVar: String, value: String): Result<Unit>

    protected data class Snapshot(
        val currentLabel: String?,
        val rows: List<ProviderListRowUi>,
        val details: Map<String, ProviderDetailUi>,
    )
}

/** Badge shown on a provider list row, shared by TTS and STT. */
internal fun providerBadge(externalSetup: Boolean, keyEnv: String?, keySet: Boolean): String? = when {
    keySet -> "✓ key set"
    externalSetup -> null
    keyEnv != null -> "add key"
    else -> null
}
