package com.hermes.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders a useful subset of Markdown that Hermes emits — headings, bullet/numbered
 * lists, blockquotes, fenced/inline code, horizontal rules, and inline bold / italic
 * / strikethrough / links — as Compose text, so the chat shows formatted prose
 * instead of literal `**asterisks**`. Deliberately small and dependency-free;
 * unsupported or malformed syntax falls back to plain text rather than failing.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val lines = text.split("\n")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                // Fenced code block: gather until the closing fence (or end of text).
                line.trimStart().startsWith("```") -> {
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i++
                    }
                    if (i < lines.size) i++ // consume the closing fence
                    CodeBlock(body.toString(), color)
                }
                HR.matches(line) -> {
                    HorizontalDivider(color = color.copy(alpha = 0.3f))
                    i++
                }
                else -> {
                    HEADING.matchEntire(line)?.let { m ->
                        val level = m.groupValues[1].length
                        Text(
                            text = parseInline(m.groupValues[2], color),
                            style = headingStyle(level),
                            color = color,
                        )
                    } ?: BULLET.matchEntire(line)?.let { m ->
                        ListItem(marker = "•  ", content = m.groupValues[1], color = color)
                    } ?: NUMBERED.matchEntire(line)?.let { m ->
                        ListItem(marker = "${m.groupValues[1]}.  ", content = m.groupValues[2], color = color)
                    } ?: QUOTE.matchEntire(line)?.let { m ->
                        Text(
                            text = parseInline(m.groupValues[1], color),
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            color = color.copy(alpha = 0.85f),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    } ?: run {
                        // Plain paragraph line. Skip empty lines (the spacedBy gap
                        // already separates blocks) so blank lines don't pile up.
                        if (line.isNotBlank()) {
                            Text(
                                text = parseInline(line, color),
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                            )
                        }
                    }
                    i++
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.Bold)

@Composable
private fun ListItem(marker: String, content: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = marker, style = MaterialTheme.typography.bodyLarge, color = color)
        Text(
            text = parseInline(content, color),
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
    }
}

@Composable
private fun CodeBlock(code: String, color: Color) {
    Text(
        text = code,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private val HEADING = Regex("^(#{1,6})[ \\t]+(.*)$")
private val BULLET = Regex("^[ \\t]*[-*+][ \\t]+(.*)$")
private val NUMBERED = Regex("^[ \\t]*(\\d+)[.)][ \\t]+(.*)$")
private val QUOTE = Regex("^[ \\t]*>[ \\t]?(.*)$")
private val HR = Regex("^[ \\t]*(?:[-*_][ \\t]*){3,}$")

// One pass over inline tokens: code, bold (** / __), italic (* / _), strikethrough,
// and links. Inner content isn't re-parsed (no nesting), which is fine for chat.
// Emphasis markers must hug their content (no space just inside the markers) so plain
// prose like "5 * 4 * 3" or "a _ b" isn't mis-rendered as italic; single underscores
// must also be at a word boundary so snake_case identifiers stay plain.
private val INLINE = Regex(
    "`[^`]+`" +
        "|\\*\\*(?=\\S)[^*\\n]+?(?<=\\S)\\*\\*" +
        "|__(?=\\S)[^_\\n]+?(?<=\\S)__" +
        "|\\*(?=\\S)[^*\\n]+?(?<=\\S)\\*" +
        "|(?<![A-Za-z0-9])_(?=\\S)[^_\\n]+?(?<=\\S)_(?![A-Za-z0-9])" +
        "|~~(?=\\S)[^~\\n]+?(?<=\\S)~~" +
        "|\\[[^\\]]+]\\([^)]*\\)",
)

private fun parseInline(line: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(line)) {
        if (m.range.first > last) append(line.substring(last, m.range.first))
        val tok = m.value
        when {
            tok.startsWith("`") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.08f))) {
                    append(tok.substring(1, tok.length - 1))
                }
            tok.startsWith("**") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.substring(2, tok.length - 2)) }
            tok.startsWith("__") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.substring(2, tok.length - 2)) }
            tok.startsWith("~~") ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(tok.substring(2, tok.length - 2)) }
            tok.startsWith("[") -> {
                val display = tok.substringAfter('[').substringBefore(']')
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(display) }
            }
            tok.startsWith("*") ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(tok.substring(1, tok.length - 1)) }
            tok.startsWith("_") ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(tok.substring(1, tok.length - 1)) }
            else -> append(tok)
        }
        last = m.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}
