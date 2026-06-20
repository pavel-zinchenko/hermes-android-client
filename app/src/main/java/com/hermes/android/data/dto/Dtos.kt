package com.hermes.android.data.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs mirroring the Hermes api_server contract. Field names match the JSON keys
 * produced by `_session_response` / `_message_response` in
 * hermes-agent/gateway/platforms/api_server.py. Only the fields the app uses are
 * declared; unknown keys are ignored by Gson.
 */

data class HealthDto(
    val status: String?,
    val platform: String?,
    val version: String?,
)

data class SessionDto(
    val id: String,
    val title: String? = null,
    val model: String? = null,
    val source: String? = null,
    @SerializedName("message_count") val messageCount: Int? = null,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("last_active") val lastActive: String? = null,
    @SerializedName("ended_at") val endedAt: String? = null,
    val preview: String? = null,
)

/** Envelope for GET /api/sessions. */
data class SessionListDto(
    val data: List<SessionDto> = emptyList(),
    @SerializedName("has_more") val hasMore: Boolean = false,
)

/** Envelope for POST/GET/PATCH /api/sessions[/{id}]. */
data class SessionEnvelopeDto(
    val session: SessionDto,
)

data class MessageDto(
    val id: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    val role: String,
    val content: String? = null,
    @SerializedName("tool_name") val toolName: String? = null,
    val timestamp: String? = null,
    @SerializedName("finish_reason") val finishReason: String? = null,
)

/** Envelope for GET /api/sessions/{id}/messages. */
data class MessageListDto(
    @SerializedName("session_id") val sessionId: String? = null,
    val data: List<MessageDto> = emptyList(),
)

data class CreateSessionRequest(
    val title: String? = null,
    val model: String? = null,
)

data class PatchSessionRequest(
    val title: String,
)

data class ChatRequest(
    val message: String,
)

data class ChatMessageDto(
    val role: String,
    val content: String? = null,
)

data class UsageDto(
    @SerializedName("input_tokens") val inputTokens: Int? = null,
    @SerializedName("output_tokens") val outputTokens: Int? = null,
    @SerializedName("total_tokens") val totalTokens: Int? = null,
)

/** Response for POST /api/sessions/{id}/chat. */
data class ChatResponse(
    @SerializedName("session_id") val sessionId: String? = null,
    val message: ChatMessageDto? = null,
    val usage: UsageDto? = null,
)

// --- Voice (STT/TTS) ---------------------------------------------------------
// Mirror the audio endpoints exposed by the Hermes dashboard web server
// (hermes_cli/web_server.py) and — once the upstream PR lands — the gateway
// api_server. The shapes are identical on both servers; see hermes-pr.md.

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

/** Subset of GET /v1/capabilities used to detect gateway-native audio support. */
data class CapabilitiesDto(
    val features: FeaturesDto? = null,
)

data class FeaturesDto(
    @SerializedName("audio_api") val audioApi: Boolean = false,
)
