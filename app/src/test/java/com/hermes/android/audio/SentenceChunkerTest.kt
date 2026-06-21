package com.hermes.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `emits a complete sentence once it passes the min length`() {
        val chunker = SentenceChunker()
        // Below min length: held back even though it's a full sentence.
        assertEquals(emptyList<String>(), chunker.push("Hi there. "))
        // Now long enough to emit the next sentence.
        val out = chunker.push("This is the second sentence. ")
        assertEquals(listOf("Hi there. This is the second sentence."), out)
    }

    @Test
    fun `splits multiple sentences delivered in one delta`() {
        val chunker = SentenceChunker()
        val out = chunker.push("The first sentence is here. The second one follows along. ")
        assertEquals(
            listOf("The first sentence is here.", "The second one follows along."),
            out,
        )
    }

    @Test
    fun `accumulates across deltas that split a sentence mid-word`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.push("The quick brown fox jum"))
        val out = chunker.push("ped over the lazy dog. ")
        assertEquals(listOf("The quick brown fox jumped over the lazy dog."), out)
    }

    @Test
    fun `breaks on newlines`() {
        val chunker = SentenceChunker()
        val out = chunker.push("Here is a line of some length\nand here is another one\n")
        assertEquals(
            listOf("Here is a line of some length", "and here is another one"),
            out,
        )
    }

    @Test
    fun `does not split on a decimal point mid-token`() {
        val chunker = SentenceChunker()
        // "3.14159..." has a '.' but the next char is a digit, not whitespace.
        val out = chunker.push("Pi is approximately 3.14159 in this case. ")
        assertEquals(listOf("Pi is approximately 3.14159 in this case."), out)
    }

    @Test
    fun `force-breaks a terminator-less run past the cap at a space`() {
        val chunker = SentenceChunker(minChars = 4, maxChars = 20)
        val out = chunker.push("aaaa bbbb cccc dddd eeee ffff")
        // First fragment is force-broken at the last space before the 20-char cap.
        assertEquals("aaaa bbbb cccc dddd", out.first())
    }

    @Test
    fun `drain returns the trailing partial sentence`() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.push("No terminator yet"))
        assertEquals("No terminator yet", chunker.drain())
        // Buffer is cleared after draining.
        assertNull(chunker.drain())
    }
}
