package bw.agriconnect.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes only the signed-in [Session] (identity + role) to the settings hub. No
 * network call: the session is already local, and nothing on this screen needs
 * fresher data than the one that drew the bottom navigation.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    val session: StateFlow<Session?> = authRepository.session

    /**
     * Revokes the session (best-effort server-side) and always clears it locally
     * before handing control back to the navigation host.
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onDone()
        }
    }
}
