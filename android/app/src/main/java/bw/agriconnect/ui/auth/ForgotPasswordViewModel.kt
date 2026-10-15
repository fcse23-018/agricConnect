package bw.agriconnect.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ResetStep { Request, Verify, Reset, Done }

data class ForgotUiState(
    val identifier: String = "",
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val step: ResetStep = ResetStep.Request,
    val resetToken: String? = null,
    val message: String? = null,
    val error: String? = null,
    val isBusy: Boolean = false,
)

@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ForgotUiState())
    val state: StateFlow<ForgotUiState> = _state.asStateFlow()

    fun onIdentifierChange(v: String) = _state.update { it.copy(identifier = v, error = null) }
    fun onCodeChange(v: String) = _state.update { it.copy(code = v, error = null) }
    fun onNewPasswordChange(v: String) = _state.update { it.copy(newPassword = v, error = null) }
    fun onConfirmPasswordChange(v: String) = _state.update { it.copy(confirmPassword = v, error = null) }

    fun requestCode(onDone: (() -> Unit)? = null) {
        val s = _state.value
        if (s.identifier.isBlank()) { _state.update { it.copy(error = "Enter phone or email.") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            when (val r = authRepository.requestPasswordReset(s.identifier.trim())) {
                is AppResult.Success -> _state.update { it.copy(isBusy = false, step = ResetStep.Verify, message = "Reset code sent. Enter it below.") }
                is AppResult.Failure -> _state.update { it.copy(isBusy = false, error = r.error.message) }
            }
        }
    }

    fun verify() {
        val s = _state.value
        if (s.code.isBlank()) { _state.update { it.copy(error = "Enter the reset code.") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            when (val r = authRepository.verifyPasswordResetCode(s.identifier.trim(), s.code.trim())) {
                is AppResult.Success -> _state.update { it.copy(isBusy = false, step = ResetStep.Reset, resetToken = r.data, message = "Code verified. Choose a new password.") }
                is AppResult.Failure -> _state.update { it.copy(isBusy = false, error = r.error.message) }
            }
        }
    }

    fun reset(onDone: () -> Unit) {
        val s = _state.value
        val token = s.resetToken ?: return
        if (s.newPassword.length < 8) { _state.update { it.copy(error = "Use at least 8 characters.") }; return }
        if (s.confirmPassword != s.newPassword) { _state.update { it.copy(error = "Passwords do not match.") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            when (val r = authRepository.resetPassword(token, s.newPassword)) {
                is AppResult.Success -> { _state.update { it.copy(isBusy = false, step = ResetStep.Done, message = "Password updated.") }; onDone() }
                is AppResult.Failure -> _state.update { it.copy(isBusy = false, error = r.error.message) }
            }
        }
    }
}