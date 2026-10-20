package bw.agriconnect.ui.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.BuildConfig
import bw.agriconnect.data.update.UpdateDecision
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriTextButton
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Root-level host for every update-related dialog. Rendered once from
 * [bw.agriconnect.ui.AgriConnectRoot] above the NavHost, so it works on any
 * screen (including before sign-in) and the state lives in
 * [UpdateViewModel]/[bw.agriconnect.data.update.UpdateChecker] — never in
 * `remember`.
 *
 * Behaviour contract (docs/13-ANDROID-RELEASES.md §4):
 *  - OPTIONAL: non-intrusive, dismissible ("Update later" — any dismissal
 *    counts as deferring this version code).
 *  - REQUIRED: blocks interaction, no back/outside dismiss, only "Update now".
 *    It is only ever shown after a *successful* check decided it is required;
 *    a failed or offline check leaves `prompt` null and this host renders
 *    nothing.
 *  - Progress dialogs run while a download/verify/install is active; Cancel is
 *    offered only during the download (nothing user-visible to cancel after).
 */
@Composable
fun UpdateDialogHost(
    viewModel: UpdateViewModel = hiltViewModel(),
) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val flow by viewModel.flow.collectAsStateWithLifecycle()

    // Returns from the system's "install unknown apps" screen; the VM
    // re-checks the grant before continuing.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onInstallPermissionResult()
    }

    when (val state = flow) {
        UpdateFlow.Idle -> when (val decision = prompt) {
            is UpdateDecision.Optional -> OptionalUpdateDialog(
                decision = decision,
                onLater = viewModel::onLater,
                onUpdateNow = viewModel::onUpdateNow,
            )

            is UpdateDecision.Required -> RequiredUpdateDialog(
                decision = decision,
                onUpdateNow = viewModel::onUpdateNow,
            )

            else -> Unit
        }

        UpdateFlow.NeedsInstallPermission -> UnknownSourcesDialog(
            onOpenSettings = { settingsLauncher.launch(viewModel.unknownSourcesIntent()) },
            onNotNow = viewModel::onPermissionNotNow,
        )

        is UpdateFlow.Downloading -> UpdateProgressDialog(
            title = "Downloading update",
            message = if (state.fraction != null) {
                "Downloading the new version of AgriConnect..."
            } else {
                "Downloading the new version of AgriConnect. This may take a moment."
            },
            fraction = state.fraction,
            onCancel = viewModel::onCancel,
        )

        UpdateFlow.Verifying -> UpdateProgressDialog(
            title = "Checking the download",
            message = "Verifying the downloaded update...",
            fraction = null,
            onCancel = null,
        )

        UpdateFlow.Installing -> UpdateProgressDialog(
            title = "Installing update",
            message = "Handing the update to Android's installer...",
            fraction = null,
            onCancel = null,
        )

        is UpdateFlow.Failed -> AlertDialog(
            onDismissRequest = viewModel::onDismissResult,
            shape = MaterialTheme.shapes.extraLarge,
            title = {
                Text("Update not completed", style = MaterialTheme.typography.titleLarge)
            },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                AgriPrimaryButton(
                    text = "OK",
                    onClick = viewModel::onDismissResult,
                    fillWidth = false,
                )
            },
        )

        UpdateFlow.Installed -> AlertDialog(
            onDismissRequest = viewModel::onInstalledDismissed,
            shape = MaterialTheme.shapes.extraLarge,
            title = {
                Text("Update installed", style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Text(
                    "AgriConnect was updated successfully and will restart with the new version.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                AgriPrimaryButton(
                    text = "OK",
                    onClick = viewModel::onInstalledDismissed,
                    fillWidth = false,
                )
            },
        )
    }
}

/** Non-intrusive prompt for a new build the user may defer. */
@Composable
private fun OptionalUpdateDialog(
    decision: UpdateDecision.Optional,
    onLater: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    val manifest = decision.manifest
    AlertDialog(
        // Any dismissal (button, back, outside tap) defers this version code,
        // so the same release is never offered twice.
        onDismissRequest = onLater,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text("Update available", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                Text(
                    manifest.optionalMessage
                        ?: "A newer version of AgriConnect is available.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Version ${manifest.latestVersionName ?: manifest.latestVersionCode} " +
                        "(build ${manifest.latestVersionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (manifest.releaseNotes.isNotEmpty()) {
                    Text("What's new", style = MaterialTheme.typography.titleSmall)
                    manifest.releaseNotes.forEach { note ->
                        Text("- $note", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            AgriPrimaryButton(text = "Update now", onClick = onUpdateNow, fillWidth = false)
        },
        dismissButton = {
            AgriTextButton(text = "Update later", onClick = onLater)
        },
    )
}

/**
 * Blocking prompt for a build below `minSupportedVersionCode`. Never dismissible
 * — but it can only appear after a successful check produced Required, so a
 * dead update server can never lock the user out.
 */
@Composable
private fun RequiredUpdateDialog(
    decision: UpdateDecision.Required,
    onUpdateNow: () -> Unit,
) {
    val manifest = decision.manifest
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text("Update required", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                Text(
                    manifest.requiredMessage ?: "Please update AgriConnect to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "You have build ${BuildConfig.VERSION_CODE}; build " +
                        "${manifest.latestVersionCode} is required to keep using the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            AgriPrimaryButton(text = "Update now", onClick = onUpdateNow)
        },
    )
}

/**
 * Short in-app explanation shown *before* the system's "install unknown apps"
 * screen (Android 8+). We never request or imply this permission at startup —
 * only at the moment the user chose to install a downloaded update.
 */
@Composable
private fun UnknownSourcesDialog(
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNotNow,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text("Allow installing updates", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Text(
                "Android requires a one-time permission before AgriConnect can install " +
                    "the update you requested. You will be taken to a system screen where " +
                    "you can allow installs for AgriConnect, then come back here to continue.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            AgriPrimaryButton(text = "Open settings", onClick = onOpenSettings, fillWidth = false)
        },
        dismissButton = {
            AgriTextButton(text = "Not now", onClick = onNotNow)
        },
    )
}

/** Determinate (when a total is known) or indeterminate progress with Cancel. */
@Composable
private fun UpdateProgressDialog(
    title: String,
    message: String,
    fraction: Float?,
    onCancel: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = if (onCancel != null) {
            { AgriTextButton(text = "Cancel", onClick = onCancel) }
        } else {
            null
        },
    )
}
