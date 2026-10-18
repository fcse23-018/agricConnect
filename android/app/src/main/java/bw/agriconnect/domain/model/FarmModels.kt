package bw.agriconnect.domain.model

/**
 * Farm-domain models. Every synchronisable model carries a [syncStatus] and, for
 * records created offline, a [clientId] (the device-generated id used for
 * idempotent upserts server-side).
 */

data class FarmPlot(
    val plotId: String,
    val farmerId: String,
    val plotName: String,
    val areaHectares: Double?,
    val geoJsonBoundary: String?,
    val centroidLat: Double? = null,
    val centroidLng: Double? = null,
    val notes: String? = null,
    /** Origin-relative path of the plot photo (`/uploads/…`), or null. */
    val imageUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val clientId: String? = null,
)

data class HarvestRecord(
    val harvestId: String,
    val farmerId: String,
    val plotId: String? = null,
    val cropType: String,
    val quantityKg: Double,
    val harvestDate: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val clientId: String? = null,
)

data class LivestockCount(
    val countId: String,
    val farmerId: String,
    val animalType: String,
    val headCount: Int,
    val recordDate: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val clientId: String? = null,
)

data class SaleTransaction(
    val transactionId: String,
    val farmerId: String,
    val item: String,
    val quantity: Double,
    val unitPrice: Double? = null,
    val amount: Double,
    val currency: String = "BWP",
    val buyerName: String? = null,
    val date: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val clientId: String? = null,
)

data class Expense(
    val expenseId: String,
    val farmerId: String,
    val category: String,
    val description: String? = null,
    val amount: Double,
    val currency: String = "BWP",
    val expenseDate: String,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val clientId: String? = null,
)

data class FinanceSummary(
    val totalSales: Double,
    val totalExpenses: Double,
    val netBalance: Double,
    val currency: String = "BWP",
    val series: List<FinancePoint> = emptyList(),
)

data class FinancePoint(val period: String, val sales: Double, val expenses: Double)

data class SatelliteNdvi(
    val ndviId: String,
    val plotId: String,
    val farmerId: String,
    val satelliteDate: String?,
    val meanNdviValue: Double?,
    val stressLevel: StressLevel?,
    val status: NdviProcessingStatus,
    val failureReason: String? = null,
    val cloudCover: Double? = null,
    val compressedVectorJson: String? = null,
    val isCached: Boolean = true,
    val source: String = "SENTINEL-2",
    val createdAt: String,
    val plotName: String? = null,
)

data class ProduceListing(
    val listingId: String,
    val farmerId: String,
    val produce: String,
    val description: String? = null,
    val quantity: Double,
    val reservedQty: Double = 0.0,
    val soldQty: Double = 0.0,
    val unit: Unit = Unit.KG,
    val price: Double,
    val currency: String = "BWP",
    val status: ListingStatus,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val publishedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val farmerName: String? = null,
) {
    val availableQty: Double get() = (quantity - reservedQty - soldQty).coerceAtLeast(0.0)
}

data class Order(
    val orderId: String,
    val listingId: String,
    val buyerId: String,
    val farmerId: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val currency: String = "BWP",
    val status: OrderStatus,
    val note: String? = null,
    val buyerContact: String? = null,
    val produce: String? = null,
    val buyerName: String? = null,
    val farmerDecisionAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

data class AppNotification(
    val notificationId: String,
    val type: String,
    val title: String,
    val body: String,
    val relatedEntityType: String? = null,
    val relatedEntityId: String? = null,
    val isRead: Boolean,
    val createdAt: String,
)

data class SyncQueueItem(
    val queueId: String,
    val entityType: String,
    val entityId: String,
    val operation: SyncOperation,
    val payload: String,
    val createdAt: Long,
    val status: SyncStatus = SyncStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
)
