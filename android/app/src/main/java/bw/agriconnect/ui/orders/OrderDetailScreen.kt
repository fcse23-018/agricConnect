package bw.agriconnect.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.core.util.MoneyFormat
import bw.agriconnect.domain.model.Order
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.OrderStatusBadge
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * One order, with the lifecycle actions the signed-in role is allowed to take.
 *
 * The action set is derived from [Order.status] and the caller's relationship to the
 * order (buyer vs owning farmer): a farmer confirms or rejects a pending order, a
 * confirmed order can be completed, and either party can cancel while it is active.
 * The server is the source of truth, so the buttons reflect the last server-acknowledged
 * state rather than an optimistic guess.
 */
@Composable
fun OrderDetailScreen(
    orderId: String,
    onBack: () -> Unit,
    onOpenListing: (String) -> Unit,
    viewModel: OrderDetailViewModel = hiltViewModel(),
) {
    val order by viewModel.order.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    MainScaffold(
        title = "Order",
        subtitle = order?.produce?.takeIf { it.isNotBlank() },
        onBack = onBack,
    ) { padding ->
        val current = order
        if (current == null) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AgriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            ) {
                if (state.isBusy) {
                    LoadingState(message = "Loading order…")
                } else {
                    ErrorBanner(
                        message = "This order is not available on this device. It may have been removed.",
                    )
                }
                AgriSecondaryButton(text = "Back", onClick = onBack)
            }
        } else {
            OrderDetailContent(
                order = current,
                state = state,
                viewModel = viewModel,
                onBack = onBack,
                onOpenListing = onOpenListing,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun OrderDetailContent(
    order: Order,
    state: OrderDetailUiState,
    viewModel: OrderDetailViewModel,
    onBack: () -> Unit,
    onOpenListing: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSeller = viewModel.isSeller(order)
    val showConfirm = viewModel.canConfirm(order)
    val showReject = viewModel.canReject(order)
    val showComplete = viewModel.canComplete(order)
    val showCancel = viewModel.canCancel(order)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AgriSpacing.lg)
            .padding(bottom = AgriSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
    ) {
        state.error?.let { ErrorBanner(message = it, onDismiss = viewModel::clearMessage) }
        state.message?.let { InfoBanner(message = it) }

        AgriCard {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = order.produce?.takeIf { it.isNotBlank() } ?: "Order",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Ref ${order.orderId.take(8).uppercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OrderStatusBadge(status = order.status)
                }
                Text(
                    text = "Placed ${DateFormatters.relative(order.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AgriCard {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                DetailRow("Quantity", "${order.quantity}")
                DetailRow("Unit price", MoneyFormat.pula(order.unitPrice))
                DetailRow("Total", MoneyFormat.pula(order.totalPrice))
                DetailRow("Buyer", order.buyerName?.takeIf { it.isNotBlank() } ?: "—")
                DetailRow("Role on this order", if (isSeller) "Selling" else "Buying")
                order.buyerContact?.takeIf { it.isNotBlank() }?.let { DetailRow("Contact", it) }
                if (!order.note.isNullOrBlank()) DetailRow("Note", order.note)
                HorizontalDivider()
                Text(
                    text = "Last updated ${DateFormatters.relative(order.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (order.listingId.isNotBlank()) {
            AgriSecondaryButton(
                text = "View listing",
                onClick = { onOpenListing(order.listingId) },
            )
        }

        if (showConfirm) {
            AgriPrimaryButton(
                text = "Confirm order",
                onClick = viewModel::confirm,
                enabled = !state.isBusy,
                loading = state.isBusy,
            )
        }
        if (showComplete) {
            AgriPrimaryButton(
                text = "Mark as completed",
                onClick = viewModel::complete,
                enabled = !state.isBusy,
                loading = state.isBusy,
            )
        }
        if (showReject) {
            AgriSecondaryButton(
                text = "Reject order",
                onClick = viewModel::reject,
                enabled = !state.isBusy,
                accent = true,
            )
        }
        if (showCancel) {
            AgriSecondaryButton(
                text = "Cancel order",
                onClick = viewModel::cancel,
                enabled = !state.isBusy,
                accent = true,
            )
        }

        if (!showConfirm && !showReject && !showComplete && !showCancel) {
            InfoBanner(message = "This order is closed — no further action is needed.")
        }

        AgriSecondaryButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
