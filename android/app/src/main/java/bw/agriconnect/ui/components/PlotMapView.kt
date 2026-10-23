package bw.agriconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bw.agriconnect.BuildConfig
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.NdviUtil
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.domain.model.StressLevel
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay

/**
 * One plot drawn on the interactive map.
 *
 * [vertices] is the **stored** boundary, read straight from the plot record — the map
 * never derives a polygon from a single point. A plot with only a centroid and no ring
 * is shown as a marker and flagged [hasBoundary] = false, which is the honest signal
 * that a real boundary still has to be surveyed.
 */
data class PlotMapShape(
    val plotId: String,
    val plotName: String,
    val vertices: List<GeoVertex>,
    val centroid: GeoVertex?,
    val hasBoundary: Boolean,
    /** Measured NDVI mean when one exists. Drives the honest colouring only. */
    val ndvi: Double? = null,
    val isSimulated: Boolean = false,
) {
    /** The point the camera should centre on: the ring's centre, else the stored centroid. */
    val focusPoint: GeoVertex? get() = PlotBoundary.centroid(vertices) ?: centroid
}

/**
 * The farm map used by Satellite Insights: a genuine, interactive Google map (not a
 * static image) showing the farmer's own registered plots.
 *
 * Why it exists. Before this the satellite screen had no geographic view at all — the
 * "satellite sight" picture is a Canvas texture whose *average* is measured but whose
 * *pattern* is generated. A farmer cannot see where their plot actually is on that. This
 * map is the honest counterpart: the boundaries drawn here are the real stored geometry,
 * and the imagery is Google's actual satellite layer.
 *
 * **What is real and what is not.**
 *  - The polygons are the stored plot boundaries. Real.
 *  - The base imagery is Google's satellite/hybrid layer. Real, and the layer is swappable.
 *  - A plot polygon is tinted by its **measured** NDVI mean when one exists (green /
 *    amber / red). The number is real; the flat tint repeats it, and per-pixel variation
 *    is NOT drawn because the backend does not measure it. A simulated mean is tinted
 *    but marked in the legend rather than silently looking like a real reading.
 *
 * **Failure is stated, never hidden.** A missing key, or a key Google rejects (Maps SDK
 * for Android not enabled), would otherwise render as a blank grey grid that is
 * indistinguishable from "still loading". Both cases show an explained panel with the
 * reason instead.
 */
@Composable
fun PlotMapView(
    plots: List<PlotMapShape>,
    selectedPlotId: String?,
    onSelectPlot: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** True when the farmer's own position may be shown (permission granted). */
    locationEnabled: Boolean = false,
    /** The device position, if known. Drawn as a distinct "you are here" pin. */
    myLocation: GeoVertex? = null,
    /** Called when the farmer taps the locate control. */
    onLocate: () -> Unit = {},
    /** Opens whatever search/coordinate entry the host screen provides. */
    onSearch: (() -> Unit)? = null,
    /**
     * Draw the strongest plot boundary with a thicker stroke. Used while drawing a new
     * plot so the shape being edited is unmistakable.
     */
    emphasiseSelected: Boolean = true,
) {
    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MapUnavailable(
            reason = "No Google Maps API key is configured for this build, so the farm map " +
                "cannot be shown. Your plot boundaries and readings are still available below.",
            modifier = modifier,
        )
        return
    }

    // The first shape with a known position seeds the camera; otherwise Gaborone, the
    // app's home market, so the map opens somewhere sensible rather than at (0, 0).
    val start = plots.firstNotNullOfOrNull { it.focusPoint } ?: FarmMapDefaults.Start
    val cameraPositionState = rememberCameraPositionState()
    var satellite by remember { mutableStateOf(true) }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapGaveUp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cameraPositionState.position =
            CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), FarmMapDefaults.Zoom)
    }

    // Follow the selection: when the farmer picks a different plot the camera moves to it.
    val selectedPoint = plots.firstOrNull { it.plotId == selectedPlotId }?.focusPoint
    LaunchedEffect(selectedPlotId, selectedPoint?.latitude, selectedPoint?.longitude) {
        if (selectedPoint != null) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(selectedPoint.latitude, selectedPoint.longitude),
                    FarmMapDefaults.FocusZoom,
                ),
            )
        }
    }

    // Re-centre when the farmer taps the locate button and a position is known.
    val latestLocation = rememberUpdatedState(myLocation)
    LaunchedEffect(myLocation?.latitude, myLocation?.longitude) {
        val point = latestLocation.value ?: return@LaunchedEffect
        cameraPositionState.animate(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), FarmMapDefaults.FocusZoom),
        )
    }

    // A rejected key never reports an error; it just never loads. Treat a silent first
    // frame as a rejected key rather than waiting forever on a spinner.
    LaunchedEffect(Unit) {
        delay(FarmMapLoad.TimeoutMs)
        if (!mapLoaded) mapGaveUp = true
    }

    if (mapGaveUp) {
        MapUnavailable(
            reason = "The map could not load. Google did not accept the Maps key for this " +
                "build — enable \"Maps SDK for Android\" for the key's project and allow this " +
                "app's signing certificate. Your plot boundaries and readings are still shown below.",
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.matchParentSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = if (satellite) MapType.SATELLITE else MapType.NORMAL,
                isMyLocationEnabled = locationEnabled,
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = true,
            ),
            onMapLoaded = { mapLoaded = true },
        ) {
            plots.forEach { shape ->
                val isSelected = shape.plotId == selectedPlotId
                if (shape.hasBoundary && shape.vertices.size >= PlotBoundary.MIN_VERTICES) {
                    val tint = ndviTint(shape)
                    Polygon(
                        points = shape.vertices.map { LatLng(it.latitude, it.longitude) },
                        strokeColor = if (isSelected && emphasiseSelected) AgriColors.ForestGreen else tint.stroke,
                        strokeWidth = if (isSelected && emphasiseSelected) 7f else 3f,
                        fillColor = tint.fill.copy(alpha = if (isSelected) 0.40f else 0.18f),
                        zIndex = if (isSelected) 2f else 1f,
                        clickable = true,
                        onClick = {
                            onSelectPlot(shape.plotId)
                            true
                        },
                    )
                }

                // A point-only plot still deserves a pin: it is registered, it just has
                // no surveyed outline yet. The marker is deliberately NOT a polygon.
                val pin = shape.focusPoint
                if (pin != null) {
                    Marker(
                        state = MarkerState(position = LatLng(pin.latitude, pin.longitude)),
                        title = shape.plotName,
                        snippet = when {
                            !shape.hasBoundary -> "No boundary yet — tap to add one"
                            shape.ndvi != null && shape.isSimulated -> "Demo reading"
                            shape.ndvi != null -> "NDVI ${"%.2f".format(shape.ndvi)}"
                            else -> "No reading yet"
                        },
                        zIndex = if (isSelected) 3f else 1f,
                        onClick = {
                            onSelectPlot(shape.plotId)
                            false
                        },
                    )
                }
            }

            // The device position, drawn only when the caller says it is known and allowed.
            if (locationEnabled && myLocation != null) {
                Marker(
                    state = MarkerState(position = LatLng(myLocation.latitude, myLocation.longitude)),
                    title = "Your position",
                )
            }
        }

        MapControls(
            satellite = satellite,
            locationEnabled = locationEnabled,
            onToggleLayer = { satellite = !satellite },
            onLocate = onLocate,
            onSearch = onSearch,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(AgriSpacing.sm),
        )
    }
}

/** Layer / locate / search controls, floating over the map. */
@Composable
private fun MapControls(
    satellite: Boolean,
    locationEnabled: Boolean,
    onToggleLayer: () -> Unit,
    onLocate: () -> Unit,
    onSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
        MapControlButton(
            icon = Icons.Filled.Layers,
            contentDescription = if (satellite) "Show street map" else "Show satellite imagery",
            onClick = onToggleLayer,
        )
        MapControlButton(
            icon = Icons.Filled.MyLocation,
            contentDescription = "Centre on my position",
            onClick = onLocate,
            tint = if (locationEnabled) AgriColors.ForestGreen else AgriColors.TextSecondary,
        )
        if (onSearch != null) {
            MapControlButton(
                icon = Icons.Filled.Search,
                contentDescription = "Search for a place or enter coordinates",
                onClick = onSearch,
            )
        }
    }
}

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = AgriColors.TextMain,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The tint for a plot polygon, derived from its **measured** NDVI mean using the same
 * published thresholds the rest of the app uses ([bw.agriconnect.core.util.NdviUtil]).
 *
 * Simulation is marked by the legend rather than by a different colour: recolouring a
 * demo reading would make the picture look like a different measurement, not a demo.
 */
private fun ndviTint(shape: PlotMapShape): MapTint {
    val ndvi = shape.ndvi
        ?: return MapTint(AgriColors.ForestGreen, AgriColors.GreenContainer)
    // Classify with the one shared utility so the map can never disagree with the gauge.
    return when (NdviUtil.classify(ndvi)) {
        StressLevel.HIGH_STRESS -> MapTint(AgriColors.ErrorRed, AgriColors.ErrorRed)
        StressLevel.MODERATE_STRESS -> MapTint(AgriColors.WarningAmber, AgriColors.WarningAmber)
        StressLevel.HEALTHY_VEGETATION -> MapTint(AgriColors.SuccessGreen, AgriColors.SuccessGreen)
        null -> MapTint(AgriColors.ForestGreen, AgriColors.GreenContainer)
    }
}

private data class MapTint(val stroke: Color, val fill: Color)

/** Camera and fallback constants for the farm map. */
private object FarmMapDefaults {
    const val Zoom = 14f
    const val FocusZoom = 16f

    /** Gaborone — the app's home market — as the camera origin with no plot position. */
    val Start = GeoVertex(-24.6282, 25.9231)
}

/**
 * How long to wait for the first map frame before declaring the key rejected. Generous,
 * because a slow rural connection is not a broken key: wrongly showing "unavailable"
 * would hide a working map, which is worse than waiting.
 */
private object FarmMapLoad {
    const val TimeoutMs = 12_000L
}
