package bw.agriconnect.data.remote

import android.util.Log
import bw.agriconnect.data.session.TokenStore
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Attaches `Authorization: Bearer <accessToken>` to every authenticated request.
 *
 * Why an interceptor rather than annotating each call: the token changes out from
 * under us every 15 minutes (and on every silent refresh), so a per-call header
 * would inevitably go stale. Reading a volatile in-memory cache here also means no
 * disk I/O and no risk of blocking the request thread on the Keystore.
 *
 * Unauthenticated endpoints are skipped so that (a) a 401 from a bad login is not
 * mistaken for an expired session, and (b) we never send a stale token to the
 * public deletion page or the health probe.
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (isAuthExempt(request.url.encodedPath)) return chain.proceed(request)

        val token = tokenStore.cachedAccessToken ?: tokenStore.accessToken()
        if (token.isNullOrBlank()) return chain.proceed(request)

        val authenticated = request.newBuilder()
            .header(HEADER_AUTHORIZATION, "Bearer $token")
            .build()
        return chain.proceed(authenticated)
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"

        /**
         * True for the auth and public routes that must never carry (or be judged
         * by) a bearer token. Matches on path suffix/segment so it is independent
         * of the versioned prefix (`/api/v1`).
         */
        fun isAuthExempt(path: String): Boolean =
            path.endsWith("/auth/login") ||
                path.endsWith("/auth/register") ||
                path.endsWith("/auth/refresh") ||
                path.contains("/auth/password/") ||
                path.contains("/auth/verification/") ||
                path.contains("/public/") ||
                path == "/health"
    }
}

/**
 * An [HttpLoggingInterceptor] that scrubs credentials before they reach a sink.
 *
 * Why this exists at all: verbose HTTP logging is invaluable while debugging auth
 * and sync, but the contract and our own rules forbid ever emitting a token or a
 * password. Rather than rely on every developer remembering to disable the logger,
 * this wraps the logger and redacts the sensitive JSON keys and the Authorization
 * header unconditionally. It is safe to install at any log level and in any build.
 *
 * The sink is injectable so tests (and alternative crash reporters) can capture the
 * redacted output without touching `android.util.Log`.
 */
class RedactingLoggingInterceptor(
    level: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BASIC,
    sink: (String) -> Unit = { message -> Log.d(TAG, message) },
) : Interceptor {

    private val delegate = HttpLoggingInterceptor { message -> sink(redact(message)) }
        .apply { this.level = level }

    override fun intercept(chain: Interceptor.Chain): Response = delegate.intercept(chain)

    companion object {
        private const val TAG = "AgriConnectHttp"

        /** JSON keys whose values are credentials and must never be logged. */
        private val SENSITIVE_JSON = Regex(
            "(\"(?:password|currentPassword|newPassword|refreshToken|accessToken)\"\\s*:\\s*)\"[^\"]*\"",
            RegexOption.IGNORE_CASE,
        )

        private val AUTHORIZATION_HEADER = Regex(
            "((?:authorization)\\s*:\\s*)\\S+",
            RegexOption.IGNORE_CASE,
        )

        private val BEARER_TOKEN = Regex("(Bearer\\s+)[A-Za-z0-9._~+/-]+=*")

        /**
         * Replaces credential values with `<redacted>`. Visible for testing so the
         * redaction rules can be asserted without spinning up a server.
         */
        fun redact(input: String): String {
            var scrubbed = SENSITIVE_JSON.replace(input) { match ->
                match.groupValues[1] + "\"<redacted>\""
            }
            scrubbed = AUTHORIZATION_HEADER.replace(scrubbed) { match ->
                match.groupValues[1] + "<redacted>"
            }
            scrubbed = BEARER_TOKEN.replace(scrubbed) { match ->
                match.groupValues[1] + "<redacted>"
            }
            return scrubbed
        }
    }
}
