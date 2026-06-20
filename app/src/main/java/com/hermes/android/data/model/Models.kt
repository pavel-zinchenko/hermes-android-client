package com.hermes.android.data.model

/** App-facing domain models, decoupled from the wire DTOs. */

data class ChatSession(
    val id: String,
    val title: String,
    val messageCount: Int,
    val lastActive: String?,
    val preview: String?,
)

enum class Sender { USER, ASSISTANT }

/** Lifecycle of a single tool invocation within an assistant turn. */
enum class ToolState { RUNNING, DONE }

/**
 * One ordered segment of a streamed assistant turn. A turn interleaves plain
 * text with tool invocations and a thinking trace in arrival order, mirroring the
 * real Hermes TUI. Built incrementally from gateway [ChatEvent]s.
 */
sealed interface TurnPart {
    data class Text(val text: String) : TurnPart

    /** The agent's thinking/reasoning trace; [expanded] auto-collapses once the answer begins. */
    data class Thinking(val text: String, val expanded: Boolean = true) : TurnPart

    data class Tool(
        val toolId: String,
        val name: String,
        val context: String?,
        val state: ToolState,
        val summary: String? = null,
        val resultText: String? = null,
        val durationS: Double? = null,
    ) : TurnPart
}

data class ChatMessage(
    val id: String,
    val sender: Sender,
    val text: String,
    /**
     * Ordered parts of a streamed assistant turn (text / tool / thinking). Empty
     * for user messages and history-loaded turns — the UI then renders [text].
     */
    val parts: List<TurnPart> = emptyList(),
    /** True while this (assistant) message is still being streamed in. */
    val streaming: Boolean = false,
)
