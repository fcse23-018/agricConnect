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
    data object DismissMessage : SatelliteEvent
}

/** State flow for the plot list (shared with FarmPlotRepository). */
@HiltViewModel
class SatelliteViewModel @Inject constructor(
    private val monitoringRepo: SatelliteMonitoringRepository,
    private val legacyRepo: SatelliteRepository,
    private val farmPlots: FarmPlotRepository,
    authRepository: bw.agriconnect.data.repository.AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SatelliteUiState())
    val state: StateFlow<SatelliteUiState> = _state.asStateFlow()

    val plots: StateFlow<List<FarmPlot>> = farmPlots.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedPlotId = MutableStateFlow<String?>(null)
    val selectedPlotId: StateFlow<String?> = _selectedPlotId.asStateFlow()

    /** Only the farm owner can request a fresh NDVI computation. */
    val canGenerate: StateFlow<Boolean> = authRepository.session
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
            is SatelliteEvent.DismissMessage -> dismissMessages()
        }
    }

    /** Loads the complete monitoring overview for a plot. */
    private fun loadOverview(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, offline = false, infoMessage = null) }
            when (val result = monitoringRepo.getOverview(plotId)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            overview = result.data,
                            // Derive a legacy-compatible Ndvi from the newest observation so
                            // the pre-existing advice/guidance UI keeps working. The series
                            // arrives oldest-first, so pick by date rather than position.
                            ndvi = result.data.observations.maxByOrNull { obs -> obs.observedOn }?.let { obs ->
                                SatelliteNdvi(
                                    ndviId = obs.observationId,
                                    plotId = obs.plotId,
                                    farmerId = obs.farmerId,
                                    satelliteDate = obs.observedOn,
                                    meanNdviValue = obs.ndvi,
                                    stressLevel = when {
                                        obs.ndvi == null -> null
                                        obs.ndvi!! < 0.3 -> bw.agriconnect.domain.model.StressLevel.HIGH_STRESS
                                        obs.ndvi!! < 0.5 -> bw.agriconnect.domain.model.StressLevel.MODERATE_STRESS
                                        else -> bw.agriconnect.domain.model.StressLevel.HEALTHY_VEGETATION
                                    },
                                    status = bw.agriconnect.domain.model.NdviProcessingStatus.GENERATED,
                                    cloudCover = obs.cloudCoverPct,
                                    source = obs.source ?: "SENTINEL-2",
                                    isCached = obs.isSimulated,
                                    createdAt = obs.createdAt.orEmpty(),
                                    plotName = it.overview?.plot?.plotName,
                                )
                            } ?: it.ndvi,
                        )
                    }
                    loadEvents(plotId)
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
        loadOverview(plotId)
    }

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
        _state.update { it.copy(error = null, infoMessage = null) }
    }

    /**
     * Called once when the screen opens. [preferred] is the plot id passed via
     * navigation. When null (bottom-nav tab), the first available plot is selected.
     */
    fun start(preferred: String?) {
        viewModelScope.launch {
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