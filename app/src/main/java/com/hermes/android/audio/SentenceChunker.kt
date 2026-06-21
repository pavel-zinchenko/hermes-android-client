package com.hermes.android.audio

/**
 * Splits a streaming assistant answer into speakable fragments as text deltas
 * arrive, so each fragment can be synthesized and played while the rest of the
 * answer is still being generated. The first fragment is ready the moment the
 * first sentence lands — long before the turn finishes.
 *
 * Not thread-safe: feed it from a single coroutine.
 */
class SentenceChunker(
    private val minChars: Int = MIN_CHARS,
    private val maxChars: Int = MAX_CHARS,
) {
    private val buffer = StringBuilder()

    /** Appends [delta] and returns any fragments that are now complete (in order). */
    fun push(delta: String): List<String> {
        buffer.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val fragment = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (fragment.isNotEmpty()) out += fragment
        }
        return out
    }

    /** Returns the leftover buffered tail at end of turn, or null if blank. */
    fun drain(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.ifEmpty { null }
    }

    /**
     * Index to cut the buffer at (exclusive), or null if nothing is ready yet.
     * Cuts after a sentence terminator (or newline) once the fragment is at least
     * [minChars] long — coalescing tiny fragments so TTS isn't choppy — and
     * force-breaks a terminator-less run past [maxChars] so playback isn't starved
     * waiting for punctuation.
     */
    private fun nextCut(): Int? {
        val len = buffer.length
        var i = 0
        while (i < len) {
            val c = buffer[i]
            val isTerminator = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'
            if (isTerminator) {
                // A terminator ends a fragment only when followed by whitespace or
                // the end of the buffer so far (so we don't split "3.14" or an
                // abbreviation mid-token); newlines always break.
                val next = if (i + 1 < len) buffer[i + 1] else ' '
                val boundary = c == '\n' || next.isWhitespace()
                if (boundary && (i + 1) >= minChars) return i + 1
            }
            i++
        }
        if (len >= maxChars) {
            val space = buffer.lastIndexOf(" ", maxChars - 1)
            return if (space > 0) space + 1 else maxChars
        }
        return null
    }

    companion object {
        const val MIN_CHARS = 24
        const val MAX_CHARS = 300
    }
}
