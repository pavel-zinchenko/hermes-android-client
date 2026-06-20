package com.hermes.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.Sender
import com.hermes.android.data.model.ToolState
import com.hermes.android.data.model.TurnPart
import com.hermes.android.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenVoice: () -> Unit,
    viewModel: ChatViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Only auto-follow when the user is already at the bottom; if they've scrolled
    // up to read history mid-stream, don't yank them back down.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // Within one item of the end counts as "at the bottom": a freshly
            // appended message sits just below the fold (last visible == total-2)
            // until this effect scrolls it into view.
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    // A signal that grows with the streaming turn's content (deltas, thinking, tool
    // completion), so the effect re-pins on every chunk — not just when a whole new
    // message is added — keeping the end of a growing think/answer bubble in view.
    val streamSignal = state.messages.lastOrNull()?.let { m ->
        m.text.length + m.parts.sumOf { part ->
            when (part) {
                is TurnPart.Text -> part.text.length
                is TurnPart.Thinking -> part.text.length
                is TurnPart.Tool -> part.state.ordinal + 1
            }
        }
    } ?: 0

    // Keep the bottom of the newest content in view as the conversation grows.
    LaunchedEffect(state.messages.size, state.sending, streamSignal) {
        if (!atBottom) return@LaunchedEffect
        val target = state.messages.size + if (state.sending) 1 else 0
        // Scroll past the top of the last item (large offset clamps to content end)
        // so a tall thinking trace or still-growing bubble shows its newest line.
        if (target > 0) listState.scrollToItem(target - 1, 1_000_000)
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
                title = { Text("Chat", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenVoice) {
                        Icon(Icons.Filled.Mic, contentDescription = "Voice mode")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // We consume the bottom insets ourselves (below) so the keyboard and nav
        // bar aren't padded twice; the top bar still handles the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Pad the bottom by whichever is larger: the keyboard or the nav bar.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.loadingHistory && state.messages.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            when {
                                // Streaming bubble with nothing to show yet.
                                message.streaming && message.text.isBlank() &&
                                    message.parts.isEmpty() -> TypingBubble(state.statusLine)
                                // A streamed turn rendered as ordered parts.
                                message.parts.isNotEmpty() ->
                                    AssistantPartsMessage(message) { viewModel.toggleThinking(message.id) }
                                else -> MessageBubble(message)
                            }
                        }
                        // Show a typing/status indicator while a turn is in flight
                        // and no streaming bubble is yet carrying text.
                        val hasLiveBubble = state.messages.any { it.streaming }
                        if (state.sending && !hasLiveBubble) {
                            item(key = "typing") { TypingBubble(state.statusLine) }
                        }
                    }
                }
            }

            MessageInput(
                value = input,
                onValueChange = { input = it },
                sending = state.sending,
                onSend = {
                    viewModel.sendMessage(input)
                    input = ""
                },
                onStop = viewModel::stop,
            )
        }

        // Interactive request modals (approval / clarify / sudo / secret). Shows
        // the head of the queue; an AlertDialog overlays regardless of where it
        // sits in the composition.
        InteractiveRequestHost(
            request = state.pendingRequests.firstOrNull(),
            onApproval = viewModel::respondApproval,
            onClarify = viewModel::respondClarify,
            onSudo = viewModel::respondSudo,
            onSecret = viewModel::respondSecret,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Renders a streamed assistant turn as its ordered parts (thinking / tool / text). */
@Composable
private fun AssistantPartsMessage(message: ChatMessage, onToggleThinking: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        message.parts.forEach { part ->
            when (part) {
                is TurnPart.Thinking -> ThinkingBlock(part, onToggleThinking)
                is TurnPart.Tool -> ToolActivityChip(part)
                is TurnPart.Text -> AssistantTextBubble(part.text)
            }
        }
    }
}

@Composable
private fun AssistantTextBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** Dim, collapsible thinking/reasoning trace; auto-collapses once the answer begins. */
@Composable
private fun ThinkingBlock(part: TurnPart.Thinking, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "💭 Thinking",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (part.expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (part.expanded) "Collapse thinking" else "Expand thinking",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (part.expanded && part.text.isNotBlank()) {
                Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A tool invocation chip: icon + context, spinner while running, check + duration when done. */
@Composable
private fun ToolActivityChip(part: TurnPart.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val detail = part.resultText?.takeIf { it.isNotBlank() } ?: part.summary?.takeIf { it.isNotBlank() }
    val subtitle = if (part.state == ToolState.DONE) {
        part.summary?.takeIf { it.isNotBlank() } ?: part.context?.takeIf { it.isNotBlank() }
    } else {
        part.context?.takeIf { it.isNotBlank() } ?: part.summary?.takeIf { it.isNotBlank() }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = !detail.isNullOrBlank()) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = toolIcon(part.name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = part.name.ifBlank { "tool" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (part.state) {
                    ToolState.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ToolState.DONE -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        part.durationS?.let {
                            Text(
                                text = formatDuration(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Done",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (expanded && !detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Maps a Hermes tool name to a core Material icon (no material-icons-extended dep). */
private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    name.contains("web", ignoreCase = true) || name.contains("search", ignoreCase = true) ->
        Icons.Filled.Search
    name.contains("edit", ignoreCase = true) || name.contains("write", ignoreCase = true) ||
        name.contains("read", ignoreCase = true) || name.contains("replace", ignoreCase = true) ->
        Icons.Filled.Edit
    else -> Icons.Filled.Build
}

private fun formatDuration(seconds: Double): String =
    if (seconds >= 10) "${seconds.toInt()}s" else "%.1fs".format(java.util.Locale.US, seconds)

@Composable
private fun TypingBubble(statusLine: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = statusLine?.takeIf { it.isNotBlank() } ?: "Hermes is thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Hermes") },
                enabled = !sending,
                maxLines = 5,
            )
            if (sending) {
                // While a turn streams, the trailing control becomes Stop.
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                val canSend = value.isNotBlank()
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
