package bw.agriconnect.core.result

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * A transport-agnostic result envelope for anything that can fail for a reason a
 * human needs to see.
 *
 * Why a sealed type instead of Kotlin's `Result`: [AppError] carries a stable
 * machine [AppError.code], per-field validation messages and the HTTP status, all
 * of which the UI needs. `Result` only carries an opaque `Throwable`, which would
 * force every call site to re-inspect the exception. Modelling success/failure as
 * data also makes it impossible to accidentally swallow an error in an `else`.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/**
 * Transforms the success payload while leaving a failure untouched. Declared as an
 * extension (not an interface member) so it can be `inline` without fighting the
 * JVM's virtual dispatch — an inline member of an interface must be non-virtual
 * and would complicate the sealed hierarchy.
 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

/**
 * A safe, presentation-ready error. Everything here is intentionally something we
 * are allowed to show the user: the *why* is never a raw server string for 5xx
 * (see [bw.agriconnect.data.remote.NetworkErrorParser]); it is a stable code plus
 * a message that has been vetted server-side or synthesised locally.
 */
data class AppError(
    val code: String,
    val message: String,
    /** `fieldName -> human message`, populated from the server's error `details`. */
    val fieldErrors: Map<String, String> = emptyMap(),
    /** The HTTP status, when the failure came from a network call. */
    val httpStatus: Int? = null,
) {
    /** True when the failure is a connectivity problem rather than a rejection. */
    val isNetwork: Boolean get() = code == NETWORK

    companion object {
        /** Could not reach the server / lost connectivity. */
        const val NETWORK = "NETWORK"
        /** The server responded with something we could not decode. */
        const val PARSE = "PARSE"
        /** Anything we could not classify. */
        const val UNKNOWN = "UNKNOWN"
        /** Server-side failure (5xx); message is always generic. */
        const val INTERNAL = "INTERNAL"

        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val FORBIDDEN = "FORBIDDEN"
        const val NOT_FOUND = "NOT_FOUND"
        const val CONFLICT = "CONFLICT"
        const val RATE_LIMITED = "RATE_LIMITED"

        /**
         * Shown when a request could not reach the server at all.
         *
         * Deliberately neutral and *not* the old "your changes are saved" copy: this
         * fires for every transport failure (sign-in, token refresh, a remote read),
         * most of which persist nothing. Offline *writes* are queued by the
         * repositories and surface their own "saved / waiting to sync" copy through
         * the sync UI ([bw.agriconnect.ui.components.OfflineBanner] /
         * `PendingSyncBanner`) — never through this error.
         */
        const val NETWORK_MESSAGE =
            "Can't reach AgriConnect. Check your connection and try again."
    }
}

/** Runs [action] only on success; returns the receiver so it can be chained. */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

/** Runs [action] only on failure; returns the receiver so it can be chained. */
inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

/**
 * Wraps a suspending API call, converting transport failures into an [AppResult].
 *
 * This overload exists so the common call site (`runCatchingApi { api.me() }`)
 * stays terse. The two-argument overload below accepts a richer [mapError] — the
 * data layer passes `NetworkErrorParser::parse` so validation errors survive.
 *
 * Cancellation is deliberately rethrown: a coroutine that is cancelled because of
 * a screen going away must not be reported to the user as a failure.
 */
suspend fun <T> runCatchingApi(block: suspend () -> T): AppResult<T> =
    runCatchingApi(::defaultApiError, block)

/**
 * Wraps a suspending API call using [mapError] to translate any non-cancellation
 * exception into an [AppError].
 */
suspend fun <T> runCatchingApi(
    mapError: (Throwable) -> AppError,
    block: suspend () -> T,
): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(mapError(e))
}

/**
 * The default, parser-free mapping. It never inspects the response body, so it is
 * safe to use from code that has no `Json` instance, and it is guaranteed to never
 * echo raw server text (critical for 5xx).
 */
fun defaultApiError(throwable: Throwable): AppError = when (throwable) {
    is HttpException -> {
        val status = throwable.code()
        AppError(
            code = httpFallbackCode(status),
            message = httpFallbackMessage(status),
            httpStatus = status,
        )
    }
    is IOException -> AppError(AppError.NETWORK, AppError.NETWORK_MESSAGE)
    is SerializationException -> AppError(
        AppError.PARSE,
        "We couldn't read the server response. Please try again.",
    )
    else -> AppError(AppError.UNKNOWN, "Something went wrong. Please try again.")
}

/** Maps an HTTP status to the documented error code family. */
fun httpFallbackCode(status: Int): String = when (status) {
    400 -> AppError.VALIDATION_ERROR
    401 -> AppError.UNAUTHENTICATED
    403 -> AppError.FORBIDDEN
    404 -> AppError.NOT_FOUND
    409 -> AppError.CONFLICT
    429 -> AppError.RATE_LIMITED
    in 500..599 -> AppError.INTERNAL
    else -> AppError.UNKNOWN
}

/** Safe, generic copy for each status; never contains server internals. */
fun httpFallbackMessage(status: Int): String = when (status) {
    400 -> "Please check the highlighted fields."
    401 -> "Your session has expired. Please sign in again."
    403 -> "You do not have permission to do that."
    404 -> "We couldn't find what you were looking for."
    409 -> "That conflicts with a change made elsewhere."
    429 -> "Too many attempts. Please wait a moment and try again."
    in 500..599 -> "Something went wrong on our side. Please try again."
    else -> "Something went wrong. Please try again."
}
