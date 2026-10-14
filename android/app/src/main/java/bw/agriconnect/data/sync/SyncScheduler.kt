package bw.agriconnect.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import bw.agriconnect.core.util.ConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WorkManager schedule for [SyncWorker] and reacts to connectivity changes.
 *
 * Two work items, both unique, both network-constrained:
 *  - a periodic catch-up every [PERIODIC_INTERVAL_MINUTES] minutes, and
 *  - a one-shot "sync now" used at sign-in, after a local write, and when the device
 *    comes back online.
 *
 * **Backoff.** Both requests use `EXPONENTIAL` with an initial delay of
 * [BACKOFF_INITIAL_SECONDS]s (10s → 20s → 40s → 80s …), matching the documented
 * schedule (docs/00 §4 and §2.10). WorkManager's own exponential cap is 5 hours, but
 * the design target of ≤1 hour is enforced far below that by [SyncWorker]'s attempt
 * limit: with [SyncWorker.MAX_ATTEMPTS] attempts the outermost backoff is 160s, so the
 * 1-hour ceiling is never approached in practice.
 *
 * **Connectivity.** WorkManager's `NetworkType.CONNECTED` constraint already prevents
 * work from starting while offline, so the observer is an *optimisation*, not a
 * correctness requirement: the instant a validated connection returns we enqueue the
 * one-shot so a user who was offline for hours does not wait for the next 15-minute
 * tick. We only react to the false→true *transition* — `isOnline` replays the current
 * state to a new collector, so [drop]ping the first emission avoids firing a redundant
 * sync on every process start.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectivity: ConnectivityObserver,
) {

    /**
     * A process-lifetime scope for the connectivity collector. [SyncScheduler] is a
     * singleton, so this lives exactly as long as the app process — the correct
     * lifetime for a `ConnectivityManager` callback registration.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        observeConnectivity()
    }

    /**
     * Enqueues the 15-minute periodic worker with `KEEP`. `KEEP` (not `UPDATE`) means a
     * freshly installed app does not reset an already-scheduled period, so the cadence
     * the OS has learned is preserved across repeated `schedulePeriodic()` calls.
     */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueues a one-shot sync, replacing any pending one. `REPLACE` (not `KEEP`) is
     * correct here: the newest request reflects the most recent local edit, and
     * collapsing bursts of taps into a single run avoids a queue of redundant workers.
     */
    fun requestImmediate() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Cancels both work items (sign-out / account purge). */
    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    private fun observeConnectivity() {
        scope.launch {
            connectivity.isOnline
                .drop(1) // ignore the replayed current state; react only to transitions
                .filter { online -> online }
                .collect { requestImmediate() }
        }
    }

    private fun networkConstraint(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    companion object {
        /** Unique work names — stable so `cancelAll`/`KEEP` address the same items. */
        const val PERIODIC_WORK_NAME = "agriconnect-sync-periodic"
        const val IMMEDIATE_WORK_NAME = "agriconnect-sync-now"

        /** Documented cadence (docs/00 §4). */
        const val PERIODIC_INTERVAL_MINUTES = 15L

        /** First backoff step; doubles each retry. See the class docs. */
        const val BACKOFF_INITIAL_SECONDS = 10L
    }
}
