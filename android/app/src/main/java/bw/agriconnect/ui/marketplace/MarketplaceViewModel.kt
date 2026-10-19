package bw.agriconnect.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.MarketplaceRepository
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.ProduceListing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketplaceUiState(
    val query: String = "",
    val category: String = "All",
    val listings: List<ProduceListing> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** True when [listings] came from the offline cache rather than a fresh response. */
    val isFromCache: Boolean = false,
)

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val repo: MarketplaceRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MarketplaceUiState())
    val state: StateFlow<MarketplaceUiState> = _state.asStateFlow()

    /** Only a farm owner may create listings (docs/02-API-CONTRACT.md §marketplace). */
    val canCreateListing: StateFlow<Boolean> = authRepository.session
        .map { it?.role?.canSell == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init { refresh() }

    fun onQueryChange(v: String) = _state.update { it.copy(query = v) }
    fun onCategoryChange(c: String) = _state.update { it.copy(category = c) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val s = _state.value
            val crop = if (s.category == "All") null else s.category
            when (val r = repo.listPage(search = s.query.ifBlank { null }, crop = crop)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        listings = r.data.items.map { dto -> dto.toDomain() },
                        isFromCache = false,
                    )
                }

                is AppResult.Failure -> {
                    // Offline (or the server rejected the query): fall back to whatever the
                    // cache holds, applying the free-text search locally. We never invent
                    // results — an empty cache simply yields an empty list plus the reason.
                    val cached = repo.observePublished().first()
                    val filtered = cached.filter { it.matches(s.query) }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            listings = if (filtered.isNotEmpty()) filtered else it.listings,
                            isFromCache = true,
                            error = r.error.message,
                        )
                    }
                }
            }
        }
    }
}

private fun ProduceListing.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return sequenceOf(produce, description.orEmpty(), locationText.orEmpty(), farmerName.orEmpty())
        .any { it.contains(q, ignoreCase = true) }
}
