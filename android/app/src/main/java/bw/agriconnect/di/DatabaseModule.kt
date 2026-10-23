package bw.agriconnect.di

import android.content.Context
import bw.agriconnect.data.local.AgriDatabase
import bw.agriconnect.data.local.DatabaseProvider
import bw.agriconnect.data.local.dao.ExpenseDao
import bw.agriconnect.data.local.dao.FarmPlotDao
import bw.agriconnect.data.local.dao.HarvestRecordDao
import bw.agriconnect.data.local.dao.LivestockCountDao
import bw.agriconnect.data.local.dao.NotificationDao
import bw.agriconnect.data.local.dao.OrderDao
import bw.agriconnect.data.local.dao.ProduceListingDao
import bw.agriconnect.data.local.dao.SaleTransactionDao
import bw.agriconnect.data.local.dao.SatelliteNdviDao
import bw.agriconnect.data.local.dao.SatelliteOverviewCacheDao
import bw.agriconnect.data.local.dao.SyncQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room provisioning.
 *
 * [DatabaseProvider.build] stays a pure function (testable without Hilt); this
 * module is the only place that gives it a singleton scope and exposes the DAOs.
 * DAOs are cheap handles and are intentionally unscoped so Room can hand out a
 * fresh instance without a graph-wide lifetime.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AgriDatabase =
        DatabaseProvider.build(context)

    @Provides fun provideFarmPlotDao(db: AgriDatabase): FarmPlotDao = db.farmPlotDao()

    @Provides fun provideHarvestRecordDao(db: AgriDatabase): HarvestRecordDao = db.harvestRecordDao()

    @Provides fun provideLivestockCountDao(db: AgriDatabase): LivestockCountDao = db.livestockCountDao()

    @Provides fun provideSaleTransactionDao(db: AgriDatabase): SaleTransactionDao = db.saleTransactionDao()

    @Provides fun provideExpenseDao(db: AgriDatabase): ExpenseDao = db.expenseDao()

    @Provides fun provideSatelliteNdviDao(db: AgriDatabase): SatelliteNdviDao = db.satelliteNdviDao()

    @Provides
    fun provideSatelliteOverviewCacheDao(db: AgriDatabase): SatelliteOverviewCacheDao =
        db.satelliteOverviewCacheDao()

    @Provides fun provideProduceListingDao(db: AgriDatabase): ProduceListingDao = db.produceListingDao()

    @Provides fun provideOrderDao(db: AgriDatabase): OrderDao = db.orderDao()

    @Provides fun provideNotificationDao(db: AgriDatabase): NotificationDao = db.notificationDao()

    @Provides fun provideSyncQueueDao(db: AgriDatabase): SyncQueueDao = db.syncQueueDao()
}
