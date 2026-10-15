package bw.agriconnect.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AccountRepository
import bw.agriconnect.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountDeletionUiState(
    val password: String = "",
    val confirmed: Boolean = false,
    val isDeleting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AccountDeletionViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountDeletionUiState())
    val state: StateFlow<AccountDeletionUiState> = _state.asStateFlow()

    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onConfirmedChange(v: Boolean) = _state.update { it.copy(confirmed = v) }

    fun delete(onDeleted: () -> Unit) {
        val s = _state.value
        if (!s.confirmed) { _state.update { it.copy(error = "Confirm deletion to continue.") }; return }
        if (s.password.isBlank()) { _state.update { it.copy(error = "Enter your password to confirm.") }; return }
        viewModelScope.launch {
            _state.update { it.copy(isDeleting = true, error = null) }
            when (val r = accountRepository.requestAccountDeletion(s.password)) {
                is AppResult.Success -> {
                    sessionManager.signOut()
                    _state.update { it.copy(isDeleting = false) }
                    onDeleted()
                }
                is AppResult.Failure -> _state.update { it.copy(isDeleting = false, error = r.error.message) }
            }
        }
    }
}