package bw.agriconnect.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.RoleBadge
import bw.agriconnect.ui.components.StatTile
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Minimal post-login shell.
 *
 * It intentionally shows only what the wired graph can actually back today:
 * identity, connectivity and outbox depth. The brief's feature screens (farms,
 * finance, satellite, marketplace, orders, notifications, settings) have models,
 * DAOs and API methods in place but no UI yet — tracked in PROGRESS.md.
 */
@Composable
fun DashboardScreen(
    onLoggedOut: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()

    // A failed silent refresh (or an explicit sign-out elsewhere) clears the
    // session; bounce to login rather than render a shell with no identity.
    LaunchedEffect(session) {
        if (session == null) onLoggedOut()
    }

    AgriScaffold(
        title = "Dashboard",
        subtitle = session?.fullName,
        showBrand = true,
        offline = !isOnline,
        pendingSyncCount = if (isOnline) pending else 0,
        topBarActions = {
            IconButton(onClick = { viewModel.logout(onLoggedOut) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Sign out",
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
        ) {
            session?.let { current ->
                AgriCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = current.fullName,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Signed in on this device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RoleBadge(role = current.role)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.lg)) {
                StatTile(
                    label = "Waiting to sync",
                    value = pending.toString(),
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.CloudUpload,
                    caption = if (pending == 0) "All caught up" else "Saved locally",
                )
                StatTile(
                    label = "Connection",
                    value = if (isOnline) "Online" else "Offline",
                    modifier = Modifier.weight(1f),
                    icon = if (isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                )
            }

            AgriPrimaryButton(
                text = "Sync now",
                onClick = viewModel::syncNow,
                enabled = isOnline,
            )

            AgriSecondaryButton(
                text = "Sign out",
                onClick = { viewModel.logout(onLoggedOut) },
            )

            Spacer(Modifier.height(AgriSpacing.xs))
            Text(
                text = "More screens (farms, finances, satellite health, marketplace and orders) " +
                    "are on the way. Their data layer is already in place.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
