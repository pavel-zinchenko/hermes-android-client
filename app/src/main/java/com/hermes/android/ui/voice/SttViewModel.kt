package com.hermes.android.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.audio.OnDeviceTts
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.gateway.SttConfig
import com.hermes.android.ui.AppViewModelProvider
import com.hermes.android.ui.providers.ProviderDetailScreen
import com.hermes.android.ui.providers.ProviderDetailUi
import com.hermes.android.ui.providers.ProviderListRowUi
import com.hermes.android.ui.providers.ProviderListScreen
import com.hermes.android.ui.providers.ProviderTestResult
import com.hermes.android.ui.toUserMessage

/**
 * Drives the server-side speech-recognition (STT) provider pages. STT isn't a Hermes
 * toolset, so the repository assembles the choices from the config schema/values and
 * writes `stt.provider` via PUT /api/config; keys go through PUT /api/env. Test
 * synthesizes "test" with the device's built-in TTS and transcribes it via the
 * now-active provider (REST /api/audio/transcribe).
 */
class SttViewModel(
    repository: HermesRepository,
    private val onDeviceTts: OnDeviceTts,
) : ProviderPickerViewModel(repository) {

    override suspend fun load(): Result<Snapshot> =
        repository.listSttConfig().map(::toSnapshot)

    override suspend fun activate(id: String): Result<Unit> = repository.setSttProvider(id)

    override suspend fun persistKey(envVar: String, value: String): Result<Unit> =
        repository.saveSttKey(envVar, value)

    override suspend fun runTest(id: String): ProviderTestResult {
        val audio = onDeviceTts.synthesize("test").getOrElse {
            return ProviderTestResult.Failure(
                "Couldn't generate a test sound on this device: ${it.message}",
            )
        }
        val transcript = repository.transcribe(audio.bytes, audio.mime)
            .getOrElse { return ProviderTestResult.Failure(it.toUserMessage()) }
        return if (transcript.isBlank()) {
            ProviderTestResult.Failure("No speech was transcribed.")
        } else {
            ProviderTestResult.Success("Transcribed: \"$transcript\"")
        }
    }

    override fun onCleared() {
        super.onCleared()
        onDeviceTts.shutdown()
    }

    private fun toSnapshot(config: SttConfig): Snapshot {
        val rows = mutableListOf<ProviderListRowUi>()
        val details = mutableMapOf<String, ProviderDetailUi>()
        config.providers.forEach { row ->
            rows += ProviderListRowUi(
                id = row.slug,
                label = row.label,
                badge = providerBadge(externalSetup = false, keyEnv = row.keyEnv, keySet = row.keySet),
                selected = row.slug == config.currentProvider,
            )
            details[row.slug] = ProviderDetailUi(
                id = row.slug,
                title = row.label,
                keyEnv = row.keyEnv,
                keySet = row.keySet,
            )
        }
        return Snapshot(currentLabel = currentLabel(config), rows = rows, details = details)
    }

    /** Display the active provider's label (not its bare slug) when we know it. */
    private fun currentLabel(config: SttConfig): String? {
        val slug = config.currentProvider ?: return null
        return config.providers.firstOrNull { it.slug == slug }?.label ?: slug
    }
}

/** First screen: the radio list of STT providers. */
@Composable
fun SttListScreen(
    onBack: () -> Unit,
    onConfigure: () -> Unit,
    viewModel: SttViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProviderListScreen(
        title = "Speech recognition",
        description = "Which engine Hermes uses to transcribe your voice. " +
            "Applies to new recordings.",
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

/** Second screen: API key + Test for the STT provider opened from the list. */
@Composable
fun SttDetailScreen(
    onBack: () -> Unit,
    viewModel: SttViewModel = viewModel(factory = AppViewModelProvider.Factory),
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
