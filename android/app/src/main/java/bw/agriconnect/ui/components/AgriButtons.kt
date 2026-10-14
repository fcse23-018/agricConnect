package bw.agriconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

private val MinButtonHeight = 52.dp

/**
 * Primary action — forest green, full emphasis. Shows an inline spinner and
 * disables itself while [loading] is true so a user can never double-submit
 * (the brief's double-registration requirement is enforced here AND server-side).
 */
@Composable
fun AgriPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    fillWidth: Boolean = true,
    height: Dp = MinButtonHeight,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = AgriColors.ForestGreen,
            contentColor = AgriColors.PureWhite,
            disabledContainerColor = AgriColors.DisabledBg,
            disabledContentColor = AgriColors.DisabledText,
        ),
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = height),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = AgriColors.PureWhite,
            )
            Spacer(Modifier.width(AgriSpacing.md))
        } else if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(AgriSpacing.sm))
        }
        Text(text = if (loading) "Please wait…" else text, style = MaterialTheme.typography.labelLarge)
        if (!loading && trailingIcon != null) {
            Spacer(Modifier.width(AgriSpacing.sm))
            trailingIcon()
        }
    }
}

/** Secondary action — outlined, brown accent optional. */
@Composable
fun AgriSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Boolean = false,
    fillWidth: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (accent) AgriColors.DarkBrown else AgriColors.ForestGreen,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (accent) AgriColors.SoilBrown else AgriColors.ForestGreen,
        ),
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = MinButtonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(AgriSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AgriTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Destructive action (logout with pending changes, delete account). */
@Composable
fun AgriDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = AgriColors.ErrorRed,
            contentColor = AgriColors.PureWhite,
        ),
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = MinButtonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AgriColors.PureWhite)
            Spacer(Modifier.width(AgriSpacing.md))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A row of actions (e.g. Save / Cancel) that collapses on narrow screens. */
@Composable
fun AgriButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun AgriButtonSpacer(height: Dp = AgriSpacing.sm) = Spacer(Modifier.height(height))
