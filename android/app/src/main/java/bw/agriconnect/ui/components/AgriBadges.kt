package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import bw.agriconnect.domain.model.ListingStatus
import bw.agriconnect.domain.model.OrderStatus
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Status pills for marketplace listings and orders. Every pill pairs an **icon** with a
 * **label** and a colour, so status is never communicated by colour alone (WCAG 1.4.1).
 */
@Composable
fun StatusPill(
    label: String,
    colour: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(colour.copy(alpha = 0.12f), RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
    }
}

@Composable
fun ListingStatusBadge(status: ListingStatus, modifier: Modifier = Modifier) {
    val (label, colour, icon) = when (status) {
        ListingStatus.DRAFT -> Triple("Draft", AgriColors.TextSecondary, Icons.Filled.EditNote)
        ListingStatus.PENDING_PUBLISH -> Triple("Publishing", AgriColors.WarningAmber, Icons.Filled.Schedule)
        ListingStatus.SYNCING -> Triple("Syncing", AgriColors.InfoBlue, Icons.Filled.Sync)
        ListingStatus.RETRY -> Triple("Retry", AgriColors.WarningAmber, Icons.Filled.WarningAmber)
        ListingStatus.PUBLISHED -> Triple("Published", AgriColors.SuccessGreen, Icons.Filled.Publish)
        ListingStatus.SOLD -> Triple("Sold", AgriColors.SoilBrown, Icons.Filled.Paid)
    }
    StatusPill(label = label, colour = colour, icon = icon, modifier = modifier)
}

@Composable
fun OrderStatusBadge(status: OrderStatus, modifier: Modifier = Modifier) {
    val (label, colour, icon) = when (status) {
        OrderStatus.PENDING -> Triple("Awaiting confirmation", AgriColors.WarningAmber, Icons.Filled.HourglassEmpty)
        OrderStatus.CONFIRMED -> Triple("Confirmed", AgriColors.InfoBlue, Icons.Filled.ThumbUp)
        OrderStatus.COMPLETED -> Triple("Completed", AgriColors.SuccessGreen, Icons.Filled.CheckCircle)
        OrderStatus.CANCELLED -> Triple("Cancelled", AgriColors.ErrorRed, Icons.Filled.Cancel)
    }
    StatusPill(label = label, colour = colour, icon = icon, modifier = modifier)
}

/** Icon-only helper reused by order action rows. */
@Composable
internal fun statusIcon(icon: ImageVector, colour: Color) {
    Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
}

/**
 * Simple text-only status chip (no icon) for satellite monitoring.
 * Used for classification labels, alert severity, confidence levels, etc.
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.sm, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

internal val unavailableIcon: ImageVector = Icons.Filled.Block
internal val inventoryIcon: ImageVector = Icons.Filled.Inventory2
internal val pausedIcon: ImageVector = Icons.Filled.PauseCircle
