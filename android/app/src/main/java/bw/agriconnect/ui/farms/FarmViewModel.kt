package bw.agriconnect.ui.farms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.ImageFiles
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.MediaRepository
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

data class FarmFormState(
    val name: String = "",
    val area: String = "",
    val lat: String = "",
    val lng: String = "",
    val notes: String = "",
    /** Device-local URI of a picked-but-not-yet-uploaded photo, for the preview. */
    val imagePreview: String? = null,
    val pendingImage: ImageFiles.Picked? = null,
    /**
     * The plot polygon corners, in the order drawn. Required to compute NDVI; stored as a
     * GeoJSON Polygon in `farm_plots.geojson_boundary`.
     */
    val boundary: List<GeoVertex> = emptyList(),
    /** Staged corner coordinates for manual entry (cleared once a corner is added). */
    val vertexLat: String = "",
    val vertexLng: String = "",
    val isLocating: Boolean = false,
    val isUploading: Boolean = false,
    val error: String? = null,
    val isSaving: Boolean = false,
    val showForm: Boolean = false,
) {
    val isBusy: Boolean get() = isSaving || isUploading

    /** True once enough corners exist to enclose an area — i.e. NDVI is possible. */
    val hasBoundary: Boolean get() = boundary.size >= PlotBoundary.MIN_VERTICES
}

@HiltViewModel
class FarmViewModel @Inject constructor(
    private val repo: FarmPlotRepository,
    private val media: MediaRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val now get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    val plots: StateFlow<List<FarmPlot>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _form = MutableStateFlow(FarmFormState())
    val form: StateFlow<FarmFormState> = _form.asStateFlow()

    fun refresh() = viewModelScope.launch { repo.refresh() }
    fun showForm(show: Boolean) = _form.update { it.copy(showForm = show, error = null) }
    fun onNameChange(v: String) = _form.update { it.copy(name = v, error = null) }
    fun onAreaChange(v: String) = _form.update { it.copy(area = v) }
    fun onLatChange(v: String) = _form.update { it.copy(lat = v) }
    fun onLngChange(v: String) = _form.update { it.copy(lng = v) }
    fun onNotesChange(v: String) = _form.update { it.copy(notes = v) }

    fun onVertexLatChange(v: String) = _form.update { it.copy(vertexLat = v, error = null) }
    fun onVertexLngChange(v: String) = _form.update { it.copy(vertexLng = v, error = null) }

    /** Appends a corner typed by hand, rejecting anything that is not a usable coordinate. */
    fun addManualVertex() {
        val latitude = _form.value.vertexLat.trim().toDoubleOrNull()
        val longitude = _form.value.vertexLng.trim().toDoubleOrNull()
        val vertex = if (latitude != null && longitude != null) GeoVertex(latitude, longitude) else null
        if (vertex == null || !PlotBoundary.isValid(vertex)) {
            _form.update { it.copy(error = "Enter a valid corner latitude (-90 to 90) and longitude (-180 to 180).") }
            return
        }
        _form.update { it.copy(boundary = it.boundary + vertex, vertexLat = "", vertexLng = "", error = null) }
    }

    /** Appends a corner captured from GPS. */
    fun addVertex(vertex: GeoVertex) = _form.update { it.copy(boundary = it.boundary + vertex, error = null) }

    fun removeVertex(index: Int) =
        _form.update { it.copy(boundary = it.boundary.filterIndexed { i, _ -> i != index }) }

    fun clearBoundary() = _form.update { it.copy(boundary = emptyList(), error = null) }

    fun setLocating(locating: Boolean) = _form.update { it.copy(isLocating = locating) }

    fun onLocationFailed(message: String) = _form.update { it.copy(isLocating = false, error = message) }

    fun onImagePicked(preview: String, picked: ImageFiles.Picked) =
        _form.update { it.copy(imagePreview = preview, pendingImage = picked, error = null) }

    fun onImageCleared() = _form.update { it.copy(imagePreview = null, pendingImage = null) }

    fun onImageError(message: String) = _form.update { it.copy(error = message) }

    fun savePlot() {
        val s = _form.value
        val farmerId = auth.session.value?.userId ?: return
        if (s.name.isBlank()) { _form.update { it.copy(error = "Plot name required.") }; return }

        // When a boundary is drawn it is authoritative: it is what NDVI is computed over,
        // and its centroid/area supersede the optional manual location fields. The area
        // falls back to a planar estimate so the offline row is never left blank.
        val boundary = PlotBoundary.toGeoJsonPolygon(s.boundary)
        val centre = PlotBoundary.centroid(s.boundary)
        val area = s.area.toDoubleOrNull() ?: PlotBoundary.estimateAreaHectares(s.boundary)
        val lat = centre?.latitude ?: s.lat.toDoubleOrNull()
        val lng = centre?.longitude ?: s.lng.toDoubleOrNull()
        viewModelScope.launch {
            _form.update { it.copy(isSaving = true, error = null) }

            // Upload the photo before writing so the plot (and its queued sync payload)
            // carries the final URL; a failed upload aborts rather than saving silently
            // without the picture the user chose.
            val imageUrl = s.pendingImage?.let { picked ->
                _form.update { it.copy(isUploading = true) }
                when (val up = media.uploadImage(picked.bytes, picked.mimeType, picked.fileName)) {
                    is AppResult.Success -> up.data
                    is AppResult.Failure -> {
                        _form.update { it.copy(isSaving = false, isUploading = false, error = up.error.message) }
                        return@launch
                    }
                }
            }
            _form.update { it.copy(isUploading = false) }

            val plot = FarmPlot(
                plotId = UUID.randomUUID().toString(),
                farmerId = farmerId,
                plotName = s.name.trim(),
                areaHectares = area,
                geoJsonBoundary = boundary,
                centroidLat = lat,
                centroidLng = lng,
                notes = s.notes.trim().ifBlank { null },
                imageUrl = imageUrl,
                createdAt = now,
                updatedAt = now,
            )
            when (repo.savePlot(plot)) {
                is AppResult.Success -> _form.update { FarmFormState(showForm = false) }
                is AppResult.Failure -> _form.update { it.copy(isSaving = false, error = "Could not save plot.") }
            }
        }
    }

    fun deletePlot(id: String) = viewModelScope.launch { repo.deletePlot(id) }
}