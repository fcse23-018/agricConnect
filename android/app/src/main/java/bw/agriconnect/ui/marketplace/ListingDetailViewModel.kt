package bw.agriconnect.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.MarketplaceRepository
import bw.agriconnect.data.repository.OrderRepository
import bw.agriconnect.data.remote.dto.UpdateListingRequest
import bw.agriconnect.domain.model.ProduceListing
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class ListingDetailUiState(
    val listing: ProduceListing? = null,
    val session: Session? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val orderQuantity: String = "",
    val orderNote: String = "",
    val orderError: String? = null,
    val priceInput: String = "",
)

@HiltViewModel
class ListingDetailViewModel @Inject constructor(
    private val marketplaceRepository: MarketplaceRepository,
    private val orderRepository: OrderRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ListingDetailUiState(session = authRepository.session.value))
    val state: StateFlow<ListingDetailUiState> = _state.asStateFlow()

    private val session = authRepository.session

    fun load(listingId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, message = null) }
            // Network first (upserts the cache), falling back to the cached row when the
            // device is offline — the repo already does the fallback for reads that cannot
            // reach the server via `getListing`, so a null result means "not found yet".
            when (val result = marketplaceRepository.getListing(listingId)) {
                is AppResult.Success -> _state.update { it.copy(listing = result.data, isLoading = false) }
                is AppResult.Failure -> {
                    val cached = readFromCache(listingId)
                    _state.update { current ->
                        if (cached != null) {
                            current.copy(listing = cached, isLoading = false, error = null)
                        } else {
                            current.copy(isLoading = false, error = result.error.message)
                        }
                    }
                }
            }
        }
    }

    private fun readFromCache(listingId: String): ProduceListing? = runBlocking {
        // observeById is a cold Flow over Room; the first emission is the current row.
        marketplaceRepository.observeById(listingId).first()
    }

    fun onOrderQuantityChange(v: String) = _state.update { it.copy(orderQuantity = v, orderError = null) }
    fun onOrderNoteChange(v: String) = _state.update { it.copy(orderNote = v) }
    fun onPriceChange(v: String) = _state.update { it.copy(priceInput = v, error = null) }
    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    fun placeOrder(listingId: String, onPlaced: (String) -> Unit) {
        val quantity = _state.value.orderQuantity.toDoubleOrNull()
        if (quantity == null || quantity <= 0.0) {
            _state.update { it.copy(orderError = "Enter a quantity to order.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, orderError = null) }
            when (val result = orderRepository.createOrder(listingId, quantity, _state.value.orderNote.trim().ifBlank { null })) {
                is AppResult.Success -> {
                    _state.update { it.copy(isBusy = false, message = "Order placed.") }
                    onPlaced(result.data.orderId)
                }
                is AppResult.Failure -> _state.update { it.copy(isBusy = false, orderError = result.error.message) }
            }
        }
    }

    fun publish(listingId: String) = transition(listingId, marketplaceRepository::publishListing) { "Listing published." }
    fun archive(listingId: String) = transition(listingId, marketplaceRepository::archiveListing) { "Listing archived." }

    fun updatePrice(listingId: String, price: String) {
        val value = price.toDoubleOrNull() ?: return
        transition(listingId, { marketplaceRepository.updateListing(listingId, UpdateListingRequest(price = value)) }) { "Price updated." }
    }

    private fun transition(
        listingId: String,
        call: suspend (String) -> AppResult<ProduceListing>,
        successMessage: () -> String,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, error = null, message = null) }
            when (val result = call(listingId)) {
                is AppResult.Success -> _state.update {
                    it.copy(isBusy = false, listing = result.data, message = successMessage())
                }
                is AppResult.Failure -> _state.update { it.copy(isBusy = false, error = result.error.message) }
            }
        }
    }
}
