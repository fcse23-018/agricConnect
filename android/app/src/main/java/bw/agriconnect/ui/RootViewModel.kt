package bw.agriconnect.ui

import androidx.lifecycle.ViewModel
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the signed-in [Session] to the navigation host so it can choose the correct
 * role-based bottom navigation and guard privileged destinations. It holds no other
 * state — screen state belongs to the screen's own ViewModel.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {
    val session: StateFlow<Session?> = authRepository.session
}
