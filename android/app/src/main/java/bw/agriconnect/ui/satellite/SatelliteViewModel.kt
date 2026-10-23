package bw.agriconnect.ui.satellite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.remote.dto.BoundaryPreviewRequest
import bw.agriconnect.data.remote.dto.BoundaryPreviewResponse
import bw.agriconnect.data.remote.dto.SetBoundaryRequest
import bw.agriconnect.data.remote.dto.SetBoundaryResponse
import bw.agriconnect.data.remote.dto.EventCreateRequest
import bw.agriconnect.data.remote.dto.EventUpdateRequest
import bw.agriconnect.data.remote.dto.AlertStatusUpdateRequest
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.SatelliteMonitoringRepository
import bw.agriconnect.data.repository.SatelliteRepository
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.SatelliteNdvi
import bw.agriconnect.domain.model.satellite.*
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.ui.components.PlotMapShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

/** UI state for the unified satellite monitoring dashboard. */
data class SatelliteUiState(
    val overview: SatelliteOverview? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
    val infoMessage: String? = null,
    /** Legacy single-NDVI state (kept for backward compatibility during transition). */
    val ndvi: SatelliteNdvi? = null,
    val isGenerating: Boolean = false,
    /** Farmer + system event log for the selected plot (newest first). */
    val events: List<PlotEvent> = emptyList(),
    val isLoadingEvents: Boolean = false,
    /** Latest boundary preview (computed area/perimeter) — not yet saved. */
    val boundaryPreview: BoundaryPreview? = null,
    val isPreviewingBoundary: Boolean = false,
    val boundaryPreviewError: String? = null,
    /** Per-pixel colour map ("satellite sight") for the selected plot. */
    val colourMap: ColourMap? = null,
    val isLoadingColourMap: Boolean = false,
    val colourMapError: String? = null,
    /**
     * The observation date currently drawn. `null` means "the newest reading";
     * a value means the farmer chose that date from the plot's own history.
     */
    val colourMapDate: String? = null,
    /** Farm-wide attention summary across every plot the user owns. */
    val alertsByFarm: AlertsByFarm? = null,
    val isLoadingAlertsByFarm: Boolean = false,
    /**
     * True when [overview] came from the local mirror rather than the network, so the
     * screen says "Cached" instead of implying a live fetch.
     */
    val overviewFromCache: Boolean = false,
    /** When the local mirror was written (millis). Never an observation date. */
    val overviewCachedAtMillis: Long? = null,
    /**
     * Every plot the farmer owns, shaped for the map: stored boundary, centroid, and the
     * measured NDVI mean when one exists. A plot with no ring is kept with
     * [PlotMapShape.hasBoundary] false rather than dropped, so it still appears as a pin.
     */
    val plotMapShapes: List<PlotMapShape> = emptyList(),
    /** Plain-language result of creating a plot from the map. */
    val createPlotMessage: String? = null,
    val isCreatingPlot: Boolean = false,
)

/** Event representing a user action on the satellite screen. */
sealed interface SatelliteEvent {
    data class RefreshOverview(val plotId: String) : SatelliteEvent
    data class GenerateLegacyNdvi(val plotId: String) : SatelliteEvent
    data class SelectPlot(val plotId: String) : SatelliteEvent
    data class PreviewBoundary(val request: BoundaryPreviewRequest) : SatelliteEvent
    data class SaveBoundary(val plotId: String, val request: SetBoundaryRequest) : SatelliteEvent
    data object ClearBoundaryPreview : SatelliteEvent
    data class CreateEvent(val request: EventCreateRequest) : SatelliteEvent
    data class UpdateEvent(val eventId: String, val request: EventUpdateRequest) : SatelliteEvent
    data class DeleteEvent(val eventId: String) : SatelliteEvent
    data class UpdateAlertStatus(val alertId: String, val status: AlertStatus) : SatelliteEvent
    /** Reloads the per-pixel colour map for a plot, optionally switching index. */
    data class LoadColourMap(
        val plotId: String,
        val index: String = "ndvi",
        /** A specific observation date (`YYYY-MM-DD`); null means the newest reading. */
        val date: String? = null,
    ) : SatelliteEvent
    /** Reloads the farm-wide attention summary. */
    data object LoadAlertsByFarm : SatelliteEvent
    data object DismissMessage : SatelliteEvent
}

/** State flow for the plot list (shared with FarmPlotRepository). */
@HiltViewModel
class SatelliteViewModel @Inject constructor(
    private val monitoringRepo: SatelliteMonitoringRepository,
    private val legacyRepo: SatelliteRepository,
    private val farmPlots: FarmPlotRepository,
    private val auth: bw.agriconnect.data.repository.AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SatelliteUiState())
    val state: StateFlow<SatelliteUiState> = _state.asStateFlow()

    val plots: StateFlow<List<FarmPlot>> = farmPlots.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedPlotId = MutableStateFlow<String?>(null)
    val selectedPlotId: StateFlow<String?> = _selectedPlotId.asStateFlow()

    /** Only the farm owner can request a fresh NDVI computation. */
    val canGenerate: StateFlow<Boolean> = auth.session
        .map { it?.role?.canComputeNdvi == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Processes a user event, updating UI state accordingly.
     */
    fun onEvent(event: SatelliteEvent) {
        when (event) {
            is SatelliteEvent.RefreshOverview -> loadOverview(event.plotId)
            is SatelliteEvent.GenerateLegacyNdvi -> generateLegacyNdvi(event.plotId)
            is SatelliteEvent.SelectPlot -> selectPlot(event.plotId)
            is SatelliteEvent.PreviewBoundary -> previewBoundary(event.request)
            is SatelliteEvent.SaveBoundary -> saveBoundary(event.plotId, event.request)
            is SatelliteEvent.ClearBoundaryPreview -> clearBoundaryPreview()
            is SatelliteEvent.CreateEvent -> createEvent(event.request)
            is SatelliteEvent.UpdateEvent -> updateEvent(event.eventId, event.request)
            is SatelliteEvent.DeleteEvent -> deleteEvent(event.eventId)
            is SatelliteEvent.UpdateAlertStatus -> updateAlertStatus(event.alertId, event.status)
            is SatelliteEvent.LoadColourMap -> loadColourMap(event.plotId, event.index, event.date)
            is SatelliteEvent.LoadAlertsByFarm -> loadAlertsByFarm()
            is SatelliteEvent.DismissMessage -> dismissMessages()
        }
    }

    /** Loads the complete monitoring overview for a plot. */
    private fun loadOverview(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, offline = false, infoMessage = null) }
            when (val result = monitoringRepo.getOverviewWithFallback(plotId)) {
                is AppResult.Success -> {
                    val overview = result.data.overview
                    _state.update {
                        it.copy(
                            isLoading = false,
                            overview = overview,
                            overviewFromCache = result.data.fromCache,
                            overviewCachedAtMillis = result.data.cachedAtMillis,
                            // A cached payload is not an error, but it must not read as
                            // live either: the banner below says which it is.
                            offline = result.data.fromCache,
                            // Derive a legacy-compatible Ndvi from the newest observation so
                            // the pre-existing advice/guidance UI keeps working. The series
                            // arrives oldest-first, so pick by date rather than position.
                            ndvi = overview.observations.maxByOrNull { obs -> obs.observedOn }?.let { obs ->
                                SatelliteNdvi(
                                    ndviId = obs.observationId,
                                    plotId = obs.plotId,
                                    farmerId = obs.farmerId,
                                    satelliteDate = obs.observedOn,
                                    meanNdviValue = obs.ndvi,
                                    stressLevel = bw.agriconnect.core.util.NdviUtil.classify(obs.ndvi),
                                    status = bw.agriconnect.domain.model.NdviProcessingStatus.GENERATED,
                                    cloudCover = obs.cloudCoverPct,
                                    source = obs.source ?: "SENTINEL-2",
                                    isCached = obs.isSimulated,
                                    createdAt = obs.createdAt.orEmpty(),
                                    plotName = overview.plot.plotName,
                                )
                            } ?: it.ndvi,
                        )
                    }
                    loadEvents(plotId)
                    // A fresh plot has its own history, so the previously chosen
                    // date would not apply — always open on the newest reading.
                    loadColourMap(plotId, date = null)
                    // The tint on the map depends on the newest stored reading, which
                    // may have just changed.
                    refreshMapShapes()
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        error = result.error.message,
                        offline = result.error.isNetwork,
                    )
                }
            }
        }
    }

    /**
     * Rebuilds [SatelliteUiState.plotMapShapes] from the local plot list, attaching each
     * plot's measured NDVI mean from the local observation mirror when one exists.
     *
     * The NDVI value only ever comes from a stored observation; a plot with no reading
     * gets `null`, which the map draws as an untinted default rather than as green.
     */
    private fun refreshMapShapes() {
        viewModelScope.launch {
            val plots = farmPlots.observeAll().first()
            val shapes = plots.filter { !it.plotId.isBlank() }.map { plot ->
                val vertices = PlotBoundary.fromGeoJson(plot.geoJsonBoundary)
                val cached = monitoringRepo.getCachedOverview(plot.plotId)
                val newest = cached?.overview?.observations?.maxByOrNull { it.observedOn }
                PlotMapShape(
                    plotId = plot.plotId,
                    plotName = plot.plotName.ifBlank { "Plot" },
                    vertices = vertices,
                    centroid = plot.centroidLat?.let { lat ->
                        plot.centroidLng?.let { lng -> bw.agriconnect.core.util.GeoVertex(lat, lng) }
                    },
                    hasBoundary = vertices.size >= PlotBoundary.MIN_VERTICES,
                    ndvi = newest?.ndvi,
                    isSimulated = newest?.isSimulated ?: false,
                )
            }
            _state.update { it.copy(plotMapShapes = shapes) }
        }
    }

    /**
     * Creates a plot from the map: the farmer places or draws a boundary and names it,
     * and the SAME repository call the Farm Management screen uses persists it (locally,
     * queued for sync). There is deliberately no second write path, so a plot created here
     * is immediately a Farm Management plot — one record, two screens.
     *
     * A boundary is required: without one the plot cannot be monitored, and inventing a
     * polygon around a single pin would be a fabricated survey. A point-only plot is still
     * allowed through [centreOnly] for a plot the farmer intends to survey later, and is
     * saved with no boundary at all rather than a made-up one.
     */
    fun createPlotFromMap(
        name: String,
        boundary: List<bw.agriconnect.core.util.GeoVertex>,
        centreOnly: bw.agriconnect.core.util.GeoVertex? = null,
        areaHectares: Double? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _state.update { it.copy(createPlotMessage = "Give the plot a name first.") }
            return
        }
        val hasBoundary = boundary.size >= PlotBoundary.MIN_VERTICES
        if (!hasBoundary && centreOnly == null) {
            _state.update { it.copy(createPlotMessage = "Add at least three corners, or drop a single pin.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isCreatingPlot = true, createPlotMessage = null) }
            val ownerId = auth.session.value?.userId
            if (ownerId.isNullOrBlank()) {
                _state.update { it.copy(isCreatingPlot = false, createPlotMessage = "You need to be signed in to add a plot.") }
                return@launch
            }
            val now = nowIso
            val geoJson = if (hasBoundary) PlotBoundary.toGeoJsonPolygon(boundary) else null
            val centre = if (hasBoundary) PlotBoundary.centroid(boundary) else centreOnly
            val plot = FarmPlot(
                plotId = UUID.randomUUID().toString(),
                farmerId = ownerId,
                plotName = trimmed,
                areaHectares = areaHectares ?: PlotBoundary.estimateAreaHectares(boundary),
                geoJsonBoundary = geoJson,
                centroidLat = centre?.latitude,
                centroidLng = centre?.longitude,
                createdAt = now,
                updatedAt = now,
            )
            when (val saved = farmPlots.savePlot(plot)) {
                is AppResult.Success -> {
                    _selectedPlotId.value = saved.data.plotId
                    _state.update {
                        it.copy(
                            isCreatingPlot = false,
                            createPlotMessage = if (hasBoundary) {
                                "Plot saved and queued for sync."
                            } else {
                                "Plot saved without a boundary. Draw one to start monitoring it."
                            },
                        )
                    }
                    refreshMapShapes()
                    loadOverview(saved.data.plotId)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isCreatingPlot = false, createPlotMessage = saved.error.message)
                }
            }
        }
    }

    /** Clears the plot-creation result message once it has been shown. */
    fun dismissCreatePlotMessage() = _state.update { it.copy(createPlotMessage = null) }

    private val nowIso: String
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    /**
     * Loads the farmer + system event log for [plotId].
     *
     * A failure here is not fatal: the overview already reports its own error, so
     * the last known list is kept instead of blanking the tab.
     */
    private fun loadEvents(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingEvents = true) }
            when (val result = monitoringRepo.getEvents(plotId)) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoadingEvents = false, events = result.data)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isLoadingEvents = false,
                        error = it.error ?: result.error.message,
                        offline = it.offline || result.error.isNetwork,
                    )
                }
            }
        }
    }

    /**
     * Loads the per-pixel colour map ("satellite sight") for [plotId].
     *
     * Failure is not fatal: the picture is an extra view over data the overview
     * already reports, so a failure clears the map and records its own error
     * without disturbing the rest of the screen.
     */
    private fun loadColourMap(plotId: String, index: String = "ndvi", date: String? = null) {
        viewModelScope.launch {
            _state.update {
                it.copy(isLoadingColourMap = true, colourMapError = null, colourMapDate = date)
            }
            when (val result = monitoringRepo.getColourMap(plotId, index, date = date)) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoadingColourMap = false, colourMap = result.data, colourMapError = null)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isLoadingColourMap = false,
                        colourMap = null,
                        colourMapError = result.error.message,
                    )
                }
            }
        }
    }

    /**
     * Loads the farm-wide attention summary: every plot the user owns, grouped by
     * farm. This is what lets the Alerts tab say *which* farms need attention
     * rather than describing only the plot currently selected.
     */
    fun loadAlertsByFarm() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAlertsByFarm = true) }
            when (val result = monitoringRepo.getAlertsByFarm()) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoadingAlertsByFarm = false, alertsByFarm = result.data)
                }
                is AppResult.Failure -> _state.update {
                    // Keep whatever was last shown rather than blanking the tab; the
                    // overview already surfaces connectivity problems.
                    it.copy(isLoadingAlertsByFarm = false)
                }
            }
        }
    }

    /** Requests a fresh NDVI computation (legacy endpoint). */
    private fun generateLegacyNdvi(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null, offline = false, infoMessage = null) }

            val localBoundary = when (val result = farmPlots.getPlot(plotId)) {
                is AppResult.Success -> result.data?.geoJsonBoundary
                is AppResult.Failure -> null
            }
            val boundaryElement = bw.agriconnect.core.util.PlotBoundary.toJsonElement(localBoundary)
                .takeIf { bw.agriconnect.core.util.PlotBoundary.isUsableBoundary(it) }

            when (val result = legacyRepo.requestComputation(plotId, boundaryElement)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isGenerating = false,
                        ndvi = result.data,
                        infoMessage = if (result.data.advice != null) {
                            "New reading ready — guidance below."
                        } else {
                            "Satellite data generated. Status: ${result.data.status.name.lowercase()}"
                        },
                        // Also refresh the full overview
                        overview = it.overview?.copy(observations = it.overview?.observations?.plus(result.data.toPlotObservation()) ?: emptyList()),
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isGenerating = false,
                        error = result.error.message,
                        offline = result.error.isNetwork,
                    )
                }
            }
        }
    }

    /** Selects a plot and loads its monitoring overview. */
    private fun selectPlot(plotId: String) {
        _selectedPlotId.value = plotId
        // Switching plots drops any chosen observation date: the new plot has a
        // different series, so the old date would not exist on it.
        _state.update { it.copy(colourMapDate = null) }
        loadOverview(plotId)
    }

    /** Public selection entry point for the farm map (tapping a polygon or pin). */
    fun selectPlotFromMap(plotId: String) = selectPlot(plotId)

    /**
     * Measures a boundary the farmer is still drawing, without saving anything.
     * The server measures the real geometry, so the previewed area/perimeter is
     * the same number the save would store - never a client-side estimate.
     */
    private fun previewBoundary(request: BoundaryPreviewRequest) {
        viewModelScope.launch {
            _state.update { it.copy(isPreviewingBoundary = true, boundaryPreviewError = null) }
            when (val result = monitoringRepo.previewBoundary(request)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isPreviewingBoundary = false,
                        boundaryPreview = result.data,
                        boundaryPreviewError = null,
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isPreviewingBoundary = false,
                        boundaryPreview = null,
                        boundaryPreviewError = result.error.message,
                    )
                }
            }
        }
    }

    /** Drops the transient preview (e.g. when the editor is closed). */
    private fun clearBoundaryPreview() {
        _state.update {
            it.copy(
                boundaryPreview = null,
                boundaryPreviewError = null,
                isPreviewingBoundary = false,
            )
        }
    }

    /** Saves a boundary for a plot. */
    private fun saveBoundary(plotId: String, request: SetBoundaryRequest) {
        viewModelScope.launch {
            when (val result = monitoringRepo.setBoundary(plotId, request)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            infoMessage = "Boundary saved. Area: ${result.data.geometry.areaHectares?.let { "%.2f".format(it) }} ha",
                            overview = it.overview?.copy(
                                plot = result.data.plot,
                                geometry = result.data.geometry,
                            ),
                        )
                    }
                    // Refresh the full overview to get updated observations
                    loadOverview(plotId)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(error = result.error.message, offline = result.error.isNetwork)
                }
            }
        }
    }

    /** Creates a farmer event (idempotent via clientId). */
    private fun createEvent(request: EventCreateRequest) {
        viewModelScope.launch {
            when (val result = monitoringRepo.createEvent(request)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(infoMessage = "Event recorded: ${result.data.kind.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}")
                    }
                    // Refresh overview + event log to include the new event
                    loadOverview(result.data.plotId)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(error = result.error.message, offline = result.error.isNetwork)
                }
            }
        }
    }

    /** Updates a farmer event. */
    private fun updateEvent(eventId: String, request: EventUpdateRequest) {
        viewModelScope.launch {
            when (val result = monitoringRepo.updateEvent(eventId, request)) {
                is AppResult.Success -> {
                    _state.update { it.copy(infoMessage = "Event updated") }
                    loadOverview(result.data.plotId)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(error = result.error.message, offline = result.error.isNetwork)
                }
            }
        }
    }

    /** Deletes a farmer event. */
    private fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            // The event carries the plot, so the right plot is refreshed even when
            // nothing is stored in selectedPlotId (single-plot farms).
            val plotId = _state.value.events.firstOrNull { it.eventId == eventId }?.plotId
                ?: _selectedPlotId.value
            when (val result = monitoringRepo.deleteEvent(eventId)) {
                is AppResult.Success -> {
                    _state.update { state ->
                        state.copy(
                            infoMessage = "Event deleted",
                            events = state.events.filterNot { it.eventId == eventId },
                        )
                    }
                    plotId?.let { loadOverview(it) }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(error = result.error.message, offline = result.error.isNetwork)
                }
            }
        }
    }

    /** Updates an alert's status (acknowledge/dismiss). */
    private fun updateAlertStatus(alertId: String, status: AlertStatus) {
        viewModelScope.launch {
            when (val result = monitoringRepo.updateAlertStatus(alertId, status)) {
                is AppResult.Success -> {
                    _state.update { it.copy(infoMessage = "Alert ${status.name.lowercase()}") }
                    // The status response carries the plot; if a future payload omits
                    // it, fall back to the plot already on screen so the refresh and
                    // the message never diverge.
                    val plotId = result.data.plotId ?: _state.value.overview?.plot?.plotId
                    plotId?.let { loadOverview(it) }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(error = result.error.message, offline = result.error.isNetwork)
                }
            }
        }
    }

    /** Clears error/info messages. */
    private fun dismissMessages() {
        _state.update { it.copy(error = null, infoMessage = null, createPlotMessage = null) }
    }

    /** Switches the colour map between NDVI and EVI for the selected plot. */
    fun switchColourMapIndex(index: String) {
        val plotId = _selectedPlotId.value ?: _state.value.overview?.plot?.plotId ?: return
        // Keep the chosen observation date when only the index changes.
        loadColourMap(plotId, index, _state.value.colourMapDate)
    }

    /**
     * Draws a specific observation date instead of the newest reading. Passing
     * null returns to the newest. The date must come from
     * [ColourMap.availableDates]; the server refuses one it does not have rather
     * than substituting a different day.
     */
    fun selectColourMapDate(date: String?) {
        val plotId = _selectedPlotId.value ?: _state.value.overview?.plot?.plotId ?: return
        val index = _state.value.colourMap?.index ?: "ndvi"
        loadColourMap(plotId, index, date)
    }

    /**
     * Called once when the screen opens. [preferred] is the plot id passed via
     * navigation. When null (bottom-nav tab), the first available plot is selected.
     */
    fun start(preferred: String?) {
        viewModelScope.launch {
            refreshMapShapes()
            if (!preferred.isNullOrBlank()) {
                _selectedPlotId.value = preferred
                loadOverview(preferred)
                return@launch
            }
            val available = plots.first()
            val first = available.firstOrNull()
            if (first != null) {
                _selectedPlotId.value = first.plotId
                loadOverview(first.plotId)
            } else {
                _selectedPlotId.value = null
                _state.update { it.copy(overview = null, ndvi = null, isLoading = false, error = null) }
            }
        }
    }

    /** Legacy compatibility: exposes the NDVI from the overview as a SatelliteNdvi. */
    fun SatelliteOverview.toPlotObservation(): PlotObservation? = observations.firstOrNull()

    /** Legacy compatibility: converts SatelliteNdvi to PlotObservation. */
    fun SatelliteNdvi.toPlotObservation(): PlotObservation = PlotObservation(
        observationId = ndviId,
        plotId = plotId,
        farmerId = farmerId,
        observedOn = satelliteDate.orEmpty(),
        ndvi = meanNdviValue,
        evi = null,
        smoothedNdvi = meanNdviValue,
        smoothedEvi = null,
        validPixelPct = cloudCover?.let { 100.0 - it },
        cloudCoverPct = cloudCover,
        pixelCount = null,
        quality = bw.agriconnect.domain.model.satellite.ObservationQuality.GOOD,
        isOutlier = false,
        isSimulated = source == "MOCK",
        source = source,
        createdAt = createdAt,
        updatedAt = null,
    )
}