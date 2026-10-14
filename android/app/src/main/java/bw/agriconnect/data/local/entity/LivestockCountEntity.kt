package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.LivestockCount
import bw.agriconnect.domain.model.SyncStatus

/**
 * Local mirror of the server `livestock_counts` table. Offline-capable with the same
 * tombstone/sync bookkeeping as [FarmPlotEntity].
 */
@Entity(
    tableName = "livestock_counts",
    indices = [
        Index(value = ["farmer_id", "client_id"], unique = true),
        Index(value = ["farmer_id", "record_date"]),
    ],
)
data class LivestockCountEntity(
    @PrimaryKey
    @ColumnInfo(name = "count_id")
    val countId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "animal_type")
    val animalType: String,
    @ColumnInfo(name = "head_count")
    val headCount: Int,
    @ColumnInfo(name = "record_date")
    val recordDate: String,
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
    fun toDomain(): LivestockCount = LivestockCount(
        countId = countId,
        farmerId = farmerId,
        animalType = animalType,
        headCount = headCount,
        recordDate = recordDate,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
        clientId = clientId,
    )

    companion object {
        fun fromDomain(domain: LivestockCount, pendingDelete: Boolean = false): LivestockCountEntity =
            LivestockCountEntity(
                countId = domain.countId,
                farmerId = domain.farmerId,
                animalType = domain.animalType,
                headCount = domain.headCount,
                recordDate = domain.recordDate,
                notes = domain.notes,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                syncStatus = domain.syncStatus,
                clientId = domain.clientId,
                pendingDelete = pendingDelete,
            )
    }
}
