package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.SyncStatus

/**
 * Local mirror of the server `harvest_records` table. Writable by farmers and (through
 * their employer link) farm employees. Carries the same offline tombstone/sync bookkeeping
 * as [FarmPlotEntity] so an unsynced harvest survives logout.
 */
@Entity(
    tableName = "harvest_records",
    indices = [
        Index(value = ["farmer_id", "client_id"], unique = true),
        Index(value = ["farmer_id", "harvest_date"]),
    ],
)
data class HarvestRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "harvest_id")
    val harvestId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "plot_id")
    val plotId: String? = null,
    @ColumnInfo(name = "crop_type")
    val cropType: String,
    @ColumnInfo(name = "quantity_kg")
    val quantityKg: Double,
    @ColumnInfo(name = "harvest_date")
    val harvestDate: String,
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
    fun toDomain(): HarvestRecord = HarvestRecord(
        harvestId = harvestId,
        farmerId = farmerId,
        plotId = plotId,
        cropType = cropType,
        quantityKg = quantityKg,
        harvestDate = harvestDate,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
        clientId = clientId,
    )

    companion object {
        fun fromDomain(domain: HarvestRecord, pendingDelete: Boolean = false): HarvestRecordEntity =
            HarvestRecordEntity(
                harvestId = domain.harvestId,
                farmerId = domain.farmerId,
                plotId = domain.plotId,
                cropType = domain.cropType,
                quantityKg = domain.quantityKg,
                harvestDate = domain.harvestDate,
                notes = domain.notes,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                syncStatus = domain.syncStatus,
                clientId = domain.clientId,
                pendingDelete = pendingDelete,
            )
    }
}
