package bw.agriconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
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
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun NotificationPrefsScreen(onBack: () -> Unit, viewModel: NotificationPrefsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AgriScaffoldSettings("Notification preferences", onBack) { padding ->
        Column(modifier = Modifier.padding(padding).padding(AgriSpacing.lg), verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    SwitchRow("Orders", state.orders, viewModel::setOrders)
                    SwitchRow("Sync", state.sync, viewModel::setSync)
                    SwitchRow("Satellite", state.satellite, viewModel::setSatellite)
                    SwitchRow("Marketplace", state.marketplace, viewModel::setMarketplace)
                }
            }
            if (state.message != null) Text(state.message.orEmpty(), color = bw.agriconnect.ui.theme.AgriColors.SuccessGreen)
            if (state.error != null) Text(state.error.orEmpty(), color = bw.agriconnect.ui.theme.AgriColors.ErrorRed)
            AgriPrimaryButton(text = "Save", onClick = viewModel::save, loading = state.isSaving)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AgriScaffoldSettings(title: String, onBack: () -> Unit, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    bw.agriconnect.ui.components.AgriScaffold(title = title, onBack = onBack, content = content)
}