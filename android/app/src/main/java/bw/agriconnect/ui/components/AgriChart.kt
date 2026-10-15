package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bw.agriconnect.domain.model.FinancePoint
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * A dependency-free grouped bar chart for the finance summary.
 *
 * Vico is on the classpath, but its API surface changes between releases and the brief only
 * needs a compact "sales vs expenses by period" view. Rendering two proportional bars per
 * period in plain Compose keeps the release build stable and lets us pair every bar with a
 * numeric label (so the value is readable without estimating a pixel height).
 */
@Composable
fun AgriBarChart(
    points: List<FinancePoint>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 160.dp,
) {
    if (points.isEmpty()) {
        Text(
            "No data for this period yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val maxValue = points.maxOfOrNull { maxOf(it.sales, it.expenses) }?.takeIf { it > 0.0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            points.forEach { point ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.height(height),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ChartBar(fraction = (point.sales / maxValue).toFloat(), colour = AgriColors.SuccessGreen)
                        ChartBar(fraction = (point.expenses / maxValue).toFloat(), colour = AgriColors.SoilBrown)
                    }
                    Spacer(Modifier.height(AgriSpacing.xs))
                    Text(
                        text = DateFormatters.formatMonthYear(point.period).take(6),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(AgriSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.lg)) {
            ChartLegend(AgriColors.SuccessGreen, "Sales")
            ChartLegend(AgriColors.SoilBrown, "Expenses")
        }
    }
}

@Composable
private fun ChartBar(fraction: Float, colour: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .width(14.dp)
            .fillMaxHeight(fraction.coerceIn(0.02f, 1f))
            .clip(RoundedCornerShape(topStart = AgriRadius.xs, topEnd = AgriRadius.xs))
            .background(colour),
    )
}

@Composable
private fun ChartLegend(colour: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colour),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
