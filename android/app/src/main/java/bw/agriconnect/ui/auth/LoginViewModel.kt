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

/** Immutable UI state for the login screen. */
data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val identifierError: String? = null,
    val passwordError: String? = null,
    /** Form-level error from the server (e.g. bad credentials). */
    val formError: String? = null,
    val isSubmitting: Boolean = false,
) {
    val canSubmit: Boolean get() = identifier.isNotBlank() && password.isNotBlank() && !isSubmitting
}

/**
 * Drives the login screen. Validation happens locally first so an obviously empty
 * form never costs a round trip; the server remains the authority (there is no
 * client-side password policy on *sign-in*, only on registration/reset).
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onIdentifierChange(value: String) {
        _state.update { it.copy(identifier = value, identifierError = null, formError = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(password = value, passwordError = null, formError = null) }
    }

    /**
     * Validates, then attempts sign-in. [onSuccess] is invoked on the main thread
     * once the session has been persisted by the repository.
     */
    fun submit(onSuccess: () -> Unit) {
        val current = _state.value
        if (current.isSubmitting) return

        val identifier = current.identifier.trim()
        val identifierError = if (identifier.isBlank()) "Enter your phone number or email." else null
        val passwordError = if (current.password.isBlank()) "Enter your password." else null

        if (identifierError != null || passwordError != null) {
            _state.update { it.copy(identifierError = identifierError, passwordError = passwordError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, formError = null) }
            when (val result = authRepository.login(identifier, current.password)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSubmitting = false) }
                    onSuccess()
                }

                is AppResult.Failure -> {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            formError = result.error.message,
                            identifierError = result.error.fieldErrors["identifier"],
                            passwordError = result.error.fieldErrors["password"],
                        )
                    }
                }
            }
        }
    }
}
