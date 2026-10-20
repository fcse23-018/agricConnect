package bw.agriconnect.di

import javax.inject.Qualifier

/**
 * A bare `OkHttpClient` for downloading release APKs.
 *
 * The download URL can point at any host (GitHub Releases, a CDN, ...), so
 * this client must never carry credentials: the main client's
 * [bw.agriconnect.data.remote.AuthInterceptor] attaches the bearer token to
 * every request that is not auth-exempt, which would leak the access token to
 * a third-party host. Hence a separate, interceptor-free client — the same
 * reasoning that produced [RefreshClient].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
)
annotation class ApkDownloadClient
