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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.hermes.android.data.VoiceEngine
import com.hermes.android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }

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
                text = "Connect to the Hermes dashboard (run `hermes dashboard`) on this " +
                    "device. It serves chat, sessions, and voice on one port.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Default: http://127.0.0.1:9119") },
            )

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("Token (HERMES_DASHBOARD_SESSION_TOKEN)") },
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
                text = "Voice output",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Which engine speaks replies in voice mode. On-device works " +
                    "offline and adds no latency; the server offers higher-quality voices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val engineOptions = listOf(
                VoiceEngine.SERVER to "Hermes server",
                VoiceEngine.ON_DEVICE to "On-device",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                engineOptions.forEachIndexed { index, (engine, label) ->
                    SegmentedButton(
                        selected = state.voiceEngine == engine,
                        onClick = { viewModel.setVoiceEngine(engine) },
                        shape = SegmentedButtonDefaults.itemShape(index, engineOptions.size),
                    ) {
                        Text(label)
                    }
                }
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

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = state.testResult != TestResult.Testing,
            ) {
                Text("Test connection")
            }

            TestResultRow(state)
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
