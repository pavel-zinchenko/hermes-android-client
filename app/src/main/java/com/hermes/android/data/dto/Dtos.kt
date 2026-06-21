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

// --- Cron / scheduled tasks --------------------------------------------------
// Mirror the cron job shape returned by GET /api/cron/jobs (web_server.py,
// backed by cron/jobs.py). Only the fields the app needs to mirror a job into a
// local Android alarm are declared; Gson ignores the rest.

/** One job from GET /api/cron/jobs. */
data class CronJobDto(
    val id: String,
    val name: String? = null,
    val prompt: String? = null,
    val schedule: CronScheduleDto? = null,
    @SerializedName("next_run_at") val nextRunAt: String? = null,
    val enabled: Boolean = true,
    val state: String? = null,
    val deliver: String? = null,
    val repeat: CronRepeatDto? = null,
)

/**
 * The `schedule` sub-object. Fields vary by [kind] (`once`/`interval`/`cron`);
 * the app only reads [kind] (for the recurring label) and [display] (for the row
 * subtitle) — the actual fire time comes from [CronJobDto.nextRunAt].
 */
data class CronScheduleDto(
    val kind: String? = null,
    val display: String? = null,
)

/** The `repeat` sub-object: `times` = 1 (one-shot), null/N (recurring). */
data class CronRepeatDto(
    val times: Int? = null,
    val completed: Int? = null,
)
