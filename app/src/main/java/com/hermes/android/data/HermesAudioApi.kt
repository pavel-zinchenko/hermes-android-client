package com.hermes.android.data

import com.hermes.android.data.dto.SpeakRequest
import com.hermes.android.data.dto.SpeakResponse
import com.hermes.android.data.dto.TranscribeRequest
import com.hermes.android.data.dto.TranscribeResponse
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for Hermes's audio (STT/TTS) endpoints. These live on the
 * Hermes dashboard web server (127.0.0.1:9119) today, and — once the upstream PR
 * in hermes-pr.md lands — on the gateway api_server too. The shapes are identical
 * on both, so [HermesRepository] only swaps the base URL + Bearer key. The key is
 * injected by an [AuthInterceptor], so it does not appear in these signatures.
 */
interface HermesAudioApi {

    @POST("api/audio/transcribe")
    suspend fun transcribe(@Body body: TranscribeRequest): TranscribeResponse

    @POST("api/audio/speak")
    suspend fun speak(@Body body: SpeakRequest): SpeakResponse
}
