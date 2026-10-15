package bw.agriconnect.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.MarketplaceRepository
import bw.agriconnect.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateListingUiState(
    val produce: String = "",
    val description: String = "",
    val quantity: String = "",
    val unit: String = "kg",
    val price: String = "",
    val locationText: String = "",
    val error: String? = null,
    val isSaving: Boolean = false,
)

@HiltViewModel
class CreateListingViewModel @Inject constructor(
    private val repo: MarketplaceRepository,
    private val auth: bw.agriconnect.data.repository.AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateListingUiState())
    val state: StateFlow<CreateListingUiState> = _state.asStateFlow()

    fun onProduceChange(v: String) = _state.update { it.copy(produce = v, error = null) }
    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v) }
    fun onQuantityChange(v: String) = _state.update { it.copy(quantity = v) }
    fun onUnitChange(v: String) = _state.update { it.copy(unit = v) }
    fun onPriceChange(v: String) = _state.update { it.copy(price = v) }
    fun onLocationChange(v: String) = _state.update { it.copy(locationText = v) }

    fun save(onSaved: (String) -> Unit) {
        val s = _state.value
        val q = s.quantity.toDoubleOrNull()
        val p = s.price.toDoubleOrNull()
        if (s.produce.isBlank() || q == null || p == null) {
            _state.update { it.copy(error = "Enter produce, quantity and price.") }; return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val req = bw.agriconnect.data.remote.dto.CreateListingRequest(
                produce = s.produce.trim(),
                description = s.description.trim().ifBlank { null },
                quantity = q,
                unit = s.unit,
                price = p,
                locationText = s.locationText.trim().ifBlank { null },
            )
            when (val r = repo.createListing(req)) {
                is bw.agriconnect.core.result.AppResult.Success -> {
                    // Publish immediately so the farmer's new stock is visible to buyers.
                    // A publish failure is surfaced rather than silently swallowed.
                    when (val published = repo.publishListing(r.data.listingId)) {
                        is bw.agriconnect.core.result.AppResult.Success -> {
                            _state.update { it.copy(isSaving = false) }
                            onSaved(published.data.listingId)
                        }
                        is bw.agriconnect.core.result.AppResult.Failure -> {
                            _state.update {
                                it.copy(
                                    isSaving = false,
                                    error = "Listing saved as a draft, but publishing failed: ${published.error.message}",
                                )
                            }
                            onSaved(r.data.listingId)
                        }
                    }
                }
                is bw.agriconnect.core.result.AppResult.Failure -> _state.update { it.copy(isSaving = false, error = r.error.message) }
            }
        }
    }
}