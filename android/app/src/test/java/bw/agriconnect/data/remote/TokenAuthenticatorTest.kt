package bw.agriconnect.data.remote

import bw.agriconnect.data.session.AuthTokens
import bw.agriconnect.data.session.TokenStore
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET

/**
 * Behavioural tests for the silent-refresh flow. They drive a real OkHttp client
 * wired exactly as production (`AuthInterceptor` + `TokenAuthenticator`) against a
 * [MockWebServer] whose `401`/refresh responses are scripted.
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a 401 triggers one refresh, retries success and persists the rotated token`() = runTest {
        val store = FakeTokenStore(access = "old-access", refresh = "old-refresh")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(ROTATED_TOKENS)

                "/api/v1/protected" ->
                    if (request.getHeader("Authorization") == "Bearer new-access") {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"ok":true}""")
                    } else {
                        MockResponse()
                            .setResponseCode(401)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"error":{"code":"TOKEN_EXPIRED","message":"expired"}}""")
                    }

                else -> MockResponse().setResponseCode(404)
            }
        }

        val result = buildService(store).protected()

        assertThat(result.ok).isTrue()
        // Rotated pair was written back through the store.
        assertThat(store.savedTokens).hasSize(1)
        assertThat(store.accessToken()).isEqualTo("new-access")
        assertThat(store.refreshToken()).isEqualTo("new-refresh")
        // Initial (401) + refresh + retried (200).
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `when refresh fails the session is cleared and the 401 propagates`() = runTest {
        val store = FakeTokenStore(access = "old-access", refresh = "old-refresh")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh" -> MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"nope"}}""")

                "/api/v1/protected" -> MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"code":"TOKEN_EXPIRED","message":"expired"}}""")

                else -> MockResponse().setResponseCode(404)
            }
        }

        val service = buildService(store)
        val thrown = try {
            service.protected()
            null
        } catch (e: HttpException) {
            e
        }

        assertThat(thrown).isNotNull()
        assertThat(thrown!!.code()).isEqualTo(401)
        assertThat(store.cleared).isTrue()
        assertThat(store.accessToken()).isNull()
        assertThat(store.refreshToken()).isNull()
        // The token is never retried a second time.
        assertThat(server.requestCount).isEqualTo(2)
    }

    /** The production wiring, against the mock server. */
    private fun buildService(store: TokenStore): ProtectedApi {
        val refreshClient = OkHttpClient()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenAuthenticator(store, server.url("/api/v1/"), refreshClient, json))
            .build()

        return Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ProtectedApi::class.java)
    }

    /** In-memory stand-in for [bw.agriconnect.data.session.EncryptedTokenStore]. */
    private class FakeTokenStore(access: String?, refresh: String?) : TokenStore {

        @Volatile
        private var accessValue = access

        @Volatile
        private var refreshValue = refresh

        @Volatile
        var cleared = false
            private set

        val savedTokens = mutableListOf<AuthTokens>()

        override val cachedAccessToken: String? get() = accessValue

        override fun accessToken(): String? = accessValue

        override fun refreshToken(): String? = refreshValue

        override suspend fun save(tokens: AuthTokens) {
            savedTokens += tokens
            accessValue = tokens.accessToken
            refreshValue = tokens.refreshToken
        }

        override fun clear() {
            cleared = true
            accessValue = null
            refreshValue = null
        }
    }

    private companion object {
        val ROTATED_TOKENS = """
            {"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":900}
        """.trimIndent()
    }
}

/** Minimal authenticated endpoint used to exercise the authenticator. */
interface ProtectedApi {
    @GET("protected")
    suspend fun protected(): ProtectedDto
}

@Serializable
data class ProtectedDto(val ok: Boolean)
