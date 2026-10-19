package bw.agriconnect.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bw.agriconnect.core.util.MoneyFormat
import bw.agriconnect.core.util.NdviUtil
import bw.agriconnect.domain.model.StressLevel
import bw.agriconnect.domain.model.UserRole
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriElevation
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Base content surface. Outlined card on pure white with a restrained shadow —
 * the design system never uses heavy elevation on content.
 */
@Composable
fun AgriCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColour: Color = MaterialTheme.colorScheme.surface,
    borderColour: Color = AgriColors.Border,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(containerColor = containerColour)
    val elevation = CardDefaults.cardElevation(defaultElevation = AgriElevation.card)
    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = BorderStroke(1.dp, borderColour),
            modifier = modifier.fillMaxWidth(),
            content = { Box(Modifier.padding(AgriSpacing.lg)) { content() } },
        )
    } else {
        Card(
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = BorderStroke(1.dp, borderColour),
            modifier = modifier.fillMaxWidth(),
            content = { Box(Modifier.padding(AgriSpacing.lg)) { content() } },
        )
    }
}

/** Section title with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Large numeral statistic with a supporting label. Used on the dashboard and
 * finance summary. `delta` renders a green/red change chip when supplied.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    delta: Double? = null,
    caption: String? = null,
    accent: Color = AgriColors.ForestGreen,
) {
    AgriCard(modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(AgriRadius.sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(AgriSpacing.md))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(AgriSpacing.md))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (caption != null) {
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (delta != null) {
                Spacer(Modifier.height(AgriSpacing.sm))
                val positive = delta >= 0
                Text(
                    text = (if (positive) "▲ " else "▼ ") + MoneyFormat.pulaCompact(kotlin.math.abs(delta)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (positive) AgriColors.SuccessGreen else AgriColors.ErrorRed,
                )
            }
        }
    }
}

/** Pula amount rendered with the currency mark and thousands separators. */
@Composable
fun PulaAmount(
    amount: Double,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    colour: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = if (compact) MoneyFormat.pulaCompact(amount) else MoneyFormat.pula(amount),
        style = style,
        color = colour,
        modifier = modifier,
    )
}

/** Role pill shown on profile and staff lists. */
@Composable
fun RoleBadge(role: UserRole, modifier: Modifier = Modifier) {
    val (bg, fg) = when (role) {
        UserRole.FARM_OWNER -> AgriColors.GreenContainer to AgriColors.DeepGreen
        UserRole.FARMER -> AgriColors.BrownContainer to AgriColors.DarkBrown
        UserRole.BUYER -> AgriColors.GreenContainer to AgriColors.InfoBlue
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.md, vertical = AgriSpacing.xs),
    ) {
        Text(role.displayName, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/**
 * NDVI indicator: a coloured value with a stress pill and, optionally, a bar.
 * Deliberately always paired with the stress label so colour is never the only
 * signal (accessibility requirement).
 */
@Composable
fun NdviGauge(
    meanNdvi: Double?,
    statusText: String,
    modifier: Modifier = Modifier,
    stressLevel: StressLevel? = NdviUtil.classify(meanNdvi),
    cached: Boolean = false,
) {
    val colour = NdviUtil.colour(stressLevel)
    AgriCard(modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Vegetation index (NDVI)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AgriSpacing.xs))
                    Text(
                        text = meanNdvi?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "—",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = colour,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    StressPill(NdviUtil.label(stressLevel), colour)
                    if (cached) {
                        Spacer(Modifier.height(AgriSpacing.xs))
                        CachedBadge()
                    }
                }
            }
            Spacer(Modifier.height(AgriSpacing.md))
            val fraction = meanNdvi?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
            LinearProgressIndicator(
                progress = { fraction },
                color = colour,
                trackColor = AgriColors.DisabledBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            Spacer(Modifier.height(AgriSpacing.sm))
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AgriSpacing.xs))
            Text(
                NdviUtil.DISCLAIMER,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StressPill(label: String, colour: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(colour.copy(alpha = 0.14f), RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.md, vertical = AgriSpacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

/** "CACHED" badge used when NDVI is shown from the local cache while offline. */
@Composable
fun CachedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(AgriColors.BrownContainer, RoundedCornerShape(AgriRadius.xs))
            .padding(horizontal = AgriSpacing.sm, vertical = 2.dp),
    ) {
        Text(
            "CACHED",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AgriColors.DarkBrown,
        )
    }
}
