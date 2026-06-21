package com.hermes.android.data.gateway

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Wire + domain models for the Hermes `tui_gateway` JSON-RPC-over-WebSocket
 * protocol (served at `/api/ws` on the dashboard backend, port 9119). Mirrors
 * the reference client in `hermes-agent/apps/shared/src/json-rpc-gateway.ts`.
 *
 * Two frame shapes travel over the socket:
 *  - request/response: `{jsonrpc, id, method, params}` ↔ `{jsonrpc, id, result|error}`
 *  - server events:     `{jsonrpc, method:"event", params:{type, session_id, payload}}`
 */

/** Lifecycle of the underlying WebSocket, mirrored to observers. */
enum class ConnectionState { IDLE, CONNECTING, OPEN, CLOSED, ERROR }

/**
 * A decoded server→client event frame. [payload] is left as a raw [JsonObject]
 * so each consumer parses only the fields it needs (the protocol carries many
 * event types with divergent payloads).
 */
data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: JsonObject?,
)

/** Raw JSON-RPC frame as it arrives on the wire (either a response or an event). */
internal data class JsonRpcFrame(
    val id: String? = null,
    val method: String? = null,
    val result: com.google.gson.JsonElement? = null,
    val error: RpcError? = null,
    val params: EventParams? = null,
) {
    data class RpcError(val code: Int? = null, val message: String? = null)

    data class EventParams(
        val type: String? = null,
        @SerializedName("session_id") val sessionId: String? = null,
        val payload: JsonObject? = null,
    )
}

// --- Typed event payloads (parsed lazily from GatewayEvent.payload) -----------

/** `message.delta` / `reasoning.delta` / `thinking.delta`: `{text}`. */
data class DeltaPayload(val text: String? = null)

/** `status.update`: `{kind, text}`. */
data class StatusPayload(val kind: String? = null, val text: String? = null)

/** `message.complete`: `{text, status?, warning?}` (usage/reasoning ignored here). */
data class MessageCompletePayload(
    val text: String? = null,
    val status: String? = null,
    val warning: String? = null,
)

/** `error`: `{message}`. */
data class ErrorPayload(val message: String? = null)

/** `tool.start`: `{tool_id, name, context, args_text?}`. */
data class ToolStartPayload(
    @SerializedName("tool_id") val toolId: String? = null,
    val name: String? = null,
    val context: String? = null,
)

/**
 * `tool.complete`: `{tool_id, name, args, result, duration_s?, summary?,
 * result_text?, …}`. Only the fields the chip renders are decoded; `result_text`
 * is present only when the session runs in verbose tool-progress mode.
 */
data class ToolCompletePayload(
    @SerializedName("tool_id") val toolId: String? = null,
    val name: String? = null,
    val summary: String? = null,
    @SerializedName("result_text") val resultText: String? = null,
    @SerializedName("duration_s") val durationS: Double? = null,
)

// --- Interactive request payloads (the agent blocks until we respond) ----------

/**
 * `approval.request`: `{command, description, allow_permanent?}`. Unlike the
 * other interactive requests it carries **no** `request_id` — it is resolved by
 * session id via `approval.respond {session_id, choice}`.
 */
data class ApprovalRequestPayload(
    val command: String? = null,
    val description: String? = null,
    @SerializedName("allow_permanent") val allowPermanent: Boolean? = null,
)

/** `clarify.request`: `{question, choices, request_id}`. */
data class ClarifyRequestPayload(
    val question: String? = null,
    val choices: List<String>? = null,
    @SerializedName("request_id") val requestId: String? = null,
)

/** `sudo.request`: `{request_id}` (masked password entry). */
data class SudoRequestPayload(
    @SerializedName("request_id") val requestId: String? = null,
)

/** `secret.request`: `{prompt, env_var, request_id}` (masked value for an env var). */
data class SecretRequestPayload(
    val prompt: String? = null,
    @SerializedName("env_var") val envVar: String? = null,
    @SerializedName("request_id") val requestId: String? = null,
)

/** Result of `session.list`: `{sessions:[…]}` (see tui_gateway/server.py). */
data class SessionListResult(
    val sessions: List<GatewaySessionRow> = emptyList(),
)

data class GatewaySessionRow(
    val id: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @SerializedName("started_at") val startedAt: Long? = null,
    @SerializedName("message_count") val messageCount: Int? = null,
    val source: String? = null,
)

/**
 * Result of `session.resume` / `session.create`: the live `session_id` plus the
 * stored transcript. Note the gateway serializes each message's visible text
 * under the key **`text`** (see `_history_to_messages` in tui_gateway/server.py),
 * not `content` as the REST api_server does.
 */
data class ResumeResult(
    @SerializedName("session_id") val sessionId: String? = null,
    val messages: List<GatewayMessage> = emptyList(),
    val running: Boolean = false,
    val status: String? = null,
)

data class GatewayMessage(
    val role: String? = null,
    val text: String? = null,
    val name: String? = null,
)

/**
 * Result of `session.create`: a live `session_id` for this connection plus the
 * durable `stored_session_id` (the DB key used for resume/list/delete). The row
 * is persisted lazily on the first prompt, so the stored id is not resumable
 * until then — callers must hold the live sid to run the first turn.
 */
data class CreateSessionResult(
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("stored_session_id") val storedSessionId: String? = null,
)

// --- Model configuration (model.options / model.save_key / model.disconnect) --

/**
 * Result of `model.options`: the available providers plus the currently selected
 * `model`/`provider`. The gateway is invoked with picker hints, so each provider
 * row carries auth state (`authenticated`, `auth_type`, `key_env`, `warning`) and,
 * when present, per-model `pricing`. Only the lean subset the UI renders is decoded;
 * Gson ignores the rest (`total_models`, `capabilities`, `is_user_defined`, …).
 */
data class ModelOptionsResult(
    val providers: List<ModelProviderRow> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
)

/** One provider row from `model.options` (and the refreshed row from `model.save_key`). */
data class ModelProviderRow(
    val slug: String = "",
    val name: String = "",
    @SerializedName("is_current") val isCurrent: Boolean = false,
    val models: List<String> = emptyList(),
    val authenticated: Boolean = false,
    @SerializedName("auth_type") val authType: String? = null,
    @SerializedName("key_env") val keyEnv: String? = null,
    val warning: String? = null,
    val pricing: Map<String, ModelPrice>? = null,
)

/** Per-model pricing under [ModelProviderRow.pricing]; values are pre-formatted ("$3.00"). */
data class ModelPrice(
    val input: String? = null,
    val output: String? = null,
    val cache: String? = null,
    val free: Boolean = false,
)

// --- Repository → ViewModel contract -----------------------------------------

/**
 * An interactive decision the agent is blocked awaiting, normalized for the UI.
 * Approval is resolved by session id; the rest by their [requestId]. The user's
 * answer goes back via the matching `HermesRepository.respond*` method.
 */
sealed interface InteractiveRequest {
    data class Approval(val command: String, val description: String) : InteractiveRequest
    data class Clarify(
        val requestId: String,
        val question: String,
        val choices: List<String>,
    ) : InteractiveRequest
    data class Sudo(val requestId: String) : InteractiveRequest
    data class Secret(
        val requestId: String,
        val prompt: String,
        val envVar: String,
    ) : InteractiveRequest
}

/**
 * A streamed chat turn, normalized so the UI layer never sees gateway JSON.
 * Emitted by `HermesRepository.submitPromptStreaming`.
 */
sealed interface ChatEvent {
    /** The assistant has begun a reply; open/clear the streaming bubble. */
    data object Start : ChatEvent

    /** Append [text] to the in-progress assistant message. */
    data class Delta(val text: String) : ChatEvent

    /** Transient activity line, e.g. "Searching the web…". */
    data class Status(val kind: String, val text: String) : ChatEvent

    /** A tool invocation has begun; render a pending chip keyed by [toolId]. */
    data class ToolStart(val toolId: String, val name: String, val context: String?) : ChatEvent

    /** A tool invocation (matched by [toolId]) finished. */
    data class ToolComplete(
        val toolId: String,
        val summary: String?,
        val resultText: String?,
        val durationS: Double?,
    ) : ChatEvent

    /** A chunk of the agent's thinking/reasoning trace (`thinking.delta` + `reasoning.delta`). */
    data class Thinking(val text: String) : ChatEvent

    /** The agent is blocked awaiting a human decision; surface a modal. */
    data class Interactive(val request: InteractiveRequest) : ChatEvent

    /** Terminal: the final assistant text and turn [status]. */
    data class Complete(val text: String, val status: String?) : ChatEvent

    /** Terminal: the turn failed with [message]. */
    data class Failure(val message: String) : ChatEvent
}
