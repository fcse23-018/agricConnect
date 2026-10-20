package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the Sentinel-2 NDVI feature (docs/02-API-CONTRACT.md §2.6).
 *
 * `compressedVectorJson` is a GeoJSON Feature produced by the backend; it is kept
 * as a [JsonElement] on the wire and flattened to a string for the domain model
 * (which persists it as opaque JSON, not as a typed geometry).
 *
 * The monitoring block below mirrors the **actual** server payloads produced by
 * `backend/src/modules/satellite` (routes, service, schemas). Every field the
 * server omits has a default
 * here, so an older or partial server degrades to absent values instead of
 * throwing a parse error.
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

/* =========================================================================
 * Satellite Monitoring — Unified Dashboard (§2.6.2)
 * ========================================================================= */

/** A latitude/longitude pair as the server writes it: `{"lat":…,"lon":…}`. */
@Serializable
data class LatLonDto(val lat: Double, val lon: Double)

/**
 * The compact plot reference the monitoring endpoints return (`plotId`,
 * `plotName`, area, centroid, boundary, photo). It deliberately carries no
 * ownership or timestamp fields - those live on the farms endpoints.
 */
@Serializable
data class PlotRefDto(
    val plotId: String,
    val plotName: String = "",
    val areaHectares: Double? = null,
    val centroidLat: Double? = null,
    val centroidLng: Double? = null,
    /** GeoJSON Polygon/Feature object, stored verbatim. */
    val geoJsonBoundary: JsonElement? = null,
    val imageUrl: String? = null,
)

@Serializable
data class SatelliteOverviewDto(
    val plot: PlotRefDto,
    val geometry: PlotGeometryDto,
    val observationStatus: ObservationStatusDto,
    val observations: List<PlotObservationDto> = emptyList(),
    val summary: IndexStatsDto,
    val eviSummary: IndexStatsDto? = null,
    val growthCycle: GrowthCycleDto? = null,
    val drops: List<VegetationDropDto> = emptyList(),
    val weather: WeatherDataDto? = null,
    val alerts: List<PlotAlertDto> = emptyList(),
    val quality: QualityDto? = null,
    val modelStatus: ModelStatusDto? = null,
    val thresholds: Map<String, Double> = emptyMap(),
    val thresholdDocumentation: String? = null,
    val disclaimer: String? = null,
    val generatedAt: String? = null,
)

/**
 * A boundary as measured on the server (`services/geometry.js`):
 * `method`, `vertices`, `vertexCount`, `areaM2`, `areaHectares`, `perimeterM`,
 * `centroid {latitude,longitude}`, `sideLengthsM`, `valid`, `reason`.
 */
@Serializable
data class PlotGeometryDto(
    val method: String? = null,
    val vertices: List<LatLonDto> = emptyList(),
    val vertexCount: Int? = null,
    val areaM2: Double? = null,
    val areaHectares: Double? = null,
    val perimeterM: Double? = null,
    val centroid: LatLonDto? = null,
    val sideLengthsM: List<Double>? = null,
    val valid: Boolean = true,
    val reason: String? = null,
)

/** What the satellite provider said, independent of what is stored. */
@Serializable
data class ProviderStatusDto(
    val available: Boolean = false,
    val source: String? = null,
    val isSimulated: Boolean = false,
    val returned: Int = 0,
    val note: String? = null,
    val providerFailure: String? = null,
)

@Serializable
data class ObservationStatusDto(
    val provider: ProviderStatusDto? = null,
    val providerError: String? = null,
    val returned: Int = 0,
    val isCached: Boolean = false,
    val isSimulated: Boolean = false,
    val coversDays: Int? = null,
    val boundaryUsable: Boolean? = null,
    val newestObservation: String? = null,
    val newestObservationAgeDays: Int? = null,
    val note: String? = null,
)

/**
 * One row of the returned series. The monitoring series has no row ids - it is
 * computed from stored observations - so the client builds its own stable key.
 */
@Serializable
data class PlotObservationDto(
    val date: String,
    val ndvi: Double? = null,
    val evi: Double? = null,
    val smoothedNdvi: Double? = null,
    val smoothedEvi: Double? = null,
    val validPct: Double? = null,
    val cloudPct: Double? = null,
    val pixels: Int? = null,
    /** `GOOD` | `FAIR` | `POOR`. */
    val quality: String = "GOOD",
    val isOutlier: Boolean = false,
    val isSimulated: Boolean = false,
    val source: String? = null,
)

/** One reading inside a summary (`latest`/`previous`). */
@Serializable
data class SeriesValueDto(
    val date: String? = null,
    val ndvi: Double? = null,
    val evi: Double? = null,
)

/** Statistics for one vegetation index over the usable series. */
@Serializable
data class IndexStatsDto(
    val available: Boolean = false,
    val reason: String? = null,
    val count: Int = 0,
    val latest: SeriesValueDto? = null,
    val previous: SeriesValueDto? = null,
    val min: Double? = null,
    val max: Double? = null,
    val average: Double? = null,
    val change: Double? = null,
    val changePct: Double? = null,
    /** `RISING` | `STABLE` | `DECLINING` | `INSUFFICIENT`. */
    val trend: String? = null,
    val dataAgeDays: Int? = null,
    val firstDate: String? = null,
    val lastDate: String? = null,
    val coverageFrom: String? = null,
    val coverageTo: String? = null,
)

@Serializable
data class GrowthCycleDto(
    val available: Boolean = false,
    val reason: String? = null,
    val greenUpOn: String? = null,
    val peakOn: String? = null,
    val endOn: String? = null,
    val peakValue: Double? = null,
    val baseline: Double? = null,
    val amplitude: Double? = null,
    /** `HIGH` | `MEDIUM` | `LOW` - confidence of the cycle as a whole. */
    val confidence: String? = null,
    val strategy: String? = null,
    val evidence: List<String> = emptyList(),
)

/**
 * A detected vegetation drop with its possible explanations.
 *
 * `classification`, `label`, `explanation`, `confidence` and `nextStep` are null
 * when the engine could not derive an explanation; the client must show that as
 * "no explanation" rather than inventing one.
 */
@Serializable
data class VegetationDropDto(
    val date: String,
    val previousDate: String? = null,
    val daysBetween: Int? = null,
    val ndviBefore: Double? = null,
    val ndviAfter: Double? = null,
    val eviBefore: Double? = null,
    val eviAfter: Double? = null,
    val magnitude: Double? = null,
    val relativePct: Double? = null,
    val quality: String? = null,
    val recoveryFraction: Double? = null,
    val recoveredOn: String? = null,
    val gradual: Boolean = false,
    /** `TRANSIENT_DIP` | `POSSIBLE_HARVEST` | `POSSIBLE_HEAT_OR_DRY_STRESS` | `GRADUAL_DECLINE` | `UNCLEAR`. */
    val classification: String? = null,
    val label: String? = null,
    val explanation: String? = null,
    /** Array of evidence sentences. */
    val evidence: JsonElement? = null,
    val confidence: String? = null,
    val nextStep: String? = null,
    val weatherContext: JsonElement? = null,
    val disclaimer: String? = null,
    val detectedEventId: String? = null,
)

@Serializable
data class PlotEventDto(
    val eventId: String,
    val plotId: String,
    val farmerId: String,
    val eventDate: String,
    /** `DETECTED_DROP` | `HARVEST` | `CLEARING` | `HEAT_STRESS` | `WATER_STRESS` | `OBSERVATION` | `OTHER`. */
    val kind: String,
    val source: String = "FARMER", // FARMER | SYSTEM
    val classification: String? = null,
    val confirmed: Boolean = false,
    val notes: String? = null,
    val ndviBefore: Double? = null,
    val ndviAfter: Double? = null,
    val magnitude: Double? = null,
    val confidence: String? = null, // HIGH | MEDIUM | LOW
    val evidence: JsonObject? = null,
    val weatherContext: JsonObject? = null,
    val detectedKey: String? = null,
    val clientId: String? = null,
    val recordedBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * `plotId`/`farmerId` are present on the alerts endpoint but absent from the
 * overview payload, which does not repeat them.
 */
@Serializable
data class PlotAlertDto(
    val alertId: String,
    val plotId: String? = null,
    val farmerId: String? = null,
    val alertKey: String = "",
    val alertType: String = "",
    val severity: String, // INFO | WARNING | CRITICAL
    val title: String,
    val body: String,
    val status: String = "OPEN", // OPEN | ACKNOWLEDGED | DISMISSED
    val raisedAt: String? = null,
    val resolvedAt: String? = null,
    val acknowledgedAt: String? = null,
    val dismissedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** `GET /satellite/weather` wraps the weather block. */
@Serializable
data class WeatherResponseDto(
    val plotId: String? = null,
    val weather: WeatherDataDto? = null,
    val source: String? = null,
    val dataType: String? = null,
)

@Serializable
data class WeatherDataDto(
    val available: Boolean = false,
    val reason: String? = null,
    val provider: String? = null,
    val source: String? = null,
    /** `MEASURED` | `MODELLED` | `ESTIMATED`. */
    val dataType: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val daily: List<WeatherDailyDto> = emptyList(),
    val summary: WeatherSummaryDto? = null,
    val fetchedAt: String? = null,
    val note: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class WeatherDailyDto(
    val date: String,
    val rainfallMm: Double? = null,
    val tempMaxC: Double? = null,
    val tempMinC: Double? = null,
    val tempMeanC: Double? = null,
    /** Top-soil moisture (0-7 cm) in percent. */
    val soilMoisturePct: Double? = null,
)

@Serializable
data class WeatherSummaryDto(
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val rainfall7dMm: Double? = null,
    val rainfall14dMm: Double? = null,
    val tempMax7dC: Double? = null,
    val tempMin7dC: Double? = null,
    val avgTemp7dC: Double? = null,
    val soilMoistureMeanPct: Double? = null,
)

@Serializable
data class QualityDto(
    val readingsReceived: Int = 0,
    val readingsUsable: Int = 0,
    val readingsRejected: Int = 0,
    val outliers: Int = 0,
    val note: String? = null,
)

@Serializable
data class ModelStatusDto(
    val trained: Boolean = false,
    val readyToTrain: Boolean = false,
    val labelledEvents: Int = 0,
    val requiredLabels: Int = 0,
    val note: String? = null,
)

/* ------------------------------ geometry editing -------------------------- */

/** A boundary as the farmer is drawing it: a rectangle or a polygon. */
@Serializable
data class BoundaryDto(
    /** `RECTANGLE` | `POLYGON`. */
    val method: String,
    /** Method A - rotated rectangle. */
    val centreLat: Double? = null,
    val centreLon: Double? = null,
    val widthM: Double? = null,
    val lengthM: Double? = null,
    val bearingDeg: Double? = null,
    /** Method B - polygon ring, at least three vertices. */
    val vertices: List<LatLonDto>? = null,
) {
    companion object {
        /** Method A - a rectangle the farmer can rotate, resize and reposition. */
        fun rectangle(
            centreLat: Double,
            centreLon: Double,
            widthM: Double,
            lengthM: Double,
            bearingDeg: Double,
        ): BoundaryDto = BoundaryDto(
            method = "RECTANGLE",
            centreLat = centreLat,
            centreLon = centreLon,
            widthM = widthM,
            lengthM = lengthM,
            bearingDeg = bearingDeg,
        )

        /** Method B - a custom polygon ring (at least three vertices). */
        fun polygon(vertices: List<LatLonDto>): BoundaryDto = BoundaryDto(
            method = "POLYGON",
            vertices = vertices,
        )
    }
}

@Serializable
data class BoundaryPreviewRequest(
    val boundary: BoundaryDto,
)

@Serializable
data class BoundaryPreviewResponse(
    val method: String? = null,
    val geometry: PlotGeometryDto,
    val vertices: List<LatLonDto> = emptyList(),
    val areaM2: Double? = null,
    val areaHectares: Double? = null,
    val perimeterM: Double? = null,
    val note: String? = null,
)

@Serializable
data class SetBoundaryRequest(
    val boundary: BoundaryDto,
    val plotName: String? = null,
)

@Serializable
data class SetBoundaryResponse(
    val plot: PlotRefDto,
    val geometry: PlotGeometryDto,
    val observationsInvalidated: Int = 0,
    val note: String? = null,
)

/* --------------------------------- events -------------------------------- */

/**
 * Farmer event creation. Only the fields `createEventSchema` accepts are sent;
 * anything else would be silently stripped by the server, which would make the
 * client believe it stored more than it did.
 */
@Serializable
data class EventCreateRequest(
    val plotId: String,
    /** `YYYY-MM-DD`. */
    val eventDate: String,
    val kind: String, // HARVEST | CLEARING | HEAT_STRESS | WATER_STRESS | OBSERVATION | OTHER
    val notes: String? = null,
    /** Idempotency key for an offline queue replaying the same event. */
    val clientId: String? = null,
    /** Links the entry to a detected drop so the two become one record. */
    val detectedEventId: String? = null,
)

@Serializable
data class EventUpdateRequest(
    val kind: String? = null,
    val confirmed: Boolean? = null,
    val notes: String? = null,
    val eventDate: String? = null,
)

/**
 * Alert status change. The server takes an **action**
 * (`ACKNOWLEDGE` | `DISMISS` | `REOPEN`), not a target status.
 */
@Serializable
data class AlertStatusUpdateRequest(
    val action: String,
)

/* ---------------------------- paged collections --------------------------- */

/** `GET /satellite/events` is paginated. */
@Serializable
data class PagedEventsDto(
    val items: List<PlotEventDto> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val hasMore: Boolean = false,
)

/** `GET /satellite/alerts` is paginated and repeats the alert thresholds. */
@Serializable
data class AlertListDto(
    val items: List<PlotAlertDto> = emptyList(),
    val page: Int = 1,
    val pageSize: Int = 20,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val thresholds: Map<String, Double> = emptyMap(),
    val thresholdDocumentation: String? = null,
    val note: String? = null,
)

/** Newest stored observation per plot (`GET /satellite/plots/latest`). */
@Serializable
data class LatestPlotsDto(
    val items: List<PlotObservationSummaryDto> = emptyList(),
    val note: String? = null,
)

@Serializable
data class PlotObservationSummaryDto(
    val plotId: String,
    val observedOn: String? = null,
    val ndvi: Double? = null,
    val evi: Double? = null,
    val quality: String? = null,
    val isSimulated: Boolean? = null,
    val source: String? = null,
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
