package com.hermes.android.data

import com.hermes.android.data.dto.ChatRequest
import com.hermes.android.data.dto.CreateSessionRequest
import com.hermes.android.data.dto.MessageDto
import com.hermes.android.data.dto.PatchSessionRequest
import com.hermes.android.data.dto.SessionDto
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

    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedApi: HermesApi? = null

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

    /** Reads the persisted settings directly (avoids the StateFlow seed race). */
    suspend fun currentSettings(): HermesSettings = settingsStore.settings.first()

    suspend fun updateSettings(baseUrl: String, apiKey: String) =
        settingsStore.update(baseUrl, apiKey)

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
}

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
