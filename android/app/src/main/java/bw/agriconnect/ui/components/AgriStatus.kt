package bw.agriconnect.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import bw.agriconnect.domain.model.SyncStatus
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing
import bw.agriconnect.ui.theme.SyncColors

/**
 * Compact sync indicator. Every synchronisable record can render its state so the
 * user always knows whether their work has reached the server. Icon + text +
 * colour together — never colour alone.
 */
@Composable
fun SyncStatusBadge(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val (icon, colour, label) = syncAppearance(status)
    Row(
        modifier = modifier
            .background(colour.copy(alpha = 0.12f), RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
        if (showLabel) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
        }
    }
}

fun syncAppearance(status: SyncStatus): Triple<ImageVector, Color, String> = when (status) {
    SyncStatus.PENDING -> Triple(Icons.Filled.CloudQueue, SyncColors.Pending, "Waiting to sync")
    SyncStatus.SYNCING -> Triple(Icons.Filled.Sync, SyncColors.Syncing, "Syncing…")
    SyncStatus.SYNCED -> Triple(Icons.Filled.CheckCircle, SyncColors.Synced, "Synced")
    SyncStatus.CONFLICT -> Triple(Icons.Filled.CompareArrows, SyncColors.Conflict, "Review needed")
    SyncStatus.FAILED -> Triple(Icons.Filled.ErrorOutline, SyncColors.Failed, "Not synced")
}

/**
 * Animated strip shown when the device is offline. It reassures the user that
 * their entry has been saved locally — which is the whole point of the app
 * being offline-first.
 */
@Composable
fun OfflineBanner(
    visible: Boolean,
    modifier: Modifier = Modifier,
    pendingCount: Int = 0,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(AgriColors.BrownContainer)
                .padding(horizontal = AgriSpacing.lg, vertical = AgriSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = AgriColors.DarkBrown,
                modifier = Modifier.size(18.dp),
            )
            val text = when {
                pendingCount > 0 -> "You're offline. $pendingCount change${if (pendingCount == 1) "" else "s"} saved and waiting to sync."
                else -> "You're offline. New entries are saved on this device and sync automatically later."
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = AgriColors.DarkBrown,
            )
        }
    }
}

/** Thin pending-sync strip shown when online but items are still queued. */
@Composable
fun PendingSyncBanner(
    pendingCount: Int,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    if (pendingCount <= 0) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AgriColors.GreenContainer)
            .padding(horizontal = AgriSpacing.lg, vertical = AgriSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = AgriColors.DeepGreen, modifier = Modifier.size(18.dp))
            Text(
                "$pendingCount item${if (pendingCount == 1) "" else "s"} waiting to sync",
                style = MaterialTheme.typography.bodySmall,
                color = AgriColors.DeepGreen,
            )
        }
    }
}
