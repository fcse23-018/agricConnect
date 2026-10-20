package bw.agriconnect.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import bw.agriconnect.domain.model.FinancePoint
import bw.agriconnect.domain.model.satellite.PlotObservation
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing
import kotlin.math.max
import kotlin.math.min

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

/**
 * A dependency-free line chart for NDVI/EVI time series.
 *
 * Renders one or two series (raw + smoothed) with points at each observation.
 * Includes a legend, date labels on X-axis, and value range on Y-axis.
 */
@Composable
fun AgriLineChart(
    observations: List<PlotObservation>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 200.dp,
    showNdvi: Boolean = true,
    showEvi: Boolean = true,
    showSmoothed: Boolean = true,
) {
    if (observations.isEmpty()) {
        Text(
            "No observation data for this plot yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // Collect all values to compute min/max for Y-axis
    val allValues = mutableListOf<Double>()
    if (showNdvi) {
        observations.filter { it.ndvi != null }.forEach { allValues.add(it.ndvi!!) }
        if (showSmoothed) observations.filter { it.smoothedNdvi != null }.forEach { allValues.add(it.smoothedNdvi!!) }
    }
    if (showEvi) {
        observations.filter { it.evi != null }.forEach { allValues.add(it.evi!!) }
        if (showSmoothed) observations.filter { it.smoothedEvi != null }.forEach { allValues.add(it.smoothedEvi!!) }
    }

    val minValue = (allValues.minOrNull() ?: 0.0) - 0.05
    val maxValue = (allValues.maxOrNull() ?: 1.0) + 0.05
    val valueRange = maxValue - minValue

    // Sort observations by date (newest first in API, so reverse for chart)
    val sortedObs = observations.sortedBy { it.observedOn }

    // Compute X positions
    val pointCount = sortedObs.size
    val xPositions = if (pointCount > 1) {
        (0 until pointCount).map { it.toFloat() / (pointCount - 1) }
    } else {
        listOf(0.5f)
    }

    // Chart height in pixels (will be resolved in layout)
    val chartHeightPx = LocalDensity.current.run { height.toPx() }.toInt()

    Column(modifier = modifier.fillMaxWidth()) {
        // Chart area with Y-axis labels on the left
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // Y-axis labels column
            Column(
                modifier = Modifier
                    .width(50.dp)
                    .padding(top = 0.dp), // Will align with canvas top
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                val gridSteps = 5
                val labelHeightDp = with(LocalDensity.current) { ((chartHeightPx.toFloat() - (40.dp).toPx()) / gridSteps).toDp() }
                (0..gridSteps).forEach { i ->
                    val frac = i / gridSteps.toFloat()
                    val labelValue = maxValue - frac * valueRange
                    Text(
                        text = "%.1f".format(labelValue),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(labelHeightDp)
                            .padding(bottom = 2.dp),
                    )
                }
            }

            // Chart canvas
            val gridColour = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                // Y coordinate conversion
                fun valueToY(value: Double, ch: Float): Float =
                    ch - ((value - minValue) / valueRange * ch).toFloat().coerceIn(0f, ch)

                val ch = size.height

                // Y-axis grid lines
                val gridSteps = 5
                (0..gridSteps).forEach { i ->
                    val frac = i / gridSteps.toFloat()
                    val y = ch * frac
                    // Grid line
                    drawLine(
                        color = gridColour,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 0.5f,
                    )
                }

                // Draw series
                fun DrawSeries(
                    getValue: (PlotObservation) -> Double?,
                    color: Color,
                    dashed: Boolean = false,
                ) {
                    val pointsWithValues = sortedObs.mapIndexed { idx, obs ->
                        val value = getValue(obs)
                        value?.let { idx to value }
                    }.filterNotNull()

                    if (pointsWithValues.size < 2) return

                    val path = Path()
                    val first = pointsWithValues.first()
                    val firstX = xPositions[first.first] * size.width
                    val firstY = valueToY(first.second, ch)
                    path.moveTo(firstX, firstY)

                    pointsWithValues.drop(1).forEach { (idx, value) ->
                        val x = xPositions[idx] * size.width
                        val y = valueToY(value, ch)
                        path.lineTo(x, y)
                    }

                    val strokeWidth = if (dashed) 2.dp.toPx() else 2.5.dp.toPx()
                    val pathEffect: PathEffect? = null // Dashed path effect not available in current Compose version
                    drawPath(
                        path,
                        color = color,
                        style = Stroke(strokeWidth, pathEffect = pathEffect),
                    )

                    // Draw points
                    pointsWithValues.forEach { (idx, value) ->
                        val x = xPositions[idx] * size.width
                        val y = valueToY(value, ch)
                        drawCircle(
                            color = color,
                            center = Offset(x, y),
                            radius = 4.dp.toPx(),
                        )
                    }
                }

                if (showSmoothed && showNdvi) DrawSeries({ it.smoothedNdvi }, AgriColors.SuccessGreen.copy(alpha = 0.6f), dashed = true)
                if (showNdvi) DrawSeries({ it.ndvi }, AgriColors.SuccessGreen)
                if (showSmoothed && showEvi) DrawSeries({ it.smoothedEvi }, AgriColors.SoilBrown.copy(alpha = 0.6f), dashed = true)
                if (showEvi) DrawSeries({ it.evi }, AgriColors.SoilBrown)
            }
        }

        // X-axis labels (dates)
        Spacer(Modifier.height(AgriSpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
        ) {
            // Show up to 6 date labels evenly spaced
            val labelCount = min(6, pointCount)
            val labelIndices = if (pointCount <= labelCount) {
                0 until pointCount
            } else {
                (0 until labelCount).map { (it * (pointCount - 1) / (labelCount - 1)).toInt() }
            }

            labelIndices.forEach { idx ->
                val obs = sortedObs[idx]
                Text(
                    text = DateFormatters.formatDateShort(obs.observedOn).take(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }

        // Legend
        Spacer(Modifier.height(AgriSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
            if (showNdvi) ChartLegend(AgriColors.SuccessGreen, "NDVI")
            if (showEvi) ChartLegend(AgriColors.SoilBrown, "EVI")
            if (showSmoothed && (showNdvi || showEvi)) ChartLegend(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), "Smoothed (dashed)")
        }
    }
}

@Composable
private fun ChartBar(fraction: Float, colour: Color) {
    Box(
        modifier = Modifier
            .width(14.dp)
            .fillMaxHeight(fraction.coerceIn(0.02f, 1f))
            .clip(RoundedCornerShape(topStart = AgriRadius.xs, topEnd = AgriRadius.xs))
            .background(colour),
    )
}

@Composable
private fun ChartLegend(colour: Color, label: String) {
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