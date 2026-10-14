package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Confirmation dialog used for destructive and consequential actions
 * (delete plot, delete account, logout with pending changes). Always explains
 * the consequence in the body — never a bare "Are you sure?".
 */
@Composable
fun ConfirmationDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    loading: Boolean = false,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (destructive) {
                AgriDangerButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    loading = loading,
                    fillWidth = false,
                )
            } else {
                AgriPrimaryButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    loading = loading,
                    fillWidth = false,
                )
            }
        },
        dismissButton = {
            AgriTextButton(text = "Cancel", onClick = onDismiss, enabled = !loading)
        },
    )
}

/** Snackbar host wired to the design system's success / error / info styling. */
@Composable
fun AgriSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp)
                )
                Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Inline success banner — used instead of a transient toast so a confirmation is
 * still on screen if the user looks away (brief §14 "success state everywhere").
 */
@Composable
fun SuccessBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AgriColors.GreenContainer, MaterialTheme.shapes.medium)
            .padding(AgriSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AgriColors.SuccessGreen)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AgriColors.DeepGreen, modifier = Modifier.weight(1f))
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** Inline error banner for recoverable errors that should not replace the screen. */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AgriColors.ErrorContainer, MaterialTheme.shapes.medium)
            .padding(AgriSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = AgriColors.ErrorRed)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AgriColors.ErrorRed, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry", color = AgriColors.ErrorRed) }
        }
    }
}

/** Inline informational banner (brown, non-alarming). */
@Composable
fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AgriColors.BrownContainer, MaterialTheme.shapes.medium)
            .padding(AgriSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
    ) {
        if (icon != null) icon() else Icon(Icons.Filled.Info, contentDescription = null, tint = AgriColors.DarkBrown)
        Text(message, style = MaterialTheme.typography.bodySmall, color = AgriColors.DarkBrown, modifier = Modifier.weight(1f))
    }
}

/** Fires a one-shot message exactly once for a key, then clears it. */
@Composable
fun HandleUserMessage(
    message: String?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(message) {
        if (message != null) {
            hostState.showSnackbar(message)
            onConsumed()
        }
    }
}
