package bw.agriconnect.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.util.ImageFiles
import bw.agriconnect.data.repository.MarketplaceRepository
import bw.agriconnect.data.repository.MediaRepository
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.domain.model.Unit as MeasureUnit
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
    /**
     * The chosen unit. Held as the enum, not free text, so an invalid unit cannot
     * reach the server — typing "cattle" used to produce a 400 at publish time.
     */
    val unit: MeasureUnit = MeasureUnit.KG,
    val price: String = "",
    val locationText: String = "",
    /** Device-local URI of a picked-but-not-yet-uploaded photo, for the preview. */
    val imagePreview: String? = null,
    /** The picked bytes staged for upload; null when no photo was chosen. */
    val pendingImage: ImageFiles.Picked? = null,
    val isUploading: Boolean = false,
    val error: String? = null,
    val isSaving: Boolean = false,
) {
    val isBusy: Boolean get() = isSaving || isUploading

    /**
     * Livestock is counted, not weighed, so the quantity field must accept a whole
     * number ("1 herd", "20 head") and the price label changes to read naturally.
     */
    val isLivestock: Boolean get() = unit.livestock
}

@HiltViewModel
class CreateListingViewModel @Inject constructor(
    private val repo: MarketplaceRepository,
    private val media: MediaRepository,
    private val auth: bw.agriconnect.data.repository.AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateListingUiState())
    val state: StateFlow<CreateListingUiState> = _state.asStateFlow()

    fun onProduceChange(v: String) = _state.update { it.copy(produce = v, error = null) }
    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v) }
    fun onQuantityChange(v: String) = _state.update { it.copy(quantity = v) }
    fun onUnitChange(v: MeasureUnit) = _state.update { it.copy(unit = v, error = null) }
    fun onPriceChange(v: String) = _state.update { it.copy(price = v) }
    fun onLocationChange(v: String) = _state.update { it.copy(locationText = v) }

    /** Stages a picked photo; the upload itself happens on save so a cancel costs nothing. */
    fun onImagePicked(preview: String, picked: ImageFiles.Picked) =
        _state.update { it.copy(imagePreview = preview, pendingImage = picked, error = null) }

    fun onImageCleared() =
        _state.update { it.copy(imagePreview = null, pendingImage = null) }

    /** Surfaces a pick/read failure (e.g. unsupported type or an unreadable URI). */
    fun onImageError(message: String) = _state.update { it.copy(error = message) }

    fun save(onSaved: (String) -> Unit) {
        val s = _state.value
        val q = s.quantity.toDoubleOrNull()
        val p = s.price.toDoubleOrNull()
        if (s.produce.isBlank() || q == null || p == null) {
            _state.update { it.copy(error = "Enter produce, quantity and price.") }; return
        }
        // "Animals" are counted in whole animals; half a head of cattle is not a
        // listing. This is a local guard so the user is told before a round-trip.
        if (s.isLivestock && q % 1.0 != 0.0) {
            _state.update {
                it.copy(error = "Livestock is sold in whole animals — enter a whole number (e.g. 3 ${s.unit.label}).")
            }
            return
        }
        if (q <= 0 || p <= 0) {
            _state.update { it.copy(error = "Quantity and price must be greater than zero.") }; return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }

            // Upload the photo first (when one was picked) so the listing is created with
            // its final image URL in a single write. A failed upload aborts the save
            // rather than publishing a listing the user believes has a photo.
            val imageUrl = s.pendingImage?.let { picked ->
                _state.update { it.copy(isUploading = true) }
                when (val up = media.uploadImage(picked.bytes, picked.mimeType, picked.fileName)) {
                    is AppResult.Success -> up.data
                    is AppResult.Failure -> {
                        _state.update {
                            it.copy(isSaving = false, isUploading = false, error = up.error.message)
                        }
                        return@launch
                    }
                }
            }
            _state.update { it.copy(isUploading = false) }

            val req = bw.agriconnect.data.remote.dto.CreateListingRequest(
                produce = s.produce.trim(),
                description = s.description.trim().ifBlank { null },
                quantity = q,
                unit = s.unit.wire,
                price = p,
                imageUrl = imageUrl,
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