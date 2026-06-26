package com.hermes.android.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.hermes.android.data.DecodedAudio

/**
 * Loops an optional user-chosen audio file (a SAF content URI) to fill the gaps
 * between spoken sentences while Hermes is thinking. A blank URI or any playback
 * error is a silent no-op so it never interferes with the actual chat/voice flow.
 *
 * The filler is toggled on and off on *every* sentence gap, so it is [prepare]d
 * once per turn and then driven with cheap [resume]/[pause] calls. Re-preparing a
 * fresh [MediaPlayer] on each gap (the old model) meant `prepareAsync` was still in
 * flight when a short gap ended, so brief gaps fell silent; a prepared player
 * resumes instantly and actually covers them.
 */
class ThinkingSoundPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    // Set once the async prepare completes, so resume/pause know it's safe to drive.
    private var prepared = false
    // Whether the filler should be sounding right now. A resume()/pause() before
    // prepare completes records intent here; onPrepared applies it.
    private var wantPlaying = false

    /**
     * Prepares [uri] as a looping filler, ready for instant [resume]/[pause]. Tears
     * down any previous player first. No-op if [uri] is blank or can't be played.
     */
    fun prepare(uri: String?) {
        release()
        if (uri.isNullOrBlank()) return
        try {
            val mp = MediaPlayer()
            mp.isLooping = true
            mp.setDataSource(context, Uri.parse(uri))
            mp.setOnPreparedListener {
                // The player may have been released while preparing; only touch it if
                // it's still current, or a late callback would start a released player.
                if (player === it) {
                    prepared = true
                    if (wantPlaying) it.start()
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                release()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            // Missing file / revoked permission / unsupported codec — stay silent.
            release()
        }
    }

    /** Starts or resumes the filler. Cheap once [prepare]d; safe to call repeatedly. */
    fun resume() {
        wantPlaying = true
        val mp = player ?: return
        if (!prepared) return // onPrepared will start it once ready
        try {
            if (!mp.isPlaying) mp.start()
        } catch (_: IllegalStateException) {
            // Player moved to an invalid state (e.g. error) — leave it alone.
        }
    }

    /** Pauses the filler without tearing it down, so the next [resume] is instant. */
    fun pause() {
        wantPlaying = false
        val mp = player ?: return
        if (!prepared) return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * Reads [uri]'s raw bytes + MIME so the call-mode filler can be played through the
     * clip player (which applies the right echo strategy) instead of this standalone
     * MediaPlayer. Returns null for a blank/unreadable URI.
     */
    fun loadClip(uri: String?): DecodedAudio? {
        if (uri.isNullOrBlank()) return null
        return try {
            val u = Uri.parse(uri)
            val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty()) return null
            val mime = context.contentResolver.getType(u) ?: "audio/mpeg"
            DecodedAudio(bytes, mime)
        } catch (_: Exception) {
            null
        }
    }

    /** Fully stops the filler and releases its resources. */
    fun release() {
        player?.let { mp ->
            try {
                mp.stop()
            } catch (_: IllegalStateException) {
                // not started yet
            }
            mp.release()
        }
        player = null
        prepared = false
        wantPlaying = false
    }
}
