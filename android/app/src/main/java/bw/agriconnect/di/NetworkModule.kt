package bw.agriconnect.di

import bw.agriconnect.BuildConfig
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

    @Provides
    @Singleton
    fun provideBaseUrl(): HttpUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    /**
     * A bare client used only by [TokenAuthenticator] to exchange a refresh token.
     * No auth interceptor and no authenticator, so a 401 on the refresh call cannot
     * recurse into another refresh.
     */
    @Provides
    @Singleton
    fun provideRefreshClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenStore: TokenStore,
        baseUrl: HttpUrl,
        json: Json,
        refreshClient: OkHttpClient,
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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
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
}
