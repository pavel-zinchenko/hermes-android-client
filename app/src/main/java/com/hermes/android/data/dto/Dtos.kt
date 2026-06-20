package com.hermes.android.data.dto

import com.google.gson.annotations.SerializedName

/**
 * REST DTOs for the Hermes dashboard server (9119). Chat and sessions run over the
 * JSON-RPC gateway WebSocket (see data/gateway/), so the only REST shapes left are
 * the health probe and the audio (STT/TTS) endpoints. Only the fields the app uses
 * are declared; unknown keys are ignored by Gson.
 */

data class HealthDto(
    val status: String?,
    val platform: String?,
    val version: String?,
)

// --- Voice (STT/TTS) ---------------------------------------------------------
// Mirror the audio endpoints exposed by the Hermes dashboard server
// (hermes_cli/web_server.py): /api/audio/transcribe and /api/audio/speak.

/** Request for POST /api/audio/transcribe. */
data class TranscribeRequest(
    @SerializedName("data_url") val dataUrl: String,
    @SerializedName("mime_type") val mimeType: String,
)

/** Response for POST /api/audio/transcribe. */
data class TranscribeResponse(
    val ok: Boolean = false,
    val transcript: String? = null,
    val provider: String? = null,
)

/** Request for POST /api/audio/speak. */
data class SpeakRequest(
    val text: String,
)

/** Response for POST /api/audio/speak. The audio is a base64 `data:` URL. */
data class SpeakResponse(
    val ok: Boolean = false,
    @SerializedName("data_url") val dataUrl: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null,
    val provider: String? = null,
)
