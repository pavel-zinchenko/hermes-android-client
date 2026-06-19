package com.hermes.android.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <key>` to every request. The key is read lazily on
 * each call via [keyProvider] so settings changes take effect without rebuilding
 * the OkHttp client. A blank key is omitted (e.g. for the unauthenticated /health
 * probe before the user has configured a key).
 */
class AuthInterceptor(
    private val keyProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = keyProvider().trim()
        val request = if (key.isEmpty()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $key")
                .build()
        }
        return chain.proceed(request)
    }
}
