package com.hermes.android.ui.voice

import com.hermes.android.ui.chat.isLikelyNoise
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTranscriptTest {

    @Test
    fun `matches common whisper silence hallucinations`() {
        assertTrue(isLikelyNoise("you"))
        assertTrue(isLikelyNoise("You"))
        assertTrue(isLikelyNoise("Thank you."))
        assertTrue(isLikelyNoise("  thank you  "))
        assertTrue(isLikelyNoise("Thanks for watching!"))
    }

    @Test
    fun `keeps real speech`() {
        assertFalse(isLikelyNoise("what's the weather today"))
        assertFalse(isLikelyNoise("thank you for the help with the build"))
        assertFalse(isLikelyNoise("you should refactor this"))
    }
}
