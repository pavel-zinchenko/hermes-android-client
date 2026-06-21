package com.hermes.android.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

/**
 * Loops an optional user-chosen audio file (a SAF content URI) while Hermes is
 * thinking. A blank URI or any playback error is a silent no-op so it never
 * interferes with the actual chat/voice flow.
 */
class ThinkingSoundPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /** Starts looping [uri]. No-op if [uri] is blank or can't be played. */
    fun start(uri: String?) {
        stop()
        if (uri.isNullOrBlank()) return
        try {
            val mp = MediaPlayer()
            mp.isLooping = true
            mp.setDataSource(context, Uri.parse(uri))
            // The filler is started then stopped on nearly every sentence gap, so a
            // stop()/release() routinely lands while prepareAsync is still in flight.
            // Only start if this player is still current, or a late onPrepared would
            // call start() on a released player and crash.
            mp.setOnPreparedListener { if (player === it) it.start() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            // Missing file / revoked permission / unsupported codec — stay silent.
            stop()
        }
    }

    fun stop() {
        player?.let { mp ->
            try {
                mp.stop()
            } catch (_: IllegalStateException) {
                // not started yet
            }
            mp.release()
        }
        player = null
    }
}
