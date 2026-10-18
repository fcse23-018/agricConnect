package bw.agriconnect.data.remote

import bw.agriconnect.data.remote.dto.RefreshRequest
import bw.agriconnect.data.remote.dto.TokenPairResponse
import bw.agriconnect.data.remote.dto.toAuthTokens
import bw.agriconnect.data.session.AuthTokens
import bw.agriconnect.data.session.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Performs the contract's single silent-refresh attempt on `401`.
 *
 * Why a dedicated `OkHttpClient`: the refresh endpoint is public, and if it were
 * called through the authenticated client a 401 on *it* would re-enter this
 * authenticator and loop. A small bare client (no auth interceptor, no
 * authenticator) makes recursion impossible by construction.
 *
 * The whole operation is guarded by a lock and by a token-comparison check, because
 * OkHttp runs concurrent requests on its dispatcher threads: if five requests 401
 * at once, only the first should spend a refresh token (they are single-use and
 * rotated); the rest see the access token has changed and simply retry.
 *
 * If the refresh token is rejected we clear the session and return `null`, so the
 * original 401 propagates and the UI can sign the user out. We never attempt more
 * than one retry.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val baseUrl: HttpUrl,
    /** A bare client used only for the refresh call. Never the authenticated one. */
    private val refreshClient: OkHttpClient,
    private val json: Json,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only meaningful for requests that actually carried a bearer token.
        val authorization = response.request.header(AuthInterceptor.HEADER_AUTHORIZATION)
            ?: return null

        // Exactly one retry: if the retried request also 401s, give up.
        if (responseCount(response) > 1) return null

        synchronized(lock) {
            val failedToken = authorization.removePrefix("Bearer ").trim()
            val current = tokenStore.accessToken()

            // Someone else refreshed while we waited for the lock — reuse their result.
            if (!current.isNullOrBlank() && current != failedToken) {
                return response.request.newBuilder()
                    .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer $current")
                    .build()
            }

            val refreshToken = tokenStore.refreshToken() ?: return null

            val refreshResult = refreshBlocking(refreshToken)
            when (refreshResult) {
                is RefreshResult.NetworkError -> {
                    // Cannot reach server while offline. Keep tokens/session intact;
                    // let the original request fail due to connectivity so UI shows offline state.
                    return null
                }
                is RefreshResult.Invalid -> {
                    // The refresh token is expired/revoked. Drop everything; the caller
                    // keeps its 401 and the UI signs the user out.
                    tokenStore.clear()
                    return null
                }
                is RefreshResult.Success -> {
                    // `save` is suspending for the encrypted store's I/O; authenticator
                    // callbacks are blocking by contract, so bridge with runBlocking here.
                    runBlocking { tokenStore.save(refreshResult.tokens) }
                    return response.request.newBuilder()
                        .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer ${refreshResult.tokens.accessToken}")
                        .build()
                }
            }
        }
    }

    private sealed class RefreshResult {
        data class Success(val tokens: AuthTokens) : RefreshResult()
        data object Invalid : RefreshResult()
        data object NetworkError : RefreshResult()
    }

    /** Blocking refresh; returns success only when refresh is valid. */
    private fun refreshBlocking(refreshToken: String): RefreshResult {
        val url = baseUrl.newBuilder().addPathSegments("auth/refresh").build()
        val payload = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Treat 401/403/others from refresh as invalid (token revoked/expired)
                    return RefreshResult.Invalid
                }
                val body = response.body?.string() ?: return RefreshResult.Invalid
                try {
                    RefreshResult.Success(json.decodeFromString(TokenPairResponse.serializer(), body).toAuthTokens())
                } catch (_: Exception) {
                    RefreshResult.Invalid
                }
            }
        } catch (_: IOException) {
            // Network connectivity issue - do not clear session
            RefreshResult.NetworkError
        } catch (_: IllegalArgumentException) {
            // Malformed base URL — treat as an unrecoverable refresh failure.
            RefreshResult.Invalid
        }
    }

    /** Number of responses in the retry chain, used to cap retries at one. */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
