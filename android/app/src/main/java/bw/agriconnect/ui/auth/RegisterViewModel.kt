package bw.agriconnect.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUiState(
    val fullName: String = "",
    val phone: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val role: UserRole = UserRole.FARMER,
    val acceptTerms: Boolean = true,
    val fullNameError: String? = null,
    val phoneError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val formError: String? = null,
    val isSubmitting: Boolean = false,
) {
    val canSubmit: Boolean get() = !isSubmitting
}

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    fun onFullNameChange(v: String) = _state.update { it.copy(fullName = v, fullNameError = null, formError = null) }
    fun onPhoneChange(v: String) = _state.update { it.copy(phone = v, phoneError = null, formError = null) }
    fun onEmailChange(v: String) = _state.update { it.copy(email = v, emailError = null, formError = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, passwordError = null, formError = null) }
    fun onConfirmPasswordChange(v: String) = _state.update { it.copy(confirmPassword = v, confirmPasswordError = null, formError = null) }
    fun onRoleChange(v: UserRole) = _state.update { it.copy(role = v) }
    fun onAcceptTermsChange(v: Boolean) = _state.update { it.copy(acceptTerms = v) }

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.isSubmitting) return
        var fullNameError: String? = null
        var phoneError: String? = null
        var emailError: String? = null
        var passwordError: String? = null
        var confirmError: String? = null
        if (s.fullName.trim().length < 2) fullNameError = "Enter your full name."
        if (s.phone.trim().length < 7) phoneError = "Enter a valid phone number."
        if (s.email.isNotBlank()) {
            if (!s.email.contains("@")) emailError = "Enter a valid email."
        }
        if (s.password.length < 8) passwordError = "Use at least 8 characters."
        if (s.confirmPassword != s.password) confirmError = "Passwords do not match."
        if (!s.acceptTerms) fullNameError = "Accept the terms to continue."
        if (listOf(fullNameError, phoneError, emailError, passwordError, confirmError).any { it != null }) {
            _state.update { it.copy(fullNameError = fullNameError, phoneError = phoneError, emailError = emailError, passwordError = passwordError, confirmPasswordError = confirmError) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, formError = null) }
            when (val result = authRepository.register(
                fullName = s.fullName.trim(),
                phoneNumber = s.phone.trim(),
                email = s.email.trim().ifBlank { null },
                password = s.password,
                role = s.role,
                acceptTerms = s.acceptTerms,
                acceptPrivacy = s.acceptTerms,
            )) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSubmitting = false) }
                    onSuccess()
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(isSubmitting = false, formError = result.error.message) }
                }
            }
        }
    }
}