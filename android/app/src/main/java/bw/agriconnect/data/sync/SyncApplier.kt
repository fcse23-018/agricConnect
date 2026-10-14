package bw.agriconnect.data.sync

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
import bw.agriconnect.data.remote.dto.ExpenseDto
import bw.agriconnect.data.remote.dto.FarmPlotDto
import bw.agriconnect.data.remote.dto.HarvestDto
import bw.agriconnect.data.remote.dto.ListingDto
import bw.agriconnect.data.remote.dto.LivestockDto
import bw.agriconnect.data.remote.dto.NdviDto
import bw.agriconnect.data.remote.dto.NotificationDto
import bw.agriconnect.data.remote.dto.OrderDto
import bw.agriconnect.data.remote.dto.SaleDto
import bw.agriconnect.data.remote.dto.SyncPullResponse
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies pulled server rows to Room as `SYNCED`.
 *
 * This is the *only* place the sync layer writes entity data, deliberately: the
 * [SyncEngine] is pure transport, and the applier is the single choke-point for
 * conflict policy. Every row goes through the same guard:
 *
 *  1. If the entity still has outstanding local work (`sync_queue` has a `PENDING`/
 *     `CONFLICT` row for `(entityType, entityId)`), the local copy must not be
 *     overwritten blindly. [LastWriteWinsResolver] decides using the queued write's
 *     `clientTimestamp` (exactly the timestamp the server itself compares against)
 *     versus the server row's `updatedAt`. The server only wins when it is strictly
 *     newer, so an unsynced local edit is not silently discarded.
 *  2. Otherwise (no queued write) the server row is authoritative and is upserted
 *     with `syncStatus = SYNCED`.
 *
 * The applier uses the DAOs' raw `upsertOne(...)` (not the transactional
 * `*AndEnqueue` variants) because server rows arrive already synced and must never
 * enqueue new outbound work.
 *
 * Ordering note: [apply] processes collections in a fixed order (plots before
 * harvests before livestock…) so a pull that contains a new plot and a harvest
 * against it writes the parent first, which keeps any future foreign-key-ish checks
 * happy.
 */
@Singleton
class SyncApplier @Inject constructor(
    private val syncQueueDao: SyncQueueDao,
    private val farmPlotDao: FarmPlotDao,
    private val harvestRecordDao: HarvestRecordDao,
    private val livestockCountDao: LivestockCountDao,
    private val saleTransactionDao: SaleTransactionDao,
    private val expenseDao: ExpenseDao,
    private val produceListingDao: ProduceListingDao,
    private val orderDao: OrderDao,
    private val notificationDao: NotificationDao,
    private val satelliteNdviDao: SatelliteNdviDao,
) {

    /** Applies every collection in a pull response. Safe to call more than once. */
    suspend fun apply(response: SyncPullResponse) {
        val changes = response.changes
        changes.plots.forEach { applyFarmPlot(it.plotId, it.updatedAt, it) }
        changes.harvests.forEach { applyHarvest(it.harvestId, it.updatedAt, it) }
        changes.livestock.forEach { applyLivestock(it.countId, it.updatedAt, it) }
        changes.sales.forEach { applySale(it.transactionId, it.updatedAt, it) }
        changes.expenses.forEach { applyExpense(it.expenseId, it.updatedAt, it) }
        changes.listings.forEach { applyListing(it.listingId, it.updatedAt, it) }
        changes.orders.forEach { applyOrder(it.orderId, it.updatedAt, it) }
        changes.notifications.forEach { applyNotification(it.notificationId, it.createdAt, it) }
        changes.ndvi.forEach { applyNdvi(it.ndviId, it.createdAt.orEmpty(), it) }
    }

    /* ------------------------------ farm records --------------------------- */

    private suspend fun applyFarmPlot(entityId: String, serverUpdatedAt: String, dto: FarmPlotDto) {
        if (!serverWins(TYPE_FARM_PLOT, entityId, serverUpdatedAt)) return
        farmPlotDao.upsertOne(
            FarmPlotEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED),
        )
    }

    private suspend fun applyHarvest(entityId: String, serverUpdatedAt: String, dto: HarvestDto) {
        if (!serverWins(TYPE_HARVEST, entityId, serverUpdatedAt)) return
        harvestRecordDao.upsertOne(
            HarvestRecordEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED),
        )
    }

    private suspend fun applyLivestock(entityId: String, serverUpdatedAt: String, dto: LivestockDto) {
        if (!serverWins(TYPE_LIVESTOCK, entityId, serverUpdatedAt)) return
        livestockCountDao.upsertOne(
            LivestockCountEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED),
        )
    }

    private suspend fun applySale(entityId: String, serverUpdatedAt: String, dto: SaleDto) {
        if (!serverWins(TYPE_SALE, entityId, serverUpdatedAt)) return
        saleTransactionDao.upsertOne(
            SaleTransactionEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED),
        )
    }

    private suspend fun applyExpense(entityId: String, serverUpdatedAt: String, dto: ExpenseDto) {
        if (!serverWins(TYPE_EXPENSE, entityId, serverUpdatedAt)) return
        expenseDao.upsertOne(
            ExpenseEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED),
        )
    }

    private suspend fun applyListing(entityId: String, serverUpdatedAt: String, dto: ListingDto) {
        if (!serverWins(TYPE_LISTING, entityId, serverUpdatedAt)) return
        produceListingDao.upsertOne(
            ProduceListingEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED),
        )
    }

    /* ------------------------- server-cache entities ----------------------- */

    // Orders, notifications and NDVI are authored server-side, so they can never be in
    // the outbox; they still run through [serverWins] for uniformity (a cheap indexed
    // miss) rather than being special-cased.

    private suspend fun applyOrder(entityId: String, serverUpdatedAt: String, dto: OrderDto) {
        if (!serverWins(TYPE_ORDER, entityId, serverUpdatedAt)) return
        orderDao.upsertOne(OrderEntity.fromDomain(dto.toDomain()))
    }

    private suspend fun applyNotification(
        entityId: String,
        serverUpdatedAt: String,
        dto: NotificationDto,
    ) {
        if (!serverWins(TYPE_NOTIFICATION, entityId, serverUpdatedAt)) return
        notificationDao.upsertOne(NotificationEntity.fromDomain(dto.toDomain(), userId = dto.userId))
    }

    private suspend fun applyNdvi(entityId: String, serverUpdatedAt: String, dto: NdviDto) {
        if (!serverWins(TYPE_NDVI, entityId, serverUpdatedAt)) return
        satelliteNdviDao.upsertOne(SatelliteNdviEntity.fromDomain(dto.toDomain()))
    }

    /* ------------------------------ conflict guard ------------------------- */

    /**
     * True when the server copy should be written. When the entity is queued locally,
     * only a strictly-newer server timestamp wins (ties go to the server, matching the
     * engine's and the backend's rule). A queued local *delete* always wins.
     */
    private suspend fun serverWins(
        entityType: String,
        entityId: String,
        serverUpdatedAt: String,
    ): Boolean {
        val queued = syncQueueDao.findByEntity(entityType, entityId) ?: return true
        val resolution = LastWriteWinsResolver.resolve(
            localUpdatedAt = isoFromEpochMillis(queued.createdAt),
            serverUpdatedAt = serverUpdatedAt,
            localPendingDelete = queued.operation == SyncOperation.DELETE,
        )
        // USE_SERVER writes; USE_LOCAL and MERGE both leave the local row untouched
        // (LWW never returns MERGE — it exists for a future field-level merger).
        return resolution == Resolution.USE_SERVER
    }

    /**
     * The queue stores `createdAt` as epoch millis (see [SyncQueueEntity]); the
     * resolver compares ISO strings, so convert with the same UTC format
     * [bw.agriconnect.data.remote.dto.toSyncItemDto] puts on the wire.
     */
    private fun isoFromEpochMillis(millis: Long): String =
        synchronized(isoFormat) { isoFormat.format(Date(millis)) }

    private companion object {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Syncable entity types: reuse the canonical constants so the guard and the
        // outbox can never disagree on the key.
        const val TYPE_FARM_PLOT = SyncQueueEntity.EntityTypes.FARM_PLOT
        const val TYPE_HARVEST = SyncQueueEntity.EntityTypes.HARVEST_RECORD
        const val TYPE_LIVESTOCK = SyncQueueEntity.EntityTypes.LIVESTOCK_COUNT
        const val TYPE_SALE = SyncQueueEntity.EntityTypes.SALE_TRANSACTION
        const val TYPE_EXPENSE = SyncQueueEntity.EntityTypes.EXPENSE
        const val TYPE_LISTING = SyncQueueEntity.EntityTypes.PRODUCE_LISTING

        // Server-authored cache types (never in the outbox; no shared constant exists).
        const val TYPE_ORDER = "Order"
        const val TYPE_NOTIFICATION = "Notification"
        const val TYPE_NDVI = "SatelliteNdvi"
    }
}
