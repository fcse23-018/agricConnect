package bw.agriconnect.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.MoneyFormat
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriFilterChip
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InlineLoading
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

private val categories = listOf("All", "Vegetables", "Grains", "Fruits", "Livestock", "Other")

/**
 * The public produce catalogue. Reads are offline-first (a failed refresh falls back to
 * the last-saved catalogue, clearly labelled), and the "create listing" affordance is
 * shown only to farmers because the server restricts listing creation to that role.
 */
@Composable
fun MarketplaceScreen(
    onListingClick: (String) -> Unit,
    onCreateListing: () -> Unit,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canCreateListing by viewModel.canCreateListing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    MainScaffold(
        title = "Marketplace",
        onBack = null,
        floatingActionButton = {
            if (canCreateListing) {
                FloatingActionButton(
                    onClick = onCreateListing,
                    containerColor = AgriColors.ForestGreen,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Create listing",
                        tint = AgriColors.PureWhite,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            Column(
                modifier = Modifier.padding(top = AgriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            ) {
                AgriTextField(
                    value = state.query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    label = "Search produce or crops",
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    categories.forEach { category ->
                        AgriFilterChip(
                            label = category,
                            selected = state.category == category,
                            onClick = {
                                viewModel.onCategoryChange(category)
                                viewModel.refresh()
                            },
                        )
                    }
                }
                SectionHeader(title = "Available produce")
            }

            when {
                state.isLoading && state.listings.isEmpty() ->
                    LoadingState(message = "Loading marketplace…")

                state.listings.isEmpty() && state.error != null -> EmptyState(
                    title = "Could not load marketplace",
                    message = state.error.orEmpty(),
                    actionLabel = "Try again",
                    onAction = viewModel::refresh,
                )

                state.listings.isEmpty() -> EmptyState(
                    title = "No listings",
                    message = if (canCreateListing) {
                        "No produce is available yet, or your filters returned nothing. Add your first listing to get started."
                    } else {
                        "No produce is available yet, or your filters returned nothing."
                    },
                    actionLabel = if (canCreateListing) "Create listing" else "Refresh",
                    onAction = if (canCreateListing) onCreateListing else viewModel::refresh,
                )

                else -> {
                    if (state.isLoading) InlineLoading(message = "Refreshing…")

                    if (state.error != null) {
                        ErrorBanner(
                            message = if (state.isFromCache) {
                                "Showing the last saved catalogue — ${state.error}"
                            } else {
                                state.error.orEmpty()
                            },
                            onRetry = viewModel::refresh,
                            onDismiss = viewModel::clearError,
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = AgriSpacing.xxl),
                    ) {
                        items(state.listings, key = { it.listingId }) { listing ->
                            AgriCard(onClick = { onListingClick(listing.listingId) }) {
                                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
                                    Text(listing.produce, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${listing.availableQty} ${listing.unit.label} • ${MoneyFormat.pula(listing.price)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    listing.farmerName?.takeIf { it.isNotBlank() }?.let {
                                        Text("Seller: $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                    listing.locationText?.takeIf { it.isNotBlank() }?.let {
                                        Text("Location: $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
