package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.HarvestRecordEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Harvest-record persistence, modelled on [FarmPlotDao]: `Flow` reads, `suspend` writes,
 * and transactional write-and-enqueue variants so an offline harvest and its outbox entry
 * are always written together.
 */
@Dao
abstract class HarvestRecordDao {

    @Query("SELECT * FROM harvest_records WHERE pending_delete = 0 ORDER BY harvest_date DESC")
    abstract fun observeAll(): Flow<List<HarvestRecordEntity>>

    @Query(
        """
        SELECT * FROM harvest_records
        WHERE farmer_id = :farmerId AND pending_delete = 0
        ORDER BY harvest_date DESC
        """,
    )
    abstract fun observeByFarmer(farmerId: String): Flow<List<HarvestRecordEntity>>

    @Query("SELECT * FROM harvest_records WHERE plot_id = :plotId AND pending_delete = 0 ORDER BY harvest_date DESC")
    abstract fun observeByPlot(plotId: String): Flow<List<HarvestRecordEntity>>

    @Query("SELECT * FROM harvest_records WHERE harvest_id = :harvestId LIMIT 1")
    abstract suspend fun findById(harvestId: String): HarvestRecordEntity?

    @Query("SELECT * FROM harvest_records WHERE farmer_id = :farmerId AND client_id = :clientId LIMIT 1")
    abstract suspend fun findByClientId(farmerId: String, clientId: String): HarvestRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<HarvestRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: HarvestRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enqueue(item: SyncQueueEntity)

    @Query("DELETE FROM harvest_records WHERE harvest_id = :harvestId")
    abstract suspend fun deleteById(harvestId: String)

    @Query("DELETE FROM harvest_records WHERE farmer_id = :farmerId")
    abstract suspend fun deleteByFarmer(farmerId: String)

    @Query(
        """
        UPDATE harvest_records
        SET pending_delete = 1,
            sync_status = 'PENDING',
            updated_at = :updatedAt
        WHERE harvest_id = :harvestId
        """,
    )
    abstract suspend fun markPendingDelete(harvestId: String, updatedAt: String)

    @Query(
        """
        SELECT COUNT(*) FROM harvest_records
        WHERE pending_delete = 1 OR sync_status != 'SYNCED'
        """,
    )
    abstract fun countPending(): Flow<Int>

    @Transaction
    open suspend fun insertAndEnqueue(entity: HarvestRecordEntity, item: SyncQueueEntity) {
        upsertOne(entity)
        enqueue(item)
    }

    @Transaction
    open suspend fun upsertAndEnqueue(entities: List<HarvestRecordEntity>, items: List<SyncQueueEntity>) {
        upsert(entities)
        items.forEach { enqueue(it) }
    }

    @Transaction
    open suspend fun markPendingDeleteAndEnqueue(
        harvestId: String,
        updatedAt: String,
        item: SyncQueueEntity,
    ) {
        markPendingDelete(harvestId, updatedAt)
        enqueue(item)
    }
}
