package bw.agriconnect.ui.farms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.FarmPlotRepository
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
    val error: String? = null,
    val isSaving: Boolean = false,
    val showForm: Boolean = false,
)

@HiltViewModel
class FarmViewModel @Inject constructor(
    private val repo: FarmPlotRepository,
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

    fun savePlot() {
        val s = _form.value
        val farmerId = auth.session.value?.userId ?: return
        if (s.name.isBlank()) { _form.update { it.copy(error = "Plot name required.") }; return }
        val area = s.area.toDoubleOrNull()
        val lat = s.lat.toDoubleOrNull()
        val lng = s.lng.toDoubleOrNull()
        viewModelScope.launch {
            _form.update { it.copy(isSaving = true, error = null) }
            val plot = FarmPlot(
                plotId = UUID.randomUUID().toString(),
                farmerId = farmerId,
                plotName = s.name.trim(),
                areaHectares = area,
                geoJsonBoundary = null,
                centroidLat = lat,
                centroidLng = lng,
                notes = s.notes.trim().ifBlank { null },
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