package com.hermes.android.data

import com.hermes.android.data.dto.HealthDto
import retrofit2.http.GET

/**
 * Retrofit interface to the Hermes dashboard server (9119). Chat and sessions run
 * over the JSON-RPC gateway WebSocket; the only REST call that remains is the
 * lightweight health probe used by the connection gate and the settings "Test
 * connection" button. The Bearer key is injected by [AuthInterceptor].
 */
interface HermesApi {

    @GET("health")
    suspend fun health(): HealthDto
}
