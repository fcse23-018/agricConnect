package bw.agriconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * The five mandatory screen states (docs/01-DESIGN-SYSTEM.md §9) live here so no
 * screen can forget one: Loading, Empty, Error, Offline, and inline Success (via
 * the snackbar helpers).
 */

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String = "Loading…",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AgriSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = AgriColors.ForestGreen)
        Spacer(Modifier.height(AgriSpacing.lg))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Inline loading row for lists that are refreshing over existing content. */
@Composable
fun InlineLoading(modifier: Modifier = Modifier, message: String = "Refreshing…") {
    Column(
        modifier = modifier.fillMaxWidth().padding(AgriSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = AgriColors.ForestGreen)
        Spacer(Modifier.height(AgriSpacing.sm))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgriSpacing.xl, vertical = AgriSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(AgriColors.GreenContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AgriColors.ForestGreen, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(AgriSpacing.lg))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AgriSpacing.sm))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AgriSpacing.xl))
            AgriPrimaryButton(text = actionLabel, onClick = onAction, fillWidth = false)
        }
    }
}

/**
 * Error state. [message] must already be plain language — callers pass the
 * AppError.message, never a raw exception.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    onRetry: (() -> Unit)? = null,
    icon: ImageVector = Icons.Filled.ErrorOutline,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AgriSpacing.xl, vertical = AgriSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(AgriColors.ErrorContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AgriColors.ErrorRed, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(AgriSpacing.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(AgriSpacing.sm))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(AgriSpacing.xl))
            AgriSecondaryButton(text = "Try again", onClick = onRetry, fillWidth = false)
        }
    }
}

/** Empty state that explicitly explains offline behaviour rather than looking broken. */
@Composable
fun OfflineEmptyState(
    message: String = "Nothing has been downloaded yet. Connect to the internet once so this screen can load, then it works offline.",
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(AgriSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(AgriColors.BrownContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = AgriColors.DarkBrown, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(AgriSpacing.lg))
        Text("You're offline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(AgriSpacing.sm))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(AgriSpacing.lg))
            AgriSecondaryButton("Check connection", onRetry, fillWidth = false)
        }
    }
}
