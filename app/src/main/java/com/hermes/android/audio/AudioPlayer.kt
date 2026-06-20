package com.hermes.android.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.File

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
        file.writeBytes(bytes)
        tempFile = file

        val mp = MediaPlayer()
        var finished = false
        val finishOnce = {
            if (!finished) {
                finished = true
                onFinished()
            }
        }
        mp.setOnCompletionListener {
            finishOnce()
            stop()
        }
        mp.setOnErrorListener { _, _, _ ->
            finishOnce()
            stop()
            true
        }
        mp.setOnPreparedListener { it.start() }
        mp.setDataSource(file.absolutePath)
        mp.prepareAsync()
        player = mp
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
