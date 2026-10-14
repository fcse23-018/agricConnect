package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.SaleTransactionEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Sale-transaction persistence (farmer-only domain). `Flow` reads, `suspend` writes, and
 * transactional write-and-enqueue variants so an offline sale and its outbox entry are
 * atomic.
 */
@Dao
abstract class SaleTransactionDao {

    @Query("SELECT * FROM sale_transactions WHERE pending_delete = 0 ORDER BY sale_date DESC")
    abstract fun observeAll(): Flow<List<SaleTransactionEntity>>

    @Query(
        """
        SELECT * FROM sale_transactions
        WHERE farmer_id = :farmerId AND pending_delete = 0
        ORDER BY sale_date DESC
        """,
    )
    abstract fun observeByFarmer(farmerId: String): Flow<List<SaleTransactionEntity>>

    @Query("SELECT * FROM sale_transactions WHERE transaction_id = :transactionId LIMIT 1")
    abstract suspend fun findById(transactionId: String): SaleTransactionEntity?

    @Query("SELECT * FROM sale_transactions WHERE farmer_id = :farmerId AND client_id = :clientId LIMIT 1")
    abstract suspend fun findByClientId(farmerId: String, clientId: String): SaleTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<SaleTransactionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: SaleTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enqueue(item: SyncQueueEntity)

    @Query("DELETE FROM sale_transactions WHERE transaction_id = :transactionId")
    abstract suspend fun deleteById(transactionId: String)

    @Query("DELETE FROM sale_transactions WHERE farmer_id = :farmerId")
    abstract suspend fun deleteByFarmer(farmerId: String)

    @Query(
        """
        UPDATE sale_transactions
        SET pending_delete = 1,
            sync_status = 'PENDING',
            updated_at = :updatedAt
        WHERE transaction_id = :transactionId
        """,
    )
    abstract suspend fun markPendingDelete(transactionId: String, updatedAt: String)

    @Query(
        """
        SELECT COUNT(*) FROM sale_transactions
        WHERE pending_delete = 1 OR sync_status != 'SYNCED'
        """,
    )
    abstract fun countPending(): Flow<Int>

    @Transaction
    open suspend fun insertAndEnqueue(entity: SaleTransactionEntity, item: SyncQueueEntity) {
        upsertOne(entity)
        enqueue(item)
    }

    @Transaction
    open suspend fun upsertAndEnqueue(entities: List<SaleTransactionEntity>, items: List<SyncQueueEntity>) {
        upsert(entities)
        items.forEach { enqueue(it) }
    }

    @Transaction
    open suspend fun markPendingDeleteAndEnqueue(
        transactionId: String,
        updatedAt: String,
        item: SyncQueueEntity,
    ) {
        markPendingDelete(transactionId, updatedAt)
        enqueue(item)
    }
}
