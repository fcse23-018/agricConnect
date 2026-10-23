package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of a plot's **satellite overview** so the Satellite Insights screen still
 * works without a connection.
 *
 * Why one row per plot rather than per observation. The screen's headline facts are the
 * selected plot's newest reading, its freshness, and the measured mean — the whole series
 * is only needed for the chart tab, and caching every observation would multiply the
 * storage for a view that is secondary. The full JSON payload is stored verbatim
 * ([overviewJson]) and re-parsed on read, so the cache can never drift from the server's
 * field names: whatever the API returned is exactly what is shown offline.
 *
 * [cachedAt] is a local timestamp and is **never** presented as the observation date. The
 * two are deliberately separate columns ([observedOn] vs [cachedAt]) because showing a
 * cache write time as if it were a satellite pass would be a fabricated reading — the
 * single most important rule this feature has to keep.
 */
@Entity(
    tableName = "satellite_overview_cache",
    indices = [Index(value = ["farmer_id"])],
)
data class SatelliteOverviewCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "plot_id")
    val plotId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "plot_name")
    val plotName: String? = null,
    /** The newest observation date the payload carried (`YYYY-MM-DD`), or null. */
    @ColumnInfo(name = "observed_on")
    val observedOn: String? = null,
    /** The measured mean NDVI of the newest reading, or null. Never invented. */
    @ColumnInfo(name = "latest_ndvi")
    val latestNdvi: Double? = null,
    /** `SENTINEL-2`, `MOCK`, … exactly as the server labelled it. */
    @ColumnInfo(name = "source")
    val source: String? = null,
    @ColumnInfo(name = "is_simulated")
    val isSimulated: Boolean = false,
    /** The raw `GET /satellite/overview` body, so the screen can be rebuilt faithfully. */
    @ColumnInfo(name = "overview_json")
    val overviewJson: String,
    /** When this row was written locally. Not an observation date. */
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long,
)
