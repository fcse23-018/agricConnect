package bw.agriconnect.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriScaffold
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Debug-only server picker: point an installed APK at a Codespaces forward, a LAN
 * address or a staging host without rebuilding.
 *
 * Reachable only from Settings in a debug build (the row is gated on
 * `BuildConfig.DEBUG`), because a release build cannot do cleartext HTTP anyway and a
 * shipped app should not invite users to retarget its API.
 */
@Composable
fun ServerSettingsScreen(
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A saved change cannot take effect in this process: the Retrofit base URL is a
    // singleton and image paths resolve against an origin seeded at start-up. Restart
    // once, then let the flag clear so a recomposition cannot restart twice.
    LaunchedEffect(state.restartRequired) {
        if (state.restartRequired) {
            viewModel.onRestartHandled()
            restartApp(context)
        }
    }

    AgriScaffold(title = "Server", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
                    Text(
                        "Currently using",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(state.currentBaseUrl, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.isOverridden) "Saved on this device" else "Compiled into this build",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionHeader(title = "Change server")
            AgriTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = "Base URL",
                placeholder = "https://<host>/api/v1/",
                error = state.error,
                helper = "Include the API path (e.g. /api/v1/). Takes effect after a restart.",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            )

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AgriPrimaryButton(text = "Save & restart", onClick = viewModel::save)
            AgriSecondaryButton(
                text = "Reset to default",
                onClick = viewModel::reset,
                enabled = state.isOverridden,
            )

            Spacer(Modifier.height(AgriSpacing.sm))
            Text(
                "Default for this build: ${state.defaultBaseUrl}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Kill and relaunch the app. The launch intent is resolved from the package manager so
 * the debug package (`.debug` suffix) relaunches itself rather than any sibling install.
 */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
    if (intent != null) context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
