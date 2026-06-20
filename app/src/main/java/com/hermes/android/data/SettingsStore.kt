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

data class HermesSettings(
    val baseUrl: String,
    val apiKey: String,
    /**
     * Base URL of the server that exposes the audio (STT/TTS) endpoints. Defaults
     * to the Hermes dashboard web server (`hermes web`, port 9119). Ignored once
     * the gateway advertises `audio_api` — see [HermesRepository] resolveAudioTarget.
     */
    val voiceServerUrl: String = DEFAULT_VOICE_SERVER_URL,
    /** Bearer key for [voiceServerUrl] (HERMES_DASHBOARD_SESSION_TOKEN for 9119). */
    val voiceApiKey: String = "",
    /**
     * Optional content URI (SAF) of an audio file looped while Hermes is thinking.
     * Empty = no thinking sound.
     */
    val thinkingSoundUri: String = "",
) {
    /** Normalized base URL guaranteed to end with a single trailing slash. */
    val normalizedBaseUrl: String
        get() = baseUrl.ensureTrailingSlash()

    /** Normalized voice server URL guaranteed to end with a single trailing slash. */
    val normalizedVoiceServerUrl: String
        get() = voiceServerUrl.ensureTrailingSlash()

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8642/"
        const val DEFAULT_VOICE_SERVER_URL = "http://127.0.0.1:9119/"
    }
}

private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

/** Persists the server URL and API key via DataStore Preferences. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val VOICE_SERVER_URL = stringPreferencesKey("voice_server_url")
        val VOICE_API_KEY = stringPreferencesKey("voice_api_key")
        val THINKING_SOUND_URI = stringPreferencesKey("thinking_sound_uri")
    }

    val settings: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            baseUrl = prefs[Keys.BASE_URL]?.takeIf { it.isNotBlank() }
                ?: HermesSettings.DEFAULT_BASE_URL,
            apiKey = prefs[Keys.API_KEY].orEmpty(),
            voiceServerUrl = prefs[Keys.VOICE_SERVER_URL]?.takeIf { it.isNotBlank() }
                ?: HermesSettings.DEFAULT_VOICE_SERVER_URL,
            voiceApiKey = prefs[Keys.VOICE_API_KEY].orEmpty(),
            thinkingSoundUri = prefs[Keys.THINKING_SOUND_URI].orEmpty(),
        )
    }

    suspend fun update(baseUrl: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.API_KEY] = apiKey.trim()
        }
    }

    suspend fun updateVoice(voiceServerUrl: String, voiceApiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOICE_SERVER_URL] = voiceServerUrl.trim()
            prefs[Keys.VOICE_API_KEY] = voiceApiKey.trim()
        }
    }

    suspend fun updateThinkingSound(uri: String) {
        context.dataStore.edit { prefs -> prefs[Keys.THINKING_SOUND_URI] = uri.trim() }
    }
}
