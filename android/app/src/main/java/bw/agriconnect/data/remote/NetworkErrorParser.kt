package bw.agriconnect.data.remote

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.defaultApiError
import bw.agriconnect.data.remote.dto.ErrorEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * Turns a thrown transport failure into a user-safe [AppError].
 *
 * It is the one place that decodes the backend's error envelope
 * (`{ "error": { code, message, details:[{field,message}] } }`). Two rules matter:
 *
 *  1. **Field errors survive.** A `400 VALIDATION_ERROR` carries per-field messages
 *     that the forms display inline, so we lift `details[]` into a map.
 *  2. **5xx never leaks server text.** For any 5xx we ignore the body entirely and
 *     return fixed generic copy — an internal error must not become an information
 *     disclosure channel.
 *
 * It is a class (not an object) because it needs the configured [Json]; that keeps
 * the DTO configuration in one place and makes the parser trivially testable.
 */
class NetworkErrorParser(private val json: Json) {

    /**
     * The single entry point for `runCatchingApi`. Cancellation is handled by the
     * caller (`runCatchingApi` rethrows it), so it never reaches here.
     */
    fun parse(throwable: Throwable): AppError = when (throwable) {
        is HttpException -> fromHttp(throwable)
        is IOException -> AppError(AppError.NETWORK, AppError.OFFLINE_MESSAGE)
        is SerializationException -> AppError(
            AppError.PARSE,
            "We couldn't read the server response. Please try again.",
        )
        else -> defaultApiError(throwable)
    }

    /** Parses an HTTP failure's body, falling back to status-based copy. */
    fun parseHttp(exception: HttpException): AppError = fromHttp(exception)

    private fun fromHttp(exception: HttpException): AppError {
        val status = exception.code()

        // Consume the body defensively: reading it can itself fail (stream closed)
        // and a 5xx body is deliberately ignored anyway.
        val body = runCatching { exception.response()?.errorBody()?.string() }.getOrNull()
        val envelope = body
            ?.takeIf { status < 500 }
            ?.let { runCatching { json.decodeFromString<ErrorEnvelope>(it) }.getOrNull() }

        // 5xx: generic, vetted copy only. Never echo the server message.
        if (status >= 500) {
            return defaultApiError(exception)
        }

        val error = envelope?.error ?: return defaultApiError(exception)

        val fieldErrors = error.details
            ?.associate { it.field to it.message }
            .orEmpty()

        return AppError(
            code = error.code.ifBlank { defaultApiError(exception).code },
            message = error.message.ifBlank { defaultApiError(exception).message },
            fieldErrors = fieldErrors,
            httpStatus = status,
        )
    }
}
