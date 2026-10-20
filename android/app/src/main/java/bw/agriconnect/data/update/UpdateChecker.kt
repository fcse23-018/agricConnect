package bw.agriconnect.data.update

import android.util.Log
import bw.agriconnect.BuildConfig
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.util.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the automatic update check: when to check, whether the result
 * may be shown, and the prompt the UI observes.
 *
 * Check timing:
 *  - once shortly after process start ([checkOnStartup], non-blocking), and
 *  - periodically while online via [UpdateCheckWorker] (WorkManager, 6-hour
 *    period, network-constrained — same architecture as `SyncScheduler`).
 *
 * Rules:
 *  - **Throttle**: at most one check per [CHECK_INTERVAL_MILLIS]; an explicit
 *    user action may pass `force = true`.
 *  - **Offline**: skipped entirely, and no timestamp is recorded, so the next
 *    opportunity (start or worker tick) retries.
 *  - **Failures are silent**: logged, mapped to [UpdateDecision.None], and the
 *    previous prompt is left untouched. A down update server must never show
 *    an error or lock the user out.
 *  - **Dismissal**: an OPTIONAL update the user deferred for version X is not
 *    shown again until a newer version code appears; REQUIRED prompts are
 *    never dismissed.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val repository: UpdateRepository,
    private val connectivity: ConnectivityObserver,
    private val stateStore: UpdateStateStore,
) {

    /**
     * Process-lifetime scope: [UpdateChecker] is a singleton, so this lives
     * exactly as long as the app process (same lifetime as `SyncScheduler`).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serialises checks so a start-time check and a worker tick cannot race. */
    private val mutex = Mutex()

    private val _prompt = MutableStateFlow<UpdateDecision?>(null)

    /** The Optional/Required decision the UI must surface, or null for none. */
    val prompt: StateFlow<UpdateDecision?> = _prompt.asStateFlow()

    /**
     * Fires the throttled check on the application scope, shortly after start.
     * Returns immediately — launch must never block on the network.
     */
    fun checkOnStartup() {
        scope.launch { runCheck() }
    }

    /**
     * Runs one throttled check and returns the decision it reached. Never
     * throws for network/parse reasons (only coroutine cancellation does).
     */
    suspend fun runCheck(force: Boolean = false): UpdateDecision = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && !isCheckDue(stateStore.lastCheckAtMillis(), now, CHECK_INTERVAL_MILLIS)) {
            return@withLock UpdateDecision.None(THROTTLED_REASON)
        }
        if (!connectivity.isCurrentlyOnline()) {
            // Offline: consume no throttle window, so we retry as soon as the
            // device is back (start or the next network-constrained tick).
            return@withLock UpdateDecision.None(OFFLINE_REASON)
        }

        when (val result = repository.check(BuildConfig.VERSION_CODE)) {
            is AppResult.Failure -> {
                // Deliberately swallowed: no prompt, no user-facing error, and
                // no timestamp — a failed check is not a completed check.
                Log.w(TAG, "update check failed (${result.error.code})")
                UpdateDecision.None("Check failed: ${result.error.code}")
            }

            is AppResult.Success -> {
                // Only a completed exchange counts toward the throttle, so a
                // server that was down at start is retried on the next tick.
                stateStore.markCheckedAt(now)
                applyDecision(result.data)
            }
        }
    }

    /** Updates [stateStore]/[_prompt] from a successfully fetched decision. */
    private fun applyDecision(decision: UpdateDecision): UpdateDecision = when {
        decision is UpdateDecision.Optional &&
            !shouldPromptFor(
                stateStore.dismissedVersionCode(),
                decision.manifest.latestVersionCode,
            ) -> {
                _prompt.value = null
                UpdateDecision.None(
                    "Skipped: version ${decision.manifest.latestVersionCode} was already dismissed.",
                )
            }

        decision is UpdateDecision.Optional || decision is UpdateDecision.Required -> {
            _prompt.value = decision
            decision
        }

        else -> {
            _prompt.value = null
            decision
        }
    }

    /**
     * "Update later": remember the deferred version code so the same release
     * is not offered twice, then hide the dialog.
     */
    fun dismissPrompt() {
        (_prompt.value as? UpdateDecision.Optional)
            ?.let { stateStore.dismiss(it.manifest.latestVersionCode) }
        _prompt.value = null
    }

    /** Hides the prompt without recording a dismissal (post-install cleanup). */
    fun clearPrompt() {
        _prompt.value = null
    }

    companion object {
        private const val TAG = "AgriConnectUpdate"

        /** Network budget: at most one successful check per 6 hours. */
        const val CHECK_INTERVAL_MILLIS: Long = 6L * 60L * 60L * 1000L

        const val THROTTLED_REASON = "Skipped: checked within the last 6 hours."
        const val OFFLINE_REASON = "Skipped: the device is offline."
    }
}
