package bw.agriconnect.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the updater remembers between checks:
 *
 *  - when the last *successful* check completed (throttling), and
 *  - which `latestVersionCode` the user chose "Later" for (deduplication).
 *
 * An interface so the throttle/deduplication behaviour is unit-testable on the
 * JVM with an in-memory fake; the production binding is
 * [SharedPreferencesUpdateStateStore] (same preferences pattern as
 * `bw.agriconnect.core.config.ServerConfig`).
 */
interface UpdateStateStore {
    /** Epoch millis of the last successful check; 0 before the first one. */
    fun lastCheckAtMillis(): Long

    /** Records that a check completed successfully at [millis]. */
    fun markCheckedAt(millis: Long)

    /** `latestVersionCode` of the deferred update, or null when none was. */
    fun dismissedVersionCode(): Int?

    /** Remembers that the user deferred the update for [versionCode]. */
    fun dismiss(versionCode: Int)
}

/**
 * SharedPreferences-backed [UpdateStateStore]. One dedicated file so update
 * bookkeeping never mixes with the server override or session data.
 */
@Singleton
class SharedPreferencesUpdateStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : UpdateStateStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastCheckAtMillis(): Long = prefs.getLong(KEY_LAST_CHECK_AT, 0L)

    override fun markCheckedAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_AT, millis).apply()
    }

    override fun dismissedVersionCode(): Int? =
        prefs.getInt(KEY_DISMISSED_VERSION, NO_DISMISSED).takeIf { it != NO_DISMISSED }

    override fun dismiss(versionCode: Int) {
        prefs.edit().putInt(KEY_DISMISSED_VERSION, versionCode).apply()
    }

    companion object {
        private const val PREFS_NAME = "agriconnect_updates"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_DISMISSED_VERSION = "dismissed_version_code"

        /** Sentinel for "nothing dismissed"; version codes start at 1. */
        private const val NO_DISMISSED = -1
    }
}

/**
 * True when a throttled check may run: never checked (`lastCheckAtMillis == 0`)
 * always counts as due, and a check exactly [intervalMillis] old is due.
 * A failed or offline attempt records no timestamp, so it does not consume the
 * throttle window (see `UpdateChecker.runCheck`).
 */
fun isCheckDue(lastCheckAtMillis: Long, nowMillis: Long, intervalMillis: Long): Boolean =
    nowMillis - lastCheckAtMillis >= intervalMillis

/**
 * True when an OPTIONAL update for [latestVersionCode] may be offered: nothing
 * was dismissed yet, or the release is *newer* than the one the user deferred.
 * Re-prompting for the same version code after "Update later" would nag; a
 * newer release legitimately prompts again. REQUIRED updates ignore this —
 * they are never dismissed by definition.
 */
fun shouldPromptFor(dismissedVersionCode: Int?, latestVersionCode: Int): Boolean =
    dismissedVersionCode == null || latestVersionCode > dismissedVersionCode
