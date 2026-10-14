package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.SaleTransaction
import bw.agriconnect.domain.model.SyncStatus

/**
 * Local mirror of the server `sale_transactions` table. Farmer-only writes
 * (docs/02-API-CONTRACT.md §2.5); the role check happens server-side, but offline rows are
 * still scoped by `farmer_id` so an employee device can never surface another farm's sales.
 */
@Entity(
    tableName = "sale_transactions",
    indices = [
        Index(value = ["farmer_id", "client_id"], unique = true),
        Index(value = ["farmer_id", "sale_date"]),
    ],
)
data class SaleTransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "item")
    val item: String,
    @ColumnInfo(name = "quantity")
    val quantity: Double,
    @ColumnInfo(name = "unit_price")
    val unitPrice: Double? = null,
    @ColumnInfo(name = "amount")
    val amount: Double,
    @ColumnInfo(name = "currency")
    val currency: String = "BWP",
    @ColumnInfo(name = "buyer_name")
    val buyerName: String? = null,
    @ColumnInfo(name = "sale_date")
    val date: String,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    @ColumnInfo(name = "client_id")
    val clientId: String? = null,
    @ColumnInfo(name = "pending_delete", defaultValue = "0")
    val pendingDelete: Boolean = false,
) {
    fun toDomain(): SaleTransaction = SaleTransaction(
        transactionId = transactionId,
        farmerId = farmerId,
        item = item,
        quantity = quantity,
        unitPrice = unitPrice,
        amount = amount,
        currency = currency,
        buyerName = buyerName,
        date = date,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
        clientId = clientId,
    )

    companion object {
        fun fromDomain(domain: SaleTransaction, pendingDelete: Boolean = false): SaleTransactionEntity =
            SaleTransactionEntity(
                transactionId = domain.transactionId,
                farmerId = domain.farmerId,
                item = domain.item,
                quantity = domain.quantity,
                unitPrice = domain.unitPrice,
                amount = domain.amount,
                currency = domain.currency,
                buyerName = domain.buyerName,
                date = domain.date,
                notes = domain.notes,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                syncStatus = domain.syncStatus,
                clientId = domain.clientId,
                pendingDelete = pendingDelete,
            )
    }
}
