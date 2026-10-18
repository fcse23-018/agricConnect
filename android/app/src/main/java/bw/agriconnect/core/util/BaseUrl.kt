package bw.agriconnect.core.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Pure rules for the API base URL a build talks to.
 *
 * Kept apart from [bw.agriconnect.core.config.ServerConfig], which owns the
 * `SharedPreferences` write, so the validation can be unit-tested on the JVM.
 */
object BaseUrl {

    /**
     * Validate and canonicalise a user-entered base URL, or `null` when it cannot be one.
     *
     * Retrofit needs a trailing `/` for relative paths to resolve, so it is added here
     * rather than trusted from input.
     *
     * The `…/api/v1/` suffix is **not** appended automatically: a proxy or a staging host
     * may legitimately expose the API elsewhere, and silently rewriting the path would
     * make a wrong URL harder to diagnose. Only the scheme is constrained, because a
     * `javascript:`-style value would otherwise be persisted and handed to OkHttp.
     */
    fun normalize(raw: String?): String? {
        val url = raw?.trim()?.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host.isBlank()) return null
        val value = url.toString()
        return if (value.endsWith("/")) value else "$value/"
    }
}
