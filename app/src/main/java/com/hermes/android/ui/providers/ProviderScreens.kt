package com.hermes.android.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** One selectable provider on a [ProviderListScreen]. */
data class ProviderListRowUi(
    /** Selection id sent back to the ViewModel (LLM: slug; TTS: display name; STT: slug). */
    val id: String,
    val label: String,
    val badge: String? = null,
    val selected: Boolean = false,
)

/** One model choice on an LLM [ProviderDetailScreen]; unused for TTS/STT. */
data class ProviderModelUi(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
)

/** The single-provider detail shown on a [ProviderDetailScreen]. */
data class ProviderDetailUi(
    val id: String,
    val title: String,
    /** Env var to prompt for; null = no key needed (e.g. local STT) or non-api-key auth. */
    val keyEnv: String?,
    val keySet: Boolean,
    val warning: String? = null,
    /** Provider can't be configured here (e.g. Nous OAuth) — point to desktop. */
    val externalSetup: Boolean = false,
    val canDisconnect: Boolean = false,
    /** LLM model choices; empty for TTS/STT. */
    val models: List<ProviderModelUi> = emptyList(),
)

/** Result of the lightweight key check behind the detail screen's Test button. */
sealed interface ProviderTestResult {
    data object Idle : ProviderTestResult
    data object Testing : ProviderTestResult
    data class Success(val detail: String? = null) : ProviderTestResult
    data class Failure(val message: String) : ProviderTestResult
}

/**
 * Generic provider list: a radio group of providers (radio selects the active one)
 * with a Configure button per row that opens [ProviderDetailScreen]. Shared by the
 * LLM, TTS, and STT settings pages; each feeds it from its own ViewModel/backend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    title: String,
    description: String,
    currentLabel: String?,
    rows: List<ProviderListRowUi>,
    loading: Boolean,
    error: String?,
    busyId: String?,
    onSelect: (String) -> Unit,
    onConfigure: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Current: ${currentLabel ?: "none"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (loading && rows.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        ProviderListRow(
                            row = row,
                            busy = busyId == row.id,
                            anyBusy = busyId != null,
                            onSelect = { onSelect(row.id) },
                            onConfigure = { onConfigure(row.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderListRow(
    row: ProviderListRowUi,
    busy: Boolean,
    anyBusy: Boolean,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !anyBusy, onClick = onSelect)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = row.selected, onClick = onSelect, enabled = !anyBusy)
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                row.badge?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onConfigure, enabled = !anyBusy) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Configure ${row.label}",
                )
            }
        }
    }
}

/**
 * Generic single-provider detail: API-key entry, optional model list (LLM), and a
 * lightweight Test button. Shared by the LLM, TTS, and STT settings pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    detail: ProviderDetailUi?,
    keyDraft: String,
    testResult: ProviderTestResult,
    busy: Boolean,
    error: String?,
    onKeyDraftChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectModel: (String) -> Unit,
    onTest: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "Provider") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (detail == null) {
            Row(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            detail.warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                detail.externalSetup ->
                    Text(
                        text = "Configure this provider on desktop (run `hermes model`).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                detail.keyEnv != null && !detail.keySet -> {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = onKeyDraftChange,
                        label = { Text(detail.keyEnv) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedButton(
                        onClick = onSaveKey,
                        enabled = keyDraft.isNotBlank() && !busy,
                    ) {
                        Text("Save key")
                    }
                }

                detail.keyEnv != null && detail.keySet -> {
                    Text(
                        text = "✓ ${detail.keyEnv} is set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (detail.canDisconnect) {
                        TextButton(onClick = onDisconnect, enabled = !busy) {
                            Text("Remove key")
                        }
                    }
                }
            }

            if (detail.models.isNotEmpty()) {
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                detail.models.forEach { model ->
                    ModelRow(
                        model = model,
                        enabled = !busy,
                        onClick = { onSelectModel(model.id) },
                    )
                }
            }

            OutlinedButton(onClick = onTest, enabled = testResult != ProviderTestResult.Testing) {
                Text("Test")
            }
            TestResultRow(testResult)
        }
    }
}

@Composable
private fun ModelRow(
    model: ProviderModelUi,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = model.selected, onClick = onClick, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            model.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TestResultRow(result: ProviderTestResult) {
    when (result) {
        ProviderTestResult.Idle -> Unit
        ProviderTestResult.Testing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Testing…", style = MaterialTheme.typography.bodyMedium)
        }
        is ProviderTestResult.Success -> Text(
            text = result.detail ?: "Test passed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is ProviderTestResult.Failure -> Text(
            text = result.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
