package com.hermes.android.audio.echo

import android.util.Log

/**
 * Loads `libhermesaec.so` and exposes the raw JNI entry points. Kept private to this
 * file so callers go through [WebRtcAec]; [available] is false if the native library
 * couldn't be loaded (then full-duplex call mode degrades to a half-duplex fallback).
 */
private object WebRtcAecNative {
    val available: Boolean = runCatching { System.loadLibrary("hermesaec") }
        .onFailure { Log.w("HermesAec", "libhermesaec failed to load", it) }
        .isSuccess

    external fun nativeCreate(sampleRate: Int): Long
    external fun nativeProcessRender(handle: Long, far: ShortArray, len: Int)
    external fun nativeProcessCapture(handle: Long, near: ShortArray, len: Int)
    external fun nativeSetStreamDelayMs(handle: Long, delayMs: Int)
    external fun nativeDestroy(handle: Long)
}

/**
 * Kotlin binding for the vendored WebRTC AECM (acoustic echo canceller for mobile),
 * built from BSD-3 sources as `libhermesaec.so`. It runs on **10 ms / 160-sample,
 * 16 kHz mono int16** frames — the only format the AECM accepts here.
 *
 * Drive it from the two audio threads of a full-duplex call:
 *  - **render:** call [processRender] with each playback chunk *before* it reaches the
 *    speaker, so the canceller learns the reference (far-end) signal;
 *  - **capture:** call [processCapture] on each mic frame to subtract that signal's
 *    echo in place — what's left is (mostly) just the user.
 *
 * Because the echo is removed, downstream VAD only triggers on the user's voice, so
 * the user can interrupt a playing reply mid-sentence (true barge-in).
 *
 * Not thread-safe for overlapping calls on the *same* method, but [processRender] and
 * [processCapture] may run concurrently on their respective threads (the native
 * far-end ring buffer is single-producer/single-consumer). [close] must not race
 * with either — stop both audio threads first.
 */
class WebRtcAec private constructor(@Volatile private var handle: Long) : AutoCloseable {

    /** Subtract the current echo from [frame] (≤160 samples) in place. */
    fun processCapture(frame: ShortArray, len: Int = frame.size) {
        val h = handle
        if (h != 0L) WebRtcAecNative.nativeProcessCapture(h, frame, len)
    }

    /** Register a playback [frame] (≤160 samples) as the echo reference before it plays. */
    fun processRender(frame: ShortArray, len: Int = frame.size) {
        val h = handle
        if (h != 0L) WebRtcAecNative.nativeProcessRender(h, frame, len)
    }

    /** Update the estimated mic↔speaker round-trip delay (ms) the canceller assumes. */
    fun setStreamDelayMs(delayMs: Int) {
        val h = handle
        if (h != 0L) WebRtcAecNative.nativeSetStreamDelayMs(h, delayMs)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) WebRtcAecNative.nativeDestroy(h)
    }

    companion object {
        /** True when the native library loaded; full-duplex requires it. */
        val isAvailable: Boolean get() = WebRtcAecNative.available

        /** 10 ms at 16 kHz — the frame size every call into [WebRtcAec] must use. */
        const val FRAME_SAMPLES = 160
        const val SAMPLE_RATE = 16_000

        /**
         * Creates a canceller at [sampleRate] (16 kHz), or null if the native library
         * is unavailable or allocation failed. Caller [close]s it when the call ends.
         */
        fun create(sampleRate: Int = SAMPLE_RATE): WebRtcAec? {
            if (!WebRtcAecNative.available) return null
            val h = WebRtcAecNative.nativeCreate(sampleRate)
            return if (h != 0L) WebRtcAec(h) else null
        }
    }
}
