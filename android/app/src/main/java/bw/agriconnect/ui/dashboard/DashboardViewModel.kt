package bw.agriconnect.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.util.ConnectivityObserver
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.sync.SyncQueue
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the (minimal) dashboard: who is signed in, whether we are online, how many
 * local changes are still queued, and the two actions a shell needs — manual sync
 * and sign-out.
 *
 * Feature summaries (plots, harvests, finance) are deliberately absent here; the
 * repositories for them are not wired into the shell yet. See PROGRESS.md.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectivity: ConnectivityObserver,
    private val syncScheduler: SyncScheduler,
    private val syncQueue: SyncQueue,
) : ViewModel() {

    /** The signed-in session, or null if it was cleared (e.g. a failed refresh). */
    val session: StateFlow<Session?> = authRepository.session

    /** Validated connectivity, used to drive the offline banner. */
    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = connectivity.isCurrentlyOnline(),
        )

    /** Reactive outbox depth for the "N waiting to sync" strip. */
    val pendingCount: StateFlow<Int> = syncQueue.pendingCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = 0,
        )

    init {
        // Best-effort profile refresh so a name/role change made elsewhere appears.
        // Failures are ignored: the cached session is authoritative for the shell.
        viewModelScope.launch { authRepository.getCurrentUser() }
    }

    /** Ask WorkManager for an immediate sync pass. Safe to call repeatedly. */
    fun syncNow() = syncScheduler.requestImmediate()

    /** Revokes the session (best-effort server-side) then invokes [onDone]. */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onDone()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
