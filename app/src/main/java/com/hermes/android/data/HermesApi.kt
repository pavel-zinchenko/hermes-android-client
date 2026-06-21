package com.hermes.android.data

import com.hermes.android.data.dto.CronJobDto
import com.hermes.android.data.dto.HealthDto
import com.hermes.android.data.dto.ModelSetRequest
import com.hermes.android.data.dto.ModelSetResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface to the Hermes dashboard server (9119). Chat and sessions run
 * over the JSON-RPC gateway WebSocket; the REST calls that remain are the
 * lightweight health probe used by the connection gate, and the cron-job
 * endpoints the app mirrors into local Android reminders. The Bearer key is
 * injected by [AuthInterceptor].
 */
interface HermesApi {

    @GET("health")
    suspend fun health(): HealthDto

    /** Lists scheduled cron jobs (GET /api/cron/jobs). */
    @GET("api/cron/jobs")
    suspend fun listCronJobs(@Query("profile") profile: String = "default"): List<CronJobDto>

    /** Deletes a cron job (DELETE /api/cron/jobs/{id}). */
    @DELETE("api/cron/jobs/{id}")
    suspend fun deleteCronJob(@Path("id") id: String)

    /** Selects the active main model (POST /api/model/set). */
    @POST("api/model/set")
    suspend fun setModel(@Body body: ModelSetRequest): ModelSetResponse
}
