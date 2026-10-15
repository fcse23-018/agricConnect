package bw.agriconnect.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InlineLoading
import bw.agriconnect.ui.components.OrderStatusBadge
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * The order list for whichever side of the marketplace the signed-in user is on: a
 * buyer sees the orders they placed, a farmer sees the orders against their listings.
 * Tapping a row opens [OrderDetailScreen] where the lifecycle actions live.
 */
@Composable
fun OrdersScreen(
    onOrderClick: (String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val active = state.orders.filter { it.isActive() }
    val history = state.orders.filter { it.isHistory() }
    val displayed = if (state.selectedTab == 0) active else history

    MainScaffold(title = "Orders") { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = state.selectedTab) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.onTabChange(0) },
                    text = { Text("Active (${active.size})") },
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.onTabChange(1) },
                    text = { Text("History (${history.size})") },
                )
            }

            if (state.error != null) {
                ErrorBanner(
                    message = state.error.orEmpty(),
                    modifier = Modifier.padding(AgriSpacing.lg),
                    onRetry = viewModel::refresh,
                )
            }

            if (state.isLoading) InlineLoading(message = "Loading orders…")

            if (!state.isLoading && displayed.isEmpty()) {
                EmptyState(
                    title = if (state.selectedTab == 0) "No active orders" else "No past orders",
                    message = if (state.selectedTab == 0) {
                        "Orders you place or receive will show up here while they are in progress."
                    } else {
                        "Completed and cancelled orders are kept here for your records."
                    },
                )
            }

            LazyColumn(
                modifier = Modifier.padding(horizontal = AgriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            ) {
                items(displayed, key = { it.orderId }) { order ->
                    OrderRow(order = order, onClick = { onOrderClick(order.orderId) })
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order, onClick: () -> Unit) {
    AgriCard(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = order.produce?.takeIf { it.isNotBlank() } ?: "Order",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OrderStatusBadge(status = order.status)
            }
            Text(
                text = "Ref ${order.orderId.take(8).uppercase()} • ${DateFormatters.relative(order.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${order.quantity} × ${MoneyFormat.pula(order.unitPrice)} • Total ${MoneyFormat.pula(order.totalPrice)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Tap to view details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
