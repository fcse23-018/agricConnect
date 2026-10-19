package bw.agriconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing
import java.text.NumberFormat
import java.util.Locale

/**
 * Collects the plot's polygon boundary — the input NDVI cannot be computed without.
 *
 * Two ways in, because the field reality is two ways:
 *  - **Use my location** walks the perimeter, capturing a corner per tap (GPS, needs the
 *    location permission the user may refuse);
 *  - **manual entry** types a latitude/longitude pair, for when the user is not standing
 *    on the plot or has no signal.
 *
 * The corner list and the running area estimate are shown live so the user can see the
 * boundary is complete before saving. Manual fields use a text keyboard because a numeric
 * keypad has no minus sign, and southern-hemisphere latitudes are always negative.
 */
@Composable
fun BoundaryEditor(
    vertices: List<GeoVertex>,
    manualLat: String,
    onManualLatChange: (String) -> Unit,
    manualLng: String,
    onManualLngChange: (String) -> Unit,
    onAddManual: () -> Unit,
    onUseMyLocation: () -> Unit,
    onRemoveVertex: (Int) -> Unit,
    onClear: () -> Unit,
    isLocating: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val hasBoundary = vertices.size >= PlotBoundary.MIN_VERTICES
    val areaHectares = PlotBoundary.estimateAreaHectares(vertices)

    Column(modifier = modifier) {
        SectionHeader(
            title = "Plot boundary",
            subtitle = "Required before NDVI (satellite crop health) can be computed.",
        )

        Spacer(Modifier.height(AgriSpacing.sm))

        Text(
            text = when {
                hasBoundary -> "Boundary complete: ${vertices.size} corners" +
                    (areaHectares?.let { " • approximately ${formatHectares(it)} ha" } ?: "")
                vertices.isEmpty() -> "Add at least ${PlotBoundary.MIN_VERTICES} corners around the plot."
                else -> "${vertices.size} of ${PlotBoundary.MIN_VERTICES} corners added — keep going around the edge."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (hasBoundary) AgriColors.SuccessGreen else AgriColors.WarningAmber,
        )

        Spacer(Modifier.height(AgriSpacing.sm))

        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            AgriSecondaryButton(
                text = "Use my location",
                onClick = onUseMyLocation,
                enabled = enabled && !isLocating,
                loading = isLocating,
                fillWidth = false,
            )
            if (vertices.isNotEmpty()) {
                AgriTextButton(text = "Clear", onClick = onClear, enabled = enabled)
            }
        }

        Spacer(Modifier.height(AgriSpacing.sm))

        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            AgriTextField(
                value = manualLat,
                onValueChange = onManualLatChange,
                label = "Corner latitude",
                placeholder = "e.g. -24.6543",
                keyboardType = KeyboardType.Text,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            AgriTextField(
                value = manualLng,
                onValueChange = onManualLngChange,
                label = "Corner longitude",
                placeholder = "e.g. 25.9123",
                keyboardType = KeyboardType.Text,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(AgriSpacing.sm))

        AgriSecondaryButton(
            text = "Add corner",
            onClick = onAddManual,
            enabled = enabled && manualLat.isNotBlank() && manualLng.isNotBlank(),
            fillWidth = false,
        )

        if (vertices.isNotEmpty()) {
            Spacer(Modifier.height(AgriSpacing.sm))
            Column {
                vertices.forEachIndexed { index, vertex ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}. ${formatCoordinate(vertex.latitude)}, ${formatCoordinate(vertex.longitude)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemoveVertex(index) }, enabled = enabled) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove corner ${index + 1}",
                                modifier = Modifier.height(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatHectares(value: Double): String =
    NumberFormat.getInstance().apply { maximumFractionDigits = 2 }.format(value)

private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.5f", value)
