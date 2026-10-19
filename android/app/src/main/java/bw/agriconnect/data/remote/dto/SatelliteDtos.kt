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
    val advice: NdviAdviceDto? = null,
)

/**
 * The advisory block the backend attaches to a GENERATED reading
 * (`backend/src/services/ndviAdvice.js`). Every field is optional here because a
 * client must survive an older or partial server: a missing piece renders as absent
 * rather than crashing the screen.
 */
@Serializable
data class NdviAdviceDto(
    val assessment: NdviAssessmentDto? = null,
    /** `NOW` | `SOON` | `ROUTINE`. */
    val priority: String? = null,
    val actions: List<NdviActionDto> = emptyList(),
    val watchFor: List<String> = emptyList(),
    val trend: NdviTrendDto? = null,
    val context: NdviAdviceContextDto? = null,
    val confidence: NdviConfidenceDto? = null,
    /** The published NDVI ranges the reading sits within. */
    val scaleGuide: List<NdviScaleRowDto> = emptyList(),
    val limits: List<String> = emptyList(),
    val disclaimer: String? = null,
)

@Serializable
data class NdviAssessmentDto(
    val value: Double? = null,
    /** `HIGH_STRESS` | `MODERATE_STRESS` | `HEALTHY_VEGETATION`. */
    val stressLevel: String? = null,
    val label: String? = null,
    /** `success` | `warning` | `error` | `neutral`. */
    val colour: String? = null,
    val headline: String? = null,
    val meaning: String? = null,
    val band: NdviBandDto? = null,
    val summary: String? = null,
)

@Serializable
data class NdviBandDto(val min: Double? = null, val max: Double? = null)

@Serializable
data class NdviActionDto(val title: String? = null, val detail: String? = null)

@Serializable
data class NdviTrendDto(
    /** `FIRST_READING` | `IMPROVING` | `DECLINING` | `STABLE`. */
    val direction: String? = null,
    val delta: Double? = null,
    val previousValue: Double? = null,
    val previousDate: String? = null,
    val note: String? = null,
)

@Serializable
data class NdviAdviceContextDto(
    val plotName: String? = null,
    val areaHectares: Double? = null,
    val observedOn: String? = null,
    val daysSinceObservation: Int? = null,
    val cloudCoverPercent: Double? = null,
    val source: String? = null,
    val sourceIsSimulated: Boolean = false,
)

@Serializable
data class NdviConfidenceDto(val level: String? = null, val note: String? = null)

@Serializable
data class NdviScaleRowDto(val range: String? = null, val meaning: String? = null)

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
