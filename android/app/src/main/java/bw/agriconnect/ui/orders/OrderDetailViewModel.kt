package bw.agriconnect.ui.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.OrderRepository
import bw.agriconnect.domain.model.Order
import bw.agriconnect.domain.model.OrderStatus
import bw.agriconnect.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderDetailUiState(
    val isBusy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/**
 * Order detail + lifecycle actions.
 *
 * Orders are created and transitioned **server-side** inside a stock transaction
 * (docs/02-API-CONTRACT.md §orders), so this screen performs no optimistic local
 * mutation: each action calls the repo, which upserts the row the server returns and
 * then re-emits through [OrderRepository.observeById]. While offline the call fails
 * with a network error and the UI says so honestly rather than pretending success.
 */
@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orders: OrderRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    val orderId: String = checkNotNull(savedStateHandle[Routes.ARG_ORDER_ID])

    private val _state = MutableStateFlow(OrderDetailUiState())
    val state: StateFlow<OrderDetailUiState> = _state.asStateFlow()

    val session = authRepository.session

    val order: StateFlow<Order?> = orders.observeById(orderId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Best-effort fetch of the freshest copy; a failure just leaves the cached row.
        viewModelScope.launch { orders.refresh() }
    }

    /** True when the signed-in user is the farmer who owns the listing. */
    fun isSeller(order: Order): Boolean = session.value?.userId == order.farmerId

    fun isBuyer(order: Order): Boolean = session.value?.userId == order.buyerId

    fun canConfirm(order: Order): Boolean = isSeller(order) && order.status == OrderStatus.PENDING

    fun canReject(order: Order): Boolean = isSeller(order) && order.status == OrderStatus.PENDING

    fun canComplete(order: Order): Boolean = order.status == OrderStatus.CONFIRMED

    fun canCancel(order: Order): Boolean =
        order.status in setOf(OrderStatus.PENDING, OrderStatus.CONFIRMED)

    fun confirm() = run("Order confirmed.") { orders.confirm(orderId) }
    fun reject() = run("Order rejected — stock was returned to the listing.") { orders.reject(orderId) }
    fun cancel() = run("Order cancelled.") { orders.cancel(orderId) }
    fun complete() = run("Order marked as completed.") { orders.complete(orderId) }

    private fun run(successMessage: String, call: suspend () -> AppResult<Order>) {
        if (_state.value.isBusy) return
        _state.value = _state.value.copy(isBusy = true, error = null, message = null)
        viewModelScope.launch {
            _state.value = when (val result = call()) {
                is AppResult.Success -> _state.value.copy(isBusy = false, message = successMessage)
                is AppResult.Failure -> _state.value.copy(isBusy = false, error = result.error.message)
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }
}
