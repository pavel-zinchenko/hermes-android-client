package com.hermes.android.data

import android.util.Base64
import com.hermes.android.data.dto.ChatRequest
import com.hermes.android.data.dto.CreateSessionRequest
import com.hermes.android.data.dto.MessageDto
import com.hermes.android.data.dto.PatchSessionRequest
import com.hermes.android.data.dto.SessionDto
import com.hermes.android.data.dto.SpeakRequest
import com.hermes.android.data.dto.TranscribeRequest
import com.hermes.android.data.gateway.ChatEvent
import com.hermes.android.data.gateway.ConnectionState
import com.hermes.android.data.gateway.DeltaPayload
import com.hermes.android.data.gateway.ErrorPayload
import com.hermes.android.data.gateway.GatewayException
import com.hermes.android.data.gateway.HermesGateway
import com.hermes.android.data.gateway.MessageCompletePayload
import com.hermes.android.data.gateway.ResumeResult
import com.hermes.android.data.gateway.StatusPayload
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.ChatSession
import com.hermes.android.data.model.Sender
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
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
 * Single entry point to Hermes over HTTP. Holds the current settings (collected
 * from [SettingsStore]) so the Bearer key and base URL stay live without callers
 * passing them in. Retrofit instances are memoized per base URL.
 */
class HermesRepository(
    private val settingsStore: SettingsStore,
    appScope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(
        HermesSettings(HermesSettings.DEFAULT_BASE_URL, "")
    )
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
     * The Bearer key for the audio client. Set by [resolveAudioTarget] before each
     * audio call so [AuthInterceptor] (which reads synchronously on the OkHttp
     * thread) uses the right token for the resolved server (9119 vs gateway).
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
     * Cached result of the gateway `audio_api` capability probe (null = not yet
     * probed). Decides whether voice routes to the gateway (B) or the separately
     * configured voice server (A). Reset by [updateSettings].
     */
    @Volatile
    private var gatewayAudioApi: Boolean? = null

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
        val baseUrl = settings.normalizedBaseUrl
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
     * [audioKey] (set by [resolveAudioTarget]) because it varies with the target.
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
     * Resolves where audio calls go and primes [audioKey]. If the gateway reports
     * `features.audio_api` (option B, after the upstream PR lands), voice rides the
     * main gateway with the main key; otherwise it uses the separately configured
     * voice server (option A, the dashboard web server on 9119). The probe result
     * is cached for the session and reset by [updateSettings] / [updateVoiceSettings].
     */
    private suspend fun resolveAudioTarget(): HermesAudioApi {
        val settings = settingsStore.settings.first()
        _settings.value = settings

        if (gatewayAudioApi == null) {
            gatewayAudioApi = runCatching { api().capabilities() }
                .getOrNull()?.features?.audioApi ?: false
        }

        val baseUrl: String
        if (gatewayAudioApi == true) {
            baseUrl = settings.normalizedBaseUrl
            audioKey = settings.apiKey
        } else {
            baseUrl = settings.normalizedVoiceServerUrl
            audioKey = settings.voiceApiKey
        }
        return audioApi(baseUrl)
    }

    /** Reads the persisted settings directly (avoids the StateFlow seed race). */
    suspend fun currentSettings(): HermesSettings = settingsStore.settings.first()

    suspend fun updateSettings(baseUrl: String, apiKey: String) {
        gatewayAudioApi = null // re-probe against the new gateway
        settingsStore.update(baseUrl, apiKey)
    }

    suspend fun updateVoiceSettings(voiceServerUrl: String, voiceApiKey: String) {
        gatewayAudioApi = null
        settingsStore.updateVoice(voiceServerUrl, voiceApiKey)
    }

    suspend fun updateThinkingSound(uri: String) = settingsStore.updateThinkingSound(uri)

    suspend fun updateStreaming(enabled: Boolean) = settingsStore.updateStreaming(enabled)

    /** Content URI of the looped "thinking" sound, or empty if none is set. */
    suspend fun thinkingSoundUri(): String = settingsStore.settings.first().thinkingSoundUri

    /** Lightweight reachability probe; true iff /health responds with status ok. */
    suspend fun checkHealth(): Result<Boolean> = runCatching {
        api().health().status.equals("ok", ignoreCase = true)
    }

    suspend fun listSessions(): Result<List<ChatSession>> = runCatching {
        api().listSessions().data.map { it.toModel() }
    }

    suspend fun createSession(title: String? = null): Result<ChatSession> = runCatching {
        api().createSession(CreateSessionRequest(title = title)).session.toModel()
    }

    suspend fun renameSession(id: String, title: String): Result<ChatSession> = runCatching {
        api().renameSession(id, PatchSessionRequest(title)).session.toModel()
    }

    suspend fun deleteSession(id: String): Result<Unit> = runCatching {
        api().deleteSession(id)
    }

    suspend fun getMessages(id: String): Result<List<ChatMessage>> = runCatching {
        api().getMessages(id).data.mapNotNull { it.toModelOrNull() }
    }

    /** Sends one turn and returns the assistant reply text. */
    suspend fun sendMessage(sessionId: String, text: String): Result<String> = runCatching {
        val response = api().sendMessage(sessionId, ChatRequest(text))
        response.message?.content.orEmpty()
    }

    // --- Gateway (streaming) chat ------------------------------------------
    // The gateway uses a per-connection *live* session id, distinct from the
    // stored DB id the app carries. session.resume maps the stored id → a live
    // sid (and returns the transcript); prompt.submit + events run on the live
    // sid. See data/gateway/ and HERMES_INTEGRATION.md.

    /** True iff the user has opted into the streaming gateway transport. */
    suspend fun streamingEnabled(): Boolean = settingsStore.settings.first().streamingEnabled

    /**
     * Creates a session over the gateway so it carries the gateway's configured
     * model. (A session created via the REST api_server is stamped with the
     * advertised label "hermes-agent", which the gateway later restores as the
     * live model and the provider rejects — so streaming chats must originate
     * here.) The DB row persists lazily on the first prompt; we cache the live
     * sid now so that first turn can run before any resume is possible.
     */
    suspend fun createSessionViaGateway(title: String? = null): Result<ChatSession> = runCatching {
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
    suspend fun getMessagesViaGateway(storedId: String): Result<List<ChatMessage>> = runCatching {
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
        // the first delta makes this race practically moot anyway. A busy session
        // is rejected by the server (error 4009), surfaced via the catch below.
        val submit = launch {
            yield()
            try {
                gateway.call("prompt.submit", mapOf("session_id" to liveSid, "text" to text))
            } catch (e: GatewayException) {
                trySend(ChatEvent.Failure(e.message ?: "Failed to send"))
                close()
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

    /** Transcribes recorded audio to text via Hermes's configured STT provider. */
    suspend fun transcribe(audio: ByteArray, mime: String): Result<String> = runCatching {
        val encoded = Base64.encodeToString(audio, Base64.NO_WRAP)
        val response = resolveAudioTarget().transcribe(
            TranscribeRequest(dataUrl = "data:$mime;base64,$encoded", mimeType = mime)
        )
        if (!response.ok) throw IllegalStateException("Transcription failed")
        response.transcript?.trim().orEmpty()
    }.recoverCatching { throw VoiceServiceException(VoiceService.SPEECH_TO_TEXT, it) }

    /** Synthesizes [text] to speech via Hermes's configured TTS provider chain. */
    suspend fun speak(text: String): Result<DecodedAudio> = runCatching {
        val response = resolveAudioTarget().speak(SpeakRequest(text))
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

private fun SessionDto.toModel(): ChatSession = ChatSession(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: "Untitled",
    messageCount = messageCount ?: 0,
    lastActive = lastActive ?: startedAt,
    preview = preview,
)

/**
 * Maps a stored message to a chat bubble. Only user/assistant turns are surfaced;
 * tool/system rows and empty assistant placeholders are dropped from the UI.
 */
private fun MessageDto.toModelOrNull(): ChatMessage? {
    val sender = when (role.lowercase()) {
        "user" -> Sender.USER
        "assistant" -> Sender.ASSISTANT
        else -> return null
    }
    val body = content?.trim().orEmpty()
    if (body.isEmpty()) return null
    return ChatMessage(
        id = id ?: "${role}_${body.hashCode()}",
        sender = sender,
        text = body,
    )
}

/**
 * Maps a gateway transcript message to a chat bubble. Like [toModelOrNull] but
 * for the gateway's `{role, text}` shape (tool/system rows and empties dropped).
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
