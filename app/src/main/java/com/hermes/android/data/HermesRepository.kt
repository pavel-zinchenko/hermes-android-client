package com.hermes.android.data

import android.util.Base64
import com.hermes.android.data.dto.SpeakRequest
import com.hermes.android.data.dto.TranscribeRequest
import com.hermes.android.data.gateway.ApprovalRequestPayload
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.gateway.ClarifyRequestPayload
import com.hermes.android.data.gateway.ConnectionState
import com.hermes.android.data.gateway.DeltaPayload
import com.hermes.android.data.gateway.ErrorPayload
import com.hermes.android.data.gateway.GatewayException
import com.hermes.android.data.gateway.HermesGateway
import com.hermes.android.data.gateway.InteractiveRequest
import com.hermes.android.data.gateway.MessageCompletePayload
import com.hermes.android.data.gateway.ResumeResult
import com.hermes.android.data.gateway.SecretRequestPayload
import com.hermes.android.data.gateway.SessionListResult
import com.hermes.android.data.gateway.StatusPayload
import com.hermes.android.data.gateway.SudoRequestPayload
import com.hermes.android.data.gateway.ToolCompletePayload
import com.hermes.android.data.gateway.ToolStartPayload
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.ChatSession
import com.hermes.android.data.model.Sender
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Single entry point to Hermes. Chat and sessions run over the JSON-RPC gateway
 * WebSocket; voice (STT/TTS) and the health probe use the REST endpoints on the
 * same dashboard server (9119). Holds the current settings (collected from
 * [SettingsStore]) so the Bearer key and server URL stay live without callers
 * passing them in. Retrofit instances are memoized per base URL.
 */
class HermesRepository(
    private val settingsStore: SettingsStore,
    appScope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(HermesSettings())
    val settings: StateFlow<HermesSettings> = _settings.asStateFlow()

    init {
        appScope.launch {
            settingsStore.settings.collect { _settings.value = it }
        }
    }

    private val gson = GsonBuilder().create()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { _settings.value.apiKey })
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .connectTimeout(10, TimeUnit.SECONDS)
        // Agent turns can be slow; allow a generous read window.
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(190, TimeUnit.SECONDS)
        .build()

    /**
     * The Bearer key for the audio client. Set by [audioTarget] before each audio
     * call so [AuthInterceptor] (which reads synchronously on the OkHttp thread)
     * sees the current token.
     */
    @Volatile
    private var audioKey: String = ""

    private val audioOkHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { audioKey })
        .addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        )
        .connectTimeout(10, TimeUnit.SECONDS)
        // TTS synthesis of a long reply can take a while.
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedApi: HermesApi? = null

    @Volatile
    private var cachedAudioBaseUrl: String? = null

    @Volatile
    private var cachedAudioApi: HermesAudioApi? = null

    /**
     * Dedicated client for the long-lived gateway WebSocket: ping to keep the
     * socket alive and no call timeout (an agent turn can stream for minutes).
     * Auth rides the URL `?token=` rather than a header, so no AuthInterceptor.
     */
    private val gatewayOkHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val gateway = HermesGateway(gatewayOkHttp, gson)

    /** A live gateway sid together with the connection epoch that minted it. */
    private data class LiveSession(val sid: String, val connectionId: Int)

    /** Maps a stored (DB) session id to its live per-connection gateway sid. */
    private val liveSessionIds = ConcurrentHashMap<String, LiveSession>()

    /**
     * Builds (memoized per base URL) a Retrofit client bound to the **current
     * persisted** settings. We read DataStore here rather than the [_settings]
     * StateFlow because that flow is seeded with defaults and only catches up
     * asynchronously (see the collector in [init]); a probe or authenticated call
     * issued before it emits would otherwise hit the default URL with an empty
     * Bearer key. Publishing the fresh value also keeps [AuthInterceptor] — which
     * must read synchronously on the OkHttp thread — in sync with this request.
     */
    private suspend fun api(): HermesApi {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        val baseUrl = settings.normalizedServerUrl
        val existing = cachedApi
        if (existing != null && cachedBaseUrl == baseUrl) return existing
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(HermesApi::class.java)
        cachedBaseUrl = baseUrl
        cachedApi = api
        return api
    }

    /**
     * Builds (memoized per base URL) a Retrofit client for the audio (STT/TTS)
     * endpoints, bound to [baseUrl]. The Bearer key is supplied out-of-band via
     * [audioKey] (set by [audioTarget]) because [AuthInterceptor] reads it
     * synchronously on the OkHttp thread.
     */
    private fun audioApi(baseUrl: String): HermesAudioApi {
        val existing = cachedAudioApi
        if (existing != null && cachedAudioBaseUrl == baseUrl) return existing
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(audioOkHttp)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(HermesAudioApi::class.java)
        cachedAudioBaseUrl = baseUrl
        cachedAudioApi = api
        return api
    }

    /**
     * Resolves the audio (STT/TTS) client and primes [audioKey] from the current
     * settings. Voice rides the REST `/api/audio/...` endpoints on the single
     * dashboard server — the gateway's own `voice.*` methods drive the server's
     * microphone/speakers, which is the desktop model and unusable on Android.
     */
    private suspend fun audioTarget(): HermesAudioApi {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        audioKey = settings.apiKey
        return audioApi(settings.normalizedServerUrl)
    }

    /** Reads the persisted settings directly (avoids the StateFlow seed race). */
    suspend fun currentSettings(): HermesSettings = settingsStore.settings.first()

    suspend fun updateSettings(serverUrl: String, apiKey: String) {
        settingsStore.update(serverUrl, apiKey)
    }

    suspend fun updateThinkingSound(uri: String) = settingsStore.updateThinkingSound(uri)

    /** Content URI of the looped "thinking" sound, or empty if none is set. */
    suspend fun thinkingSoundUri(): String = settingsStore.settings.first().thinkingSoundUri

    /** Lightweight reachability probe; true iff /health responds with status ok. */
    suspend fun checkHealth(): Result<Boolean> = runCatching {
        api().health().status.equals("ok", ignoreCase = true)
    }

    /**
     * Reachability probe: opens the gateway WebSocket, which resolves on the
     * `gateway.ready` handshake and throws on timeout/failure. Chat runs over the
     * gateway, so this probes exactly what the app actually depends on.
     */
    suspend fun checkHealthViaGateway(): Result<Boolean> = runCatching {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        gateway.connect(settings.gatewayWsUrl)
        gateway.connectionState.value == ConnectionState.OPEN
    }

    // --- Gateway chat ------------------------------------------------------
    // The gateway uses a per-connection *live* session id, distinct from the
    // stored DB id the app carries. session.resume maps the stored id → a live
    // sid (and returns the transcript); prompt.submit + events run on the live
    // sid. See data/gateway/ and HERMES_INTEGRATION.md.

    /**
     * Creates a session over the gateway so it carries the gateway's configured
     * model. (A session created via the REST api_server is stamped with the
     * advertised label "hermes-agent", which the gateway later restores as the
     * live model and the provider rejects — so chats must originate here.) The DB
     * row persists lazily on the first prompt; we cache the live sid now so that
     * first turn can run before any resume is possible.
     */
    suspend fun createSession(title: String? = null): Result<ChatSession> = runCatching {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        gateway.connect(settings.gatewayWsUrl)
        val params = buildMap<String, Any?> {
            put("source", "hermes-android")
            if (!title.isNullOrBlank()) put("title", title)
        }
        val created = gson.fromJson(
            gateway.call("session.create", params),
            com.hermes.android.data.gateway.CreateSessionResult::class.java,
        )
        val stored = created.storedSessionId
            ?: throw GatewayException("session.create returned no stored_session_id")
        created.sessionId?.let { liveSessionIds[stored] = LiveSession(it, gateway.connectionId) }
        ChatSession(
            id = stored,
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
            messageCount = 0,
            lastActive = null,
            preview = null,
        )
    }

    /** Lists stored sessions over the gateway (`session.list`). */
    suspend fun listSessions(): Result<List<ChatSession>> = runCatching {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        gateway.connect(settings.gatewayWsUrl)
        val result = gson.fromJson(
            gateway.call("session.list"),
            SessionListResult::class.java,
        )
        result.sessions.mapNotNull { it.toModelOrNull() }
    }

    /**
     * Deletes a stored session over the gateway (`session.delete`). The gateway
     * refuses to delete a session currently live in its process (RPC 4023); that
     * surfaces as a [GatewayException] the caller can report.
     */
    suspend fun deleteSession(storedId: String): Result<Unit> = runCatching {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        gateway.connect(settings.gatewayWsUrl)
        gateway.call("session.delete", mapOf("session_id" to storedId))
        Unit
    }

    /**
     * The cached live sid for [storedId], but only if it is still usable: minted on
     * the *current* connection (a reconnect bumps the epoch, invalidating old sids)
     * and that connection is open.
     */
    private fun validCachedSid(storedId: String): String? {
        val cached = liveSessionIds[storedId] ?: return null
        return cached.sid.takeIf {
            cached.connectionId == gateway.connectionId &&
                gateway.connectionState.value == ConnectionState.OPEN
        }
    }

    /**
     * Connects (if needed) and resolves the live sid for [storedId], caching it.
     * Prefers `session.resume` (which also returns the transcript); for a
     * freshly gateway-created session whose row isn't persisted yet, resume 404s
     * and we fall back to the live sid captured at creation time.
     */
    private suspend fun ensureLiveSession(storedId: String): ResumeResult {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        gateway.connect(settings.gatewayWsUrl)
        return try {
            val resume = gson.fromJson(
                gateway.call("session.resume", mapOf("session_id" to storedId)),
                ResumeResult::class.java,
            )
            val live = resume.sessionId
                ?: throw GatewayException("session.resume returned no session_id")
            liveSessionIds[storedId] = LiveSession(live, gateway.connectionId)
            resume
        } catch (e: GatewayException) {
            // A freshly gateway-created session has no persisted row yet, so resume
            // 404s; fall back to the live sid captured at creation — but only if it
            // is still valid for the current connection (a reconnect invalidates it).
            val cached = validCachedSid(storedId)
            if (cached != null) {
                ResumeResult(sessionId = cached)
            } else {
                throw e
            }
        }
    }

    /**
     * Resolves the live sid for [storedId] without re-fetching the transcript when
     * it can: a sid cached on the current connection is reused directly, so a send
     * does not `session.resume` (and re-download the entire history) on every turn.
     * Falls back to [ensureLiveSession] (which resumes) only when no valid sid is
     * cached — e.g. the first send after process start, or after a reconnect.
     */
    private suspend fun resolveLiveSid(storedId: String): String {
        val settings = settingsStore.settings.first()
        _settings.value = settings
        gateway.connect(settings.gatewayWsUrl)
        validCachedSid(storedId)?.let { return it }
        return ensureLiveSession(storedId).sessionId
            ?: throw GatewayException("could not resolve live session id")
    }

    /** Loads the transcript for [storedId] via the gateway (resume payload). */
    suspend fun getMessages(storedId: String): Result<List<ChatMessage>> = runCatching {
        ensureLiveSession(storedId).messages
            .mapIndexedNotNull { index, message -> message.toChatMessageOrNull(index) }
    }

    /**
     * Submits [text] and streams the assistant turn as [ChatEvent]s. Terminates
     * on `message.complete` (Complete) or `error` (Failure). The collector is
     * registered before `prompt.submit` so no early deltas are lost.
     */
    fun submitPromptStreaming(storedId: String, text: String): Flow<ChatEvent> = channelFlow {
        // Resolving the live sid connects/resumes and can fail (gateway down, bad
        // token, transient RPC error). Surface that as a terminal Failure rather
        // than throwing out of the flow — an uncaught throw here reaches the
        // collector's coroutine, where it is shown as no error at all (and crashes
        // the app on Android).
        val liveSid = try {
            resolveLiveSid(storedId)
        } catch (e: GatewayException) {
            trySend(ChatEvent.Failure(e.message ?: "Couldn't reach Hermes"))
            close()
            return@channelFlow
        }

        // Collect events in a child coroutine (the gateway event flow never
        // completes on its own); a terminal event calls close(), which resumes
        // awaitClose below and tears everything down.
        //
        // A delta/complete for *this* turn always follows the turn's own
        // `message.start`. Gating delta/complete on having seen that start drops a
        // trailing `message.complete` from a just-interrupted previous turn on the
        // same sid (Stop → immediate resend), which would otherwise close this turn
        // early. `error` is honored unconditionally — agent-init failures are
        // emitted without a preceding start.
        var started = false
        val collector = launch {
            gateway.events.collect { ev ->
                if (ev.sessionId != null && ev.sessionId != liveSid) return@collect
                when (ev.type) {
                    "message.start" -> {
                        started = true
                        trySend(ChatEvent.Start)
                    }
                    "message.delta" -> {
                        if (!started) return@collect
                        val deltaText = ev.payload
                            ?.let { gson.fromJson(it, DeltaPayload::class.java) }?.text
                        if (!deltaText.isNullOrEmpty()) trySend(ChatEvent.Delta(deltaText))
                    }
                    "status.update" -> {
                        val p = ev.payload?.let { gson.fromJson(it, StatusPayload::class.java) }
                        trySend(ChatEvent.Status(p?.kind.orEmpty(), p?.text.orEmpty()))
                    }
                    // Tool and thinking events are gated on `started` for the same
                    // reason as delta/complete: the gateway always emits this turn's
                    // `message.start` before the agent runs (and thus before any tool
                    // or thinking event), so gating here drops nothing legitimate —
                    // it only suppresses trailing events from a just-interrupted prior
                    // turn on the same sid (Stop → immediate resend), which would
                    // otherwise leak a stray chip/thinking block into this turn.
                    "tool.start" -> {
                        if (!started) return@collect
                        val p = ev.payload
                            ?.let { gson.fromJson(it, ToolStartPayload::class.java) }
                        val id = p?.toolId
                        if (!id.isNullOrEmpty()) {
                            trySend(ChatEvent.ToolStart(id, p.name.orEmpty(), p.context))
                        }
                    }
                    "tool.complete" -> {
                        if (!started) return@collect
                        val p = ev.payload
                            ?.let { gson.fromJson(it, ToolCompletePayload::class.java) }
                        val id = p?.toolId
                        if (!id.isNullOrEmpty()) {
                            trySend(ChatEvent.ToolComplete(id, p.summary, p.resultText, p.durationS))
                        }
                    }
                    "thinking.delta", "reasoning.delta" -> {
                        if (!started) return@collect
                        val text = ev.payload
                            ?.let { gson.fromJson(it, DeltaPayload::class.java) }?.text
                        if (!text.isNullOrEmpty()) trySend(ChatEvent.Thinking(text))
                    }
                    // Interactive requests are NOT gated on `started`: a clarify can
                    // legitimately precede `message.start`, and dropping it would hang
                    // the agent (it blocks until we respond). The interrupted-prior-turn
                    // leak that motivates the gate elsewhere can't happen here — Stop
                    // calls `session.interrupt`, which clears the session's pending
                    // prompts server-side, so no stale `*.request` survives a resend.
                    "approval.request" -> {
                        val p = ev.payload
                            ?.let { gson.fromJson(it, ApprovalRequestPayload::class.java) }
                        trySend(
                            ChatEvent.Interactive(
                                InteractiveRequest.Approval(
                                    command = p?.command.orEmpty(),
                                    description = p?.description.orEmpty(),
                                ),
                            ),
                        )
                    }
                    "clarify.request" -> {
                        val p = ev.payload
                            ?.let { gson.fromJson(it, ClarifyRequestPayload::class.java) }
                        val rid = p?.requestId
                        if (!rid.isNullOrEmpty()) {
                            trySend(
                                ChatEvent.Interactive(
                                    InteractiveRequest.Clarify(
                                        requestId = rid,
                                        question = p.question.orEmpty(),
                                        choices = p.choices ?: emptyList(),
                                    ),
                                ),
                            )
                        }
                    }
                    "sudo.request" -> {
                        val rid = ev.payload
                            ?.let { gson.fromJson(it, SudoRequestPayload::class.java) }?.requestId
                        if (!rid.isNullOrEmpty()) {
                            trySend(ChatEvent.Interactive(InteractiveRequest.Sudo(rid)))
                        }
                    }
                    "secret.request" -> {
                        val p = ev.payload
                            ?.let { gson.fromJson(it, SecretRequestPayload::class.java) }
                        val rid = p?.requestId
                        if (!rid.isNullOrEmpty()) {
                            trySend(
                                ChatEvent.Interactive(
                                    InteractiveRequest.Secret(
                                        requestId = rid,
                                        prompt = p.prompt.orEmpty(),
                                        envVar = p.envVar.orEmpty(),
                                    ),
                                ),
                            )
                        }
                    }
                    "message.complete" -> {
                        if (!started) return@collect
                        val p = ev.payload
                            ?.let { gson.fromJson(it, MessageCompletePayload::class.java) }
                        trySend(ChatEvent.Complete(p?.text.orEmpty(), p?.status))
                        close()
                    }
                    "error" -> {
                        val p = ev.payload?.let { gson.fromJson(it, ErrorPayload::class.java) }
                        trySend(ChatEvent.Failure(p?.message ?: "Hermes reported an error"))
                        close()
                    }
                }
            }
        }

        // Kick off the turn after the collector is active. The yield lets the
        // collector's subscription register first; the server's own latency before
        // the first delta makes this race practically moot anyway.
        //
        // A session still busy with a just-interrupted turn rejects prompt.submit with
        // error 4009. session.interrupt is asynchronous — the agent may take a beat to
        // actually stop — so retry briefly on 4009 (a barge-in: Stop/new recording →
        // immediate resend) before giving up. Other errors fail immediately.
        val submit = launch {
            yield()
            var attempt = 0
            while (true) {
                try {
                    gateway.call("prompt.submit", mapOf("session_id" to liveSid, "text" to text))
                    break
                } catch (e: GatewayException) {
                    if (e.code == 4009 && attempt < BUSY_RETRY_LIMIT) {
                        attempt++
                        delay(BUSY_RETRY_DELAY_MS)
                        continue
                    }
                    trySend(ChatEvent.Failure(e.message ?: "Failed to send"))
                    close()
                    break
                }
            }
        }

        awaitClose {
            collector.cancel()
            submit.cancel()
        }
    }

    /** Interrupts the in-flight turn for [storedId], if a live session is known. */
    suspend fun interrupt(storedId: String): Result<Unit> = runCatching {
        val live = liveSessionIds[storedId]?.sid ?: return@runCatching
        gateway.call("session.interrupt", mapOf("session_id" to live))
    }

    /**
     * Resolves a pending `approval.request` for [storedId]. [choice] is one of
     * `once` / `session` / `deny` (the gateway also accepts `always`, which the UI
     * intentionally doesn't offer). Approvals are keyed by session, not request id.
     */
    suspend fun respondApproval(storedId: String, choice: String): Result<Unit> = runCatching {
        val live = resolveLiveSid(storedId)
        gateway.call("approval.respond", mapOf("session_id" to live, "choice" to choice))
        Unit
    }

    /** Answers a pending `clarify.request` identified by [requestId]. */
    suspend fun respondClarify(requestId: String, answer: String): Result<Unit> =
        respondById("clarify.respond", requestId, "answer", answer)

    /** Supplies the password for a pending `sudo.request`. Never logged or persisted. */
    suspend fun respondSudo(requestId: String, password: String): Result<Unit> =
        respondById("sudo.respond", requestId, "password", password)

    /** Supplies the value for a pending `secret.request`. Never logged or persisted. */
    suspend fun respondSecret(requestId: String, value: String): Result<Unit> =
        respondById("secret.respond", requestId, "value", value)

    /** Shared body for the `request_id`-keyed interactive responses. */
    private suspend fun respondById(
        method: String,
        requestId: String,
        key: String,
        value: String,
    ): Result<Unit> = runCatching {
        gateway.call(method, mapOf("request_id" to requestId, key to value))
        Unit
    }

    /** Transcribes recorded audio to text via Hermes's configured STT provider. */
    suspend fun transcribe(audio: ByteArray, mime: String): Result<String> = runCatching {
        val encoded = Base64.encodeToString(audio, Base64.NO_WRAP)
        val response = audioTarget().transcribe(
            TranscribeRequest(dataUrl = "data:$mime;base64,$encoded", mimeType = mime)
        )
        if (!response.ok) throw IllegalStateException("Transcription failed")
        response.transcript?.trim().orEmpty()
    }.recoverCatching { throw VoiceServiceException(VoiceService.SPEECH_TO_TEXT, it) }

    /** Synthesizes [text] to speech via Hermes's configured TTS provider chain. */
    suspend fun speak(text: String): Result<DecodedAudio> = runCatching {
        val response = audioTarget().speak(SpeakRequest(text))
        val dataUrl = response.dataUrl
        if (!response.ok || dataUrl.isNullOrBlank()) {
            throw IllegalStateException("Speech synthesis failed")
        }
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) {
            throw IllegalStateException("Malformed audio data URL")
        }
        val header = dataUrl.substring(5, comma) // e.g. "audio/mpeg;base64"
        val mime = response.mimeType?.takeIf { it.isNotBlank() }
            ?: header.substringBefore(';').ifBlank { "audio/mpeg" }
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        DecodedAudio(bytes = bytes, mime = mime)
    }.recoverCatching { throw VoiceServiceException(VoiceService.TEXT_TO_SPEECH, it) }

    private companion object {
        /** Retries prompt.submit while a just-interrupted session is still busy (4009). */
        const val BUSY_RETRY_LIMIT = 8
        const val BUSY_RETRY_DELAY_MS = 150L
    }
}

/** Which voice service a [VoiceServiceException] refers to. */
enum class VoiceService(val label: String) {
    SPEECH_TO_TEXT("Speech-to-text"),
    TEXT_TO_SPEECH("Text-to-speech"),
}

/**
 * Wraps a failure from a voice call so the UI can name the affected [service].
 * The Hermes audio error body carries the provider's message but not its name, so
 * this label ("Speech-to-text" / "Text-to-speech") is the best service identifier
 * the client can attach; the original [cause] still supplies the detail.
 */
class VoiceServiceException(
    val service: VoiceService,
    override val cause: Throwable,
) : Exception(cause)

/** Decoded audio payload returned by [HermesRepository.speak], ready for playback. */
data class DecodedAudio(
    val bytes: ByteArray,
    val mime: String,
)

/**
 * Maps a gateway `session.list` row to the app's [ChatSession]. Rows without an
 * id are dropped. `started_at` is not surfaced (the list UI shows title + preview/
 * count only), so [ChatSession.lastActive] is left null.
 */
private fun com.hermes.android.data.gateway.GatewaySessionRow.toModelOrNull(): ChatSession? {
    val sid = id?.takeIf { it.isNotBlank() } ?: return null
    return ChatSession(
        id = sid,
        title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
        messageCount = messageCount ?: 0,
        lastActive = null,
        preview = preview?.takeIf { it.isNotBlank() },
    )
}

/**
 * Maps a gateway transcript message to a chat bubble for the gateway's
 * `{role, text}` shape (tool/system rows and empties dropped).
 *
 * The gateway transcript carries no message ids, so the id is derived from the
 * message's [index] in the transcript. Hashing the content instead would collide
 * for any two identical messages (e.g. the user sending "yes" twice), producing
 * duplicate keys that crash the LazyColumn in `ChatScreen`.
 */
private fun com.hermes.android.data.gateway.GatewayMessage.toChatMessageOrNull(index: Int): ChatMessage? {
    val sender = when (role?.lowercase()) {
        "user" -> Sender.USER
        "assistant" -> Sender.ASSISTANT
        else -> return null
    }
    val body = text?.trim().orEmpty()
    if (body.isEmpty()) return null
    return ChatMessage(
        id = "${index}_${role}_${body.hashCode()}",
        sender = sender,
        text = body,
    )
}
