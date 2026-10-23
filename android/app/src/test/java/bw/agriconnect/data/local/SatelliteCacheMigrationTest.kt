package bw.agriconnect.data.local

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the v2 → v3 migration that adds the satellite overview cache.
 *
 * A wrong `CREATE TABLE` in a migration is either a crash on upgrade or, worse, a silent
 * loss of the farmer's offline plots — indistinguishable from data loss to the user. So
 * this test builds a real v2 database file with a plot in it, runs the **production**
 * `MIGRATION_2_3` against it, and checks both halves:
 *
 *  1. the new table exists with the columns the entity expects and accepts a row, and
 *  2. the existing plot survives the upgrade untouched.
 *
 * It deliberately calls `MIGRATION_2_3.migrate(...)` rather than repeating the SQL, so the
 * assertion is on the code that actually runs on a user's phone.
 *
 * `application = Application::class` overrides the manifest's `AgriConnectApp`, whose Hilt
 * graph would build the `AndroidKeyStore`-backed token store that Robolectric cannot provide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SatelliteCacheMigrationTest {

    /**
     * A real v2 database on disk: the reduced v2 schema (the plot table is the only one the
     * app writes user data into, and the one that must survive) plus one plot.
     */
    private fun openV2Database(): SupportSQLiteOpenHelper {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(DB_NAME)
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS farm_plots (
                                plot_id TEXT NOT NULL PRIMARY KEY,
                                farmer_id TEXT NOT NULL,
                                plot_name TEXT NOT NULL,
                                area_hectares REAL,
                                geojson_boundary TEXT,
                                centroid_lat REAL,
                                centroid_lng REAL,
                                notes TEXT,
                                image_url TEXT,
                                created_at TEXT NOT NULL,
                                updated_at TEXT NOT NULL,
                                sync_status TEXT NOT NULL,
                                client_id TEXT,
                                pending_delete INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            INSERT INTO farm_plots (
                                plot_id, farmer_id, plot_name, area_hectares, geojson_boundary,
                                centroid_lat, centroid_lng, notes, image_url,
                                created_at, updated_at, sync_status, client_id, pending_delete
                            ) VALUES ('plot-1', 'farmer-1', 'North Field', 2.5, NULL,
                                NULL, NULL, 'irrigated', NULL,
                                '2026-01-01T08:00:00Z', '2026-01-01T08:00:00Z', 'SYNCED', NULL, 0)
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // The production migration is invoked explicitly by the test.
                    }
                })
                .build(),
        )
    }

    @Test
    fun migrate2To3_preservesPlots_andCreatesTheCacheTable() {
        val helper = openV2Database()
        try {
            val db = helper.writableDatabase

            // The migration under test, exactly as Room would execute it on upgrade.
            MIGRATION_2_3.migrate(db)

            // The farmer's plot is still there, unchanged.
            db.query("SELECT plot_name, area_hectares, notes FROM farm_plots WHERE plot_id = 'plot-1'")
                .use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo("North Field")
                    assertThat(cursor.getDouble(1)).isEqualTo(2.5)
                    assertThat(cursor.getString(2)).isEqualTo("irrigated")
                }

            // The new table exists and accepts a row with the entity's columns.
            db.execSQL(
                """
                INSERT INTO satellite_overview_cache (
                    plot_id, farmer_id, plot_name, observed_on, latest_ndvi,
                    source, is_simulated, overview_json, cached_at
                ) VALUES ('plot-1', 'farmer-1', 'North Field', '2026-10-22', 0.31,
                    'SENTINEL-2', 0, '{}', 1)
                """.trimIndent(),
            )
            db.query("SELECT observed_on, cached_at FROM satellite_overview_cache").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("2026-10-22")
                assertThat(cursor.getLong(1)).isEqualTo(1L)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrate2To3_isIdempotent_soARetriedUpgradeCannotFail() {
        val helper = openV2Database()
        try {
            val db = helper.writableDatabase
            // Room runs a migration once, but a hand-run or recovered upgrade must not blow
            // up on the second pass: every statement is guarded by IF NOT EXISTS.
            MIGRATION_2_3.migrate(db)
            MIGRATION_2_3.migrate(db)

            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'satellite_overview_cache'",
            ).use { cursor ->
                assertThat(cursor.count).isEqualTo(1)
            }
        } finally {
            helper.close()
        }
    }

    private companion object {
        const val DB_NAME = "satellite-migration-test.db"
    }
}
