package bw.agriconnect.ui.farms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.HarvestRepository
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HarvestsViewModel @Inject constructor(
    private val harvestRepository: HarvestRepository,
    private val plotRepository: FarmPlotRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val farmerId: StateFlow<String?> = authRepository.session.map { it?.userId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val harvests: StateFlow<List<HarvestRecord>> = farmerId
        .flatMapLatest { id -> if (id.isNullOrBlank()) flowOf(emptyList()) else harvestRepository.observeByFarmer(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val plots: StateFlow<List<FarmPlot>> = plotRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Non-null when the last save could not be persisted locally. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun clearSaveError() { _saveError.value = null }

    fun saveHarvest(
        cropType: String,
        quantityKg: Double,
        harvestDate: String,
        plotId: String?,
        notes: String?,
        onDone: () -> Unit,
    ) {
        val id = farmerId.value
        if (id.isNullOrBlank()) {
            _saveError.value = "You need to be signed in to save a harvest."
            return
        }
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val record = HarvestRecord(
            harvestId = UUID.randomUUID().toString(),
            farmerId = id,
            plotId = plotId?.takeIf { it.isNotBlank() },
            cropType = cropType.trim(),
            quantityKg = quantityKg,
            harvestDate = harvestDate.ifBlank { java.time.LocalDate.now().toString() },
            notes = notes?.trim()?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch {
            _saveError.value = null
            when (val result = harvestRepository.saveHarvest(record)) {
                is AppResult.Success -> onDone()
                is AppResult.Failure -> _saveError.value =
                    result.error.message.ifBlank { "The harvest could not be saved on this device." }
            }
        }
    }

    fun delete(harvestId: String) {
        viewModelScope.launch { harvestRepository.deleteHarvest(harvestId) }
    }
}
