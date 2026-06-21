package com.hermes.android.data.model

/**
 * Pure reducers that fold streamed gateway events into an ordered [TurnPart]
 * list. Shared by the text-chat and voice view models so both assemble a turn
 * identically (same thinking auto-collapse, same idempotent tool handling).
 */
object TurnParts {

    /** Collapses every thinking part in the turn (used once the answer begins). */
    fun collapseThinking(parts: List<TurnPart>): List<TurnPart> =
        parts.map { if (it is TurnPart.Thinking) it.copy(expanded = false) else it }

    /**
     * Appends answer text. The visible answer beginning collapses the thinking
     * trace that streamed while we waited; consecutive text deltas extend the
     * same [TurnPart.Text] segment, while a tool/thinking part in between starts
     * a fresh one.
     */
    fun appendText(parts: List<TurnPart>, delta: String): List<TurnPart> {
        val collapsed = collapseThinking(parts)
        val last = collapsed.lastOrNull()
        return if (last is TurnPart.Text) {
            collapsed.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            collapsed + TurnPart.Text(delta)
        }
    }

    fun appendThinking(parts: List<TurnPart>, delta: String): List<TurnPart> {
        val last = parts.lastOrNull()
        return if (last is TurnPart.Thinking) {
            parts.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            parts + TurnPart.Thinking(delta)
        }
    }

    /** Adds a running tool chip; ignores a duplicate start for the same [toolId]. */
    fun addTool(parts: List<TurnPart>, toolId: String, name: String, context: String?): List<TurnPart> =
        if (parts.any { it is TurnPart.Tool && it.toolId == toolId }) {
            parts
        } else {
            parts + TurnPart.Tool(toolId = toolId, name = name, context = context, state = ToolState.RUNNING)
        }

    fun completeTool(
        parts: List<TurnPart>,
        toolId: String,
        summary: String?,
        resultText: String?,
        durationS: Double?,
    ): List<TurnPart> = parts.map {
        if (it is TurnPart.Tool && it.toolId == toolId) {
            it.copy(
                state = ToolState.DONE,
                summary = summary ?: it.summary,
                resultText = resultText ?: it.resultText,
                durationS = durationS ?: it.durationS,
            )
        } else {
            it
        }
    }

    /** Toggles all thinking parts expanded/collapsed together. */
    fun toggleThinking(parts: List<TurnPart>): List<TurnPart> {
        val anyExpanded = parts.any { it is TurnPart.Thinking && it.expanded }
        return parts.map { if (it is TurnPart.Thinking) it.copy(expanded = !anyExpanded) else it }
    }
}
