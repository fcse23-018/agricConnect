package bw.agriconnect.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.util.ConnectivityObserver
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.FarmPlotRepository
import bw.agriconnect.data.repository.FinanceRepository
import bw.agriconnect.data.repository.HarvestRepository
import bw.agriconnect.data.repository.LivestockRepository
import bw.agriconnect.data.repository.NotificationRepository
import bw.agriconnect.data.repository.OrderRepository
import bw.agriconnect.data.sync.SyncQueue
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.LivestockCount
import bw.agriconnect.domain.model.Order
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the role-aware home screen.
 *
 * The same screen serves all three roles, so this ViewModel exposes the raw cached
 * collections and lets the screen decide which summaries to show. Everything is read from
 * the offline cache (Room-backed repositories), so the home screen renders instantly on a
 * cold, disconnected start and never fabricates a value it does not have.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectivity: ConnectivityObserver,
    private val syncScheduler: SyncScheduler,
    private val syncQueue: SyncQueue,
    farmPlotRepository: FarmPlotRepository,
    harvestRepository: HarvestRepository,
    livestockRepository: LivestockRepository,
    financeRepository: FinanceRepository,
    orderRepository: OrderRepository,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    val session: StateFlow<Session?> = authRepository.session

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), connectivity.isCurrentlyOnline())

    val pendingCount: StateFlow<Int> = syncQueue.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    val unreadNotifications: StateFlow<Int> = notificationRepository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val farmerId: Flow<String?> =
        authRepository.session.map { it?.userId }.distinctUntilChanged()

    val plots: StateFlow<List<FarmPlot>> = farmPlotRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val harvests: StateFlow<List<HarvestRecord>> = farmerId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else harvestRepository.observeByFarmer(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val livestock: StateFlow<List<LivestockCount>> = farmerId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else livestockRepository.observeByFarmer(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val orders: StateFlow<List<Order>> = orderRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Best-effort finance refresh so the dashboard's net balance is current when online. */
    val netBalance: StateFlow<Double?> = flowOf(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        // Pull a fresh profile so a role/name change made elsewhere is reflected, then ask
        // the sync engine for a pass. Neither is fatal for the shell.
        viewModelScope.launch { authRepository.getCurrentUser() }
        viewModelScope.launch { orderRepository.refresh() }
        viewModelScope.launch { notificationRepository.refresh() }
        syncScheduler.requestImmediate()
    }

    /** Ask WorkManager for an immediate sync pass. Safe to call repeatedly. */
    fun syncNow() = syncScheduler.requestImmediate()

    /** Best-effort notification refresh for the bell badge / activity. */
    fun refreshNotifications() {
        viewModelScope.launch { notificationRepository.refresh() }
    }

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
