package bw.agriconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun HelpScreen(onBack: () -> Unit) {
    AgriScaffold(title = "Help & support", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    Text("Getting help", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "For questions about AgriConnect, or to report a problem, contact your farm " +
                            "administrator first — they manage accounts and permissions for your farm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    Text("Working offline", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "You do not need signal to record farm work. Anything you save is stored on this " +
                            "device and sent when you are back online — look for the sync badges.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    Text("Troubleshooting sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "If something says \"Waiting to sync\", open Offline & sync and tap Sync now while " +
                            "connected. A record marked \"Not synced\" will explain what the server rejected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
