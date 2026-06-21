package com.hermes.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSanitizerTest {

    @Test
    fun `strips bold and italic markers`() {
        assertEquals("bold text is here", SpeechSanitizer.clean("**bold** *text* is here"))
        assertEquals("emphasis", SpeechSanitizer.clean("__emphasis__"))
    }

    @Test
    fun `strips inline code backticks but keeps the code text`() {
        assertEquals("run ls now", SpeechSanitizer.clean("run `ls` now"))
    }

    @Test
    fun `keeps intra-word underscores so identifiers are read intact`() {
        assertEquals("call my_func on base_case", SpeechSanitizer.clean("call my_func on base_case"))
    }

    @Test
    fun `still strips underscore emphasis at word boundaries`() {
        assertEquals("really now", SpeechSanitizer.clean("_really_ now"))
    }

    @Test
    fun `keeps fenced code body without the fences`() {
        val out = SpeechSanitizer.clean("Here:\n```kotlin\nval x = 1\n```")
        assertEquals("Here: val x = 1", out)
    }

    @Test
    fun `speaks link text not the url`() {
        assertEquals("see the docs", SpeechSanitizer.clean("see [the docs](https://example.com)"))
    }

    @Test
    fun `speaks image alt text not the url`() {
        assertEquals("a cat", SpeechSanitizer.clean("![a cat](cat.png)"))
    }

    @Test
    fun `removes heading and list markers`() {
        assertEquals("Title", SpeechSanitizer.clean("## Title"))
        assertEquals("first second", SpeechSanitizer.clean("- first\n- second"))
        assertEquals("one two", SpeechSanitizer.clean("1. one\n2. two"))
    }

    @Test
    fun `drops emoji and decorative symbols`() {
        assertEquals("Nice work", SpeechSanitizer.clean("Nice work 🎉😀"))
        assertEquals("done", SpeechSanitizer.clean("done ✅"))
    }

    @Test
    fun `collapses whitespace and trims`() {
        assertEquals("a b c", SpeechSanitizer.clean("  a   b\n\nc  "))
    }

    @Test
    fun `leaves plain prose untouched`() {
        assertEquals("Hello, how are you?", SpeechSanitizer.clean("Hello, how are you?"))
    }
}
