package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.ListingStatus
import bw.agriconnect.domain.model.ProduceListing
import bw.agriconnect.domain.model.SyncStatus
import bw.agriconnect.domain.model.Unit

/**
 * Local mirror of the server `produce_listings` table (backend/migrations/003_marketplace.sql).
 *
 * Listings can be drafted offline, so they carry the [syncStatus]/[pendingDelete]
 * bookkeeping. The contract's create-listing payload has no `clientId`, so idempotency is
 * keyed off the local [listingId] plus an `Idempotency-Key` header rather than a dedicated
 * column. `farmer_name` is a denormalised convenience for the marketplace card.
 */
@Entity(
    tableName = "produce_listings",
    indices = [
        Index(value = ["farmer_id"]),
        Index(value = ["status"]),
    ],
)
data class ProduceListingEntity(
    @PrimaryKey
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "produce")
    val produce: String,
    @ColumnInfo(name = "description")
    val description: String? = null,
    @ColumnInfo(name = "quantity")
    val quantity: Double,
    @ColumnInfo(name = "reserved_qty")
    val reservedQty: Double = 0.0,
    @ColumnInfo(name = "sold_qty")
    val soldQty: Double = 0.0,
    @ColumnInfo(name = "unit")
    val unit: Unit = Unit.KG,
    @ColumnInfo(name = "price")
    val price: Double,
    @ColumnInfo(name = "currency")
    val currency: String = "BWP",
    @ColumnInfo(name = "status")
    val status: ListingStatus,
    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,
    @ColumnInfo(name = "location_text")
    val locationText: String? = null,
    @ColumnInfo(name = "published_at")
    val publishedAt: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    @ColumnInfo(name = "farmer_name")
    val farmerName: String? = null,
    @ColumnInfo(name = "pending_delete", defaultValue = "0")
    val pendingDelete: Boolean = false,
) {
    fun toDomain(): ProduceListing = ProduceListing(
        listingId = listingId,
        farmerId = farmerId,
        produce = produce,
        description = description,
        quantity = quantity,
        reservedQty = reservedQty,
        soldQty = soldQty,
        unit = unit,
        price = price,
        currency = currency,
        status = status,
        imageUrl = imageUrl,
        locationText = locationText,
        publishedAt = publishedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
        farmerName = farmerName,
    )

    companion object {
        fun fromDomain(domain: ProduceListing, pendingDelete: Boolean = false): ProduceListingEntity =
            ProduceListingEntity(
                listingId = domain.listingId,
                farmerId = domain.farmerId,
                produce = domain.produce,
                description = domain.description,
                quantity = domain.quantity,
                reservedQty = domain.reservedQty,
                soldQty = domain.soldQty,
                unit = domain.unit,
                price = domain.price,
                currency = domain.currency,
                status = domain.status,
                imageUrl = domain.imageUrl,
                locationText = domain.locationText,
                publishedAt = domain.publishedAt,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                syncStatus = domain.syncStatus,
                farmerName = domain.farmerName,
                pendingDelete = pendingDelete,
            )
    }
}
