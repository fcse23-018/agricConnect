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

/** All migrations, in order. Passed to the Room builder by `DatabaseProvider`. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
