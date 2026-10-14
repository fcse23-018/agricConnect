package bw.agriconnect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/** Static informational chip. */
@Composable
fun AgriChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColour: Color = AgriColors.GreenContainer,
    contentColour: Color = AgriColors.DeepGreen,
) {
    Box(
        modifier = modifier
            .background(containerColour, RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.md, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColour)
    }
}

/** Selectable filter chip. Selected state is green with a tick (icon + colour). */
@Composable
fun AgriFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AgriRadius.pill),
        color = if (selected) AgriColors.ForestGreen else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) AgriColors.PureWhite else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, AgriColors.Border),
        modifier = modifier,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AgriSpacing.md, vertical = AgriSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Removable chip (selected filter tags). */
@Composable
fun AgriRemovableChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(AgriColors.GreenContainer, RoundedCornerShape(AgriRadius.pill))
            .padding(start = AgriSpacing.md, end = AgriSpacing.sm, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AgriColors.DeepGreen)
        Surface(
            onClick = onRemove,
            shape = RoundedCornerShape(AgriRadius.pill),
            color = Color.Transparent,
            contentColor = AgriColors.DeepGreen,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove $label filter", modifier = Modifier.size(14.dp))
        }
    }
}
