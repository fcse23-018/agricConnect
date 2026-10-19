package bw.agriconnect.ui.farms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.ImageFiles
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.HarvestRepository
import bw.agriconnect.data.repository.MediaRepository
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
    val isUploading: Boolean = false,
    val showEdit: Boolean = false,
    val showDelete: Boolean = false,
    // edit form
    val name: String = "",
    val area: String = "",
    val lat: String = "",
    val lng: String = "",
    val notes: String = "",
    /** Current photo reference: a server path, a just-picked `content://` URI, or null. */
    val imagePreview: String? = null,
    val pendingImage: ImageFiles.Picked? = null,
    /** The plot polygon corners, editable so an existing plot can gain a boundary for NDVI. */
    val boundary: List<GeoVertex> = emptyList(),
    val vertexLat: String = "",
    val vertexLng: String = "",
    val isLocating: Boolean = false,
    val formError: String? = null,
) {
    val isBusy: Boolean get() = isSaving || isUploading

    /** True once enough corners exist to enclose an area — i.e. NDVI is possible. */
    val hasBoundary: Boolean get() = boundary.size >= PlotBoundary.MIN_VERTICES
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FarmDetailViewModel @Inject constructor(
    private val plotRepository: FarmPlotRepository,
    private val harvestRepository: HarvestRepository,
    private val satelliteRepository: SatelliteRepository,
    private val media: MediaRepository,
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
                            imagePreview = plot?.imageUrl,
                            pendingImage = null,
                            boundary = PlotBoundary.fromGeoJson(plot?.geoJsonBoundary),
                            vertexLat = "",
                            vertexLng = "",
                            isLocating = false,
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
        _state.update {
            it.copy(
                showEdit = show,
                formError = null,
                // Re-opening the editor discards any staged (unsaved) change.
                imagePreview = if (show) it.plot?.imageUrl else it.imagePreview,
                pendingImage = if (show) null else it.pendingImage,
                boundary = if (show) PlotBoundary.fromGeoJson(it.plot?.geoJsonBoundary) else it.boundary,
                vertexLat = if (show) "" else it.vertexLat,
                vertexLng = if (show) "" else it.vertexLng,
                isLocating = false,
            )
        }
    }

    fun showDelete(show: Boolean) {
        _state.update { it.copy(showDelete = show) }
    }

    fun onNameChange(v: String) = _state.update { it.copy(name = v) }
    fun onAreaChange(v: String) = _state.update { it.copy(area = v) }
    fun onLatChange(v: String) = _state.update { it.copy(lat = v) }
    fun onLngChange(v: String) = _state.update { it.copy(lng = v) }
    fun onNotesChange(v: String) = _state.update { it.copy(notes = v) }

    fun onImagePicked(preview: String, picked: ImageFiles.Picked) =
        _state.update { it.copy(imagePreview = preview, pendingImage = picked, formError = null) }

    fun onImageCleared() = _state.update { it.copy(imagePreview = null, pendingImage = null) }

    fun onImageError(message: String) = _state.update { it.copy(formError = message) }

    fun onVertexLatChange(v: String) = _state.update { it.copy(vertexLat = v, formError = null) }
    fun onVertexLngChange(v: String) = _state.update { it.copy(vertexLng = v, formError = null) }

    /** Appends a corner typed by hand, rejecting anything that is not a usable coordinate. */
    fun addManualVertex() {
        val latitude = _state.value.vertexLat.trim().toDoubleOrNull()
        val longitude = _state.value.vertexLng.trim().toDoubleOrNull()
        val vertex = if (latitude != null && longitude != null) GeoVertex(latitude, longitude) else null
        if (vertex == null || !PlotBoundary.isValid(vertex)) {
            _state.update { it.copy(formError = "Enter a valid corner latitude (-90 to 90) and longitude (-180 to 180).") }
            return
        }
        _state.update { it.copy(boundary = it.boundary + vertex, vertexLat = "", vertexLng = "", formError = null) }
    }

    /** Appends a corner captured from GPS. */
    fun addVertex(vertex: GeoVertex) = _state.update { it.copy(boundary = it.boundary + vertex, formError = null) }

    fun removeVertex(index: Int) =
        _state.update { it.copy(boundary = it.boundary.filterIndexed { i, _ -> i != index }) }

    fun clearBoundary() = _state.update { it.copy(boundary = emptyList(), formError = null) }

    fun setLocating(locating: Boolean) = _state.update { it.copy(isLocating = locating) }

    fun onLocationFailed(message: String) = _state.update { it.copy(isLocating = false, formError = message) }

    fun save(onSaved: () -> Unit) {
        val plot = _state.value.plot ?: return
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(formError = "Plot name required.") }
            return
        }
        // The boundary is authoritative when drawn: it is what NDVI is computed over, and
        // its centroid/area supersede the optional manual fields. Clearing it (fewer than
        // three corners) sends no boundary, which removes the stored ring.
        val boundary = PlotBoundary.toGeoJsonPolygon(s.boundary)
        val centre = PlotBoundary.centroid(s.boundary)
        val area = s.area.toDoubleOrNull() ?: PlotBoundary.estimateAreaHectares(s.boundary)
        val lat = centre?.latitude ?: s.lat.toDoubleOrNull()
        val lng = centre?.longitude ?: s.lng.toDoubleOrNull()
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, formError = null) }

            // A newly picked photo is uploaded first; otherwise the existing reference
            // (kept in imagePreview) is reused — including null, which clears the photo.
            val imageUrl = s.pendingImage?.let { picked ->
                _state.update { it.copy(isUploading = true) }
                when (val up = media.uploadImage(picked.bytes, picked.mimeType, picked.fileName)) {
                    is AppResult.Success -> up.data
                    is AppResult.Failure -> {
                        _state.update { it.copy(isSaving = false, isUploading = false, formError = up.error.message) }
                        return@launch
                    }
                }
            } ?: s.imagePreview
            _state.update { it.copy(isUploading = false) }

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val updated = plot.copy(
                plotName = s.name.trim(),
                areaHectares = area,
                geoJsonBoundary = boundary,
                centroidLat = lat,
                centroidLng = lng,
                notes = s.notes.trim().ifBlank { null },
                imageUrl = imageUrl,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
            )
            when (plotRepository.updatePlot(updated)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSaving = false, showEdit = false, plot = updated, imagePreview = updated.imageUrl, pendingImage = null) }
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
