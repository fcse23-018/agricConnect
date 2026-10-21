package bw.agriconnect.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bw.agriconnect.domain.model.satellite.ColourMap
import bw.agriconnect.domain.model.satellite.ColourMapLegendStop
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Renders the per-pixel colour map ("satellite sight").
 *
 * ## What this draws, and what it does not claim
 *
 * The grid comes from the server, where it was scaled so its in-boundary average
 * equals the **measured** plot mean. So the colours are honest about the average
 * and about which cells are inside the plot (`null` cells are left blank, never
 * drawn as low values). The *pattern* across the cells is a simulated texture —
 * that is stated in the caption below the image and is not something this
 * composable can or should hide.
 *
 * Cells are drawn on a [Canvas] rather than as a grid of composables: a 24×24 grid
 * is 576 cells, which is far too many layout nodes for a phone.
 */
@Composable
fun ColourMapView(
    map: ColourMap,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AgriRadius.md))
                .background(AgriColors.DisabledBg),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val rows = map.grid.size
                if (rows == 0) return@Canvas
                val cols = map.grid.firstOrNull()?.size ?: return@Canvas
                if (cols == 0) return@Canvas

                val cellW = size.width / cols
                val cellH = size.height / rows
                // Grow each cell by a hair so neighbouring squares do not leave
                // hairline seams from rounding on fractional pixels.
                val bleed = 0.5f

                map.grid.forEachIndexed { row, values ->
                    values.forEachIndexed { col, value ->
                        // A null cell is outside the plot: leave it as the surface
                        // colour so the field's shape is readable. Never paint it.
                        if (value == null) return@forEachIndexed
                        drawRect(
                            color = indexColour(map, value),
                            topLeft = Offset(col * cellW - bleed, row * cellH - bleed),
                            size = Size(cellW + bleed * 2, cellH + bleed * 2),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(AgriSpacing.sm))
        ColourMapLegend(map)
        Spacer(Modifier.height(AgriSpacing.xs))
        Text(
            text = "Each square is one cell of the plot. Squares outside the outline are " +
                "left blank, not coloured, so you can see the field's shape.",
            style = MaterialTheme.typography.bodySmall,
            color = AgriColors.TextSecondary,
        )
    }
}

/**
 * The colour scale, drawn as a continuous strip with the legend's own labels.
 *
 * Built from the server's legend stops so the picture and its key can never drift
 * apart: if the server changes the scale, the key changes with it.
 */
@Composable
private fun ColourMapLegend(map: ColourMap) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(AgriRadius.sm)),
        ) {
            LegendStrip(map.legend, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatIndex(map.scaleMin),
                style = MaterialTheme.typography.labelSmall,
                color = AgriColors.TextSecondary,
            )
            Text(
                text = map.index.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = AgriColors.TextSecondary,
            )
            Text(
                text = formatIndex(map.scaleMax),
                style = MaterialTheme.typography.labelSmall,
                color = AgriColors.TextSecondary,
            )
        }
    }
}

/**
 * A horizontal strip of the legend's colours, laid out proportionally to the range
 * each stop covers so a wide band reads as wide.
 */
@Composable
private fun LegendStrip(stops: List<ColourMapLegendStop>, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        val cleaned = stops.filter { it.colour != null }
        if (cleaned.isEmpty()) {
            Box(Modifier.weight(1f).height(14.dp).background(AgriColors.DisabledBg))
            return@Row
        }
        val total = cleaned.sumOf { ((it.to ?: 0.0) - (it.from ?: 0.0)).coerceAtLeast(0.0001) }
        cleaned.forEach { stop ->
            val span = ((stop.to ?: 0.0) - (stop.from ?: 0.0)).coerceAtLeast(0.0001)
            val fraction = (span / total).toFloat().coerceIn(0.05f, 0.6f)
            Box(
                Modifier
                    .weight(fraction)
                    .height(14.dp)
                    .background(parseHexColour(stop.colour)),
            )
        }
    }
}

/**
 * Maps an index value to a colour using the legend stops, and falls back to a
 * simple two-point interpolation from the scale when no legend was supplied.
 *
 * The stops are ordered lowest-to-highest by the server, so the first stop whose
 * upper bound contains the value wins.
 */
internal fun indexColour(map: ColourMap, value: Double): Color {
    val stops = map.legend
    for (stop in stops) {
        val from = stop.from ?: continue
        val to = stop.to ?: continue
        if (value >= from && value < to) return parseHexColour(stop.colour)
    }
    // Above every stop, or below the first: clamp to the nearest end so a value
    // slightly outside the scale still gets a sensible colour rather than a crash.
    val last = stops.lastOrNull()
    if (last != null && value >= (last.from ?: 0.0)) return parseHexColour(last.colour)
    return parseHexColour(stops.firstOrNull()?.colour)
}

/**
 * `#RRGGBB` to [Color]. An unparseable or missing value falls back to a neutral
 * grey rather than throwing, because a colour string is presentation only and must
 * never be able to crash the screen.
 */
internal fun parseHexColour(hex: String?): Color {
    val cleaned = hex?.removePrefix("#")?.trim()
    if (cleaned == null || cleaned.length != 6) return AgriColors.DisabledBg
    return try {
        Color(0xFF000000L or cleaned.toLong(16))
    } catch (_: NumberFormatException) {
        AgriColors.DisabledBg
    }
}

private fun formatIndex(value: Double): String = String.format("%.2f", value)

/**
 * A single legend row: the swatch plus the plain-language meaning of that band.
 * Used under the strip so a farmer can read what "0.35 to 0.50" actually looks like.
 */
@Composable
fun ColourMapLegendRow(stop: ColourMapLegendStop) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(parseHexColour(stop.colour)),
        )
        Spacer(Modifier.width(AgriSpacing.sm))
        Text(
            text = "${formatIndex(stop.from ?: 0.0)} – ${formatIndex(stop.to ?: 0.0)}",
            style = MaterialTheme.typography.labelSmall,
            color = AgriColors.TextSecondary,
        )
        Spacer(Modifier.width(AgriSpacing.sm))
        Text(
            text = stop.label.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = AgriColors.TextMain,
        )
    }
}
