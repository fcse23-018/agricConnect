package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.NdviProcessingStatus
import bw.agriconnect.domain.model.SatelliteNdvi
import bw.agriconnect.domain.model.StressLevel

/**
 * Local cache of a server `satellite_ndvi` row (backend/migrations/002_domain.sql).
 *
 * Satellite NDVI is generated server-side from Earth Engine and pulled down; it is never
 * authored offline, so this entity deliberately has **no** `sync_status`/`pending_delete`
 * columns. [plotName] is a denormalised convenience column so the NDVI history list can be
 * rendered without joining `farm_plots` on every emission.
 */
@Entity(
    tableName = "satellite_ndvi",
    indices = [
        Index(value = ["plot_id", "satellite_date"]),
        Index(value = ["farmer_id"]),
    ],
)
data class SatelliteNdviEntity(
    @PrimaryKey
    @ColumnInfo(name = "ndvi_id")
    val ndviId: String,
    @ColumnInfo(name = "plot_id")
    val plotId: String,
    @ColumnInfo(name = "farmer_id")
    val farmerId: String,
    @ColumnInfo(name = "satellite_date")
    val satelliteDate: String? = null,
    @ColumnInfo(name = "mean_ndvi_value")
    val meanNdviValue: Double? = null,
    @ColumnInfo(name = "stress_level")
    val stressLevel: StressLevel? = null,
    @ColumnInfo(name = "status")
    val status: NdviProcessingStatus,
    @ColumnInfo(name = "failure_reason")
    val failureReason: String? = null,
    @ColumnInfo(name = "cloud_cover")
    val cloudCover: Double? = null,
    @ColumnInfo(name = "compressed_vector_json")
    val compressedVectorJson: String? = null,
    @ColumnInfo(name = "is_cached")
    val isCached: Boolean = true,
    @ColumnInfo(name = "source")
    val source: String = "SENTINEL-2",
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "plot_name")
    val plotName: String? = null,
) {
    fun toDomain(): SatelliteNdvi = SatelliteNdvi(
        ndviId = ndviId,
        plotId = plotId,
        farmerId = farmerId,
        satelliteDate = satelliteDate,
        meanNdviValue = meanNdviValue,
        stressLevel = stressLevel,
        status = status,
        failureReason = failureReason,
        cloudCover = cloudCover,
        compressedVectorJson = compressedVectorJson,
        isCached = isCached,
        source = source,
        createdAt = createdAt,
        plotName = plotName,
    )

    companion object {
        fun fromDomain(domain: SatelliteNdvi): SatelliteNdviEntity = SatelliteNdviEntity(
            ndviId = domain.ndviId,
            plotId = domain.plotId,
            farmerId = domain.farmerId,
            satelliteDate = domain.satelliteDate,
            meanNdviValue = domain.meanNdviValue,
            stressLevel = domain.stressLevel,
            status = domain.status,
            failureReason = domain.failureReason,
            cloudCover = domain.cloudCover,
            compressedVectorJson = domain.compressedVectorJson,
            isCached = domain.isCached,
            source = domain.source,
            createdAt = domain.createdAt,
            plotName = domain.plotName,
        )
    }
}
