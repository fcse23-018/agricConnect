package bw.agriconnect.di

import bw.agriconnect.BuildConfig
import bw.agriconnect.core.config.ServerConfig
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.AuthInterceptor
import bw.agriconnect.data.remote.RedactingLoggingInterceptor
import bw.agriconnect.data.remote.TokenAuthenticator
import bw.agriconnect.data.session.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Networking graph: `Json`, the authenticated `OkHttpClient`, the bare refresh
 * client, `Retrofit` and the single [ApiService].
 *
 * The `Json` configuration mirrors the one pinned by the wire-contract tests
 * (`AuthApiTest`): unknown keys are ignored so a newer server cannot break an older
 * client, and explicit nulls are not emitted. `coerceInputValues` defends against a
 * server sending `null` for a non-nullable field with a default.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    /**
     * The versioned API root. [ServerConfig] yields the compile-time URL unless a debug
     * build has saved an override (Settings → Server).
     */
    @Provides
    @Singleton
    fun provideBaseUrl(serverConfig: ServerConfig): HttpUrl = serverConfig.baseUrl.toHttpUrl()

    /**
     * A bare client used only by [TokenAuthenticator] to exchange a refresh token.
     * No auth interceptor and no authenticator, so a 401 on the refresh call cannot
     * recurse into another refresh.
     */
    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Bare client for APK downloads (Phase C update flow). No auth interceptor
     * and no authenticator — the download URL can point at any host and the
     * bearer token must never leave our own API host (see [ApkDownloadClient]).
     * Verbose body logging is not installed either, so a partially redacted
     * download URL is never written to logcat. The call timeout bounds a whole
     * multi-megabyte download on a slow link.
     */
    @Provides
    @Singleton
    @ApkDownloadClient
    fun provideApkDownloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(APK_DOWNLOAD_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build()


    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenStore: TokenStore,
        baseUrl: HttpUrl,
        json: Json,
        @RefreshClient refreshClient: OkHttpClient,
    ): OkHttpClient {
        // Verbose body logging is a debug-only affordance; the interceptor redacts
        // credentials at every level, so it is safe to install in any build.
        val logging = RedactingLoggingInterceptor(
            level = if (BuildConfig.VERBOSE_LOGGING) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            },
        )
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(logging)
            .authenticator(TokenAuthenticator(tokenStore, baseUrl, refreshClient, json))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, baseUrl: HttpUrl, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    /**
     * Timeouts are deliberately generous rather than OkHttp's 10s defaults.
     *
     * Two real-world conditions drive them. A free-tier backend (Render) spins down
     * after ~15 minutes without traffic, and the first request of a session spends
     * up to about a minute just waking the instance — a 30s read timeout would
     * report "can't reach AgriConnect" for a server that was about to answer.
     * Separately, the sync worker batches a whole outbox in one push, so a cold
     * server plus a large payload can stack two slow phases in a single call.
     *
     * [CALL_TIMEOUT_SECONDS] is the backstop covering the entire attempt including
     * DNS, TLS, the authenticator's single refresh round-trip and the body read, so
     * no request can hang a coroutine indefinitely.
     */
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val WRITE_TIMEOUT_SECONDS = 60L
    private const val CALL_TIMEOUT_SECONDS = 120L

    /** Whole-attempt cap for one APK download (large file, slow links). */
    private const val APK_DOWNLOAD_CALL_TIMEOUT_MINUTES = 10L
}
