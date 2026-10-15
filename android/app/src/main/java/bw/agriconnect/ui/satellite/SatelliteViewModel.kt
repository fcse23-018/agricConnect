package bw.agriconnect.ui.satellite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.SatelliteRepository
import bw.agriconnect.domain.model.SatelliteNdvi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SatelliteUiState(
    val ndvi: SatelliteNdvi? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** True when the failure was a connectivity problem (drives the offline copy). */
    val offline: Boolean = false,
)

@HiltViewModel
class SatelliteViewModel @Inject constructor(
    private val repo: SatelliteRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SatelliteUiState())
    val state: StateFlow<SatelliteUiState> = _state.asStateFlow()

    fun load(plotId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, offline = false) }
            when (val r = repo.latest(plotId)) {
                is AppResult.Success -> _state.update { it.copy(isLoading = false, ndvi = r.data) }
                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = r.error.message, offline = r.error.isNetwork)
                }
            }
        }
    }
}