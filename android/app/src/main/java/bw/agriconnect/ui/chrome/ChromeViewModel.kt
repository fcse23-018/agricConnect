package bw.agriconnect.ui.chrome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.util.ConnectivityObserver
import bw.agriconnect.data.repository.NotificationRepository
import bw.agriconnect.data.sync.SyncQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The small, app-wide bits of state every screen chrome needs: connectivity, outbox
 * depth and the unread-notification badge. Centralising them here means a screen's own
 * ViewModel only has to worry about its domain, and the offline / "N waiting to sync" /
 * bell-badge behaviour is identical everywhere by construction.
 */
@HiltViewModel
class ChromeViewModel @Inject constructor(
    connectivity: ConnectivityObserver,
    syncQueue: SyncQueue,
    notificationRepository: NotificationRepository,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), connectivity.isCurrentlyOnline())

    val pendingSyncCount: StateFlow<Int> = syncQueue.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    val unreadNotifications: StateFlow<Int> = notificationRepository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
