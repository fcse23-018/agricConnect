package bw.agriconnect.core.config

import android.content.Context
import bw.agriconnect.BuildConfig
import bw.agriconnect.core.util.BaseUrl
import bw.agriconnect.core.util.ImageUrls
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which backend this install talks to.
 *
 * The compile-time URL baked into [BuildConfig.API_BASE_URL] is the default. A debug
 * build can additionally persist an override so a physical phone can be pointed at a
 * Codespaces forward, a LAN address, or a staging host **without rebuilding the APK**
 * (Settings → Server; see `ServerSettingsScreen`).
 *
 * The override is read once, when this singleton is constructed, and applied to the
 * whole graph: the Retrofit base URL (`NetworkModule.provideBaseUrl`) and relative
 * image paths (`ImageUrls.origin`, seeded by `AgriConnectApp`). Saving a new value
 * therefore restarts the process rather than trying to re-point live singletons.
 */
@Singleton
class ServerConfig @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The URL compiled into this build type; what "Reset to default" restores. */
    val defaultBaseUrl: String = BuildConfig.API_BASE_URL

    /** The URL the app is currently using: the saved override, or [defaultBaseUrl]. */
    val baseUrl: String = prefs.getString(KEY_BASE_URL, null)
        ?.let(BaseUrl::normalize)
        ?: defaultBaseUrl

    /** True when [baseUrl] came from the saved override rather than the build. */
    val isOverridden: Boolean = baseUrl != defaultBaseUrl

    /** `scheme://host[:port]` of [baseUrl] — the origin `/uploads/…` paths hang off. */
    val origin: String? = ImageUrls.originOf(baseUrl)

    /**
     * Persist [raw] as the override. Returns `false` (persisting nothing) when it is not
     * a usable absolute `http(s)` URL.
     */
    fun save(raw: String): Boolean {
        val normalized = BaseUrl.normalize(raw) ?: return false
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        return true
    }

    /** Forget the override, returning the next process start to [defaultBaseUrl]. */
    fun reset() {
        prefs.edit().remove(KEY_BASE_URL).apply()
    }

    companion object {
        private const val PREFS_NAME = "agriconnect_server"
        private const val KEY_BASE_URL = "base_url"
    }
}
