package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import bw.agriconnect.data.local.entity.SatelliteNdviEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read-mostly cache for server-generated satellite NDVI.
 *
 * NDVI is computed server-side and never authored offline, so there is no sync queue
 * plumbing here. Ordering is newest-first and NULL dates sort last so in-flight requests
 * (`status = REQUESTED`) stay at the top until a real observation date arrives.
 */
@Dao
interface SatelliteNdviDao {

    @Query("SELECT * FROM satellite_ndvi ORDER BY satellite_date DESC")
    fun observeAll(): Flow<List<SatelliteNdviEntity>>

    @Query(
        """
        SELECT * FROM satellite_ndvi
        WHERE plot_id = :plotId
        ORDER BY satellite_date DESC
        """,
    )
    fun observeByPlot(plotId: String): Flow<List<SatelliteNdviEntity>>

    @Query(
        """
        SELECT * FROM satellite_ndvi
        WHERE farmer_id = :farmerId
        ORDER BY satellite_date DESC
        """,
    )
    fun observeByFarmer(farmerId: String): Flow<List<SatelliteNdviEntity>>

    /** Most recent observation for a plot; the dashboard card uses this. */
    @Query(
        """
        SELECT * FROM satellite_ndvi
        WHERE plot_id = :plotId
        ORDER BY satellite_date DESC
        LIMIT 1
        """,
    )
    suspend fun findLatestForPlot(plotId: String): SatelliteNdviEntity?

    @Query("SELECT * FROM satellite_ndvi WHERE ndvi_id = :ndviId LIMIT 1")
    suspend fun findById(ndviId: String): SatelliteNdviEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<SatelliteNdviEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(entity: SatelliteNdviEntity)

    @Query("DELETE FROM satellite_ndvi WHERE ndvi_id = :ndviId")
    suspend fun deleteById(ndviId: String)

    @Query("DELETE FROM satellite_ndvi WHERE plot_id = :plotId")
    suspend fun deleteByPlot(plotId: String)

    @Query("DELETE FROM satellite_ndvi WHERE farmer_id = :farmerId")
    suspend fun deleteByFarmer(farmerId: String)
}
