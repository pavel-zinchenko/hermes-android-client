package com.hermes.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records microphone audio to an OGG/Opus file (both supported from API 29, the
 * app's minSdk) and returns the raw bytes. The container/codec is widely accepted
 * by Hermes's cloud STT providers (Groq/OpenAI Whisper, etc.). Temp files live in
 * the cache dir and are deleted after the bytes are read — nothing is persisted.
 */
class VoiceRecorder(private val context: Context) {

    val mimeType: String = "audio/ogg"

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** True while a recording is in progress. */
    val isRecording: Boolean get() = recorder != null

    /** Starts recording. Throws if the mic is unavailable or permission is missing. */
    fun start() {
        if (recorder != null) return
        val file = File.createTempFile("hermes-voice-", ".ogg", context.cacheDir)
        val rec = newRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            // prepare()/start() can throw if the mic is busy or permission was
            // revoked mid-call — release the recorder and temp file before rethrowing
            // so a failed attempt never leaks the native recorder or locks the mic.
            rec.release()
            file.delete()
            throw e
        }
        recorder = rec
        outputFile = file
    }

    /**
     * Stops recording and returns the captured bytes, or null if the clip was too
     * short to produce valid audio (MediaRecorder.stop() throws in that case).
     */
    fun stop(): ByteArray? {
        val rec = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null
        return try {
            rec.stop()
            file?.readBytes()
        } catch (_: RuntimeException) {
            null
        } finally {
            rec.release()
            file?.delete()
        }
    }

    /** Aborts recording without returning data (e.g. when leaving the screen). */
    fun cancel() {
        val rec = recorder ?: return
        val file = outputFile
        recorder = null
        outputFile = null
        try {
            rec.stop()
        } catch (_: RuntimeException) {
            // never started / too short — nothing to keep
        }
        rec.release()
        file?.delete()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}
