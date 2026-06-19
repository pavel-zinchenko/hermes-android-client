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
) {
    /** Normalized base URL guaranteed to end with a single trailing slash. */
    val normalizedBaseUrl: String
        get() = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8642/"
    }
}

/** Persists the server URL and API key via DataStore Preferences. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
    }

    val settings: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            baseUrl = prefs[Keys.BASE_URL]?.takeIf { it.isNotBlank() }
                ?: HermesSettings.DEFAULT_BASE_URL,
            apiKey = prefs[Keys.API_KEY].orEmpty(),
        )
    }

    suspend fun update(baseUrl: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.API_KEY] = apiKey.trim()
        }
    }
}
