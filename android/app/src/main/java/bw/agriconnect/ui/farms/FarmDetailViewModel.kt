package bw.agriconnect.ui.farms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.HarvestRepository
import bw.agriconnect.data.repository.SatelliteRepository
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.SatelliteNdvi
import bw.agriconnect.domain.model.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

data class FarmDetailUiState(
    val plot: FarmPlot? = null,
    val harvests: List<HarvestRecord> = emptyList(),
    val ndvi: SatelliteNdvi? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSaving: Boolean = false,
    val showEdit: Boolean = false,
    val showDelete: Boolean = false,
    // edit form
    val name: String = "",
    val area: String = "",
    val lat: String = "",
    val lng: String = "",
    val notes: String = "",
    val formError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FarmDetailViewModel @Inject constructor(
    private val plotRepository: FarmPlotRepository,
    private val harvestRepository: HarvestRepository,
    private val satelliteRepository: SatelliteRepository,
) : ViewModel() {

    private val _plotId = MutableStateFlow<String?>(null)
    val plotId: StateFlow<String?> = _plotId.asStateFlow()

    private val _state = MutableStateFlow(FarmDetailUiState())
    val state: StateFlow<FarmDetailUiState> = _state.asStateFlow()

    val harvests: StateFlow<List<HarvestRecord>> = _plotId
        .flatMapLatest { id -> if (id.isNullOrBlank()) flowOf(emptyList()) else harvestRepository.observeByPlot(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPlotId(id: String) {
        _plotId.value = id
        load(id)
    }

    private fun load(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = plotRepository.getPlot(id)) {
                is AppResult.Success -> {
                    val plot = result.data
                    _state.update {
                        it.copy(
                            plot = plot,
                            isLoading = false,
                            name = plot?.plotName.orEmpty(),
                            area = plot?.areaHectares?.toString().orEmpty(),
                            lat = plot?.centroidLat?.toString().orEmpty(),
                            lng = plot?.centroidLng?.toString().orEmpty(),
                            notes = plot?.notes.orEmpty(),
                        )
                    }
                    loadExtras(id)
                }
                is AppResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error.message) }
            }
        }
    }

    private fun loadExtras(id: String) {
        viewModelScope.launch {
            when (val ndvi = satelliteRepository.latest(id)) {
                is AppResult.Success -> _state.update { it.copy(ndvi = ndvi.data) }
                else -> Unit
            }
        }
    }

    fun refresh() {
        val id = _plotId.value ?: return
        load(id)
    }

    fun showEdit(show: Boolean) {
        _state.update { it.copy(showEdit = show, formError = null) }
    }

    fun showDelete(show: Boolean) {
        _state.update { it.copy(showDelete = show) }
    }

    fun onNameChange(v: String) = _state.update { it.copy(name = v) }
    fun onAreaChange(v: String) = _state.update { it.copy(area = v) }
    fun onLatChange(v: String) = _state.update { it.copy(lat = v) }
    fun onLngChange(v: String) = _state.update { it.copy(lng = v) }
    fun onNotesChange(v: String) = _state.update { it.copy(notes = v) }

    fun save(onSaved: () -> Unit) {
        val plot = _state.value.plot ?: return
        val area = _state.value.area.toDoubleOrNull()
        val lat = _state.value.lat.toDoubleOrNull()
        val lng = _state.value.lng.toDoubleOrNull()
        if (_state.value.name.isBlank()) {
            _state.update { it.copy(formError = "Plot name required.") }
            return
        }
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val updated = plot.copy(
            plotName = _state.value.name.trim(),
            areaHectares = area,
            centroidLat = lat,
            centroidLng = lng,
            notes = _state.value.notes.trim().ifBlank { null },
            updatedAt = now,
            syncStatus = SyncStatus.PENDING,
        )
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, formError = null) }
            when (plotRepository.updatePlot(updated)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSaving = false, showEdit = false, plot = updated) }
                    onSaved()
                }
                is AppResult.Failure -> _state.update { it.copy(isSaving = false, formError = "Could not save plot.") }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = _plotId.value ?: return
        viewModelScope.launch {
            when (plotRepository.deletePlot(id)) {
                is AppResult.Success -> onDeleted()
                is AppResult.Failure -> _state.update { it.copy(error = "Could not delete plot.") }
            }
        }
    }
}
