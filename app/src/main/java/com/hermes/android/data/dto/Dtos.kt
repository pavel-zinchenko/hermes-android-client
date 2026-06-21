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

// --- Model selection ---------------------------------------------------------
// Mirror POST /api/model/set (web_server.py). Saving a provider key and listing
// providers ride the gateway WS (model.save_key / model.options); selecting the
// active model has no gateway equivalent, so it uses this REST endpoint.

/**
 * Request for POST /api/model/set. We only ever set the primary slot, so [scope]
 * is fixed to "main". [confirmExpensive] is re-sent as true after the user accepts
 * the expensive-model warning.
 */
data class ModelSetRequest(
    val provider: String,
    val model: String,
    val scope: String = "main",
    @SerializedName("confirm_expensive_model") val confirmExpensive: Boolean = false,
)

/**
 * Response for POST /api/model/set. On success `ok` is true. If the model is
 * flagged expensive the server returns `ok=false` with `confirm_required=true` and
 * a `confirm_message` to show before re-posting with `confirmExpensive=true`.
 */
data class ModelSetResponse(
    val ok: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    @SerializedName("confirm_required") val confirmRequired: Boolean = false,
    @SerializedName("confirm_message") val confirmMessage: String? = null,
)

// --- Voice providers (TTS toolset + STT config) ------------------------------
// TTS is a first-class "toolset" on the dashboard (GET/PUT
// /api/tools/toolsets/tts/...), so it has a rich provider matrix. STT is plain
// config (stt.provider in config.yaml) plus generic /api/env key storage, so it
// reuses the bare config/env DTOs below. See HERMES_INTEGRATION.md / the plan.

/** Response for GET /api/tools/toolsets/{name}/config (we use it for `tts`). */
data class ToolsetConfigResponse(
    val name: String = "",
    @SerializedName("has_category") val hasCategory: Boolean = false,
    @SerializedName("active_provider") val activeProvider: String? = null,
    val providers: List<ToolsetProviderDto> = emptyList(),
)

/** One provider row in a toolset config. Providers are keyed by display [name]. */
data class ToolsetProviderDto(
    val name: String = "",
    val badge: String? = null,
    val tag: String? = null,
    @SerializedName("env_vars") val envVars: List<ToolsetEnvVarDto> = emptyList(),
    @SerializedName("requires_nous_auth") val requiresNousAuth: Boolean = false,
    @SerializedName("is_active") val isActive: Boolean = false,
)

/** A single API-key env var a toolset provider needs, with its set-state. */
data class ToolsetEnvVarDto(
    val key: String = "",
    val prompt: String? = null,
    val url: String? = null,
    @SerializedName("is_set") val isSet: Boolean = false,
)

/** Request for PUT /api/tools/toolsets/{name}/provider — select by display name. */
data class ToolsetProviderSelectRequest(val provider: String)

/** Request for PUT /api/tools/toolsets/{name}/env — save one or more keys. */
data class ToolsetEnvUpdateRequest(val env: Map<String, String>)

/** Request for PUT /api/env — save a single provider API key by env-var name. */
data class EnvVarUpdateRequest(val key: String, val value: String)

/**
 * Request for PUT /api/config. The server overwrites the whole config with
 * [config], so callers GET the full tree, mutate one key, and send it all back.
 */
data class ConfigUpdateRequest(val config: com.google.gson.JsonObject)

/** One entry of GET /api/env (`{ ENV_VAR: { is_set, ... } }`); only is_set is used. */
data class EnvVarStatusDto(
    @SerializedName("is_set") val isSet: Boolean = false,
)

/** Generic `{ "ok": true }` ack for PUTs whose body we otherwise ignore. */
data class OkResponse(val ok: Boolean = false)

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
