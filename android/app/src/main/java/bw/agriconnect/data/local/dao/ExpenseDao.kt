package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.ExpenseEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Expense persistence (farmer-only domain). `Flow` reads, `suspend` writes, and
 * transactional write-and-enqueue variants.
 */
@Dao
abstract class ExpenseDao {

    @Query("SELECT * FROM expenses WHERE pending_delete = 0 ORDER BY expense_date DESC")
    abstract fun observeAll(): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE farmer_id = :farmerId AND pending_delete = 0
        ORDER BY expense_date DESC
        """,
    )
    abstract fun observeByFarmer(farmerId: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE expense_id = :expenseId LIMIT 1")
    abstract suspend fun findById(expenseId: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE farmer_id = :farmerId AND client_id = :clientId LIMIT 1")
    abstract suspend fun findByClientId(farmerId: String, clientId: String): ExpenseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun enqueue(item: SyncQueueEntity)

    @Query("DELETE FROM expenses WHERE expense_id = :expenseId")
    abstract suspend fun deleteById(expenseId: String)

    @Query("DELETE FROM expenses WHERE farmer_id = :farmerId")
    abstract suspend fun deleteByFarmer(farmerId: String)

    @Query(
        """
        UPDATE expenses
        SET pending_delete = 1,
            sync_status = 'PENDING',
            updated_at = :updatedAt
        WHERE expense_id = :expenseId
        """,
    )
    abstract suspend fun markPendingDelete(expenseId: String, updatedAt: String)

    @Query(
        """
        SELECT COUNT(*) FROM expenses
        WHERE pending_delete = 1 OR sync_status != 'SYNCED'
        """,
    )
    abstract fun countPending(): Flow<Int>

    @Transaction
    open suspend fun insertAndEnqueue(entity: ExpenseEntity, item: SyncQueueEntity) {
        upsertOne(entity)
        enqueue(item)
    }

    @Transaction
    open suspend fun upsertAndEnqueue(entities: List<ExpenseEntity>, items: List<SyncQueueEntity>) {
        upsert(entities)
        items.forEach { enqueue(it) }
    }

    @Transaction
    open suspend fun markPendingDeleteAndEnqueue(
        expenseId: String,
        updatedAt: String,
        item: SyncQueueEntity,
    ) {
        markPendingDelete(expenseId, updatedAt)
        enqueue(item)
    }
}
