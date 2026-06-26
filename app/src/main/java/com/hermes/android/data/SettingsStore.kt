package com.hermes.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

/** Which engine synthesizes spoken replies in voice mode. */
enum class VoiceEngine {
    /** Hermes server TTS over `/api/audio/speak` (neural voices; needs the server). */
    SERVER,

    /** Android's built-in [android.speech.tts.TextToSpeech] (offline, free). */
    ON_DEVICE,
}

/** Which engine transcribes speech in the continuous voice-call mode. */
enum class SttEngine {
    /**
     * Android's built-in [android.speech.SpeechRecognizer] (offline on API 31+,
     * free, native endpointing + live partial transcripts). Owns the mic, so
     * barge-in is best-effort.
     */
    ON_DEVICE,

    /**
     * Client-side voice-activity detection feeding the server's
     * `/api/audio/transcribe` (the configured STT provider). Half-duplex: the mic is
     * muted while a reply plays, so barge-in only works between sentences. No partials.
     */
    SERVER,

    /**
     * Like [SERVER], but keeps the mic open during playback and cancels the speaker's
     * echo in software (vendored WebRTC AECM). Enables true mid-sentence barge-in at
     * the cost of more CPU; server STT only (the canceller owns the mic). No partials.
     */
    FULL_DUPLEX,
}

data class HermesSettings(
    /**
     * Base URL of the single Hermes backend — the dashboard (`hermes dashboard`,
     * port 9119). It serves the JSON-RPC gateway WebSocket (`/api/ws`, used for
     * chat + sessions) and the REST audio endpoints (`/api/audio/...`, used for
     * voice). The legacy api_server (8642) has been retired.
     */
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Bearer/session token for [serverUrl] (HERMES_DASHBOARD_SESSION_TOKEN). */
    val apiKey: String = "",
    /**
     * Optional content URI (SAF) of an audio file looped while Hermes is thinking.
     * Empty = no thinking sound.
     */
    val thinkingSoundUri: String = "",
    /**
     * Which engine speaks replies in voice mode. Defaults to [VoiceEngine.SERVER]
     * (higher-quality neural voices); [VoiceEngine.ON_DEVICE] runs offline.
     */
    val voiceEngine: VoiceEngine = VoiceEngine.SERVER,
    /**
     * Which engine transcribes speech in continuous call mode. Defaults to
     * [SttEngine.ON_DEVICE] (offline, live partials); [SttEngine.SERVER] uses the
     * configured server STT provider with reliable barge-in.
     */
    val sttEngine: SttEngine = SttEngine.ON_DEVICE,
) {
    /** Normalized server URL guaranteed to end with a single trailing slash. */
    val normalizedServerUrl: String
        get() = serverUrl.ensureTrailingSlash()

    /**
     * WebSocket URL of the gateway, derived from the server URL:
     * `http(s)://host:9119/` → `ws(s)://host:9119/api/ws?token=<apiKey>`. The
     * gateway accepts the `?token=` query credential in loopback mode.
     */
    val gatewayWsUrl: String
        get() {
            val base = normalizedServerUrl
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            val suffix = if (apiKey.isNotBlank()) {
                "api/ws?token=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
            } else {
                "api/ws"
            }
            return base + suffix
        }

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:9119/"
    }
}

private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

/** Persists the server URL and API key via DataStore Preferences. */
class SettingsStore(private val context: Context) {

    private object Keys {
        // The single dashboard server reuses the former voice-server keys so that
        // an upgrading user who configured the 9119 dashboard token keeps it
        // without re-entry. The pre-consolidation keys ("base_url", "api_key",
        // "streaming_enabled") are intentionally no longer read.
        val SERVER_URL = stringPreferencesKey("voice_server_url")
        val API_KEY = stringPreferencesKey("voice_api_key")
        val THINKING_SOUND_URI = stringPreferencesKey("thinking_sound_uri")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val STT_ENGINE = stringPreferencesKey("stt_engine")
    }

    val settings: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            serverUrl = prefs[Keys.SERVER_URL]?.takeIf { it.isNotBlank() }
                ?: HermesSettings.DEFAULT_SERVER_URL,
            apiKey = prefs[Keys.API_KEY].orEmpty(),
            thinkingSoundUri = prefs[Keys.THINKING_SOUND_URI].orEmpty(),
            // Unknown/legacy values fall back to the server default.
            voiceEngine = prefs[Keys.VOICE_ENGINE]
                ?.let { name -> runCatching { VoiceEngine.valueOf(name) }.getOrNull() }
                ?: VoiceEngine.SERVER,
            sttEngine = prefs[Keys.STT_ENGINE]
                ?.let { name -> runCatching { SttEngine.valueOf(name) }.getOrNull() }
                ?: SttEngine.ON_DEVICE,
        )
    }

    suspend fun update(serverUrl: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = serverUrl.trim()
            prefs[Keys.API_KEY] = apiKey.trim()
        }
    }

    suspend fun updateThinkingSound(uri: String) {
        context.dataStore.edit { prefs -> prefs[Keys.THINKING_SOUND_URI] = uri.trim() }
    }

    suspend fun updateVoiceEngine(engine: VoiceEngine) {
        context.dataStore.edit { prefs -> prefs[Keys.VOICE_ENGINE] = engine.name }
    }

    suspend fun updateSttEngine(engine: SttEngine) {
        context.dataStore.edit { prefs -> prefs[Keys.STT_ENGINE] = engine.name }
    }
}
