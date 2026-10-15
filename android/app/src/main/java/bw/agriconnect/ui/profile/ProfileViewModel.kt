package bw.agriconnect.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val session: Session? = null,
    val fullName: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val currentPassword: String = "",
    val newPassword: String = "",
    val showPasswordDialog: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(session = authRepository.session.value))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = authRepository.getCurrentUser()) {
                is AppResult.Success -> _state.update { it.copy(isLoading = false, session = authRepository.session.value, fullName = r.data.fullName, email = r.data.email.orEmpty()) }
                is AppResult.Failure -> _state.update { it.copy(isLoading = false, error = r.error.message) }
            }
        }
    }

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

    fun onNameChange(v: String) = _state.update { it.copy(fullName = v, error = null) }
    fun onEmailChange(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onCurrentPasswordChange(v: String) = _state.update { it.copy(currentPassword = v) }
    fun onNewPasswordChange(v: String) = _state.update { it.copy(newPassword = v) }
    fun togglePasswordDialog(show: Boolean) = _state.update { it.copy(showPasswordDialog = show, currentPassword = "", newPassword = "", error = null) }

    fun saveProfile() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, message = null) }
            when (val r = authRepository.updateProfile(fullName = s.fullName.trim().ifBlank { null }, email = s.email.trim().ifBlank { null })) {
                is AppResult.Success -> _state.update { it.copy(isSaving = false, message = "Profile updated.", session = authRepository.session.value) }
                is AppResult.Failure -> _state.update { it.copy(isSaving = false, error = r.error.message) }
            }
        }
    }

    fun changePassword() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, message = null) }
            when (val r = authRepository.changePassword(s.currentPassword, s.newPassword)) {
                is AppResult.Success -> _state.update { it.copy(isSaving = false, showPasswordDialog = false, message = "Password updated.", currentPassword = "", newPassword = "") }
                is AppResult.Failure -> _state.update { it.copy(isSaving = false, error = r.error.message) }
            }
        }
    }
}