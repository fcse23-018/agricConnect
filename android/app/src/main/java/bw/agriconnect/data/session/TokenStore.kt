package bw.agriconnect.data.session

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pair of tokens returned by login/register/refresh. Bundled so the token
 * store, the authenticator and the session manager all pass one value around.
 * `expiresIn` is in seconds (contract §0); it is advisory and never persisted.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long? = null,
)

/**
 * Where the bearer credentials live.
 *
 * Why an interface: [AuthInterceptor] and [TokenAuthenticator] are pure OkHttp
 * components and must be unit-testable without Android. Depending on this
 * interface lets tests substitute an in-memory fake while production uses
 * [EncryptedTokenStore]. It also means nothing outside this file can reach into
 * the encrypted container.
 */
interface TokenStore {

    /**
     * The last access token seen, safe to read from any thread without touching
     * disk. The interceptor reads this on every request.
     */
    val cachedAccessToken: String?

    /** The current access token, or null when signed out. */
    fun accessToken(): String?

    /** The current refresh token, or null when signed out. */
    fun refreshToken(): String?

    /** Persists a freshly issued token pair, updating [cachedAccessToken]. */
    suspend fun save(tokens: AuthTokens)

    /** Wipes both tokens. Called on sign-out and failed refresh. */
    fun clear()
}

/**
 * [TokenStore] backed by `EncryptedSharedPreferences`, whose master key is held in
 * the Android Keystore.
 *
 * Why encrypted storage: a refresh token is a 30-day bearer credential. Storing it
 * in plain `SharedPreferences` would expose it to any app with backup access or
 * root. The token values are never written to logs, crash reports or plain files —
 * only the encrypted container.
 */
class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Cached in memory so the interceptor never does disk I/O (and never blocks)
     * on the network thread. Volatile because reads happen on OkHttp threads while
     * writes happen on coroutine dispatchers.
     */
    @Volatile
    private var cachedAccess: String? = prefs.getString(KEY_ACCESS, null)

    override val cachedAccessToken: String? get() = cachedAccess

    override fun accessToken(): String? = cachedAccess

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override suspend fun save(tokens: AuthTokens) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .commit()
        cachedAccess = tokens.accessToken
    }

    override fun clear() {
        prefs.edit().clear().commit()
        cachedAccess = null
    }

    private companion object {
        const val FILE_NAME = "agri_secure_tokens"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
    }
}
