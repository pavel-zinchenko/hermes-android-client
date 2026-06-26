package com.hermes.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw 16-bit little-endian PCM in a minimal canonical WAV container. Shared
 * by [AudioDecoder] (re-wrapping trimmed TTS clips) and [ContinuousVoiceCapture]
 * (packaging captured mic audio for the server transcribe endpoint).
 */
object PcmWav {

    /** Wraps interleaved 16-bit PCM in a 44-byte WAV header + the sample data. */
    fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                        // PCM fmt chunk size
        header.putShort(1)                       // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)                      // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
