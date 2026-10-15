package bw.agriconnect.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun PrivacyScreen(onBack: () -> Unit, onOpenDelete: () -> Unit) {
    AgriScaffold(title = "Privacy & consent", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            PolicyCard(
                title = "Privacy Policy",
                body = "AgriConnect processes farm records, account details and usage information to provide agricultural management and marketplace services.",
            )
            PolicyCard(
                title = "Terms of Service",
                body = "Use AgriConnect in accordance with applicable laws. Role permissions are enforced server-side — a client cannot grant itself a higher role.",
            )
            PolicyCard(
                title = "Data Collection",
                body = "Location, sync and diagnostic information may be collected only where permitted and only to deliver the features you use.",
            )
            PolicyCard(
                title = "Location Permissions",
                body = "Location is requested only when adding or mapping farm plots. It is never used for advertising.",
            )
            PolicyCard(
                title = "Third-Party Services",
                body = "External services such as SMS, email, mapping, satellite and push notifications may require configuration.",
            )
            AgriPrimaryButton(text = "Delete account", onClick = onOpenDelete)
        }
    }
}

@Composable
private fun PolicyCard(title: String, body: String) {
    AgriCard {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AgriColors.ForestGreen)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
