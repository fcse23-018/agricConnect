package bw.agriconnect.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.domain.model.AppNotification
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.ErrorState
import bw.agriconnect.ui.components.InlineLoading
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun NotificationsScreen(
    onBack: (() -> Unit)? = null,
    onOpen: (AppNotification) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unread by viewModel.unreadCount.collectAsStateWithLifecycle()

    MainScaffold(
        title = "Alerts",
        onBack = onBack,
        topBarActions = {
            if (unread > 0) {
                TextButton(onClick = viewModel::markAllRead) {
                    Text("Mark all read", color = AgriColors.ForestGreen)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
        ) {
            if (state.isRefreshing) {
                InlineLoading(message = "Refreshing alerts…")
            }

            if (state.error != null && state.items.isEmpty()) {
                ErrorState(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::refresh,
                )
                return@Column
            }

            if (state.error != null && state.items.isNotEmpty()) {
                ErrorBanner(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::refresh,
                    onDismiss = viewModel::consumeError,
                )
            }

            if (state.isInitialLoading && state.items.isEmpty()) {
                LoadingState(message = "Loading alerts…")
                return@Column
            }

            if (state.items.isEmpty()) {
                EmptyState(
                    title = "No alerts yet",
                    message = "You'll get order, sync and satellite updates here.",
                    actionLabel = "Refresh",
                    onAction = viewModel::refresh,
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.padding(horizontal = AgriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AgriSpacing.lg),
            ) {
                items(state.items, key = { it.notificationId }) { notification ->
                    NotificationItem(
                        notification = notification,
                        onClick = {
                            viewModel.markRead(notification)
                            // Routing (including unknown types) is owned by the nav root so a
                            // notification type the screen does not know can never double-navigate.
                            onOpen(notification)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    notification: AppNotification,
    onClick: () -> Unit,
) {
    AgriCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            Icon(
                imageVector = if (notification.isRead) Icons.Filled.CheckCircle else Icons.Filled.Circle,
                contentDescription = null,
                tint = if (notification.isRead) AgriColors.TextSecondary else AgriColors.ForestGreen,
                modifier = Modifier.size(16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (notification.body.isNotBlank()) {
                    Text(
                        notification.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AgriSpacing.xs),
                    )
                }
                Text(
                    DateFormatters.relative(notification.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AgriSpacing.sm),
                )
            }
        }
    }
}
