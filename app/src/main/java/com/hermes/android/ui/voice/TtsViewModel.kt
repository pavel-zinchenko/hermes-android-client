package com.hermes.android.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.audio.AudioPlayer
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.dto.ToolsetConfigResponse
import com.hermes.android.ui.AppViewModelProvider
import com.hermes.android.ui.providers.ProviderDetailScreen
import com.hermes.android.ui.providers.ProviderDetailUi
import com.hermes.android.ui.providers.ProviderListRowUi
import com.hermes.android.ui.providers.ProviderListScreen
import com.hermes.android.ui.providers.ProviderTestResult
import com.hermes.android.ui.toUserMessage

/**
 * Drives the server-side TTS provider pages. TTS is a Hermes "toolset", so this lists
 * providers + key status (GET .../toolsets/tts/config), selects the active provider
 * (PUT .../provider), and saves provider keys (PUT .../env). Test speaks a sample via
 * the now-active provider (REST /api/audio/speak) and plays it back.
 */
class TtsViewModel(
    repository: HermesRepository,
    private val player: AudioPlayer,
) : ProviderPickerViewModel(repository) {

    override suspend fun load(): Result<Snapshot> =
        repository.listTtsConfig().map(::toSnapshot)

    override suspend fun activate(id: String): Result<Unit> = repository.setTtsProvider(id)

    override suspend fun persistKey(envVar: String, value: String): Result<Unit> =
        repository.saveTtsKeys(mapOf(envVar to value))

    override suspend fun runTest(id: String): ProviderTestResult {
        val audio = repository.speak("test")
            .getOrElse { return ProviderTestResult.Failure(it.toUserMessage()) }
        player.playAndAwait(audio.bytes, audio.mime)
        return ProviderTestResult.Success("Played a test sample.")
    }

    override fun onCleared() {
        super.onCleared()
        player.stop()
    }

    private fun toSnapshot(config: ToolsetConfigResponse): Snapshot {
        val rows = mutableListOf<ProviderListRowUi>()
        val details = mutableMapOf<String, ProviderDetailUi>()
        config.providers.forEach { p ->
            val unsetEnv = p.envVars.firstOrNull { !it.isSet }?.key
            val allSet = p.envVars.isNotEmpty() && unsetEnv == null
            // Prompt for the first unset key; once all are set, show the first for status.
            val keyEnv = if (p.requiresNousAuth) null else (unsetEnv ?: p.envVars.firstOrNull()?.key)
            rows += ProviderListRowUi(
                id = p.name,
                label = p.name,
                badge = providerBadge(p.requiresNousAuth, keyEnv, allSet),
                selected = p.isActive,
            )
            details[p.name] = ProviderDetailUi(
                id = p.name,
                title = p.name,
                keyEnv = keyEnv,
                keySet = allSet,
                externalSetup = p.requiresNousAuth,
            )
        }
        return Snapshot(currentLabel = config.activeProvider, rows = rows, details = details)
    }
}

/** First screen: the radio list of TTS providers. */
@Composable
fun TtsListScreen(
    onBack: () -> Unit,
    onConfigure: () -> Unit,
    viewModel: TtsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProviderListScreen(
        title = "Speech (TTS)",
        description = "Which engine Hermes uses to speak. Applies to new replies.",
        currentLabel = state.currentLabel,
        rows = state.rows,
        loading = state.loading,
        error = state.error,
        busyId = state.busyId,
        onSelect = viewModel::selectProvider,
        onConfigure = { id -> viewModel.openDetail(id); onConfigure() },
        onBack = onBack,
    )
}

/** Second screen: API key + Test for the TTS provider opened from the list. */
@Composable
fun TtsDetailScreen(
    onBack: () -> Unit,
    viewModel: TtsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val id = state.detailId
    ProviderDetailScreen(
        detail = id?.let { state.details[it] },
        keyDraft = id?.let { state.keyDrafts[it] }.orEmpty(),
        testResult = state.testResult,
        busy = state.busyId != null,
        error = state.error,
        onKeyDraftChange = { text -> id?.let { viewModel.onKeyDraftChange(it, text) } },
        onSaveKey = { id?.let { viewModel.saveKey(it) } },
        onDisconnect = {},
        onSelectModel = {},
        onTest = { id?.let { viewModel.testProvider(it) } },
        onBack = onBack,
    )
}
