package com.hermes.android.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.hermes.android.data.DecodedAudio
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Synthesizes speech with Android's built-in [TextToSpeech] engine, returning the
 * audio as the same [DecodedAudio] the server path produces so it drops in behind a
 * single call site in the voice turn (see `ChatSessionViewModel.runTurn`). Offline,
 * free, and lower-latency than the server round trip — at the cost of the device
 * engine's (typically non-neural) voice quality.
 *
 * The engine initializes asynchronously; [synthesize] suspends until it is ready, or
 * returns a failure if no usable engine is present. One utterance is synthesized at a
 * time (the caller feeds fragments sequentially), but completion is dispatched by
 * utterance id so a late callback can never resolve the wrong request.
 */
class OnDeviceTts(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The waiter for each in-flight utterance, keyed by utterance id, resumed with
     * true on `onDone` / false on `onError`. The map dispatches by id so a stray late
     * callback can never resolve a different request.
     */
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Boolean>>()

    private val utteranceCounter = AtomicLong(0)

    /**
     * The platform engine binds to a system TTS service, which is wasted work when the
     * user keeps the server engine or never enters voice mode. Build it lazily on the
     * first [synthesize] so only on-device users pay that cost. `lazy` is synchronized,
     * so concurrent first calls still share one engine.
     */
    private val engine = lazy { Engine() }

    /** Owns the platform [TextToSpeech] instance and its readiness signal. */
    private inner class Engine {

        /** Resolves true once the engine is initialized and usable, false otherwise. */
        val ready = CompletableDeferred<Boolean>()

        // Referenced inside its own OnInitListener, which only runs after construction
        // completes, so `tts` is always assigned by the time the lambda fires.
        val tts: TextToSpeech = TextToSpeech(appContext) { status ->
            // A usable engine reports SUCCESS; pick a sensible default voice but don't
            // fail init if the locale's data is missing — the engine's own default still
            // speaks, just possibly in another language.
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts.setLanguage(Locale.getDefault()) }
                ready.complete(true)
            } else {
                ready.complete(false)
            }
        }

        init {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    pending.remove(utteranceId)?.let { if (it.isActive) it.resume(true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    pending.remove(utteranceId)?.let { if (it.isActive) it.resume(false) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    pending.remove(utteranceId)?.let { if (it.isActive) it.resume(false) }
                }
            })
        }
    }

    /**
     * Synthesizes [text] to a WAV clip. Suspends until the engine is ready and the
     * utterance is rendered. Returns a failure (rather than throwing) on any engine
     * problem so the caller can skip the fragment and keep the turn flowing.
     * Cancelling the coroutine stops in-flight synthesis.
     */
    suspend fun synthesize(text: String): Result<DecodedAudio> = runCatching {
        val engine = engine.value
        if (!engine.ready.await()) error("No usable text-to-speech engine on this device")

        val id = "hermes-tts-${utteranceCounter.incrementAndGet()}"
        val file = File.createTempFile(id, ".wav", appContext.cacheDir)
        try {
            // Await onDone/onError via the listener; cancellation stops the engine and
            // drops the entry. The continuation is registered (and synthesis kicked off)
            // inside the suspend so a callback can never arrive before we're waiting.
            val ok = suspendCancellableCoroutine { cont ->
                pending[id] = cont
                cont.invokeOnCancellation {
                    pending.remove(id)
                    runCatching { engine.tts.stop() }
                }
                val queued = engine.tts.synthesizeToFile(text, Bundle(), file, id)
                if (queued != TextToSpeech.SUCCESS && pending.remove(id) != null) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!ok) error("Text-to-speech synthesis failed")
            val bytes = file.readBytes()
            if (bytes.isEmpty()) error("Text-to-speech produced no audio")
            DecodedAudio(bytes = bytes, mime = "audio/wav")
        } finally {
            file.delete()
        }
    }

    /** Releases the engine; call when the owner is cleared. */
    fun shutdown() {
        pending.clear()
        // Don't trigger lazy init just to tear down — if it was never used, there's
        // nothing bound to release.
        if (engine.isInitialized()) {
            runCatching { engine.value.tts.stop() }
            runCatching { engine.value.tts.shutdown() }
        }
    }
}
