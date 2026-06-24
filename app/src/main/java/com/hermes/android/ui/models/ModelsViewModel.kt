package com.hermes.android.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.gateway.ModelProviderRow
import com.hermes.android.ui.providers.ProviderDetailUi
import com.hermes.android.ui.providers.ProviderListRowUi
import com.hermes.android.ui.providers.ProviderModelUi
import com.hermes.android.ui.providers.ProviderTestResult
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TEST_TIMEOUT_MS = 60_000L

/** A pending expensive-model confirmation surfaced by POST /api/model/set. */
data class ExpensiveConfirm(
    val provider: String,
    val model: String,
    val message: String,
)

data class ModelsUiState(
    val loading: Boolean = true,
    /** Raw provider rows from the server, used to build the detail screen. */
    val providers: List<ModelProviderRow> = emptyList(),
    val rows: List<ProviderListRowUi> = emptyList(),
    val currentModel: String? = null,
    val currentProvider: String? = null,
    val currentLabel: String? = null,
    /** Slug whose detail screen is open, or null. */
    val detailId: String? = null,
    /** Per-provider API-key draft text, keyed by slug. */
    val keyDrafts: Map<String, String> = emptyMap(),
    /** Slug of the row running a save/select/disconnect (shows a row spinner). */
    val busySlug: String? = null,
    val error: String? = null,
    val testResult: ProviderTestResult = ProviderTestResult.Idle,
    val confirm: ExpensiveConfirm? = null,
)

/** Builds the detail screen model for the currently open provider, or null. */
fun ModelsUiState.detailUi(): ProviderDetailUi? {
    val slug = detailId ?: return null
    val p = providers.firstOrNull { it.slug == slug } ?: return null
    val nonApiKeyAuth = p.authType != null && p.authType != "api_key"
    return ProviderDetailUi(
        id = p.slug,
        title = p.name.ifBlank { p.slug },
        keyEnv = if (nonApiKeyAuth) null else (p.keyEnv ?: "API key"),
        keySet = p.authenticated,
        warning = p.warning,
        externalSetup = nonApiKeyAuth,
        canDisconnect = p.authenticated,
        models = if (p.authenticated) {
            p.models.map { model ->
                ProviderModelUi(
                    id = model,
                    label = model,
                    subtitle = priceLabelFor(p, model),
                    selected = p.isCurrent && model == currentModel,
                )
            }
        } else {
            emptyList()
        },
    )
}

/**
 * Drives the Hermes Models screens: lists providers (gateway `model.options`),
 * saves/removes provider keys (`model.save_key` / `model.disconnect`), and selects
 * the active main model (REST `POST /api/model/set`). After every successful
 * mutation it re-lists so `is_current`/auth state repaint from the source of truth.
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
                            rows = result.providers.map(::toRow),
                            currentModel = result.model,
                            currentProvider = result.provider,
                            currentLabel = currentLabel(result.providers, result.provider, result.model),
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(loading = false, error = err.toUserMessage()) }
                }
        }
    }

    /** Opens a provider's detail screen (LLM radios always route here to pick a model). */
    fun openDetail(slug: String) {
        _state.update { it.copy(detailId = slug, testResult = ProviderTestResult.Idle, error = null) }
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

    /**
     * Real test: there's no one-shot completion endpoint, so this runs a throwaway
     * session — create it, ask for a one-word reply, read it, then delete the session.
     * The turn runs on the active main model, so it only makes sense once a model from
     * this provider is selected (i.e. it is the current provider).
     */
    fun testProvider(slug: String) {
        val provider = _state.value.providers.firstOrNull { it.slug == slug }
        if (provider == null || !provider.isCurrent) {
            _state.update {
                it.copy(
                    testResult = ProviderTestResult.Failure(
                        "Select a model from this provider first, then test.",
                    ),
                )
            }
            return
        }
        _state.update { it.copy(testResult = ProviderTestResult.Testing, busySlug = slug, error = null) }
        viewModelScope.launch {
            val session = repository.createSession(title = "Model test").getOrElse { err ->
                _state.update {
                    it.copy(busySlug = null, testResult = ProviderTestResult.Failure(err.toUserMessage()))
                }
                return@launch
            }
            val result = try {
                var reply = ""
                var failure: String? = null
                val completed = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                    repository.submitPromptStreaming(session.id, "Reply with exactly one word: test")
                        .collect { event ->
                            when (event) {
                                is ChatEvent.Complete -> reply = event.text
                                is ChatEvent.Failure -> failure = event.message
                                else -> Unit
                            }
                        }
                    true
                }
                when {
                    completed == null -> ProviderTestResult.Failure("The model didn't reply in time.")
                    failure != null -> ProviderTestResult.Failure(failure!!)
                    reply.isBlank() -> ProviderTestResult.Failure("The model returned an empty reply.")
                    else -> ProviderTestResult.Success("Replied: \"${reply.trim().take(80)}\"")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The streaming flow can throw (transport drop, malformed event) rather
                // than emitting a terminal Failure; surface it instead of letting it
                // escape the launch (which would freeze the screen and crash on Android).
                ProviderTestResult.Failure(e.toUserMessage())
            } finally {
                // Always clean up the throwaway session, even on cancellation/failure.
                // NonCancellable so the delete still runs when the coroutine is cancelled
                // (e.g. the screen closes mid-test) — a plain suspend call would abort.
                withContext(NonCancellable) { repository.deleteSession(session.id) }
            }
            _state.update { it.copy(busySlug = null, testResult = result) }
        }
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

    private fun toRow(p: ModelProviderRow): ProviderListRowUi = ProviderListRowUi(
        id = p.slug,
        label = p.name.ifBlank { p.slug },
        badge = providerBadge(p),
        selected = p.isCurrent,
    )

    private fun providerBadge(p: ModelProviderRow): String? = when {
        p.authenticated -> "✓ key set"
        p.authType != null && p.authType != "api_key" -> null
        else -> "add key"
    }

    private fun currentLabel(
        providers: List<ModelProviderRow>,
        provider: String?,
        model: String?,
    ): String? {
        model ?: return null
        val providerName = provider?.let { slug ->
            providers.firstOrNull { it.slug == slug }?.name?.ifBlank { slug } ?: slug
        }
        return providerName?.let { "$it / $model" } ?: model
    }
}

/** A compact "in / out" price subtitle from the provider's pricing map, if present. */
private fun priceLabelFor(provider: ModelProviderRow, model: String): String? {
    val price = provider.pricing?.get(model) ?: return null
    if (price.free) return "free"
    val input = price.input ?: return null
    val output = price.output ?: return null
    return "$input in · $output out /MTok"
}
