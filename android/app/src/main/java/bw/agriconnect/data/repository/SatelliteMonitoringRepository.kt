package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.*
import bw.agriconnect.domain.model.satellite.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Satellite monitoring repository — unified dashboard, events, alerts, weather, boundaries.
 *
 * All endpoints are farm-owner only (the server enforces this). The repository mirrors
 * the network call 1:1 and does not cache the monitoring overview (it is a composite
 * view that must reflect the latest server state). Individual pieces like weather
 * are cached by the server with a TTL.
 */
@Singleton
class SatelliteMonitoringRepository @Inject constructor(
    private val api: ApiService,
    private val errorParser: NetworkErrorParser,
) {

    // ============================ Overview ============================

    /**
     * Fetches the complete monitoring dashboard for a plot.
     *
     * This is the single entry point for the Satellite tab — one request returns
     * geometry, observations (NDVI + EVI, raw + smoothed), growth-cycle estimates,
     * detected drops with explanations, weather, alerts, quality metrics, and
     * model status. No fabricated values; every field is honest about its source.
     */
    suspend fun getOverview(
        plotId: String,
        daysBack: Int? = null,
    ): AppResult<SatelliteOverview> =
        runCatchingApi(errorParser::parse) {
            api.getSatelliteOverview(plotId, daysBack).toDomain()
        }

    // ============================ Boundary ============================

    /**
     * Validates a RECTANGLE or POLYGON geometry and returns the computed area,
     * perimeter, centroid, and any validation errors. Does not persist anything.
     */
    suspend fun previewBoundary(request: BoundaryPreviewRequest): AppResult<BoundaryPreview> =
        runCatchingApi(errorParser::parse) {
            api.previewBoundary(request).toPreviewDomain()
        }

    /**
     * Persists a RECTANGLE (centre, width, length, bearing) or POLYGON (vertices)
     * as the plot's boundary. Returns the updated plot with the computed geometry.
     */
    suspend fun setBoundary(
        plotId: String,
        request: SetBoundaryRequest,
    ): AppResult<SetBoundaryResult> =
        runCatchingApi(errorParser::parse) {
            api.setPlotBoundary(plotId, request).toDomain()
        }

    /* -------------------- colour map ("satellite sight") ------------------- */

    /**
     * The per-pixel NDVI (or EVI) colour map for the plot's newest stored
     * observation, or for a specific [date] the plot actually has. Drawn
     * server-side, so the picture always agrees with the numbers on the same
     * screen. Never triggers a provider fetch for the same reason.
     *
     * The result carries the honesty flags ([ColourMap.isSimulated],
     * [ColourMap.variationSimulated], [ColourMap.isStale], [ColourMap.isHistorical])
     * — the UI must surface them, because this is the most persuasive element on
     * the screen.
     */
    suspend fun getColourMap(
        plotId: String,
        index: String = "ndvi",
        size: Int? = null,
        date: String? = null,
    ): AppResult<ColourMap> =
        runCatchingApi(errorParser::parse) {
            api.getColourMap(plotId, index, size, date).toDomain()
        }

    // ============================ Weather ============================

    /**
     * Modelled weather from Open-Meteo for the plot centroid. Source and dataType
     * are always labelled. If the provider is disabled, the response reports
     * unavailability rather than inventing values.
     */
    suspend fun getWeather(
        plotId: String,
        daysBack: Int? = null,
    ): AppResult<WeatherData> =
        runCatchingApi(errorParser::parse) {
            // The endpoint wraps the weather block; a null block means the server
            // could not produce one (e.g. no boundary yet) and is reported as
            // unavailable with a reason instead of as fake numbers.
            api.getWeather(plotId, daysBack).toDomain() ?: WeatherData(
                available = false,
                reason = "The server returned no weather for this plot yet.",
                source = null,
                dataType = WeatherDataType.MODELLED,
                daily = emptyList(),
                summary = null,
                note = null,
                fetchedAt = null,
                cached = false,
            )
        }

    // ============================ Events ============================

    /**
     * Event log for a plot (farmer events + system-detected drops), newest first.
     *
     * One page of 50 covers a season of events; the response reports [total] and
     * `hasMore` server-side, which the UI shows as "showing the most recent 50".
     */
    suspend fun getEvents(
        plotId: String,
        pageSize: Int = 50,
    ): AppResult<List<PlotEvent>> =
        runCatchingApi(errorParser::parse) {
            api.getPlotEvents(plotId, pageSize).items.map { it.toDomain() }
        }

    /**
     * Creates a farmer event. Idempotent via [clientId]: if a row with the same
     * plotId+clientId already exists, the existing row is returned (no 500).
     * Supplying [detectedEventId] marks the detected drop as reviewed/confirmed.
     */
    suspend fun createEvent(request: EventCreateRequest): AppResult<PlotEvent> =
        runCatchingApi(errorParser::parse) {
            api.createPlotEvent(request).toDomain()
        }

    /**
     * Updates a farmer event. A farmer may correct the kind, confirm, add notes,
     * or adjust the date. Supplying a kind counts as confirmation (verified label).
     */
    suspend fun updateEvent(eventId: String, request: EventUpdateRequest): AppResult<PlotEvent> =
        runCatchingApi(errorParser::parse) {
            api.updatePlotEvent(eventId, request).toDomain()
        }

    /** Deletes a farmer event (farm owner only). */
    suspend fun deleteEvent(eventId: String): AppResult<Unit> =
        runCatchingApi(errorParser::parse) {
            api.deletePlotEvent(eventId)
            Unit
        }

    // ============================ Alerts ============================

    /**
     * Alerts for a plot, deduplicated by alert_key (one ongoing event = one alert).
     * Statuses: OPEN, ACKNOWLEDGED, DISMISSED. A dismissed alert is never
     * resurrected by a later analysis run.
     */
    suspend fun getAlerts(
        plotId: String,
        status: String? = null,
    ): AppResult<List<PlotAlert>> =
        runCatchingApi(errorParser::parse) {
            api.getPlotAlerts(plotId, status, pageSize = 50).items.map { it.toDomain() }
        }

    /**
     * Acknowledges or dismisses an alert. The server takes an **action**, not a
     * target status: `ACKNOWLEDGE`, `DISMISS` (terminal - a later analysis run
     * will not flip it back to OPEN) or `REOPEN`.
     */
    suspend fun updateAlertStatus(
        alertId: String,
        status: AlertStatus,
    ): AppResult<PlotAlert> =
        runCatchingApi(errorParser::parse) {
            val action = when (status) {
                AlertStatus.ACKNOWLEDGED -> "ACKNOWLEDGE"
                AlertStatus.DISMISSED -> "DISMISS"
                AlertStatus.OPEN -> "REOPEN"
            }
            api.updateAlertStatus(alertId, AlertStatusUpdateRequest(action)).toDomain()
        }

    /**
     * Farm-wide attention summary: **every** plot the farmer owns, grouped by farm.
     * Answers "which of my farms need me right now?" instead of only describing one
     * selected plot. Plots with no observation at all are included and flagged, so
     * a forgotten field cannot look healthy by being absent from the list.
     */
    suspend fun getAlertsByFarm(): AppResult<AlertsByFarm> =
        runCatchingApi(errorParser::parse) {
            api.getAlertsByFarm().toDomain()
        }

    // ============================ Dashboard list ============================

    /**
     * Newest stored observation per plot: date, NDVI/EVI, quality, simulation
     * flag. Deliberately provider-free (one query, no satellite fan-out).
     */
    suspend fun getLatestPlots(): AppResult<List<PlotSummary>> =
        runCatchingApi(errorParser::parse) {
            api.getLatestPlots().items.map { it.toDomain() }
        }
}