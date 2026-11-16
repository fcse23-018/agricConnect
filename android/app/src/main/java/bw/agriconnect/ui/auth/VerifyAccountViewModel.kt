package bw.agriconnect.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.User
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VerifyAccountUiState(
    val step: VerifyStep = VerifyStep.LOADING,
    val user: User? = null,
    /** Phone number or email the code is requested for; pre-filled from the account. */
    val identifier: String = "",
    val code: String = "",
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    /** Server acknowledgement or a local success sentence. */
    val message: String? = null,
    val error: String? = null,
    /** Present only when a non-production server exposes dev codes. */
    val devCode: String? = null,
    /** Honest report of what the transport did; null means "not reported". */
    val deliveryNotice: String? = null,
    val codeSent: Boolean = false,
    val resendSeconds: Int = 0,
    val expiresInMinutes: Int = 10,
) {
    val canSubmitCode: Boolean get() = isCodeComplete(code) && !isBusy
}

/**
 * Drives the verification journey against the endpoints that already exist:
 * `POST /auth/verification/request` and `POST /auth/verification/confirm`.
 *
 * It never advances to "verified" on a local assumption — the server's answer (or
 * the refreshed user it returns) is the only thing that flips the state.
 */
@HiltViewModel
class VerifyAccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VerifyAccountUiState())
    val state: StateFlow<VerifyAccountUiState> = _state.asStateFlow()

    private var cooldown: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = authRepository.getCurrentUser()) {
                is AppResult.Success -> {
                    val user = result.data
                    _state.update {
                        it.copy(
                            isLoading = false,
                            user = user,
                            // The account's own number is the default destination.
                            identifier = user.phoneNumber,
                            step = verifyStepFor(user.accountStatus),
                            error = null,
                        )
                    }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isLoading = false,
                        step = VerifyStep.LOADING,
                        error = result.error.message,
                    )
                }
            }
        }
    }

    fun onIdentifierChange(value: String) =
        _state.update { it.copy(identifier = value, error = null) }

    fun onCodeChange(value: String) =
        _state.update { it.copy(code = sanitiseCode(value), error = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissDeliveryNotice() = _state.update { it.copy(deliveryNotice = null) }

    /** Return to the identifier step so a wrong number can be corrected. */
    fun editIdentifier() = _state.update {
        if (it.step == VerifyStep.ENTER_CODE) {
            it.copy(
                step = VerifyStep.REQUEST_CODE,
                code = "",
                codeSent = false,
                message = null,
                deliveryNotice = null,
                devCode = null,
                error = null,
            )
        } else {
            it
        }
    }

    fun sendCode() {
        val current = _state.value
        if (current.isBusy) return
        val identifier = current.identifier.trim()
        if (identifier.isBlank()) {
            _state.update { it.copy(error = "Enter the phone number or email for this account.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null, message = null) }
            when (val result = authRepository.requestVerificationCode(identifier)) {
                is AppResult.Success -> {
                    val dispatch = result.data
                    _state.update {
                        it.copy(
                            isBusy = false,
                            step = VerifyStep.ENTER_CODE,
                            codeSent = true,
                            identifier = identifier,
                            message = if (dispatch.delivered == false) null else dispatch.message,
                            deliveryNotice = deliveryNotice(
                                delivered = dispatch.delivered,
                                reason = dispatch.undeliveredReason,
                                channel = dispatch.channel,
                            ),
                            devCode = dispatch.devCode,
                            expiresInMinutes = dispatch.expiresInMinutes,
                            error = null,
                        )
                    }
                    startCooldown()
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isBusy = false, error = result.error.message)
                }
            }
        }
    }

    fun resendCode() {
        if (_state.value.resendSeconds > 0) return
        sendCode()
    }

    fun verify() {
        val current = _state.value
        if (current.isBusy) return
        val code = current.code
        if (!isCodeComplete(code)) {
            _state.update { it.copy(error = "Enter the 6-digit code.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null) }
            when (val result = authRepository.confirmVerification(current.identifier.trim(), code)) {
                is AppResult.Success -> {
                    cooldown?.cancel()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            step = VerifyStep.VERIFIED,
                            user = result.data,
                            code = "",
                            message = "Your account is verified.",
                            deliveryNotice = null,
                            devCode = null,
                            resendSeconds = 0,
                            error = null,
                        )
                    }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(isBusy = false, error = result.error.message)
                }
            }
        }
    }

    /**
     * A short client-side pause between sends. The server also rate-limits; this
     * only stops an impatient double-tap and the confusion of two valid codes
     * (the newest one invalidates the previous).
     */
    private fun startCooldown(seconds: Int = RESEND_COOLDOWN_SECONDS) {
        cooldown?.cancel()
        cooldown = viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                _state.update { it.copy(resendSeconds = remaining) }
                delay(1_000)
                remaining -= 1
            }
            _state.update { it.copy(resendSeconds = 0) }
        }
    }

    private companion object {
        const val RESEND_COOLDOWN_SECONDS = 45
    }
}
