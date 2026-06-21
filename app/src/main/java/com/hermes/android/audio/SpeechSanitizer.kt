package com.hermes.android.audio

/**
 * Strips Markdown formatting and decorative characters from assistant text before
 * it is handed to TTS, so the synthesizer never reads syntax aloud ("asterisk
 * asterisk bold") or pronounces emoji. Hermes routinely answers in Markdown, but
 * the spoken channel wants plain prose.
 *
 * This is deliberately lightweight: it operates on a completed sentence fragment
 * (post-[SentenceChunker]), so paired markers like `**`…`**` are intact within the
 * fragment. It removes the markers rather than trying to interpret them — for
 * speech, dropping a stray `*` is always the right call.
 */
object SpeechSanitizer {

    // Fenced code blocks: ```lang\n…\n``` → keep the inner text, drop the fences.
    private val CODE_FENCE = Regex("```[a-zA-Z0-9]*\\n?([\\s\\S]*?)```")
    // Images: ![alt](url) → spoken as the alt text (before links, since it's a superset).
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    // Links: [text](url) → spoken as just the text.
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    // Leading block markers on a line: heading #, blockquote >, list bullet, numbered list.
    private val LEADING_BLOCK = Regex("(?m)^[ \\t]*(?:#{1,6}[ \\t]+|>[ \\t]?|[-*+][ \\t]+|\\d+[.)][ \\t]+)")
    // A horizontal rule line: ---, ***, ___ (optionally spaced).
    private val HORIZONTAL_RULE = Regex("(?m)^[ \\t]*(?:[-*_][ \\t]*){3,}$")
    // Emphasis / inline-code / strikethrough markers left anywhere in the text.
    // Asterisks, backticks and tildes aren't normal word characters, so strip them
    // anywhere; underscores only when they aren't intra-word, so snake_case
    // identifiers ("my_func") are read intact instead of mangled to "myfunc".
    private val INLINE_MARKERS = Regex("\\*+|~~|`+|(?<![A-Za-z0-9])_+|_+(?![A-Za-z0-9])")
    // Emoji, pictographs, symbols, dingbats, variation selectors and ZWJ.
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}" +
            "\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}]",
    )
    // Runs of whitespace (incl. newlines) collapse to a single space for smooth speech.
    private val WHITESPACE = Regex("\\s+")

    /** Returns [text] with Markdown syntax and decorative characters removed for TTS. */
    fun clean(text: String): String {
        var s = text
        s = CODE_FENCE.replace(s) { it.groupValues[1] }
        s = HORIZONTAL_RULE.replace(s, " ")
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { it.groupValues[1] }
        s = LEADING_BLOCK.replace(s, "")
        s = INLINE_MARKERS.replace(s, "")
        s = EMOJI.replace(s, "")
        s = WHITESPACE.replace(s, " ")
        return s.trim()
    }
}
