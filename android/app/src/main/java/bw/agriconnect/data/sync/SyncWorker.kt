package bw.agriconnect.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Runs one [SyncEngine.syncNow] pass on WorkManager's schedule or on demand.
 *
 * Retry policy: `Result.retry()` **only** when every failure in the pass was
 * transient (offline/5xx/rate-limit) *and* there is still queued work *and* we have
 * not exhausted [MAX_ATTEMPTS]. Anything else — a clean pass, a permanent rejection,
 * or a conflict that the user must resolve — returns `Result.success()`: retrying
 * cannot change those outcomes and would only burn battery. The attempt cap bounds
 * the exponential backoff (10→20→40→80→160s) so a persistently flaky link degrades to
 * the periodic cadence instead of retrying forever.
 *
 * `HiltWorker` + `@AssistedInject` let WorkManager (which instantiates the worker
 * reflectively) receive the Hilt-provided engine/applier while still passing the
 * framework-supplied [Context]/[WorkerParameters] through `@Assisted`. This requires
 * `androidx.hilt:hilt-work` and an Application implementing `Configuration.Provider`.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val engine: SyncEngine,
    private val queue: SyncQueue,
    private val applier: SyncApplier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome = engine.syncNow(applier::apply)

        // If the queue drained despite a reported transient failure, there is nothing
        // left to retry.
        val stillPending = queue.pendingCount().first() > 0

        return when {
            outcome.hasOnlyTransientFailures && stillPending && runAttemptCount < MAX_ATTEMPTS ->
                Result.retry()

            else -> Result.success()
        }
    }

    companion object {
        /**
         * Total run attempts before giving up and falling back to the periodic worker.
         * Keeps the worst-case backoff at 10·2^(MAX_ATTEMPTS-1) = 160s, comfortably
         * under the documented 1-hour ceiling.
         */
        const val MAX_ATTEMPTS = 5
    }
}
