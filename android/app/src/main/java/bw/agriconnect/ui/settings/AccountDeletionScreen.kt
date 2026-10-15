package bw.agriconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import bw.agriconnect.ui.components.AgriPasswordField
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun AccountDeletionScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: AccountDeletionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AgriScaffold(title = "Delete account", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    Text(
                        "Permanently delete your account",
                        style = MaterialTheme.typography.titleLarge,
                        color = AgriColors.ErrorRed,
                    )
                    Text(
                        "This starts a grace period and then permanently removes your profile and farm records. " +
                            "Some records (financial or audit) may be retained for as long as the law requires.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.confirmed, onCheckedChange = viewModel::onConfirmedChange)
                Text("I understand and want to delete my account.")
            }

            AgriPasswordField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Current password",
            )

            if (state.error != null) {
                InfoBanner(message = state.error.orEmpty())
            }

            Spacer(Modifier.height(AgriSpacing.sm))

            AgriPrimaryButton(
                text = "Delete account",
                onClick = { viewModel.delete(onDeleted) },
                loading = state.isDeleting,
                enabled = state.confirmed,
            )
            AgriSecondaryButton(text = "Cancel", onClick = onBack, enabled = !state.isDeleting)
        }
    }
}
