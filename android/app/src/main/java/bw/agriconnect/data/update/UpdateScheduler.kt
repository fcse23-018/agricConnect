package bw.agriconnect.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WorkManager schedule for [UpdateCheckWorker], mirroring
 * `bw.agriconnect.data.sync.SyncScheduler`: one unique, network-constrained
 * periodic job enqueued at process start with `KEEP`, so repeated starts do
 * not reset the cadence the OS has learned.
 *
 * The period matches [UpdateChecker.CHECK_INTERVAL_MILLIS]: the worker's tick
 * and the start-time throttle cooperate so the endpoint is hit at most once
 * per interval (a tick that lands just inside the throttle window is skipped
 * and the following tick runs).
 */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        /** Unique work name — stable so KEEP addresses the same job. */
        const val WORK_NAME = "agriconnect-update-check"

        /** Same cadence as `UpdateChecker.CHECK_INTERVAL_MILLIS`. */
        const val CHECK_INTERVAL_HOURS = 6L
    }
}
