package bw.agriconnect.core.util

import bw.agriconnect.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves stored image references to absolute URLs an image loader can fetch.
 *
 * The backend persists image references as origin-relative paths
 * (`/uploads/<uuid>.jpg`) so the storage origin can move without a data migration (see
 * `backend/src/services/uploads.js`). `/uploads` is mounted on the host **origin**,
 * whereas the Retrofit base URL is the versioned API root (`…/api/v1/`); resolving a
 * relative path against the latter would 404. This helper therefore resolves against
 * the base URL's origin only.
 *
 * Absolute URLs (`http(s)://…`) and device-local references (`content://`, `file://`)
 * are returned untouched — the latter lets a just-picked image render as a preview
 * before it has been uploaded.
 */
object ImageUrls {

    /** The origin (`scheme://host[:port]`) of the configured API base URL. */
    val origin: String? = originOf(BuildConfig.API_BASE_URL)

    /**
     * Absolute URL for [raw], or `null` when it is blank/unusable.
     *
     * [baseOrigin] is injectable so the resolution rules can be unit-tested without the
     * Android build config.
     */
    fun resolve(raw: String?, baseOrigin: String? = origin): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        // Device-local content/file URIs are already loadable as-is.
        if (value.contains("://")) return value
        val base = baseOrigin ?: return value
        return if (value.startsWith("/")) "$base$value" else "$base/$value"
    }

    /** Pure origin extraction from an absolute base URL string; `null` when unparseable. */
    internal fun originOf(baseUrl: String?): String? {
        val url = baseUrl?.toHttpUrlOrNull() ?: return null
        val port = if (url.port == defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port"
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
}
