package bw.agriconnect.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import bw.agriconnect.domain.model.Session
import bw.agriconnect.domain.model.User
import bw.agriconnect.domain.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Owns the observable, in-memory session and the *minimal* identity needed to
 * restore it after the process is killed.
 *
 * **Why split secrets from identity.** Only the tokens live in the encrypted
 * [TokenStore]. This manager persists just `userId`, `role` and `fullName` in
 * DataStore. That is deliberate: identity is needed synchronously at cold start to
 * route the UI (farmer vs buyer) without touching the Keystore, while the secret
 * material stays isolated and is only consulted when a request actually needs a
 * token. Nothing here is secret, so nothing here is encrypted.
 *
 * Restoring is deliberately conservative: we only treat the user as signed in if a
 * refresh token still exists *and* the identity was persisted. A half-written
 * logout (tokens cleared, identity left behind) must not resurrect a session.
 */
class SessionManager(
    private val dataStore: DataStore<Preferences>,
    private val tokenStore: TokenStore,
) {

    private val _session = MutableStateFlow<Session?>(null)

    /** The current session, or null when signed out. Single source of truth. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Convenience flag for navigation guards. */
    val isLoggedIn: Boolean get() = _session.value != null

    /**
     * True once the user has signed out but local data may not have finished
     * syncing. Kept so the sync layer can block a re-login from resurrecting
     * another user's queued work.
     */
    val logoutPending: Flow<Boolean> = dataStore.data.map { it[KEY_LOGOUT_PENDING] ?: false }

    /**
     * Persists the tokens and the non-secret identity, then publishes the session.
     * Called after a successful register/login.
     */
    suspend fun signIn(session: Session, tokens: AuthTokens) {
        tokenStore.save(tokens)
        dataStore.edit { prefs ->
            prefs[KEY_USER_ID] = session.userId
            prefs[KEY_ROLE] = session.role.name
            prefs[KEY_FULL_NAME] = session.fullName
            prefs[KEY_LOGOUT_PENDING] = false
        }
        _session.value = session
    }

    /**
     * Rebuilds the session from persisted, non-secret identity.
     *
     * @return the restored [Session], or null when there is no refresh token or no
     *   persisted identity (i.e. the user is genuinely signed out).
     */
    suspend fun restore(): Session? {
        val prefs = dataStore.data.first()

        val userId = prefs[KEY_USER_ID] ?: return null
        val roleName = prefs[KEY_ROLE] ?: return null
        val fullName = prefs[KEY_FULL_NAME] ?: return null

        // No refresh token means no way to obtain an access token: signed out.
        if (tokenStore.refreshToken().isNullOrBlank()) return null

        val session = Session(
            userId = userId,
            role = runCatching { UserRole.valueOf(roleName) }.getOrElse { UserRole.BUYER },
            fullName = fullName,
        )
        _session.value = session
        return session
    }

    /**
     * Updates the cached identity after a profile edit (e.g. a name change) so the
     * header and persisted session stay consistent.
     */
    suspend fun updateUser(user: User) {
        dataStore.edit { prefs ->
            prefs[KEY_USER_ID] = user.userId
            prefs[KEY_ROLE] = user.role.name
            prefs[KEY_FULL_NAME] = user.fullName
        }
        _session.update { current ->
            (current ?: Session(user.userId, user.role, user.fullName)).copy(
                fullName = user.fullName,
                role = user.role,
            )
        }
    }

    /**
     * Clears the tokens and the persisted identity, raises the [logoutPending] flag
     * and publishes a null session. The flag survives the wipe so a subsequent cold
     * start knows a sign-out is in progress.
     */
    suspend fun signOut() {
        tokenStore.clear()
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[KEY_LOGOUT_PENDING] = true
        }
        _session.value = null
    }

    /** Clears the [logoutPending] flag once post-logout work has completed. */
    suspend fun clearLogoutPending() {
        dataStore.edit { prefs -> prefs[KEY_LOGOUT_PENDING] = false }
    }

    /**
     * Signals that the set of observers of [session] is being re-established.
     *
     * [StateFlow] already replays the current value to new collectors, so this does
     * not have to emit anything; it exists as an explicit, documented hook for the
     * DI/observing layer so that intent is visible at call sites rather than
     * implied. It intentionally performs no I/O.
     */
    @Suppress("unused")
    fun observersChanged() {
        // No-op: StateFlow is already the notification channel.
    }

    private companion object {
        val KEY_USER_ID = stringPreferencesKey("session_user_id")
        val KEY_ROLE = stringPreferencesKey("session_role")
        val KEY_FULL_NAME = stringPreferencesKey("session_full_name")
        val KEY_LOGOUT_PENDING = booleanPreferencesKey("logout_pending")
    }
}

/**
 * The app-wide DataStore for non-secret session identity. Declared as a property
 * delegate so the instance is a process-wide singleton keyed by name, which is the
 * documented way to avoid "multiple DataStores active for the same file".
 */
private val Context.agriSessionDataStore by preferencesDataStore(name = "agri_session")

/**
 * Convenience factory for callers that only have a [Context] (e.g. an application
 * initialiser) and do not wire DI yet.
 */
fun createSessionManager(context: Context, tokenStore: TokenStore): SessionManager =
    SessionManager(context.agriSessionDataStore, tokenStore)
