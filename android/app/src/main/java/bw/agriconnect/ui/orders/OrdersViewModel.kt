package bw.agriconnect.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.OrderRepository
import bw.agriconnect.domain.model.Order
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrdersUiState(
    val orders: List<Order> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTab: Int = 0,
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repo: OrderRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OrdersUiState())
    val state: StateFlow<OrdersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeAll().collect { rows -> _state.update { it.copy(orders = rows, isLoading = false) } }
        }
        refresh()
    }

    fun onTabChange(i: Int) = _state.update { it.copy(selectedTab = i) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val r = repo.refresh()) {
                is AppResult.Failure -> _state.update { it.copy(isLoading = false, error = r.error.message) }
                else -> Unit
            }
        }
    }

    fun cancel(id: String) = viewModelScope.launch { if (repo.cancel(id) is AppResult.Success) refresh() }
    fun complete(id: String) = viewModelScope.launch { if (repo.complete(id) is AppResult.Success) refresh() }
}

fun Order.isActive(): Boolean = status.name in setOf("PENDING", "CONFIRMED")
fun Order.isHistory(): Boolean = !isActive()