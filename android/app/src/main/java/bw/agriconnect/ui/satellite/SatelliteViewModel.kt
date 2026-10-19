package bw.agriconnect.ui.satellite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.SatelliteRepository
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.SatelliteNdvi
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

data class SatelliteUiState(
    val ndvi: SatelliteNdvi? = null,
    val isLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val error: String? = null,
    /** True when the failure was a connectivity problem (drives the offline copy). */
    val offline: Boolean = false,
    val infoMessage: String? = null,
)

@HiltViewModel
class SatelliteViewModel @Inject constructor(
    private val repo: SatelliteRepository,
    private val farmPlots: FarmPlotRepository,
    authRepository: AuthRepository,
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
     * Loads the NDVI reading for [plotId]. If the local cache has no plot (e.g. a freshly
     * synced server-side plot), the backend still returns the reading if one exists.
     */
    fun load(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, offline = false, infoMessage = null) }
            when (val r = repo.latest(plotId)) {
                is AppResult.Success -> _state.update { it.copy(isLoading = false, ndvi = r.data) }
                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = r.error.message, offline = r.error.isNetwork)
                }
            }
        }
    }

    /**
     * Requests a fresh NDVI computation for [plotId].
     *
     * The locally held boundary is attached if it is a usable GeoJSON polygon, so a plot
     * drawn moments ago can be monitored before it syncs. An unusable or missing local
     * boundary is simply omitted — the backend then falls back to the plot's stored
     * boundary, or returns a clear error if there is none.
     */
    fun generate(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null, offline = false, infoMessage = null) }

            val localBoundary = when (val result = farmPlots.getPlot(plotId)) {
                is AppResult.Success -> result.data?.geoJsonBoundary
                is AppResult.Failure -> null
            }
            val boundaryElement = PlotBoundary.toJsonElement(localBoundary)
                .takeIf { PlotBoundary.isUsableBoundary(it) }

            when (val r = repo.requestComputation(plotId, boundaryElement)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isGenerating = false,
                        ndvi = r.data,
                        infoMessage = if (r.data.advice != null) {
                            "New reading ready — guidance below."
                        } else {
                            "Satellite data generated. Status: ${r.data.status.name.lowercase()}"
                        },
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isGenerating = false,
                        error = r.error.message,
                        offline = r.error.isNetwork,
                    )
                }
            }
        }
    }

    /**
     * Selects a plot and loads its reading. Re-selecting the current plot refreshes it.
     */
    fun selectPlot(id: String) {
        _selectedPlotId.value = id
        load(id)
    }

    /**
     * Called once when the screen opens. [preferred] is the plot id passed in via
     * navigation (the pushed "satellite/{plotId}" route). When null (the bottom-nav
     * Satellite tab), the first available plot is selected.
     *
     * Waits for the local plot list to emit at least once so a freshly opened screen
     * does not race the offline cache.
     */
    fun start(preferred: String?) {
        viewModelScope.launch {
            if (!preferred.isNullOrBlank()) {
                _selectedPlotId.value = preferred
                load(preferred)
                return@launch
            }
            // Wait for the first real emission (may be empty on a cold start with no plots).
            val available = plots.first()
            val first = available.firstOrNull()
            if (first != null) {
                _selectedPlotId.value = first.plotId
                load(first.plotId)
            } else {
                _selectedPlotId.value = null
                _state.update { it.copy(ndvi = null, isLoading = false, error = null) }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, infoMessage = null) }
    }
}