package com.hermes.android.audio

import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based voice-activity detector with endpointing, shared by the two
 * continuous-capture engines ([ContinuousVoiceCapture] half-duplex, and the
 * full-duplex call engine). It tracks an adaptive noise floor, confirms a speech
 * onset, keeps a short pre-roll so onsets aren't clipped, and ends an utterance
 * after a run of silence — emitting the buffered PCM via [onUtterance].
 *
 * Feed it consecutive frames of **16-bit mono PCM at ~30 ms each (480 samples @
 * 16 kHz)** via [process]; the frame-count thresholds below are tuned for that frame
 * size. The RMS thresholds are absolute, so they hold regardless of frame size.
 * Call [reset] to drop any in-progress utterance (e.g. while the mic is suppressed).
 *
 * The detector is single-threaded: call [process]/[reset] from one capture thread.
 * Emitted PCM is little-endian 16-bit; the caller wraps it (e.g. [PcmWav.wrap]).
 */
class Vad(
    /** If non-null, periodic input-level lines are logged under this tag (~every 2 s). */
    private val logTag: String? = null,
    /** Fires the instant a speech onset is confirmed — before the utterance ends. */
    private val onSpeechStarted: () -> Unit,
    /** Fires once per completed utterance with its raw little-endian 16-bit PCM. */
    private val onUtterance: (pcm: ByteArray) -> Unit,
) {

    private var floor = INITIAL_FLOOR
    private var inSpeech = false
    private var consecVoiced = 0
    private var consecSilence = 0
    private var voicedFrames = 0
    private var buffer = ByteArrayOutputStream()
    private val preroll = ArrayDeque<ShortArray>(PREROLL_FRAMES + 1)

    // Periodic level reporting (diagnostics only).
    private var frameCount = 0
    private var windowPeak = 0.0

    /** Drops any in-progress utterance and pre-roll. Floor/level state is preserved. */
    fun reset() {
        if (inSpeech) buffer = ByteArrayOutputStream()
        inSpeech = false
        preroll.clear()
        consecVoiced = 0
        consecSilence = 0
        voicedFrames = 0
    }

    /** Processes one frame ([n] samples of [frame]); may emit speechStarted/utterance. */
    fun process(frame: ShortArray, n: Int) {
        if (n <= 0) return
        val rms = rms(frame, n)

        logTag?.let { tag ->
            windowPeak = max(windowPeak, rms)
            if (++frameCount >= LEVEL_LOG_FRAMES) {
                Log.d(tag, "level: peakRms=${windowPeak.toInt()} floor=${floor.toInt()} inSpeech=$inSpeech")
                frameCount = 0
                windowPeak = 0.0
            }
        }

        val onsetThreshold = max(MIN_ONSET_RMS.toDouble(), floor * ONSET_FACTOR)
        val endThreshold = max(MIN_END_RMS.toDouble(), floor * END_FACTOR)

        if (!inSpeech) {
            // Track ambient level while silent so the bar adapts to the room.
            floor = floor * (1 - FLOOR_ALPHA) + rms * FLOOR_ALPHA
            preroll.addLast(frame.copyOf(n))
            while (preroll.size > PREROLL_FRAMES) preroll.removeFirst()

            if (rms > onsetThreshold) {
                if (++consecVoiced >= ONSET_FRAMES) {
                    inSpeech = true
                    consecSilence = 0
                    voicedFrames = consecVoiced
                    buffer = ByteArrayOutputStream()
                    for (f in preroll) buffer.writeShorts(f, f.size)
                    preroll.clear()
                    consecVoiced = 0
                    logTag?.let { Log.d(it, "onset: rms=${rms.toInt()} threshold=${onsetThreshold.toInt()}") }
                    onSpeechStarted()
                }
            } else {
                consecVoiced = 0
            }
        } else {
            buffer.writeShorts(frame, n)
            if (rms < endThreshold) {
                if (++consecSilence >= SILENCE_END_FRAMES) {
                    endUtterance()
                    inSpeech = false
                    consecSilence = 0
                    voicedFrames = 0
                    consecVoiced = 0
                    buffer = ByteArrayOutputStream()
                }
            } else {
                consecSilence = 0
                voicedFrames++
            }
        }
    }

    private fun endUtterance() {
        if (voicedFrames < MIN_SPEECH_FRAMES) {
            logTag?.let { Log.d(it, "utterance dropped: voicedFrames=$voicedFrames < $MIN_SPEECH_FRAMES") }
            return
        }
        val pcm = buffer.toByteArray()
        if (pcm.isEmpty()) return
        logTag?.let { Log.d(it, "utterance: pcmBytes=${pcm.size} voicedFrames=$voicedFrames") }
        onUtterance(pcm)
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n)
    }

    /** Appends [count] samples of [frame] as little-endian 16-bit bytes. */
    private fun ByteArrayOutputStream.writeShorts(frame: ShortArray, count: Int) {
        for (i in 0 until count) {
            val s = frame[i].toInt()
            write(s and 0xFF)
            write((s shr 8) and 0xFF)
        }
    }

    private companion object {
        /** Log the input level roughly every 2 seconds (66 × 30 ms frames). */
        const val LEVEL_LOG_FRAMES = 66

        /** ~10 frames (~300 ms) kept before a confirmed onset so speech starts aren't clipped. */
        const val PREROLL_FRAMES = 10

        /** ~25 frames (~750 ms) of sub-threshold audio ends an utterance (the endpoint). */
        const val SILENCE_END_FRAMES = 25

        /** Utterances with fewer voiced frames than this (~270 ms) are noise blips. */
        const val MIN_SPEECH_FRAMES = 9

        /** Consecutive over-threshold frames needed to confirm an onset (~90 ms). */
        const val ONSET_FRAMES = 3

        /** Onset = energy above floor × factor. */
        const val ONSET_FACTOR = 2.5

        /** Below floor × this counts toward the silence endpoint. */
        const val END_FACTOR = 1.5

        /** Absolute RMS floors so a dead-quiet room doesn't make the bar near-zero. */
        const val MIN_ONSET_RMS = 550
        const val MIN_END_RMS = 350
        const val INITIAL_FLOOR = 300.0

        /** EMA weight for adapting the noise floor while silent. */
        const val FLOOR_ALPHA = 0.05
    }
}
