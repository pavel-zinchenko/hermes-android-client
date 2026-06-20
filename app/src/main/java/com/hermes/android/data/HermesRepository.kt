package com.hermes.android.data

import android.util.Base64
import com.hermes.android.data.dto.ChatRequest
import com.hermes.android.data.dto.CreateSessionRequest
import com.hermes.android.data.dto.MessageDto
import com.hermes.android.data.dto.PatchSessionRequest
import com.hermes.android.data.dto.SessionDto
import com.hermes.android.data.dto.SpeakRequest
import com.hermes.android.data.dto.TranscribeRequest
import com.hermes.android.data.model.ChatMessage
import com.hermes.android.data.model.ChatSession
import com.hermes.android.data.model.Sender
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
