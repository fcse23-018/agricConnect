package bw.agriconnect.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AccountRepository
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.NotificationPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationPrefsUiState(
    val orders: Boolean = true,
    val sync: Boolean = true,
    val satellite: Boolean = true,
    val marketplace: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class NotificationPrefsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationPrefsUiState())
    val state: StateFlow<NotificationPrefsUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val r = authRepository.getCurrentUser()) {
            is AppResult.Success -> {
                val p = r.data.notificationPreferences
                _state.update { it.copy(isLoading = false, orders = p.orders, sync = p.sync, satellite = p.satellite, marketplace = p.marketplace) }
            }
            is AppResult.Failure -> _state.update { it.copy(isLoading = false, error = r.error.message) }
        }
    }

    fun setOrders(v: Boolean) = _state.update { it.copy(orders = v) }
    fun setSync(v: Boolean) = _state.update { it.copy(sync = v) }
    fun setSatellite(v: Boolean) = _state.update { it.copy(satellite = v) }
    fun setMarketplace(v: Boolean) = _state.update { it.copy(marketplace = v) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(isSaving = true, error = null, message = null) }
        val prefs = NotificationPreferences(orders = s.orders, sync = s.sync, satellite = s.satellite, marketplace = s.marketplace)
        when (accountRepository.updateNotificationPreferences(prefs)) {
            is AppResult.Success -> _state.update { it.copy(isSaving = false, message = "Preferences saved.") }
            is AppResult.Failure -> _state.update { it.copy(isSaving = false, error = "Could not save preferences.") }
        }
    }
}