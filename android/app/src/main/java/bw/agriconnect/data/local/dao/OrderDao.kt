package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.OrderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read-mostly cache of buyer/farmer orders.
 *
 * Orders are only ever created or transitioned server-side inside a stock-check
 * transaction, so there is nothing to enqueue locally. Screens read from [Flow] and the
 * sync layer refreshes rows with [replaceAll] after a pull.
 */
@Dao
abstract class OrderDao {

    @Query("SELECT * FROM orders ORDER BY created_at DESC")
    abstract fun observeAll(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE buyer_id = :buyerId ORDER BY created_at DESC")
    abstract fun observeByBuyer(buyerId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE farmer_id = :farmerId ORDER BY created_at DESC")
    abstract fun observeByFarmer(farmerId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE listing_id = :listingId ORDER BY created_at DESC")
    abstract fun observeByListing(listingId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE order_id = :orderId LIMIT 1")
    abstract suspend fun findById(orderId: String): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<OrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: OrderEntity)

    @Query("DELETE FROM orders WHERE order_id = :orderId")
    abstract suspend fun deleteById(orderId: String)

    @Query("DELETE FROM orders")
    abstract suspend fun deleteAll()

    /**
     * Atomically swaps the cached order set for a freshly-pulled page. An `emptyList` still
     * clears the cache, which is the correct behaviour for a pull that returns no orders.
     */
    @Transaction
    open suspend fun replaceAll(entities: List<OrderEntity>) {
        deleteAll()
        if (entities.isNotEmpty()) upsert(entities)
    }
}
