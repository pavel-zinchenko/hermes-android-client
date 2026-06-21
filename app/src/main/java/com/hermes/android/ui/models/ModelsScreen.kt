package com.hermes.android.ui.models

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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.data.gateway.ModelProviderRow
import com.hermes.android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Models") },
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
                val current = state.currentModel?.let { model ->
                    val providerName = state.currentProvider?.let { slug ->
                        state.providers.firstOrNull { it.slug == slug }?.name?.ifBlank { slug }
                            ?: slug
                    }
                    providerName?.let { "$it / $model" } ?: model
                }
                Text(
                    text = "Current: ${current ?: "none"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Sets the model Hermes runs. Changes apply to new chat " +
                        "sessions; existing ones keep their model until recreated.",
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

            if (state.loading && state.providers.isEmpty()) {
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
                    items(state.providers, key = { it.slug }) { provider ->
                        ProviderCard(
                            provider = provider,
                            expanded = state.expandedSlug == provider.slug,
                            busy = state.busySlug == provider.slug,
                            currentModel = state.currentModel,
                            keyDraft = state.keyDrafts[provider.slug].orEmpty(),
                            onToggle = { viewModel.toggleExpand(provider.slug) },
                            onKeyDraftChange = { viewModel.onKeyDraftChange(provider.slug, it) },
                            onSaveKey = { viewModel.saveKey(provider.slug) },
                            onDisconnect = { viewModel.disconnect(provider.slug) },
                            onSelectModel = { viewModel.selectModel(provider.slug, it) },
                        )
                    }
                }
            }
        }
    }

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

@Composable
private fun ProviderCard(
    provider: ModelProviderRow,
    expanded: Boolean,
    busy: Boolean,
    currentModel: String?,
    keyDraft: String,
    onToggle: () -> Unit,
    onKeyDraftChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectModel: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = provider.name.ifBlank { provider.slug },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                ProviderBadge(provider)
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    provider.authType != null && provider.authType != "api_key" ->
                        Text(
                            text = "Configure this provider on desktop (run `hermes model`).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    !provider.authenticated -> {
                        provider.warning?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = onKeyDraftChange,
                            label = { Text(provider.keyEnv ?: "API key") },
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

                    else -> {
                        if (provider.models.isEmpty()) {
                            Text(
                                text = "No models available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        provider.models.forEach { model ->
                            ModelRow(
                                model = model,
                                priceLabel = priceLabelFor(provider, model),
                                selected = provider.isCurrent && model == currentModel,
                                enabled = !busy,
                                onClick = { onSelectModel(model) },
                            )
                        }
                        TextButton(onClick = onDisconnect, enabled = !busy) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBadge(provider: ModelProviderRow) {
    val (label, color) = when {
        provider.isCurrent -> "✓ current" to MaterialTheme.colorScheme.primary
        provider.authenticated -> "authenticated" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "+ add key" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun ModelRow(
    model: String,
    priceLabel: String?,
    selected: Boolean,
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
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            priceLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
