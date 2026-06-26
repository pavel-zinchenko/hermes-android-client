package com.hermes.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PCM resampler/downmixer used by full-duplex call playback: decoded TTS
 * clips arrive at the provider's rate and channel count, but the echo canceller and
 * its [android.media.AudioTrack] render path require **16 kHz mono int16**. This
 * downmixes to mono (averaging channels) and resamples with linear interpolation —
 * adequate for speech and cheap enough to run on the playback thread.
 */
object Resampler {

    const val TARGET_RATE = 16_000

    /**
     * Converts interleaved 16-bit little-endian [pcm] (at [srcRate], [channels]) to a
     * 16 kHz mono [ShortArray]. Returns an empty array for empty/degenerate input.
     */
    fun toMono16k(pcm: ByteArray, srcRate: Int, channels: Int): ShortArray {
        if (pcm.size < 2 || srcRate <= 0 || channels <= 0) return ShortArray(0)
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val totalSamples = shorts.remaining()
        val frames = totalSamples / channels
        if (frames == 0) return ShortArray(0)

        // 1) Downmix to mono.
        val mono = ShortArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += shorts.get(idx++).toInt()
            mono[f] = (acc / channels).toShort()
        }

        // 2) Resample to TARGET_RATE (linear interpolation). Pass through if already 16 kHz.
        if (srcRate == TARGET_RATE) return mono
        val outLen = (frames.toLong() * TARGET_RATE / srcRate).toInt()
        if (outLen <= 0) return ShortArray(0)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / TARGET_RATE
        var pos = 0.0
        for (i in 0 until outLen) {
            val base = pos.toInt()
            val frac = pos - base
            val s0 = mono[base].toInt()
            val s1 = if (base + 1 < frames) mono[base + 1].toInt() else s0
            out[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
            pos += step
        }
        return out
    }
}
