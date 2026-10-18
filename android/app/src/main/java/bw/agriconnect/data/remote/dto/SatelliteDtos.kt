package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire types for the Sentinel-2 NDVI feature (docs/02-API-CONTRACT.md §2.6).
 *
 * `compressedVectorJson` is a GeoJSON Feature produced by the backend; it is kept
 * as a [JsonElement] on the wire and flattened to a string for the domain model
 * (which persists it as opaque JSON, not as a typed geometry).
 */

@Serializable
data class NdviDto(
    val ndviId: String,
    val plotId: String,
    val farmerId: String? = null,
    /** Date-only (`YYYY-MM-DD`); null while processing or on failure. */
    val satelliteDate: String? = null,
    val meanNdviValue: Double? = null,
    /** `HIGH_STRESS` | `MODERATE_STRESS` | `HEALTHY_VEGETATION`; null when unavailable. */
    val stressLevel: String? = null,
    /** `REQUESTED` | `FETCHING_IMAGERY` | `COMPUTING_NDVI` | `GENERATED` | `FAILED`. */
    val status: String,
    val failureReason: String? = null,
    val cloudCover: Double? = null,
    val compressedVectorJson: JsonElement? = null,
    val isCached: Boolean = false,
    /** `SENTINEL-2` in production, `MOCK` when Earth Engine is not configured. */
    val source: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class NdviRequest(
    val plotId: String,
    /** Optional: when omitted the backend uses the plot's stored boundary. */
    val geoJsonBoundary: JsonElement? = null,
)

/**
 * `GET /satellite/ndvi/latest` returns the newest observation nested under `ndvi`,
 * or `ndvi: null` when the plot has no observation yet. The explicit null lets the
 * UI render an empty state instead of treating it as an error.
 */
@Serializable
data class NdviResponse(val ndvi: NdviDto? = null)
