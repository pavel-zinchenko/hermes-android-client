package com.hermes.android.data

import com.hermes.android.data.dto.ChatRequest
import com.hermes.android.data.dto.ChatResponse
import com.hermes.android.data.dto.CreateSessionRequest
import com.hermes.android.data.dto.HealthDto
import com.hermes.android.data.dto.MessageListDto
import com.hermes.android.data.dto.PatchSessionRequest
import com.hermes.android.data.dto.SessionEnvelopeDto
import com.hermes.android.data.dto.SessionListDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface to the Hermes gateway api_server. See HERMES_INTEGRATION.md
 * for the endpoint contract. The Bearer key is injected by [AuthInterceptor], so
 * it does not appear in these signatures.
 */
interface HermesApi {

    @GET("health")
    suspend fun health(): HealthDto

    @GET("api/sessions")
    suspend fun listSessions(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): SessionListDto

    @POST("api/sessions")
    suspend fun createSession(@Body body: CreateSessionRequest): SessionEnvelopeDto

    @GET("api/sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionEnvelopeDto

    @PATCH("api/sessions/{id}")
    suspend fun renameSession(
        @Path("id") id: String,
        @Body body: PatchSessionRequest,
    ): SessionEnvelopeDto

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String)

    @GET("api/sessions/{id}/messages")
    suspend fun getMessages(@Path("id") id: String): MessageListDto

    @POST("api/sessions/{id}/chat")
    suspend fun sendMessage(
        @Path("id") id: String,
        @Body body: ChatRequest,
    ): ChatResponse
}
