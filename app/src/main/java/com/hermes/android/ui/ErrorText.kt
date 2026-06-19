package com.hermes.android.ui

import retrofit2.HttpException
import java.io.IOException

/** Maps a Throwable from the repository to a short, user-facing message. */
fun Throwable.toUserMessage(): String = when (this) {
    is HttpException -> when (code()) {
        401, 403 -> "Unauthorized — check the API key in Settings."
        404 -> "Not found on the server."
        503 -> "Hermes session store unavailable."
        else -> "Server error (${code()})."
    }
    is IOException -> "Can't reach Hermes. Is the gateway running on this device?"
    else -> message ?: "Something went wrong."
}
