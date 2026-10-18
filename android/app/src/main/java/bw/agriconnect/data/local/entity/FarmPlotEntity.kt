package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.SyncStatus

/**
 * Local mirror of the server `farm_plots` table (backend/migrations/002_domain.sql).
 *
 * Column names are snake_case to match the server so schema reviews can diff the two
 * directly. The `(farmer_id, client_id)` unique index enforces offline idempotency: the
 * device-generated [clientId] can be retried any number of times without creating a
 * duplicate plot locally. SQLite treats NULLs as distinct, so plots pulled from the
 * server (which may not carry a client id) are still allowed many per farmer.
 *
 * [pendingDelete] is a tombstone flag. A record is never hard-deleted while it may still
 * hold unsynced work — that is how logout/deletion cannot silently discard a user's data
 * (brief §4E/§14).
 */
@Entity(
    tableName = "farm_plots",
    indices = [
        Index(value = ["farmer_id", "client_id"], unique = true),
        Index(value = ["farmer_id"]),
    ],
)
data class FarmPlotEntity(
    @PrimaryKey
    @ColumnInfo(name = "plot_id")
    val plotId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "plot_name")
    val plotName: String,
    @ColumnInfo(name = "area_hectares")
    val areaHectares: Double?,
    @ColumnInfo(name = "geojson_boundary")
    val geoJsonBoundary: String?,
    @ColumnInfo(name = "centroid_lat")
    val centroidLat: Double? = null,
    @ColumnInfo(name = "centroid_lng")
    val centroidLng: Double? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,
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
    /** Maps the stored row back to the domain model (the sync/UI contract). */
    fun toDomain(): FarmPlot = FarmPlot(
        plotId = plotId,
        farmerId = farmerId,
        plotName = plotName,
        areaHectares = areaHectares,
        geoJsonBoundary = geoJsonBoundary,
        centroidLat = centroidLat,
        centroidLng = centroidLng,
        notes = notes,
        imageUrl = imageUrl,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
        clientId = clientId,
    )

    companion object {
        /** Maps a domain model into its stored form. See [FarmPlot] for field semantics. */
        fun fromDomain(domain: FarmPlot, pendingDelete: Boolean = false): FarmPlotEntity =
            FarmPlotEntity(
                plotId = domain.plotId,
                farmerId = domain.farmerId,
                plotName = domain.plotName,
                areaHectares = domain.areaHectares,
                geoJsonBoundary = domain.geoJsonBoundary,
                centroidLat = domain.centroidLat,
                centroidLng = domain.centroidLng,
                notes = domain.notes,
                imageUrl = domain.imageUrl,
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                syncStatus = domain.syncStatus,
                clientId = domain.clientId,
                pendingDelete = pendingDelete,
            )
    }
}
