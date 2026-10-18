package bw.agriconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.BuildConfig
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriSettingsRow
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.components.RoleBadge
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Settings hub. Every row links to a real, already-implemented destination — nothing
 * here is decorative.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onPrivacy: () -> Unit,
    onOfflineSync: () -> Unit,
    onHelp: () -> Unit,
    onServer: () -> Unit,
    onDeleteAccount: () -> Unit,
    onLogout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val displayName = session?.fullName.orEmpty().ifBlank { "Your account" }

    AgriScaffold(title = "Settings", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            AgriCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    Column(
                        modifier = Modifier
                            .size(48.dp)
                            .background(AgriColors.ForestGreen, CircleShape),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            displayName.split(" ")
                                .filter { it.isNotBlank() }
                                .take(2)
                                .joinToString("") { it.take(1).uppercase() }
                                .ifBlank { "A" },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        session?.let { RoleBadge(role = it.role) }
                    }
                }
            }

            SectionHeader(title = "Preferences")
            AgriCard {
                Column {
                    AgriSettingsRow(
                        title = "Notification preferences",
                        subtitle = "Choose which alerts you receive",
                        icon = Icons.Filled.Notifications,
                        onClick = onNotifications,
                    )
                    AgriSettingsRow(
                        title = "Privacy & consent",
                        subtitle = "Policy, terms and data collection",
                        icon = Icons.Filled.PrivacyTip,
                        onClick = onPrivacy,
                    )
                    AgriSettingsRow(
                        title = "Offline & sync",
                        subtitle = "Connectivity and queued changes",
                        icon = Icons.Filled.CloudSync,
                        onClick = onOfflineSync,
                    )
                    AgriSettingsRow(
                        title = "Help & support",
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        onClick = onHelp,
                    )
                    // Debug affordance: retarget the installed APK at another backend
                    // without rebuilding. Hidden in release, which cannot do cleartext
                    // HTTP and should not invite users to move its API.
                    if (BuildConfig.DEBUG) {
                        AgriSettingsRow(
                            title = "Server",
                            subtitle = "Point this build at another backend",
                            icon = Icons.Filled.Dns,
                            onClick = onServer,
                        )
                    }
                }
            }

            SectionHeader(title = "Account")
            AgriCard {
                Column {
                    AgriSettingsRow(
                        title = "Delete account",
                        subtitle = "Start the account deletion process",
                        icon = Icons.Filled.DeleteForever,
                        onClick = onDeleteAccount,
                    )
                    AgriSettingsRow(
                        title = "Sign out",
                        subtitle = "Your offline records stay on this device",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        onClick = { viewModel.logout(onLogout) },
                    )
                }
            }

            Spacer(Modifier.height(AgriSpacing.sm))
            // Version is shown so "which build is installed?" is answerable on-device —
            // stale sideloads are otherwise indistinguishable from fresh ones.
            Text(
                "AgriConnect ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Role permissions are enforced server-side.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
