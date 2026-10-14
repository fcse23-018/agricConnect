package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.ProduceListingEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Produce-listing persistence. Listings are drafted offline before they are published, so
 * this follows the same `Flow` reads / `suspend` writes / transactional enqueue pattern as
 * [FarmPlotDao].
 *
 * [observePublished] intentionally filters on the `PUBLISHED` status only: the contract is
 * explicit that a listing is never publicly visible before the server confirms it
 * (docs/02-API-CONTRACT.md §2.7), so the buyer-facing list must never be fed a local draft.
 */
@Dao
abstract class ProduceListingDao {

    @Query("SELECT * FROM produce_listings WHERE pending_delete = 0 ORDER BY updated_at DESC")
    abstract fun observeAll(): Flow<List<ProduceListingEntity>>

    @Query(
        """
        SELECT * FROM produce_listings
        WHERE farmer_id = :farmerId AND pending_delete = 0
        ORDER BY updated_at DESC
        """,
    )
    abstract fun observeByFarmer(farmerId: String): Flow<List<ProduceListingEntity>>

    @Query("SELECT * FROM produce_listings WHERE status = 'PUBLISHED' AND pending_delete = 0 ORDER BY published_at DESC")
    abstract fun observePublished(): Flow<List<ProduceListingEntity>>

    @Query("SELECT * FROM produce_listings WHERE listing_id = :listingId LIMIT 1")
    abstract suspend fun findById(listingId: String): ProduceListingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<ProduceListingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: ProduceListingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enqueue(item: SyncQueueEntity)

    @Query("DELETE FROM produce_listings WHERE listing_id = :listingId")
    abstract suspend fun deleteById(listingId: String)

    @Query("DELETE FROM produce_listings WHERE farmer_id = :farmerId")
    abstract suspend fun deleteByFarmer(farmerId: String)

    @Query(
        """
        UPDATE produce_listings
        SET pending_delete = 1,
            sync_status = 'PENDING',
            updated_at = :updatedAt
        WHERE listing_id = :listingId
        """,
    )
    abstract suspend fun markPendingDelete(listingId: String, updatedAt: String)

    @Query(
        """
        SELECT COUNT(*) FROM produce_listings
        WHERE pending_delete = 1 OR sync_status != 'SYNCED'
        """,
    )
    abstract fun countPending(): Flow<Int>

    @Transaction
    open suspend fun insertAndEnqueue(entity: ProduceListingEntity, item: SyncQueueEntity) {
        upsertOne(entity)
        enqueue(item)
    }

    @Transaction
    open suspend fun upsertAndEnqueue(entities: List<ProduceListingEntity>, items: List<SyncQueueEntity>) {
        upsert(entities)
        items.forEach { enqueue(it) }
    }

    @Transaction
    open suspend fun markPendingDeleteAndEnqueue(
        listingId: String,
        updatedAt: String,
        item: SyncQueueEntity,
    ) {
        markPendingDelete(listingId, updatedAt)
        enqueue(item)
    }
}
