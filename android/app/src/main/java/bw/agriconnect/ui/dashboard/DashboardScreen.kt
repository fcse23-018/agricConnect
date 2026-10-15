package bw.agriconnect.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.MoneyFormat
import bw.agriconnect.domain.model.UserRole
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSettingsRow
import bw.agriconnect.ui.components.RoleBadge
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.components.StatTile
import bw.agriconnect.ui.navigation.Routes
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Role-aware home screen.
 *
 * The three roles get materially different summaries and shortcuts, sourced only from the
 * offline cache so the screen is useful immediately and honest when a value is unavailable
 * (the finance tile simply does not render until a summary has been fetched).
 */
@Composable
fun DashboardScreen(
    onLoggedOut: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val unread by viewModel.unreadNotifications.collectAsStateWithLifecycle()
    val plots by viewModel.plots.collectAsStateWithLifecycle()
    val harvests by viewModel.harvests.collectAsStateWithLifecycle()
    val livestock by viewModel.livestock.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val netBalance by viewModel.netBalance.collectAsStateWithLifecycle()

    val role = session?.role
    val activeOrders = orders.count { it.status.name == "PENDING" || it.status.name == "CONFIRMED" }

    MainScaffold(
        title = "Home",
        showBrand = true,
        onBack = null,
        topBarActions = {
            IconButton(onClick = { viewModel.refreshNotifications() }) {
                Icon(Icons.Filled.CloudUpload, contentDescription = "Refresh", tint = AgriColors.ForestGreen)
            }
            IconButton(onClick = { viewModel.logout(onLoggedOut) }) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
        ) {
            session?.let { current ->
                AgriCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(current.fullName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Welcome back",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RoleBadge(role = current.role)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                StatTile(
                    label = if (role == UserRole.BUYER) "Active orders" else "Farm plots",
                    value = if (role == UserRole.BUYER) activeOrders.toString() else plots.size.toString(),
                    modifier = Modifier.weight(1f),
                    icon = if (role == UserRole.BUYER) Icons.Filled.ReceiptLong else Icons.Filled.Agriculture,
                )
                StatTile(
                    label = "Waiting to sync",
                    value = pending.toString(),
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.CloudUpload,
                    caption = if (pending == 0) "All caught up" else "Saved locally",
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                if (role == UserRole.BUYER) {
                    StatTile(
                        label = "Alerts",
                        value = unread.toString(),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.ReceiptLong,
                    )
                } else {
                    StatTile(
                        label = "Harvests",
                        value = harvests.size.toString(),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Grass,
                    )
                    StatTile(
                        label = "Livestock",
                        value = livestock.sumOf { it.headCount }.toString(),
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Pets,
                    )
                }
            }

            if (role != UserRole.BUYER && netBalance != null) {
                AgriCard {
                    Column {
                        Text("Net balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(AgriSpacing.xs))
                        Text(
                            MoneyFormat.pula(netBalance ?: 0.0),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if ((netBalance ?: 0.0) >= 0) AgriColors.SuccessGreen else AgriColors.ErrorRed,
                        )
                        Text("From your finance records", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            AgriPrimaryButton(
                text = if (isOnline) "Sync now" else "You're offline",
                onClick = viewModel::syncNow,
                enabled = isOnline,
            )

            SectionHeader(title = "Shortcuts")
            AgriCard {
                Column {
                    when (role) {
                        UserRole.FARMER, UserRole.FARM_EMPLOYEE -> {
                            AgriSettingsRow(title = "My farms", icon = Icons.Filled.Agriculture, onClick = { onNavigate(Routes.FARMS) })
                            AgriSettingsRow(title = "Harvests", icon = Icons.Filled.Grass, onClick = { onNavigate(Routes.HARVESTS) })
                            AgriSettingsRow(title = "Livestock", icon = Icons.Filled.Pets, onClick = { onNavigate(Routes.LIVESTOCK) })
                        }
                        else -> Unit
                    }
                    if (role == UserRole.FARMER) {
                        AgriSettingsRow(title = "Finance", icon = Icons.Filled.AccountBalanceWallet, onClick = { onNavigate(Routes.FINANCE) })
                    }
                    AgriSettingsRow(title = "Marketplace", icon = Icons.Filled.Storefront, onClick = { onNavigate(Routes.MARKETPLACE) })
                    AgriSettingsRow(title = "Orders", icon = Icons.Filled.ReceiptLong, onClick = { onNavigate(Routes.ORDERS) })
                    AgriSettingsRow(title = "Alerts", icon = Icons.Filled.ReceiptLong, onClick = { onNavigate(Routes.NOTIFICATIONS) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint = if (isOnline) AgriColors.SuccessGreen else AgriColors.WarningAmber,
                )
                Text(
                    if (isOnline) "Connected — records sync automatically." else "Offline — records are saved on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
