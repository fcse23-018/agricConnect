package bw.agriconnect.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.NotificationRepository
import bw.agriconnect.domain.model.AppNotification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(
    val items: List<AppNotification> = emptyList(),
    /** True only until the *first* read lands — lists refresh in place after that. */
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
) {
    val unreadCount: Int get() = items.count { !it.isRead }
}

/**
 * Notification feed. Read state is applied optimistically by the repository (local
 * write first, server push best-effort), so the list and the top-bar badge update the
 * moment the user taps — without waiting for the network.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    /** Newest first; the Room query is ordered, but sorting here keeps it explicit. */
    private val _sorted = MutableStateFlow(emptyList<AppNotification>())

    val unreadCount: StateFlow<Int> = repository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            repository.observeAll().collect { rows ->
                _state.update { current -> current.copy(items = rows.sortedByDescending { it.createdAt }, isInitialLoading = false) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            when (val result = repository.refresh()) {
                is AppResult.Success -> _state.update { it.copy(isRefreshing = false) }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isRefreshing = false,
                        // Only surface the failure when we have nothing cached to show;
                        // a refresh that fails over a populated list is not worth
                        // interrupting the feed for.
                        error = if (it.items.isEmpty()) result.error.message else null,
                    )
                }
            }
        }
    }

    fun markRead(notification: AppNotification) {
        if (notification.isRead) return
        viewModelScope.launch { repository.markRead(notification.notificationId) }
    }

    fun markAllRead() {
        viewModelScope.launch { repository.markAllRead() }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}
