package com.hermes.android.ui

import com.google.gson.JsonParser
import com.hermes.android.data.VoiceServiceException
import retrofit2.HttpException
import java.io.IOException

/** Maps a Throwable from the repository to a short, user-facing message. */
fun Throwable.toUserMessage(): String = when (this) {
    // Prefix the underlying detail with the affected service (STT/TTS), since the
    // audio error body names the failure but not which service it came from.
    is VoiceServiceException -> "${service.label} failed — ${cause.toUserMessage()}"
    is HttpException -> {
        val detail = serverErrorDetail()
        when (code()) {
            // The key is what's actionable here, so keep the canned guidance.
            401, 403 -> "Unauthorized — check the API key in Settings."
            404 -> detail ?: "Not found on the server."
            503 -> detail ?: "Hermes session store unavailable."
            // For everything else (400, 500, …) the server's own message names
            // which service failed and why — surface it when present.
            else -> detail ?: "Server error (${code()})."
        }
    }
    is IOException -> "Can't reach Hermes. Is the gateway running on this device?"
    else -> message ?: "Something went wrong."
}

/**
 * Pulls a human-readable message out of a Hermes error response body. Handles the
 * three shapes the servers emit:
 *  - FastAPI dashboard server: `{"detail": "Transcription failed: …"}`
 *  - Gateway api_server (OpenAI envelope): `{"error": {"message": "…"}}`
 *  - Gateway cron endpoints: `{"error": "…"}`
 *
 * Returns null if the body is missing, unparseable, or carries no usable text, so
 * callers can fall back to a status-code message.
 */
private fun HttpException.serverErrorDetail(): String? {
    // errorBody() is a one-shot stream; read it once and guard everything.
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return null

    val text = runCatching {
        val root = JsonParser.parseString(raw)
        if (!root.isJsonObject) return@runCatching null
        val obj = root.asJsonObject

        obj.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
            ?: obj.get("error")?.let { error ->
                when {
                    error.isJsonPrimitive -> error.asString
                    error.isJsonObject ->
                        error.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                    else -> null
                }
            }
    }.getOrNull()

    return text?.trim()?.takeIf { it.isNotEmpty() }
}
