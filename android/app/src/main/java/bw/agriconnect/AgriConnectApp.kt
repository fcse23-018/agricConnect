package bw.agriconnect

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import bw.agriconnect.core.config.ServerConfig
import bw.agriconnect.core.util.ImageUrls
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.data.update.UpdateChecker
import bw.agriconnect.data.update.UpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and the Hilt root.
 *
 * **WorkManager.** `@HiltWorker` (see [bw.agriconnect.data.sync.SyncWorker]) lets
 * WorkManager construct workers through Hilt, which requires this class to supply a
 * [HiltWorkerFactory] through [Configuration.Provider]. The manifest removes the
 * default `androidx.work.WorkManagerInitializer`, so WorkManager is initialised
 * on-demand the first time `WorkManager.getInstance` is called — at which point it
 * reads this configuration. Without both pieces the sync worker would be
 * instantiated reflectively and fail with a "no no-arg constructor" error.
 */
@HiltAndroidApp
class AgriConnectApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var serverConfig: ServerConfig

    @Inject lateinit var updateChecker: UpdateChecker

    @Inject lateinit var updateScheduler: UpdateScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Image references are stored origin-relative, so they must resolve against
        // whatever host the API client ended up on (which a saved server override can
        // change). Seeding it here keeps images and JSON on the same origin.
        ImageUrls.origin = serverConfig.origin
        // Establish the periodic catch-up schedule at process start. The immediate
        // "sync now" path is requested by repositories after each local write.
        syncScheduler.schedulePeriodic()
        // Phase C: one throttled update check shortly after start (non-blocking;
        // a failed or offline check is silent and never interrupts the user) and
        // the 6-hourly WorkManager tick while online.
        updateScheduler.schedulePeriodic()
        updateChecker.checkOnStartup()
    }
}
