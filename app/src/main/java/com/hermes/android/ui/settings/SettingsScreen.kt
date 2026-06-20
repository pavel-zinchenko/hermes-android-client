package com.hermes.android.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }
    var voiceKeyVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val pickThinkingSound = rememberLauncherForActivityResult(
        remember { OpenLocalAudioDocument() }
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read access so the file survives app restarts.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't grant persistable access; the URI may still
                // work for this install. Save it regardless.
            }
            viewModel.setThinkingSound(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                // Inset by the keyboard so the scroll area ends above the IME and
                // every field can be scrolled into view (edge-to-edge windows don't
                // auto-resize for the keyboard).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Connect to the Hermes API server running on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Default: http://127.0.0.1:8642") },
            )

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API key (API_SERVER_KEY)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (keyVisible) "Hide key" else "Show key",
                        )
                    }
                },
            )

            Text(
                text = "Voice",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Used for speech-to-text and text-to-speech. Defaults to the " +
                    "Hermes dashboard server (run `hermes web`, port 9119). Ignored " +
                    "automatically once the gateway itself supports audio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.voiceServerUrl,
                onValueChange = viewModel::onVoiceServerUrlChange,
                label = { Text("Voice server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Default: http://127.0.0.1:9119") },
            )

            OutlinedTextField(
                value = state.voiceApiKey,
                onValueChange = viewModel::onVoiceApiKeyChange,
                label = { Text("Voice server key (HERMES_DASHBOARD_SESSION_TOKEN)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (voiceKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { voiceKeyVisible = !voiceKeyVisible }) {
                        Icon(
                            imageVector = if (voiceKeyVisible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (voiceKeyVisible) "Hide key" else "Show key",
                        )
                    }
                },
            )

            Text(
                text = "Chat",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Streaming responses",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Stream replies token-by-token over the gateway " +
                            "(uses the voice/dashboard server, port 9119). Off uses the " +
                            "REST API server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.streamingEnabled,
                    onCheckedChange = viewModel::setStreamingEnabled,
                )
            }

            Text(
                text = "Thinking sound",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Optional audio looped while Hermes is thinking in voice mode. " +
                    "Leave unset for silence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val soundName = remember(state.thinkingSoundUri) {
                displayNameFor(context, state.thinkingSoundUri)
            }
            Text(
                text = soundName ?: "None selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { pickThinkingSound.launch(arrayOf("audio/*")) }) {
                    Text(if (soundName == null) "Choose audio…" else "Change")
                }
                if (state.thinkingSoundUri.isNotBlank()) {
                    TextButton(onClick = viewModel::clearThinkingSound) {
                        Text("Remove")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.testResult != TestResult.Testing,
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = state.testResult != TestResult.Testing,
                ) {
                    Text("Test connection")
                }
            }

            TestResultRow(state)

            if (state.saved && state.testResult is TestResult.Idle) {
                Text(
                    text = "Saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TestResultRow(state: SettingsUiState) {
    when (val result = state.testResult) {
        TestResult.Idle -> Unit
        TestResult.Testing -> Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
            )
            Text("Testing…", style = MaterialTheme.typography.bodyMedium)
        }
        TestResult.Success -> Text(
            text = "Connected. Hermes is reachable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        is TestResult.Failure -> Text(
            text = result.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Like [ActivityResultContracts.OpenDocument] but restricted to on-device files
 * (`EXTRA_LOCAL_ONLY`) so cloud/remote providers (Drive, etc.) aren't offered —
 * the thinking sound must be a local file that loops reliably offline.
 */
private class OpenLocalAudioDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

/** Resolves a friendly file name for a content URI, or null if [uriString] is blank. */
private fun displayNameFor(context: Context, uriString: String): String? {
    if (uriString.isBlank()) return null
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment ?: uriString
}
