package bw.agriconnect.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for [AgriDatabase].
 *
 * `DatabaseProvider` deliberately does **not** enable `fallbackToDestructiveMigration()`
 * for upgrades, so every version bump must ship a migration here: silently dropping a
 * user's offline farm records on upgrade would violate the offline-first guarantee
 * (brief §4E/§14).
 */

/**
 * v1 → v2: adds the optional plot photo column.
 *
 * `farm_plots.image_url` mirrors `produce_listings.image_url` and `users.avatar_url`
 * (backend migration `002_domain.sql` / `003_marketplace.sql`). It stores the
 * origin-relative upload path (`/uploads/<uuid>.<ext>`), never an absolute URL, so the
 * storage origin can change without a data migration.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE farm_plots ADD COLUMN image_url TEXT")
    }
}

/**
 * v2 → v3: adds the satellite overview cache.
 *
 * `satellite_overview_cache` stores one row per plot holding the last successful
 * `GET /satellite/overview` body, so the Satellite Insights screen can show the farmer's
 * real last reading — with its **original** observation date — when there is no
 * connection. It is additive: no existing table or row is touched, so an upgrade cannot
 * lose an offline farm record.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS satellite_overview_cache (
                plot_id TEXT NOT NULL PRIMARY KEY,
                farmer_id TEXT NOT NULL,
                plot_name TEXT,
                observed_on TEXT,
                latest_ndvi REAL,
                source TEXT,
                is_simulated INTEGER NOT NULL DEFAULT 0,
                overview_json TEXT NOT NULL,
                cached_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_satellite_overview_cache_farmer_id " +
                "ON satellite_overview_cache (farmer_id)",
        )
    }
}

/** All migrations, in order. Passed to the Room builder by `DatabaseProvider`. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
