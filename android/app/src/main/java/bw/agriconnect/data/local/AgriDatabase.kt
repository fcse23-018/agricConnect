package bw.agriconnect.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import bw.agriconnect.data.local.dao.ExpenseDao
import bw.agriconnect.data.local.dao.FarmPlotDao
import bw.agriconnect.data.local.dao.HarvestRecordDao
import bw.agriconnect.data.local.dao.LivestockCountDao
import bw.agriconnect.data.local.dao.NotificationDao
import bw.agriconnect.data.local.dao.OrderDao
import bw.agriconnect.data.local.dao.ProduceListingDao
import bw.agriconnect.data.local.dao.SaleTransactionDao
import bw.agriconnect.data.local.dao.SatelliteNdviDao
import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.ExpenseEntity
import bw.agriconnect.data.local.entity.FarmPlotEntity
import bw.agriconnect.data.local.entity.HarvestRecordEntity
import bw.agriconnect.data.local.entity.LivestockCountEntity
import bw.agriconnect.data.local.entity.NotificationEntity
import bw.agriconnect.data.local.entity.OrderEntity
import bw.agriconnect.data.local.entity.ProduceListingEntity
import bw.agriconnect.data.local.entity.SaleTransactionEntity
import bw.agriconnect.data.local.entity.SatelliteNdviEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity

/**
 * The single on-device SQLite database (Room) for AgriConnect.
 *
 * Why one database: the offline-first guarantee (brief §4E/§14) requires that a user's
 * entity write and its `sync_queue` entry commit in the same transaction. SQLite transactions
 * cannot span separate database files, so every table — local-writable and server-cache
 * alike — lives here.
 *
 * `exportSchema = true` writes the schema JSON to `app/schemas` (configured in
 * build.gradle.kts) so every migration can be diffed in review; the schema is the contract
 * for future versions.
 */
@Database(
    entities = [
        FarmPlotEntity::class,
        HarvestRecordEntity::class,
        LivestockCountEntity::class,
        SaleTransactionEntity::class,
        ExpenseEntity::class,
        SatelliteNdviEntity::class,
        ProduceListingEntity::class,
        OrderEntity::class,
        NotificationEntity::class,
        SyncQueueEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AgriDatabase : RoomDatabase() {

    abstract fun farmPlotDao(): FarmPlotDao

    abstract fun harvestRecordDao(): HarvestRecordDao

    abstract fun livestockCountDao(): LivestockCountDao

    abstract fun saleTransactionDao(): SaleTransactionDao

    abstract fun expenseDao(): ExpenseDao

    abstract fun satelliteNdviDao(): SatelliteNdviDao

    abstract fun produceListingDao(): ProduceListingDao

    abstract fun orderDao(): OrderDao

    abstract fun notificationDao(): NotificationDao

    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        /** File name of the on-device database. */
        const val NAME = "agriconnect.db"
    }
}
