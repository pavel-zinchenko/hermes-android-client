package com.hermes.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.data.model.TurnPart
import com.hermes.android.ui.AppViewModelProvider
import com.hermes.android.ui.chat.AssistantPartsMessage
import com.hermes.android.ui.chat.CallState
import com.hermes.android.ui.chat.ChatSessionViewModel
import com.hermes.android.ui.chat.InitialScrollToBottomEffect
import com.hermes.android.ui.chat.MarkdownText

/**
 * Continuous voice-call mode. Unlike push-to-talk [VoiceScreen], the mic stays open
 * for the whole call and the big button only mutes/unmutes it. The app listens,
 * transcribes each utterance, sends it to Hermes, speaks the reply, and stops
 * speaking the instant the user talks over it (barge-in). Shares the session's
 * [ChatSessionViewModel], so the call appends to the same conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    onBack: () -> Unit,
    viewModel: ChatSessionViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // Ask for the mic on entry if needed; start the call once it's granted.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.startCall()
    }
    // Keep the screen awake for the duration of the call; end the call on leave.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            viewModel.endCall()
        }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    val streamSignal = state.messages.lastOrNull()?.let { m ->
        m.text.length + m.parts.sumOf { part ->
            when (part) {
                is TurnPart.Text -> part.text.length
                is TurnPart.Thinking -> part.text.length
                is TurnPart.Tool -> part.state.ordinal + 1
            }
        }
    } ?: 0

    InitialScrollToBottomEffect(listState, state.loadingHistory, state.messages.size)
    LaunchedEffect(state.messages.size, streamSignal) {
        if (!atBottom || state.messages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(state.messages.size - 1, 1_000_000)
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    Text(
                        text = "Call mode is on.\nJust start talking — Hermes is listening.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            if (message.parts.isNotEmpty()) {
                                AssistantPartsMessage(message) { viewModel.toggleThinking(message.id) }
                            } else {
                                CallBubble(message)
                            }
                        }
                    }
                }
            }

            // Live interim transcript (on-device STT) so the user sees themselves heard.
            Text(
                text = state.interimTranscript.ifBlank { " " },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))

            MicToggleButton(
                micEnabled = state.micEnabled,
                onToggle = { viewModel.setMicEnabled(!state.micEnabled) },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = callStatusLabel(state.micEnabled, state.callState),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            ) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "End call",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MicToggleButton(
    micEnabled: Boolean,
    onToggle: () -> Unit,
) {
    val color =
        if (micEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onToggle),
    ) {
        Icon(
            imageVector = if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (micEnabled) "Mute mic" else "Unmute mic",
            tint = if (micEnabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(56.dp),
        )
    }
}

private fun callStatusLabel(micEnabled: Boolean, callState: CallState): String = when {
    !micEnabled -> "Muted — tap to unmute"
    callState == CallState.LISTENING -> "Listening…"
    callState == CallState.THINKING -> "Thinking…"
    else -> "Hermes is speaking…"
}

@Composable
private fun CallBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = if (isUser) "You" else "Hermes",
                style = MaterialTheme.typography.labelSmall,
                color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    .copy(alpha = 0.7f),
            )
            if (isUser) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                MarkdownText(
                    text = message.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
