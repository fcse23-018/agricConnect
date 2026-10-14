package bw.agriconnect.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Plain, testable factory for the app database.
 *
 * This is deliberately **not** a Hilt module: provisioning (singleton scope, `@Provides`)
 * is added by the DI task, and keeping the builder here as a pure function means a unit
 * test can call [build] with any [Context] without an Application/DI graph.
 *
 * Policies:
 *  - `fallbackToDestructiveMigrationOnDowngrade()` — a *downgrade* happens when a user
 *    installs an older APK over a newer one (e.g. a beta rollback). The older app cannot
 *    understand the newer schema, so recreating the tables is the only safe option. This is
 *    scoped to downgrades only; upgrades still require a real migration.
 *  - WAL journal mode is enabled explicitly. WAL lets the sync worker write a queued item
 *    while a UI `Flow` reads concurrently instead of the two blocking each other, which is
 *    what keeps the documented <50 ms local-write target achievable under load.
 */
object DatabaseProvider {

    fun build(context: Context): AgriDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            AgriDatabase::class.java,
            AgriDatabase.NAME,
        )
            .fallbackToDestructiveMigrationOnDowngrade()
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
}
