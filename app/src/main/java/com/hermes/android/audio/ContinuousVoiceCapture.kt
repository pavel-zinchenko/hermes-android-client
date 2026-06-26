package com.hermes.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.max

/**
 * Continuous, voice-activity-detected microphone capture for half-duplex call mode.
 * Unlike [VoiceRecorder] (one push-to-talk clip via MediaRecorder), this keeps the
 * mic open with [AudioRecord] so it can stream raw PCM frames through a shared [Vad]
 * and emit one [utterances] clip per detected utterance — the moment the user pauses
 * — without any button press.
 *
 * The capture source is the plain [MediaRecorder.AudioSource.MIC]: it's the only one
 * that reliably delivers audio here (the VOICE_COMMUNICATION source is hard-muted by
 * some devices outside a real VoIP call). Self-recording is prevented by half-duplex
 * gating: [setHermesSpeaking] suppresses detection while a reply clip plays, so the
 * mic never captures the speaker. The trade-off is that the user can't barge in
 * mid-reply by voice — for that, use the full-duplex engine (software AEC) instead.
 *
 * Emissions are non-suspending ([MutableSharedFlow.tryEmit] with spare buffer), so
 * the capture thread never blocks on a slow collector.
 */
class ContinuousVoiceCapture {

    private val _speechStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** Fires the instant speech onset is confirmed — before the utterance ends. */
    val speechStarted: SharedFlow<Unit> = _speechStarted.asSharedFlow()

    private val _utterances = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    /** One completed utterance per emission, as 16 kHz mono PCM WAV bytes. */
    val utterances: SharedFlow<ByteArray> = _utterances.asSharedFlow()

    @Volatile private var running = false
    @Volatile private var muted = false
    /** True while a reply clip is playing — the mic is suppressed to avoid self-capture. */
    @Volatile private var playing = false
    /** Until this time (elapsedRealtime), keep suppressing so the clip's echo tail decays. */
    @Volatile private var suppressUntilMs = 0L

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    private val vad = Vad(
        logTag = TAG,
        onSpeechStarted = { _speechStarted.tryEmit(Unit) },
        onUtterance = { pcm -> _utterances.tryEmit(PcmWav.wrap(pcm, SAMPLE_RATE, 1)) },
    )

    /** Starts capturing. Throws if the mic is unavailable or permission is missing. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        require(minBuf > 0) { "AudioRecord unavailable on this device" }
        val bufSize = max(minBuf, FRAME_SAMPLES * 2 * 8)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't initialize the microphone")
        }
        record = rec
        running = true
        muted = false
        rec.startRecording()
        Log.d(TAG, "capture started: minBuf=$minBuf bufSize=$bufSize session=${rec.audioSessionId}")
        thread = Thread({ loop(rec) }, "hermes-call-capture").apply { start() }
    }

    /** Stops capturing and releases all native resources. */
    fun stop() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
        record?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        record = null
    }

    /** Mutes/unmutes: while muted the mic keeps running but no utterances are emitted. */
    fun setMicEnabled(enabled: Boolean) {
        muted = !enabled
    }

    /**
     * Half-duplex gate: while a reply clip is [speaking] the mic is suppressed so the
     * speaker output is never captured. When it stops, suppression lingers for a short
     * tail so the clip's acoustic echo can decay before listening resumes.
     */
    fun setHermesSpeaking(speaking: Boolean) {
        playing = speaking
        if (!speaking) suppressUntilMs = SystemClock.elapsedRealtime() + PLAYBACK_TAIL_MS
    }

    /** The capture loop, run on a dedicated thread until [stop]. */
    private fun loop(rec: AudioRecord) {
        val frame = ShortArray(FRAME_SAMPLES)
        var firstRead = true

        while (running) {
            val n = rec.read(frame, 0, FRAME_SAMPLES)
            if (firstRead) { Log.d(TAG, "first read: n=$n"); firstRead = false }
            if (n <= 0) {
                if (n < 0) Log.w(TAG, "read error: $n")
                continue
            }
            // Suppress detection while muted, or while a reply clip is playing (plus a
            // short echo tail) so the speaker output is never captured. Keep reading so
            // the buffer doesn't overflow, but drop any partial utterance.
            val suppressed = muted || playing || SystemClock.elapsedRealtime() < suppressUntilMs
            if (suppressed) {
                vad.reset()
                continue
            }
            vad.process(frame, n)
        }
    }

    private companion object {
        const val TAG = "CallCapture"

        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 30 ms frames at 16 kHz. */
        const val FRAME_SAMPLES = 480

        /** Keep suppressing the mic this long after a clip ends so its echo decays. */
        const val PLAYBACK_TAIL_MS = 250L
    }
}
