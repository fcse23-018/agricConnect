package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.LivestockCountEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Livestock-count persistence, modelled on [FarmPlotDao]: `Flow` reads, `suspend` writes,
 * and transactional write-and-enqueue variants.
 */
@Dao
abstract class LivestockCountDao {

    @Query("SELECT * FROM livestock_counts WHERE pending_delete = 0 ORDER BY record_date DESC")
    abstract fun observeAll(): Flow<List<LivestockCountEntity>>

    @Query(
        """
        SELECT * FROM livestock_counts
        WHERE farmer_id = :farmerId AND pending_delete = 0
        ORDER BY record_date DESC
        """,
    )
    abstract fun observeByFarmer(farmerId: String): Flow<List<LivestockCountEntity>>

    @Query("SELECT * FROM livestock_counts WHERE count_id = :countId LIMIT 1")
    abstract suspend fun findById(countId: String): LivestockCountEntity?

    @Query("SELECT * FROM livestock_counts WHERE farmer_id = :farmerId AND client_id = :clientId LIMIT 1")
    abstract suspend fun findByClientId(farmerId: String, clientId: String): LivestockCountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<LivestockCountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: LivestockCountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enqueue(item: SyncQueueEntity)

    @Query("DELETE FROM livestock_counts WHERE count_id = :countId")
    abstract suspend fun deleteById(countId: String)

    @Query("DELETE FROM livestock_counts WHERE farmer_id = :farmerId")
    abstract suspend fun deleteByFarmer(farmerId: String)

    @Query(
        """
        UPDATE livestock_counts
        SET pending_delete = 1,
            sync_status = 'PENDING',
            updated_at = :updatedAt
        WHERE count_id = :countId
        """,
    )
    abstract suspend fun markPendingDelete(countId: String, updatedAt: String)

    @Query(
        """
        SELECT COUNT(*) FROM livestock_counts
        WHERE pending_delete = 1 OR sync_status != 'SYNCED'
        """,
    )
    abstract fun countPending(): Flow<Int>

    @Transaction
    open suspend fun insertAndEnqueue(entity: LivestockCountEntity, item: SyncQueueEntity) {
        upsertOne(entity)
        enqueue(item)
    }

    @Transaction
    open suspend fun upsertAndEnqueue(entities: List<LivestockCountEntity>, items: List<SyncQueueEntity>) {
        upsert(entities)
        items.forEach { enqueue(it) }
    }

    @Transaction
    open suspend fun markPendingDeleteAndEnqueue(
        countId: String,
        updatedAt: String,
        item: SyncQueueEntity,
    ) {
        markPendingDelete(countId, updatedAt)
        enqueue(item)
    }
}
