package com.hermes.android.ui.voice

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** One selectable provider in a [ProviderPickerScreen]. */
data class ProviderRowUi(
    /** Selection id sent back to the ViewModel (TTS: display name; STT: slug). */
    val id: String,
    val label: String,
    val badge: String? = null,
    val selected: Boolean = false,
    /** Env var to prompt for when a key is needed; null means no key required. */
    val keyEnv: String? = null,
    val keySet: Boolean = false,
    /** Provider can't be configured here (e.g. Nous OAuth) — point to desktop. */
    val externalSetup: Boolean = false,
)

/** UI state shared by the TTS and STT picker ViewModels. */
data class ProviderPickerUiState(
    val loading: Boolean = true,
    val currentLabel: String? = null,
    val rows: List<ProviderRowUi> = emptyList(),
    /** Per-row API-key draft text, keyed by [ProviderRowUi.id]. */
    val keyDrafts: Map<String, String> = emptyMap(),
    /** Id of the row running a select/save (shows a spinner, disables input). */
    val busyId: String? = null,
    val error: String? = null,
)

/**
 * Generic provider picker: a flat radio list with inline API-key entry, shared by
 * the server-side TTS and STT settings pages. The two differ only in their backend
 * (a toolset vs. plain config), so both feed this one screen via [ProviderPickerUiState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPickerScreen(
    title: String,
    description: String,
    state: ProviderPickerUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onKeyDraftChange: (String, String) -> Unit,
    onSaveKey: (String) -> Unit,
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
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Current: ${state.currentLabel ?: "none"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.loading && state.rows.isEmpty()) {
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
                    items(state.rows, key = { it.id }) { row ->
                        ProviderRow(
                            row = row,
                            busy = state.busyId == row.id,
                            anyBusy = state.busyId != null,
                            keyDraft = state.keyDrafts[row.id].orEmpty(),
                            onSelect = { onSelect(row.id) },
                            onKeyDraftChange = { onKeyDraftChange(row.id, it) },
                            onSaveKey = { onSaveKey(row.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    row: ProviderRowUi,
    busy: Boolean,
    anyBusy: Boolean,
    keyDraft: String,
    onSelect: () -> Unit,
    onKeyDraftChange: (String) -> Unit,
    onSaveKey: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !anyBusy, onClick = onSelect)
                    .padding(vertical = 8.dp),
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
            }

            when {
                row.externalSetup ->
                    Text(
                        text = "Configure this provider on desktop (run `hermes tools`).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                row.keyEnv != null && !row.keySet -> {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = onKeyDraftChange,
                        label = { Text(row.keyEnv) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedButton(
                        onClick = onSaveKey,
                        enabled = keyDraft.isNotBlank() && !anyBusy,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text("Save key")
                    }
                }
            }
        }
    }
}
