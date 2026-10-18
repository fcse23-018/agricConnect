package bw.agriconnect.ui.satellite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.SatelliteRepository
import bw.agriconnect.domain.model.SatelliteNdvi
import bw.agriconnect.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SatelliteUiState())
    val state: StateFlow<SatelliteUiState> = _state.asStateFlow()

    /** Only the farm owner (FARMER) can request a fresh NDVI computation. */
    val canGenerate: StateFlow<Boolean> = authRepository.session
        .map { it?.role == UserRole.FARMER }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    fun generate(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isGenerating = true, error = null, offline = false, infoMessage = null) }
            when (val r = repo.requestComputation(plotId)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isGenerating = false,
                        ndvi = r.data,
                        infoMessage = "Satellite data generated. Status: ${r.data.status.name.lowercase()}",
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

    fun clearMessages() {
        _state.update { it.copy(error = null, infoMessage = null) }
    }
}