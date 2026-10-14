package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.Expense
import bw.agriconnect.domain.model.SyncStatus

/**
 * Local mirror of the server `expenses` table. Farmer-only writes
 * (docs/02-API-CONTRACT.md §2.5). Money is stored as `Double` locally and formatted for
 * display elsewhere; amounts are BWP (brief §11).
 */
@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["farmer_id", "client_id"], unique = true),
        Index(value = ["farmer_id", "expense_date"]),
    ],
)
data class ExpenseEntity(
    @PrimaryKey
    @ColumnInfo(name = "expense_id")
    val expenseId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "description")
    val description: String? = null,
    @ColumnInfo(name = "amount")
    val amount: Double,
    @ColumnInfo(name = "currency")
    val currency: String = "BWP",
    @ColumnInfo(name = "expense_date")
    val expenseDate: String,
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
    fun toDomain(): Expense = Expense(
        expenseId = expenseId,
        farmerId = farmerId,
        category = category,
        description = description,
        amount = amount,
        currency = currency,
        expenseDate = expenseDate,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
        clientId = clientId,
    )

    companion object {
        fun fromDomain(domain: Expense, pendingDelete: Boolean = false): ExpenseEntity =
            ExpenseEntity(
                expenseId = domain.expenseId,
                farmerId = domain.farmerId,
                category = domain.category,
                description = domain.description,
                amount = domain.amount,
                currency = domain.currency,
                expenseDate = domain.expenseDate,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                syncStatus = domain.syncStatus,
                clientId = domain.clientId,
                pendingDelete = pendingDelete,
            )
    }
}
