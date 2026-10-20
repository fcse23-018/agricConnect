package bw.agriconnect.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The periodic half of the update check (the other half is
 * [UpdateChecker.checkOnStartup] at process start).
 *
 * Runs on WorkManager's schedule with a `CONNECTED` network constraint, so it
 * never fires offline; [UpdateChecker.runCheck] additionally throttles to one
 * successful check per interval and swallows every failure. The worker always
 * returns success: retrying an update *check* would burn battery without
 * changing anything (the next scheduled tick retries anyway).
 *
 * Uses the same `HiltWorker` + `@AssistedInject` pattern as
 * `bw.agriconnect.data.sync.SyncWorker`.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val params: WorkerParameters,
    private val checker: UpdateChecker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        checker.runCheck()
        return Result.success()
    }
}
