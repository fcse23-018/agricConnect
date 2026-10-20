package bw.agriconnect.domain.model.satellite

import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.domain.model.FarmPlot

/**
 * Complete satellite monitoring dashboard for one plot.
 *
 * Mirrors `GET /api/v1/satellite/overview`. Field names and types follow the
 * server contract documented in `docs/02-API-CONTRACT.md`; nothing here is
 * invented on the device.
 */
data class SatelliteOverview(
    val plot: FarmPlot,
    val geometry: PlotGeometry,
    val observationStatus: ObservationStatus,
    val observations: List<PlotObservation>,
    val summary: MonitoringSummary,
    val growthCycle: GrowthCycle?,
    val drops: List<VegetationDrop>,
    val weather: WeatherData?,
    val alerts: List<PlotAlert>,
    val quality: MonitoringQuality?,
    val modelStatus: ModelStatus?,
    val thresholds: Map<String, Double>,
    val thresholdDocumentation: String?,
    /** "Indices indicate condition, they do not prove yield or disease." */
    val disclaimer: String? = null,
    /** When the server produced this payload (ISO-8601). */
    val generatedAt: String? = null,
)

/** Plot boundary measured on the server from the real geometry. */
data class PlotGeometry(
    val method: GeometryMethod,
    val vertices: List<GeoVertex>,
    val areaM2: Double?,
    val areaHectares: Double?,
    val perimeterM: Double?,
    val centroid: GeoVertex?,
    val sideLengthsM: List<Double>?,
    val valid: Boolean = true,
    /** Why the ring could not be measured, when [valid] is false. */
    val reason: String? = null,
)

enum class GeometryMethod {
    RECTANGLE,
    POLYGON,
    UNKNOWN,
}

/** Provenance and freshness of the observation series. */
data class ObservationStatus(
    val isCached: Boolean,
    val isSimulated: Boolean,
    val newestObservation: String?,
    val newestObservationAgeDays: Int?,
    /** Provider source name (e.g. `SENTINEL-2`, `MOCK`), when a call was made. */
    val provider: String?,
    val providerError: String?,
    val note: String?,
    val returned: Int? = null,
    val coversDays: Int? = null,
    val boundaryUsable: Boolean? = null,
)

/** One observation row (date, NDVI, EVI, quality). */
data class PlotObservation(
    /**
     * The monitoring series is returned without row ids (it is a computed
     * series, not stored client-side), so the client keys rows by plot+date.
     * The value is a stable key, not a server identifier.
     */
    val observationId: String,
    val plotId: String,
    val farmerId: String,
    val observedOn: String,
    val ndvi: Double?,
    val evi: Double?,
    val smoothedNdvi: Double?,
    val smoothedEvi: Double?,
    val validPixelPct: Double?,
    val cloudCoverPct: Double?,
    val pixelCount: Int?,
    val quality: ObservationQuality,
    val isOutlier: Boolean,
    val isSimulated: Boolean,
    val source: String?,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

enum class ObservationQuality { GOOD, FAIR, POOR }

/** Summary statistics, one entry per vegetation index. */
data class MonitoringSummary(
    val ndvi: IndexSummary?,
    val evi: IndexSummary?,
)

/** Common interface for index summaries (NDVI/EVI) with identical fields. */
interface IndexSummary {
    val available: Boolean
    val count: Int
    val min: Double?
    val max: Double?
    val avg: Double?
    val latest: Double?
    val latestDate: String?
    val changeVsPrevious: Double?
    val changeDate: String?
    /** `RISING` | `STABLE` | `DECLINING` | `INSUFFICIENT`. */
    val trend: String?
    val dataAgeDays: Int?
}

data class NdviMonitoringSummary(
    override val available: Boolean,
    override val count: Int,
    override val min: Double?,
    override val max: Double?,
    override val avg: Double?,
    override val latest: Double?,
    override val latestDate: String?,
    override val changeVsPrevious: Double?,
    override val changeDate: String?,
    override val trend: String?,
    override val dataAgeDays: Int?,
) : IndexSummary

data class EviMonitoringSummary(
    override val available: Boolean,
    override val count: Int,
    override val min: Double?,
    override val max: Double?,
    override val avg: Double?,
    override val latest: Double?,
    override val latestDate: String?,
    override val changeVsPrevious: Double?,
    override val changeDate: String?,
    override val trend: String?,
    override val dataAgeDays: Int?,
) : IndexSummary

/**
 * Estimated key phenological dates from the smoothed series.
 *
 * Every date is an **estimate** between satellite passes; the server reports a
 * single confidence for the whole cycle plus the evidence it used, and returns
 * `available: false` with a reason instead of guessing.
 */
data class GrowthCycle(
    val available: Boolean,
    val reason: String?,
    val greenUp: String?,
    val peak: String?,
    val endOfSeason: String?,
    val peakValue: Double?,
    val baseline: Double?,
    val amplitude: Double?,
    val confidence: ConfidenceLevel?,
    val strategy: String?,
    val evidence: List<String>,
)

/** A detected drop with possible explanations - never a confirmed diagnosis. */
data class VegetationDrop(
    val date: String,
    val previousDate: String?,
    val daysBetween: Int?,
    /** Index the drop was measured on (the server reports both pairs). */
    val index: VegetationIndex,
    val magnitude: Double?,
    val valueBefore: Double?,
    val valueAfter: Double?,
    val relativePct: Double?,
    val classification: VegetationDropClassification?,
    val label: String?,
    val explanation: String?,
    /** Bullet points of evidence, in the server's words. */
    val evidence: List<String>,
    val confidence: ConfidenceLevel?,
    val nextStep: String?,
    val disclaimer: String?,
    val weatherContext: Map<String, Any>?,
    val recoveryFraction: Double?,
    val recoveredOn: String?,
    val gradual: Boolean,
    val detectedEventId: String?,
)

enum class VegetationIndex { NDVI, EVI }

/** The five explanations the analysis engine is allowed to offer. */
enum class VegetationDropClassification {
    TRANSIENT_DIP,
    POSSIBLE_HARVEST,
    POSSIBLE_HEAT_OR_DRY_STRESS,
    GRADUAL_DECLINE,
    UNCLEAR,
}

/** Farmer event log entry (confirmed or corrected by the farmer). */
data class PlotEvent(
    val eventId: String,
    val plotId: String,
    val farmerId: String,
    val eventDate: String,
    val kind: PlotEventKind,
    val source: PlotEventSource,
    val classification: String?,
    val confirmed: Boolean,
    val notes: String?,
    val ndviBefore: Double?,
    val ndviAfter: Double?,
    val magnitude: Double?,
    val confidence: ConfidenceLevel?,
    val evidence: Map<String, Any>?,
    val weatherContext: Map<String, Any>?,
    val recordedBy: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

enum class PlotEventKind {
    DETECTED_DROP,
    HARVEST,
    CLEARING,
    HEAT_STRESS,
    WATER_STRESS,
    OBSERVATION,
    OTHER,
}

enum class PlotEventSource {
    FARMER,
    SYSTEM,
}

/**
 * Deduplicated alert with acknowledgement/dismissal workflow.
 *
 * [plotId]/[farmerId] are null for alerts embedded in the overview payload
 * (which does not repeat them); the dedicated alerts endpoint always sends them.
 */
data class PlotAlert(
    val alertId: String,
    val plotId: String?,
    val farmerId: String?,
    val alertKey: String,
    val alertType: String,
    val severity: AlertSeverity,
    val title: String,
    val body: String,
    val status: AlertStatus,
    val raisedAt: String?,
    val resolvedAt: String?,
    val acknowledgedAt: String?,
    val dismissedAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }

enum class AlertStatus { OPEN, ACKNOWLEDGED, DISMISSED }

/**
 * Weather for the plot centroid.
 *
 * `MODELLED` from Open-Meteo: an estimate, not a field sensor reading. When the
 * provider is disabled or unreachable the server reports [available] = false
 * with a [reason] instead of inventing numbers.
 */
data class WeatherData(
    val available: Boolean,
    val reason: String?,
    val source: String?,
    val dataType: WeatherDataType,
    val daily: List<WeatherDaily>,
    val summary: WeatherSummary?,
    val note: String?,
    val fetchedAt: String?,
    val cached: Boolean,
)

enum class WeatherDataType { MEASURED, MODELLED, ESTIMATED }

data class WeatherDaily(
    val date: String,
    val rainfallMm: Double?,
    val tempMinC: Double?,
    val tempMaxC: Double?,
    val tempAvgC: Double?,
    /** Top-soil moisture (0-7 cm) as a percentage. */
    val soilMoisturePct: Double?,
)

/** Server-side period totals for the returned weather window. */
data class WeatherSummary(
    val periodStart: String?,
    val periodEnd: String?,
    val rainfall7dMm: Double?,
    val rainfall14dMm: Double?,
    val tempMax7dC: Double?,
    val tempMin7dC: Double?,
    val avgTemp7dC: Double?,
    val soilMoistureMeanPct: Double?,
)

/** How much of the stored series was usable, and why the rest was rejected. */
data class MonitoringQuality(
    val readingsReceived: Int,
    val readingsUsable: Int,
    val readingsRejected: Int,
    val outliers: Int,
    val note: String?,
)

/** Model training readiness - never a claim of a trained model. */
data class ModelStatus(
    val trained: Boolean,
    val readyToTrain: Boolean,
    val labelledEvents: Int,
    val requiredLabels: Int,
    val note: String?,
)

/** Boundary preview result (before saving). */
data class BoundaryPreview(
    val geometry: PlotGeometry,
    val note: String? = null,
)

/** Result of setting a plot boundary. */
data class SetBoundaryResult(
    val plot: FarmPlot,
    val geometry: PlotGeometry,
    /** Observations dropped because they covered the old outline. */
    val observationsInvalidated: Int,
    val note: String?,
)

/** Newest stored observation for one plot (`GET /satellite/plots/latest`). */
data class PlotSummary(
    val plotId: String,
    val observedOn: String?,
    val ndvi: Double?,
    val evi: Double?,
    val quality: String?,
    val isSimulated: Boolean?,
    val source: String?,
)

enum class ConfidenceLevel { HIGH, MEDIUM, LOW }
