package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.Order
import bw.agriconnect.domain.model.OrderStatus

/**
 * Local cache of the server `orders` table (backend/migrations/003_marketplace.sql).
 *
 * Orders are created and transitioned server-side inside a `SELECT … FOR UPDATE`
 * transaction (docs/02-API-CONTRACT.md §2.8): there is no offline order mutation, so this
 * table is read-mostly and has no sync columns. It exists purely so the order list and
 * detail screens work without a network round-trip.
 */
@Entity(
    tableName = "orders",
    indices = [
        Index(value = ["buyer_id"]),
        Index(value = ["farmer_id"]),
        Index(value = ["listing_id"]),
        Index(value = ["status"]),
    ],
)
data class OrderEntity(
    @PrimaryKey
    @ColumnInfo(name = "order_id")
    val orderId: String,
    @ColumnInfo(name = "listing_id")
    val listingId: String,
    @ColumnInfo(name = "buyer_id")
    val buyerId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "quantity")
    val quantity: Double,
    @ColumnInfo(name = "unit_price")
    val unitPrice: Double,
    @ColumnInfo(name = "total_price")
    val totalPrice: Double,
    @ColumnInfo(name = "currency")
    val currency: String = "BWP",
    @ColumnInfo(name = "status")
    val status: OrderStatus,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "buyer_contact")
    val buyerContact: String? = null,
    @ColumnInfo(name = "produce")
    val produce: String? = null,
    @ColumnInfo(name = "buyer_name")
    val buyerName: String? = null,
    @ColumnInfo(name = "farmer_decision_at")
    val farmerDecisionAt: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
) {
    fun toDomain(): Order = Order(
        orderId = orderId,
        listingId = listingId,
        buyerId = buyerId,
        farmerId = farmerId,
        quantity = quantity,
        unitPrice = unitPrice,
        totalPrice = totalPrice,
        currency = currency,
        status = status,
        note = note,
        buyerContact = buyerContact,
        produce = produce,
        buyerName = buyerName,
        farmerDecisionAt = farmerDecisionAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromDomain(domain: Order): OrderEntity = OrderEntity(
            orderId = domain.orderId,
            listingId = domain.listingId,
            buyerId = domain.buyerId,
            farmerId = domain.farmerId,
            quantity = domain.quantity,
            unitPrice = domain.unitPrice,
            totalPrice = domain.totalPrice,
            currency = domain.currency,
            status = domain.status,
            note = domain.note,
            buyerContact = domain.buyerContact,
            produce = domain.produce,
            buyerName = domain.buyerName,
            farmerDecisionAt = domain.farmerDecisionAt,
            createdAt = domain.createdAt,
            updatedAt = domain.updatedAt,
        )
    }
}
