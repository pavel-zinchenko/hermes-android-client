package com.hermes.android.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Plays TTS audio returned by Hermes. The bytes are written to a temp file in the
 * cache dir (MediaPlayer needs a path/descriptor), played once, then cleaned up.
 * Only one clip plays at a time; [play] stops any previous playback first.
 */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    /**
     * Plays [bytes] of the given [mime]. [onFinished] fires once on completion or
     * error so callers can advance their state machine without leaking.
     */
    fun play(bytes: ByteArray, mime: String, onFinished: () -> Unit) {
        stop()
        val file = File.createTempFile("hermes-tts-", extensionFor(mime), context.cacheDir)
        tempFile = file

        val mp = MediaPlayer()
        var finished = false
        val finishOnce = {
            if (!finished) {
                finished = true
                onFinished()
            }
        }
        // Order matters: tear down BEFORE signalling completion. [onFinished] resumes
        // the caller's coroutine, which on Dispatchers.Main.immediate re-enters
        // synchronously and may start the *next* clip — reassigning [player] and
        // calling prepareAsync. If stop() ran after that, it would release the clip
        // that just started (killing sequential playback after the first one). With
        // stop() first, it releases *this* clip (playback awaits each, so [player]
        // still points here), then the resume cleanly starts the next.
        mp.setOnCompletionListener {
            stop()
            finishOnce()
        }
        mp.setOnErrorListener { _, _, _ ->
            stop()
            finishOnce()
            true
        }
        // Guard against the prepare→release race: a barge-in (or the next clip)
        // can run stop()/release() while prepareAsync is in flight, after which a
        // late onPrepared would call start() on a released player and crash. Only
        // start if this MediaPlayer is still the current one.
        mp.setOnPreparedListener { if (player === it) it.start() }
        player = mp
        // writeBytes / setDataSource / prepareAsync can throw synchronously on a
        // malformed or empty clip (IOException, IllegalArgument/StateException). A
        // bad clip must resolve as "finished" — not blow up the caller's coroutine
        // (and, for a queued caller, kill the whole turn). Treat it like an error.
        try {
            file.writeBytes(bytes)
            mp.setDataSource(file.absolutePath)
            mp.prepareAsync()
        } catch (_: Exception) {
            stop()
            finishOnce()
        }
    }

    /**
     * Plays [bytes] of the given [mime] and suspends until playback finishes (or
     * errors). Cancelling the calling coroutine stops playback. Lets a caller play
     * a queue of clips sequentially with `for (clip in clips) playAndAwait(...)`.
     */
    suspend fun playAndAwait(bytes: ByteArray, mime: String): Unit =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { stop() }
            play(bytes, mime) { if (cont.isActive) cont.resume(Unit) }
        }

    /** Stops any current playback and releases resources. */
    fun stop() {
        player?.let { mp ->
            try {
                mp.stop()
            } catch (_: IllegalStateException) {
                // not started yet — fine
            }
            mp.release()
        }
        player = null
        tempFile?.delete()
        tempFile = null
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
        mime.contains("ogg") || mime.contains("opus") -> ".ogg"
        mime.contains("wav") -> ".wav"
        mime.contains("flac") -> ".flac"
        else -> ".mp3"
    }
}
