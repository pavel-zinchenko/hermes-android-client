package com.hermes.android.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes a compressed TTS clip to PCM and trims the leading/trailing silence, so
 * spoken audio starts and ends tight. TTS providers (Edge, etc.) pad each clip with
 * silence — plus MP3/AAC decoder priming adds more at the front — and that padding
 * otherwise stacks onto the gaps between sentences, making speech feel laggy.
 *
 * The trimmed PCM is re-wrapped as a WAV so the existing [AudioPlayer] (a MediaPlayer)
 * can play it unchanged. Everything is best-effort: any unsupported format or decode
 * error returns null, and the caller plays the original clip untrimmed.
 */
object AudioDecoder {

    // A 16-bit sample whose magnitude is below this (~0.6% of full scale) is silence.
    private const val SILENCE_THRESHOLD = 200
    // Keep a little audio around the speech so onsets/tails aren't clipped.
    private const val HEAD_MARGIN_MS = 20
    private const val TAIL_MARGIN_MS = 40
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Decodes [bytes] (compressed audio of [mime]) to 16-bit PCM, trims silence, and
     * returns the result as WAV bytes — or null if there's no decodable audio track,
     * the output isn't 16-bit PCM, the clip is entirely silent, or decoding fails.
     */
    fun toTrimmedWav(bytes: ByteArray, mime: String): ByteArray? = try {
        decode(bytes)?.let { pcm ->
            trim(pcm)?.let { wav(it.data, pcm.sampleRate, pcm.channels) }
        }
    } catch (_: Exception) {
        // Unsupported codec / malformed clip — caller falls back to the original.
        null
    }

    /** Raw interleaved 16-bit little-endian PCM plus its format. */
    private class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    private fun decode(bytes: ByteArray): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val codecMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            codec = MediaCodec.createDecoderByType(codecMime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: return null
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        // Bail on non-16-bit output (rare); the caller plays untrimmed.
                        val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT) return null
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    in 0..Int.MAX_VALUE -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk, 0, info.size)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED → just loop.
                    else -> {}
                }
            }
            if (channels <= 0 || sampleRate <= 0) return null
            return Pcm(pcm.toByteArray(), sampleRate, channels)
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /** Trims silence off both ends. Returns null if [pcm] is entirely silent. */
    private fun trim(pcm: Pcm): Pcm? {
        val bytesPerFrame = pcm.channels * 2
        if (bytesPerFrame == 0 || pcm.data.size < bytesPerFrame) return null
        val frameCount = pcm.data.size / bytesPerFrame
        val samples = ByteBuffer.wrap(pcm.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        var firstLoud = -1
        var lastLoud = -1
        for (frame in 0 until frameCount) {
            val base = frame * pcm.channels
            var loud = false
            for (c in 0 until pcm.channels) {
                if (abs(samples.get(base + c).toInt()) > SILENCE_THRESHOLD) {
                    loud = true
                    break
                }
            }
            if (loud) {
                if (firstLoud < 0) firstLoud = frame
                lastLoud = frame
            }
        }
        if (firstLoud < 0) return null // all silence

        val headFrames = HEAD_MARGIN_MS * pcm.sampleRate / 1000
        val tailFrames = TAIL_MARGIN_MS * pcm.sampleRate / 1000
        val startFrame = max(0, firstLoud - headFrames)
        val endFrame = min(frameCount - 1, lastLoud + tailFrames)
        val startByte = startFrame * bytesPerFrame
        val endByte = (endFrame + 1) * bytesPerFrame
        if (startByte == 0 && endByte == pcm.data.size) return pcm
        return Pcm(pcm.data.copyOfRange(startByte, endByte), pcm.sampleRate, pcm.channels)
    }

    /** Wraps 16-bit PCM in a minimal canonical WAV container. */
    private fun wav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                        // PCM fmt chunk size
        header.putShort(1)                       // audio format = PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)                      // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    /** Lets MediaExtractor read a clip straight from memory — no temp file needed. */
    private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val count = min(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {}
    }
}
