package com.hermes.android.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * On first open, jumps [listState] to the newest message regardless of layout state —
 * a freshly laid-out long history renders from the top (where "at bottom" follow logic
 * reads false), so without this the user is stranded at the top of the conversation.
 * Runs once, after [loadingHistory] clears and there is at least one message.
 *
 * Shared by the chat and voice screens, which render the same session's message list.
 */
@Composable
internal fun InitialScrollToBottomEffect(
    listState: LazyListState,
    loadingHistory: Boolean,
    messageCount: Int,
) {
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(loadingHistory, messageCount) {
        if (!done && !loadingHistory && messageCount > 0) {
            // Large offset clamps to content end so a tall last message shows its end.
            listState.scrollToItem(messageCount - 1, 1_000_000)
            done = true
        }
    }
}
