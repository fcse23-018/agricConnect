package bw.agriconnect.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.MoneyFormat
import bw.agriconnect.domain.model.ListingStatus
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.SyncStatusBadge

/**
 * Listing detail. Two roles share one screen:
 *  - the owning farmer edits price/stock and publishes or archives the listing;
 *  - a buyer fills in a quantity and places an order against real stock.
 *
 * Everything renders from the network-first read in [ListingDetailViewModel], which
 * falls back to the Room cache when the device is offline.
 */
@Composable
fun ListingDetailScreen(
    listingId: String,
    onBack: () -> Unit,
    onOrderPlaced: (String) -> Unit,
    viewModel: ListingDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listing = state.listing

    LaunchedEffect(listingId) { viewModel.load(listingId) }

    MainScaffold(
        title = listing?.produce ?: "Listing",
        subtitle = listing?.let { "${it.quantity} ${it.unit.label} available" },
        onBack = onBack,
    ) { padding ->
        when {
            state.isLoading && listing == null -> LoadingState(
                modifier = Modifier.padding(padding),
                message = "Loading listing…",
            )

            listing == null -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AgriSpacingLg),
                verticalArrangement = Arrangement.spacedBy(AgriSpacingMd),
            ) {
                ErrorBanner(
                    message = state.error ?: "This listing is unavailable. It may have been removed.",
                    onRetry = { viewModel.load(listingId) },
                )
                AgriSecondaryButton(text = "Go back", onClick = onBack)
            }

            else -> ListingDetailContent(
                state = state,
                listingId = listingId,
                onBack = onBack,
                onOrderPlaced = onOrderPlaced,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ListingDetailContent(
    state: ListingDetailUiState,
    listingId: String,
    onBack: () -> Unit,
    onOrderPlaced: (String) -> Unit,
    viewModel: ListingDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val listing = state.listing ?: return
    val session = state.session
    val isOwner = session != null && session.userId == listing.farmerId
    val status = listing.status
    val isPublic = status == ListingStatus.PUBLISHED

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AgriSpacingLg)
            .padding(bottom = AgriSpacingLg),
        verticalArrangement = Arrangement.spacedBy(AgriSpacingMd),
    ) {
        state.error?.let { message ->
            ErrorBanner(message = message, onDismiss = { viewModel.clearMessage() })
        }
        state.message?.let { message ->
            InfoBanner(message = message)
        }

        AgriCard {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacingSm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = listing.produce,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    SyncStatusBadge(status = listing.syncStatus)
                }
                Text(
                    text = listing.farmerName?.takeIf { it.isNotBlank() } ?: "Your listing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacingMd)) {
                    Column {
                        Text("Price", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${MoneyFormat.pula(listing.price)}/${listing.unit.label}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column {
                        Text("Available", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${listing.availableQty} ${listing.unit.label}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column {
                        Text("Status", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = status.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (!listing.description.isNullOrBlank()) {
                    Text(
                        text = listing.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                listing.locationText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Location: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (isOwner) {
            OwnerActions(
                state = state,
                listingId = listingId,
                isPublic = isPublic,
                viewModel = viewModel,
            )
        } else {
            BuyerOrderForm(
                state = state,
                listingId = listingId,
                onOrderPlaced = onOrderPlaced,
                viewModel = viewModel,
            )
        }

        HorizontalDivider()
        AgriSecondaryButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun OwnerActions(
    state: ListingDetailUiState,
    listingId: String,
    isPublic: Boolean,
    viewModel: ListingDetailViewModel,
) {
    var showPriceEditor by remember { mutableStateOf(false) }
    val listing = state.listing

    AgriCard {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacingMd)) {
            Text("Manage listing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (listing != null && listing.availableQty <= 0.0 && isPublic) {
                ErrorBanner(message = "Sold out — update stock to keep selling.")
            }

            if (showPriceEditor) {
                AgriTextField(
                    value = state.priceInput,
                    onValueChange = viewModel::onPriceChange,
                    label = "New price (P per ${listing?.unit?.label ?: "unit"})",
                    keyboardType = KeyboardType.Decimal,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacingSm)) {
                    AgriSecondaryButton(
                        text = "Cancel",
                        onClick = { showPriceEditor = false },
                        fillWidth = false,
                    )
                    AgriPrimaryButton(
                        text = "Save price",
                        onClick = {
                            viewModel.updatePrice(listingId, state.priceInput)
                            showPriceEditor = false
                        },
                        enabled = !state.isBusy && state.priceInput.toDoubleOrNull() != null,
                        fillWidth = false,
                    )
                }
            } else {
                AgriSecondaryButton(
                    text = "Update price",
                    onClick = {
                        viewModel.onPriceChange(listing?.price?.toString().orEmpty())
                        showPriceEditor = true
                    },
                    enabled = !state.isBusy,
                )
            }

            if (isPublic) {
                AgriSecondaryButton(
                    text = "Archive listing",
                    onClick = { viewModel.archive(listingId) },
                    enabled = !state.isBusy,
                    accent = true,
                )
                Text(
                    text = "Archiving hides the listing from buyers. You can publish it again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AgriPrimaryButton(
                    text = "Publish listing",
                    onClick = { viewModel.publish(listingId) },
                    enabled = !state.isBusy && (listing?.availableQty ?: 0.0) > 0.0,
                    loading = state.isBusy,
                )
                if ((listing?.availableQty ?: 0.0) <= 0.0) {
                    Text(
                        text = "Add stock before publishing — an empty listing cannot be ordered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BuyerOrderForm(
    state: ListingDetailUiState,
    listingId: String,
    onOrderPlaced: (String) -> Unit,
    viewModel: ListingDetailViewModel,
) {
    val listing = state.listing ?: return
    val quantity = state.orderQuantity.toDoubleOrNull() ?: 0.0
    val exceedsStock = quantity > listing.availableQty
    val total = listing.price * quantity

    AgriCard {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacingMd)) {
            Text("Place an order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (state.session == null) {
                ErrorBanner(message = "Sign in as a buyer to place an order.")
            } else if (state.orderError != null) {
                ErrorBanner(message = state.orderError.orEmpty())
            } else if (listing.status != ListingStatus.PUBLISHED) {
                InfoBanner(message = "This listing is not currently on sale.")
            }

            AgriTextField(
                value = state.orderQuantity,
                onValueChange = viewModel::onOrderQuantityChange,
                label = "Quantity (${listing.unit.label})",
                keyboardType = KeyboardType.Decimal,
            )
            AgriTextField(
                value = state.orderNote,
                onValueChange = viewModel::onOrderNoteChange,
                label = "Note to the farmer (optional)",
            )

            Text(
                text = if (quantity > 0.0 && !exceedsStock) {
                    "Total: ${MoneyFormat.pula(total)}"
                } else {
                    "Available: ${listing.availableQty} ${listing.unit.label}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (exceedsStock) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            AgriPrimaryButton(
                text = "Place order",
                onClick = { viewModel.placeOrder(listingId, onOrderPlaced) },
                enabled = !state.isBusy &&
                    quantity > 0.0 &&
                    !exceedsStock &&
                    listing.status == ListingStatus.PUBLISHED &&
                    state.session != null,
                loading = state.isBusy,
            )
        }
    }
}

/* Local spacing aliases so this screen stays readable without the theme import noise. */
private val AgriSpacingLg = bw.agriconnect.ui.theme.AgriSpacing.lg
private val AgriSpacingMd = bw.agriconnect.ui.theme.AgriSpacing.md
private val AgriSpacingSm = bw.agriconnect.ui.theme.AgriSpacing.sm
