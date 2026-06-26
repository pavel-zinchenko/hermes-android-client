package com.hermes.android.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.hermes.android.audio.echo.WebRtcAec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Full-duplex call engine: the mic stays open *while* a reply plays, and the
 * speaker's echo is removed in software ([WebRtcAec], vendored WebRTC AECM) before
 * the audio reaches the [Vad]. Because the VAD only ever sees the user's voice, an
 * onset can be detected mid-reply — enabling true mid-sentence barge-in, unlike the
 * half-duplex [ContinuousVoiceCapture] which simply mutes the mic during playback.
 *
 * One engine instance is shared by the call's transcriber (consuming [utterances])
 * and its clip player (calling [playAndAwait]); construct it once per call.
 *
 * All audio is **16 kHz mono int16**. The mic is read and the speaker is fed in
 * **10 ms / 160-sample** frames (the AECM's required block size); three captured
 * frames are batched into a 30 ms frame for the VAD, whose tuning assumes that size.
 */
class FullDuplexCallEngine {

    private val _speechStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** Fires the instant speech onset is confirmed on the *echo-cancelled* signal. */
    val speechStarted: SharedFlow<Unit> = _speechStarted.asSharedFlow()

    private val _utterances = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    /** One completed (echo-cancelled) utterance per emission, as 16 kHz mono PCM WAV. */
    val utterances: SharedFlow<ByteArray> = _utterances.asSharedFlow()

    @Volatile private var running = false
    @Volatile private var muted = false
    /** Bumped to abort the in-flight [playAndAwait] (barge-in / clip stop). */
    @Volatile private var playbackEpoch = 0

    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: WebRtcAec? = null

    /**
     * Far-end (playback) frames handed from the render coroutine to the capture thread.
     * The vendored AECM isn't safe for concurrent far/near calls, so *all* canceller
     * calls happen on the capture thread: render only enqueues here, capture drains one
     * per mic frame (keeping far ~aligned with near) and calls processRender first.
     */
    private val farQueue = ArrayDeque<ShortArray>()
    private val farLock = Any()

    private val vad = Vad(
        logTag = TAG,
        onSpeechStarted = { _speechStarted.tryEmit(Unit) },
        onUtterance = { pcm -> _utterances.tryEmit(PcmWav.wrap(pcm, SAMPLE_RATE, 1)) },
    )

    /** True only when the native echo canceller is present; required for full-duplex. */
    val isAvailable: Boolean get() = WebRtcAec.isAvailable

    /**
     * Starts the engine. Throws if the mic/speaker can't be initialized or the native
     * echo canceller is unavailable (caller should fall back to a half-duplex mode).
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val canceller = WebRtcAec.create(SAMPLE_RATE)
            ?: throw IllegalStateException("Echo canceller (libhermesaec) unavailable")
        aec = canceller

        val recMinBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, IN_CHANNEL, ENCODING)
        require(recMinBuf > 0) { "AudioRecord unavailable on this device" }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            IN_CHANNEL,
            ENCODING,
            max(recMinBuf, FRAME * 2 * 8),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            canceller.close(); aec = null
            throw IllegalStateException("Couldn't initialize the microphone")
        }

        val trkMinBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, OUT_CHANNEL, ENCODING)
        val trkBuf = max(trkMinBuf, FRAME * 2 * 8)
        val trk = AudioTrack(
            AudioAttributes.Builder()
                // Plain media path on purpose: the voice-communication usage mutes the
                // open mic on some devices (the bug that broke earlier call attempts).
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(OUT_CHANNEL)
                .setEncoding(ENCODING)
                .build(),
            trkBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (trk.state != AudioTrack.STATE_INITIALIZED) {
            trk.release()
            rec.release()
            canceller.close(); aec = null
            throw IllegalStateException("Couldn't initialize playback")
        }

        // The far-end queue is drained 1:1 with the mic, so a reference frame is fed to
        // the canceller right as it plays out (the queue absorbs the AudioTrack buffer
        // latency). The residual delay is therefore just the speaker→mic air path; the
        // AECM delay estimator refines from there. Tune AIR_MS on-device if needed.
        canceller.setStreamDelayMs(AIR_MS)

        record = rec
        track = trk
        running = true
        muted = false
        rec.startRecording()
        Log.d(TAG, "full-duplex started: recBuf=$recMinBuf trkBuf=$trkBuf delayMs=$AIR_MS")
        thread = Thread({ captureLoop(rec) }, "hermes-fullduplex-capture").apply { start() }
    }

    /** Stops everything and releases native resources (mic, speaker, canceller). */
    fun stop() {
        running = false
        playbackEpoch++
        // Stop the mic *before* joining: a capture thread blocked in AudioRecord.read
        // returns at once, so the loop sees running=false and exits promptly. Only then
        // is it safe to free the AEC the loop calls into — otherwise a slow join could
        // free the canceller while processCapture is mid-flight (native use-after-free).
        val capture = thread
        record?.let { runCatching { it.stop() } }
        capture?.let { runCatching { it.join(500) } }
        thread = null
        synchronized(farLock) { farQueue.clear() }
        track?.let { runCatching { it.pause() }; runCatching { it.flush() }; runCatching { it.stop() }; it.release() }
        track = null
        record?.let { it.release() }
        record = null
        // If the capture thread somehow didn't finish (wedged read), leak the native
        // canceller rather than free it out from under a still-running processCapture.
        if (capture == null || !capture.isAlive) aec?.close()
        aec = null
    }

    /** Mutes/unmutes the mic. While muted the engine keeps running but emits nothing. */
    fun setMicEnabled(enabled: Boolean) {
        muted = !enabled
    }

    /**
     * Stops the current [playAndAwait] promptly (barge-in or end-of-turn). The capture
     * side keeps running, so the user's interrupting speech is still captured.
     */
    fun stopPlayback() {
        playbackEpoch++
        track?.let { runCatching { it.pause() }; runCatching { it.flush() } }
        synchronized(farLock) { farQueue.clear() }
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) { val s = frame[i].toDouble(); sum += s * s }
        return kotlin.math.sqrt(sum / n)
    }

    /**
     * Plays [pcm] (16 kHz mono) through the speaker, feeding each 10 ms frame to the
     * echo canceller as the reference signal first. Suspends until the clip finishes;
     * returns early if [stopPlayback]/[stop] is called or the coroutine is cancelled.
     */
    suspend fun playAndAwait(pcm: ShortArray): Unit = withContext(Dispatchers.IO) {
        val trk = track ?: return@withContext
        val epoch = playbackEpoch
        runCatching { trk.play() }
        var offset = 0
        while (offset < pcm.size && epoch == playbackEpoch && isActive) {
            val len = min(FRAME, pcm.size - offset)
            val chunk = ShortArray(FRAME)
            pcm.copyInto(chunk, 0, offset, offset + len)
            // Hand this frame to the capture thread as the echo reference *before* it's
            // played, so the canceller (run there) can subtract its echo from the mic.
            // The zero-padded tail of the last frame is silence.
            synchronized(farLock) {
                farQueue.addLast(chunk)
                while (farQueue.size > MAX_FAR_FRAMES) farQueue.removeFirst()
            }
            var written = 0
            while (written < FRAME && epoch == playbackEpoch && isActive) {
                val w = trk.write(chunk, written, FRAME - written)
                if (w <= 0) break
                written += w
            }
            offset += FRAME
        }
    }

    /** Reads the mic, cancels echo per 10 ms frame, and runs VAD on 30 ms frames. */
    private fun captureLoop(rec: AudioRecord) {
        val frame = ShortArray(FRAME)
        val vadFrame = ShortArray(VAD_FRAME)
        // Reference fed to the canceller when no playback is queued. AECM needs a
        // far-end frame for *every* near frame to stay aligned; starving it (feeding
        // far only during playback) makes it mis-adapt and suppress the near-end too —
        // which left the mic deaf after a barge-in. Silence keeps it in lockstep.
        val silence = ShortArray(FRAME)
        var vadFill = 0
        var firstRead = true
        // Diagnostics: compare mic energy before/after cancellation while playback is
        // active, so we can tell over-suppression (clean≈0 when the user talks) from a
        // VAD-threshold issue (clean is high but no onset).
        var logFrames = 0
        var rawPeak = 0.0
        var cleanPeak = 0.0
        var farActiveFrames = 0

        while (running) {
            val n = rec.read(frame, 0, FRAME)
            if (firstRead) { Log.d(TAG, "first read: n=$n"); firstRead = false }
            if (n <= 0) {
                if (n < 0) Log.w(TAG, "read error: $n")
                continue
            }
            if (muted) {
                vad.reset()
                vadFill = 0
                synchronized(farLock) { farQueue.clear() }
                continue
            }
            // Run BOTH canceller calls here (single-threaded) and in lockstep: every
            // mic frame gets a matching reference — the queued playback frame, or
            // silence when nothing is playing — then cancel this mic frame in place.
            val far = synchronized(farLock) { farQueue.removeFirstOrNull() }
            val rawRms = if (n == FRAME) rms(frame, FRAME) else 0.0
            if (n == FRAME) {
                aec?.processRender(far ?: silence, FRAME)
                if (far != null) farActiveFrames++
                aec?.processCapture(frame, FRAME)
            }

            // Periodic raw-vs-clean level report (~every 1.3 s of frames).
            rawPeak = max(rawPeak, rawRms)
            cleanPeak = max(cleanPeak, if (n == FRAME) rms(frame, FRAME) else 0.0)
            if (++logFrames >= 128) {
                Log.d(TAG, "fd-level: rawPeak=${rawPeak.toInt()} cleanPeak=${cleanPeak.toInt()} farFrames=$farActiveFrames")
                logFrames = 0; rawPeak = 0.0; cleanPeak = 0.0; farActiveFrames = 0
            }

            if (n == FRAME && vadFill + FRAME <= VAD_FRAME) {
                frame.copyInto(vadFrame, vadFill, 0, FRAME)
                vadFill += FRAME
                if (vadFill == VAD_FRAME) {
                    vad.process(vadFrame, VAD_FRAME)
                    vadFill = 0
                }
            } else {
                // Off-size read (rare): flush whatever we have, then feed this frame.
                if (vadFill > 0) { vad.process(vadFrame, vadFill); vadFill = 0 }
                vad.process(frame, n)
            }
        }
    }

    private companion object {
        const val TAG = "CallCapture"

        const val SAMPLE_RATE = 16_000
        const val IN_CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val OUT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 10 ms at 16 kHz — the AECM block size for both capture and render. */
        const val FRAME = 160

        /** 30 ms VAD frame (3 × [FRAME]); matches the [Vad] tuning. */
        const val VAD_FRAME = 480

        /** Air-path + processing slack added to the playback-buffer delay estimate. */
        const val AIR_MS = 20

        /**
         * Cap on queued far-end frames (~1 s). Capture drains one per mic frame so the
         * queue normally stays at the playback-buffer depth; this only bounds it if the
         * two clocks drift, dropping the oldest reference rather than growing forever.
         */
        const val MAX_FAR_FRAMES = 100
    }
}
