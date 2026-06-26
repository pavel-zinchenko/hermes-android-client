package com.hermes.android.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.hermes.android.data.DecodedAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One event from a [CallTranscriber], consumed identically regardless of engine. */
sealed interface CallSttEvent {
    /** Speech onset detected — drives barge-in (interrupt a playing reply). */
    data object SpeechStarted : CallSttEvent

    /** Live interim transcript (on-device engine only). */
    data class Partial(val text: String) : CallSttEvent

    /** A finalized utterance ready to send to Hermes. */
    data class Utterance(val text: String) : CallSttEvent

    /** The engine can't transcribe (e.g. no recognizer); the call should surface this. */
    data class Failure(val message: String) : CallSttEvent
}

/**
 * Continuous speech-to-text for call mode. Implementations keep listening and emit
 * a stream of [CallSttEvent]s until [stop]. The two engines (on-device
 * [DeviceSpeechTranscriber] and server [ServerVadTranscriber]) are
 * interchangeable behind this interface so the ViewModel's call loop is
 * engine-agnostic.
 */
interface CallTranscriber {
    val events: SharedFlow<CallSttEvent>
    fun start()
    fun stop()
    fun setMicEnabled(enabled: Boolean)

    /** Lets the server engine raise its barge-in bar while a reply plays. No-op otherwise. */
    fun setHermesSpeaking(speaking: Boolean) {}
}

/**
 * On-device STT via Android's [SpeechRecognizer]: native endpointing, live partial
 * transcripts, offline on API 33+ (else the platform's cloud recognizer). The
 * recognizer is single-utterance, so we re-arm `startListening` after every result
 * or transient error to stay continuous. All recognizer calls run on the main
 * thread, as the API requires.
 */
class DeviceSpeechTranscriber(private val context: Context) : CallTranscriber {

    private val _events = MutableSharedFlow<CallSttEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CallSttEvent> = _events.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    @Volatile private var muted = false
    /** True while a reply clip plays — stop listening so we don't transcribe the reply. */
    @Volatile private var gated = false
    private val resumeRunnable = Runnable { listen() }

    override fun start() {
        running = true
        muted = false
        main.post {
            val rec = buildRecognizer()
            if (rec == null) {
                _events.tryEmit(
                    CallSttEvent.Failure(
                        "On-device speech recognition isn't available. " +
                            "Switch voice input to Server in Settings.",
                    ),
                )
                running = false
                return@post
            }
            rec.setRecognitionListener(listener)
            recognizer = rec
            listen()
        }
    }

    override fun stop() {
        running = false
        main.post {
            main.removeCallbacks(resumeRunnable)
            recognizer?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
            recognizer = null
        }
    }

    override fun setMicEnabled(enabled: Boolean) {
        muted = !enabled
        main.post {
            if (muted) {
                runCatching { recognizer?.cancel() }
            } else if (running) {
                listen()
            }
        }
    }

    private fun listen() {
        if (!running || muted || gated) return
        val rec = recognizer ?: return
        runCatching { rec.startListening(recognizerIntent()) }
    }

    /** Re-arms after a result/transient error, throttled to dodge "recognizer busy". */
    private fun rearm() {
        if (!running || muted || gated) return
        main.postDelayed({ listen() }, REARM_DELAY_MS)
    }

    /**
     * Half-duplex gate. While a reply plays, stop the recognizer so it can't hear the
     * speaker. Resuming is debounced so the brief gaps between reply sentences don't
     * thrash the recognizer; it effectively resumes once the reply has finished.
     */
    override fun setHermesSpeaking(speaking: Boolean) {
        main.post {
            main.removeCallbacks(resumeRunnable)
            if (speaking) {
                gated = true
                runCatching { recognizer?.cancel() }
            } else {
                gated = false
                main.postDelayed(resumeRunnable, GATE_RESUME_DELAY_MS)
            }
        }
    }

    private fun buildRecognizer(): SpeechRecognizer? = runCatching {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) ->
                SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }.getOrNull()

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_LENGTH_MS,
            )
        }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private val listener = object : RecognitionListener {
        override fun onBeginningOfSpeech() {
            _events.tryEmit(CallSttEvent.SpeechStarted)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { _events.tryEmit(CallSttEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let { _events.tryEmit(CallSttEvent.Utterance(it)) }
            rearm()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    _events.tryEmit(CallSttEvent.Failure("Microphone permission is required."))
                // No speech / timeout / busy / spurious client errors are normal in a
                // continuous loop — just re-arm and keep listening.
                else -> rearm()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val REARM_DELAY_MS = 60L
        const val SILENCE_LENGTH_MS = 800L

        /** Wait this long after a clip ends before resuming, so inter-sentence gaps
         *  don't churn the recognizer; a real end-of-reply gap exceeds it. */
        const val GATE_RESUME_DELAY_MS = 700L
    }
}

/**
 * Server STT: client-side VAD ([ContinuousVoiceCapture]) segments speech, and each
 * completed utterance is transcribed via [transcribe] (the server's configured STT
 * provider). Full-duplex barge-in with hardware echo cancellation; no live partials.
 */
class ServerVadTranscriber(
    private val capture: ContinuousVoiceCapture,
    private val transcribe: suspend (ByteArray) -> Result<String>,
) : CallTranscriber {

    private val _events = MutableSharedFlow<CallSttEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CallSttEvent> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null

    override fun start() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        capture.start()
        s.launch {
            capture.speechStarted.collect { _events.tryEmit(CallSttEvent.SpeechStarted) }
        }
        s.launch {
            capture.utterances.collect { wav ->
                Log.d(TAG, "transcribing utterance: ${wav.size} bytes")
                transcribe(wav)
                    .onSuccess { text ->
                        Log.d(TAG, "transcript: \"$text\"")
                        if (text.isNotBlank()) _events.tryEmit(CallSttEvent.Utterance(text))
                    }
                    .onFailure {
                        Log.w(TAG, "transcribe failed", it)
                        _events.tryEmit(CallSttEvent.Failure("Couldn't transcribe that — try again."))
                    }
            }
        }
    }

    override fun stop() {
        capture.stop()
        scope?.cancel()
        scope = null
    }

    override fun setMicEnabled(enabled: Boolean) = capture.setMicEnabled(enabled)

    override fun setHermesSpeaking(speaking: Boolean) = capture.setHermesSpeaking(speaking)

    private companion object {
        const val TAG = "CallCapture"
    }
}

/**
 * Full-duplex STT: a shared [FullDuplexCallEngine] keeps the mic open while replies
 * play and cancels the speaker echo in software ([WebRtcAec][com.hermes.android.audio.echo.WebRtcAec]),
 * so its VAD only triggers on the user — enabling true mid-sentence barge-in. Each
 * completed (echo-cancelled) utterance is transcribed via [transcribe] (server STT).
 *
 * The [engine] is shared with a [FullDuplexClipPlayer]; this transcriber owns the
 * engine's start/stop lifecycle, the clip player only renders through it.
 */
class FullDuplexTranscriber(
    private val engine: FullDuplexCallEngine,
    private val transcribe: suspend (ByteArray) -> Result<String>,
) : CallTranscriber {

    private val _events = MutableSharedFlow<CallSttEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CallSttEvent> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null

    override fun start() {
        if (!engine.isAvailable) {
            _events.tryEmit(
                CallSttEvent.Failure(
                    "Full-duplex echo cancellation isn't available on this device. " +
                        "Switch voice input to On-device or Server in Settings.",
                ),
            )
            return
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        try {
            engine.start()
        } catch (e: Exception) {
            Log.w(TAG, "full-duplex start failed", e)
            _events.tryEmit(
                CallSttEvent.Failure("Couldn't start full-duplex audio — try Server voice input instead."),
            )
            s.cancel()
            scope = null
            return
        }
        s.launch {
            engine.speechStarted.collect { _events.tryEmit(CallSttEvent.SpeechStarted) }
        }
        s.launch {
            engine.utterances.collect { wav ->
                Log.d(TAG, "transcribing utterance: ${wav.size} bytes")
                transcribe(wav)
                    .onSuccess { text ->
                        Log.d(TAG, "transcript: \"$text\"")
                        if (text.isNotBlank()) _events.tryEmit(CallSttEvent.Utterance(text))
                    }
                    .onFailure {
                        Log.w(TAG, "transcribe failed", it)
                        _events.tryEmit(CallSttEvent.Failure("Couldn't transcribe that — try again."))
                    }
            }
        }
    }

    override fun stop() {
        engine.stop()
        scope?.cancel()
        scope = null
    }

    override fun setMicEnabled(enabled: Boolean) = engine.setMicEnabled(enabled)

    // setHermesSpeaking: no-op — full-duplex needs no mic gating (the AEC handles echo).

    private companion object {
        const val TAG = "CallCapture"
    }
}

/**
 * Plays the spoken reply clips of a call. Abstracts the echo strategy from the
 * ViewModel: half-duplex gates the mic around each clip, full-duplex streams the
 * clip through the echo canceller with the mic left open. One per call, paired with
 * the matching [CallTranscriber] in a [CallSession].
 */
interface CallClipPlayer {
    /** Plays one reply [clip], suspending until it finishes (or is stopped/cancelled). */
    suspend fun play(clip: DecodedAudio)

    /**
     * Plays [clip] on repeat until the calling coroutine is cancelled — used for the
     * looping filler/thinking sound. The default re-[play]s each repetition; overrides
     * prepare the clip once (decode/resample) to avoid redoing that work per loop.
     */
    suspend fun playLoop(clip: DecodedAudio) {
        try {
            while (currentCoroutineContext().isActive) play(clip)
        } finally {
            stop()
        }
    }

    /** Stops any in-flight playback immediately (barge-in / end of turn). */
    fun stop()
}

/**
 * Half-duplex playback: suppress the [transcriber]'s mic for the clip's duration so
 * the call never records its own voice, reopening it between clips for barge-in. Used
 * with the on-device and server STT engines.
 */
class HalfDuplexClipPlayer(
    private val player: AudioPlayer,
    private val transcriber: CallTranscriber,
) : CallClipPlayer {
    override suspend fun play(clip: DecodedAudio) {
        transcriber.setHermesSpeaking(true)
        try {
            player.playAndAwait(clip.bytes, clip.mime)
        } finally {
            transcriber.setHermesSpeaking(false)
        }
    }

    /** Gates the mic once for the whole loop instead of toggling it per repetition. */
    override suspend fun playLoop(clip: DecodedAudio) {
        transcriber.setHermesSpeaking(true)
        try {
            while (currentCoroutineContext().isActive) player.playAndAwait(clip.bytes, clip.mime)
        } finally {
            transcriber.setHermesSpeaking(false)
            player.stop()
        }
    }

    override fun stop() = player.stop()
}

/**
 * Full-duplex playback: decode → 16 kHz mono PCM → stream through the shared
 * [FullDuplexCallEngine], which feeds each frame to the echo canceller as it plays.
 * No mic gating — the mic stays open so the user can barge in mid-sentence.
 */
class FullDuplexClipPlayer(
    private val engine: FullDuplexCallEngine,
) : CallClipPlayer {
    override suspend fun play(clip: DecodedAudio) {
        val pcm = decode(clip)
        if (pcm.isEmpty()) return
        engine.playAndAwait(pcm)
    }

    /** Decodes/resamples the clip once, then streams it on repeat until cancelled. */
    override suspend fun playLoop(clip: DecodedAudio) {
        val pcm = decode(clip)
        if (pcm.isEmpty()) return
        try {
            while (currentCoroutineContext().isActive) engine.playAndAwait(pcm)
        } finally {
            engine.stopPlayback()
        }
    }

    private suspend fun decode(clip: DecodedAudio): ShortArray = withContext(Dispatchers.Default) {
        val decoded = AudioDecoder.decodeToPcm(clip.bytes, clip.mime)
            ?: return@withContext ShortArray(0)
        Resampler.toMono16k(decoded.data, decoded.sampleRate, decoded.channels)
    }

    override fun stop() = engine.stopPlayback()
}

/** A call's transcriber + matching clip player, built together so they can share state. */
class CallSession(
    val transcriber: CallTranscriber,
    val clipPlayer: CallClipPlayer,
)
