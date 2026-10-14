package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.FarmPlotEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Farm-plot persistence.
 *
 * Reads that feed the UI return [Flow] so screens recompose automatically after an
 * offline write. Writes are `suspend` and come in two flavours:
 *  - the *raw* variant (`upsertOne`, `upsert`, `markPendingDelete`) touches only the row, and
 *  - the *transactional* variant (`insertAndEnqueue`, `markPendingDeleteAndEnqueue`) writes
 *    the row **and** its `sync_queue` entry in one transaction.
 *
 * Use the transactional variant for anything the user authored, so a crash between the two
 * writes can never lose work or leave an unqueued record. The raw variant exists for
 * server-pulled rows (already synced) and for tests.
 *
 * `observe*` excludes tombstones ([FarmPlotEntity.pendingDelete] = 1) so a deleted plot
 * disappears from the UI immediately while its DELETE op is still queued.
 */
@Dao
abstract class FarmPlotDao {

    @Query("SELECT * FROM farm_plots WHERE pending_delete = 0 ORDER BY updated_at DESC")
    abstract fun observeAll(): Flow<List<FarmPlotEntity>>

    @Query(
        """
        SELECT * FROM farm_plots
        WHERE farmer_id = :farmerId AND pending_delete = 0
        ORDER BY plot_name ASC
        """,
    )
    abstract fun observeByFarmer(farmerId: String): Flow<List<FarmPlotEntity>>

    @Query("SELECT COUNT(*) FROM farm_plots WHERE pending_delete = 0")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT * FROM farm_plots WHERE plot_id = :plotId LIMIT 1")
    abstract suspend fun findById(plotId: String): FarmPlotEntity?

    @Query("SELECT * FROM farm_plots WHERE farmer_id = :farmerId AND client_id = :clientId LIMIT 1")
    abstract suspend fun findByClientId(farmerId: String, clientId: String): FarmPlotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<FarmPlotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: FarmPlotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enqueue(item: SyncQueueEntity)

    /** Hard delete. Only for rows already confirmed deleted server-side or purge-on-logout. */
    @Query("DELETE FROM farm_plots WHERE plot_id = :plotId")
    abstract suspend fun deleteById(plotId: String)

    @Query("DELETE FROM farm_plots WHERE farmer_id = :farmerId")
    abstract suspend fun deleteByFarmer(farmerId: String)

    /** Tombstones the row and marks it pending so it stays visible to the sync worker. */
    @Query(
        """
        UPDATE farm_plots
        SET pending_delete = 1,
            sync_status = 'PENDING',
            updated_at = :updatedAt
        WHERE plot_id = :plotId
        """,
    )
    abstract suspend fun markPendingDelete(plotId: String, updatedAt: String)

    /**
     * Counts rows with outstanding local work: an unsynced status or a pending tombstone.
     * Drives the "unsynced changes" prompt before logout.
     */
    @Query(
        """
        SELECT COUNT(*) FROM farm_plots
        WHERE pending_delete = 1 OR sync_status != 'SYNCED'
        """,
    )
    abstract fun countPending(): Flow<Int>

    @Transaction
    open suspend fun insertAndEnqueue(entity: FarmPlotEntity, item: SyncQueueEntity) {
        upsertOne(entity)
        enqueue(item)
    }

    @Transaction
    open suspend fun upsertAndEnqueue(entities: List<FarmPlotEntity>, items: List<SyncQueueEntity>) {
        upsert(entities)
        items.forEach { enqueue(it) }
    }

    @Transaction
    open suspend fun markPendingDeleteAndEnqueue(
        plotId: String,
        updatedAt: String,
        item: SyncQueueEntity,
    ) {
        markPendingDelete(plotId, updatedAt)
        enqueue(item)
    }
}
