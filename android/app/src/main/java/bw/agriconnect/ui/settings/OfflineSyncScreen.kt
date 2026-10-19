package bw.agriconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun OfflineSyncScreen(onBack: () -> Unit, viewModel: OfflineSyncViewModel = hiltViewModel()) {
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    AgriScaffold(title = "Offline & sync", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            AgriCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    Icon(
                        imageVector = if (isOnline) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (isOnline) AgriColors.SuccessGreen else AgriColors.DarkBrown,
                    )
                    Column {
                        Text(
                            text = if (isOnline) "Connected" else "Offline",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (isOnline) "Changes sync automatically." else "New entries are saved on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            InfoBanner(
                message = "Farm plots, harvests, livestock, sales and expenses are written to this device first, " +
                    "then queued. Cached records stay readable with no signal, and the queue drains the moment a " +
                    "validated connection returns.",
            )

            AgriPrimaryButton(
                text = if (isOnline) "Sync now" else "Waiting for a connection…",
                onClick = viewModel::syncNow,
                enabled = isOnline,
            )
        }
    }
}
