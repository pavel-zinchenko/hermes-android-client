package com.hermes.android.ui.models

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.ui.AppViewModelProvider
import com.hermes.android.ui.providers.ProviderDetailScreen
import com.hermes.android.ui.providers.ProviderListScreen

/** First screen: the radio list of LLM providers. */
@Composable
fun ModelsListScreen(
    onBack: () -> Unit,
    onConfigure: () -> Unit,
    viewModel: ModelsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProviderListScreen(
        title = "Hermes Models",
        description = "Sets the model Hermes runs. Changes apply to new chat sessions; " +
            "existing ones keep their model until recreated.",
        currentLabel = state.currentLabel,
        rows = state.rows,
        loading = state.loading,
        error = state.error,
        busyId = state.busySlug,
        // An LLM provider has many models and none is pre-selected for a non-active
        // one, so tapping a radio opens its detail to pick a model (and add a key).
        onSelect = { slug -> viewModel.openDetail(slug); onConfigure() },
        onConfigure = { slug -> viewModel.openDetail(slug); onConfigure() },
        onBack = onBack,
    )
}

/** Second screen: API key + model picker for the provider opened from the list. */
@Composable
fun ModelsDetailScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val slug = state.detailId
    ProviderDetailScreen(
        detail = state.detailUi(),
        keyDraft = slug?.let { state.keyDrafts[it] }.orEmpty(),
        testResult = state.testResult,
        busy = state.busySlug != null,
        error = state.error,
        onKeyDraftChange = { text -> slug?.let { viewModel.onKeyDraftChange(it, text) } },
        onSaveKey = { slug?.let { viewModel.saveKey(it) } },
        onDisconnect = { slug?.let { viewModel.disconnect(it) } },
        onSelectModel = { model -> slug?.let { viewModel.selectModel(it, model) } },
        onTest = { slug?.let { viewModel.testProvider(it) } },
        onBack = onBack,
    )

    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("Use this model?") },
            text = { Text(confirm.message) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmExpensive) { Text("Use it") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirm) { Text("Cancel") }
            },
        )
    }
}
