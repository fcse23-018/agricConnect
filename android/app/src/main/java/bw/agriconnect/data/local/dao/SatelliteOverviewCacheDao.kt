package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import bw.agriconnect.data.local.entity.SatelliteOverviewCacheEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the satellite overview cache.
 *
 * The cache is server-derived, never authored on the device, so there is no sync queue
 * here: it is a read-through mirror that is replaced wholesale when a fresh payload
 * arrives. [observeForPlot] is a `Flow` so the Satellite screen can fall back to it the
 * moment a network request fails.
 */
@Dao
interface SatelliteOverviewCacheDao {

    @Query("SELECT * FROM satellite_overview_cache WHERE plot_id = :plotId LIMIT 1")
    suspend fun findForPlot(plotId: String): SatelliteOverviewCacheEntity?

    @Query("SELECT * FROM satellite_overview_cache WHERE plot_id = :plotId LIMIT 1")
    fun observeForPlot(plotId: String): Flow<SatelliteOverviewCacheEntity?>

    @Query("SELECT * FROM satellite_overview_cache WHERE farmer_id = :farmerId")
    suspend fun findForFarmer(farmerId: String): List<SatelliteOverviewCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SatelliteOverviewCacheEntity)

    @Query("DELETE FROM satellite_overview_cache WHERE plot_id = :plotId")
    suspend fun deleteForPlot(plotId: String)

    @Query("DELETE FROM satellite_overview_cache WHERE farmer_id = :farmerId")
    suspend fun deleteForFarmer(farmerId: String)
}
