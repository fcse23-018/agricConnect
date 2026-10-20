package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import bw.agriconnect.BuildConfig
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Where a plot is mapped.
 *
 * Google Maps rather than a text field, because a boundary is a shape on the
 * ground and typing coordinates is how you get a plot in the wrong place. Tapping
 * the map adds a corner; dragging a numbered corner moves it. The corner list
 * stays the single source of truth (`GeoVertex`), so the manual-entry editor and
 * this map build the *same* boundary rather than two that can disagree.
 *
 * **Honest failure.** A Maps key compiled into an APK is a build-time input, not a
 * secret, and it may be absent (a fresh clone, a CI build without the secret). When
 * it is, this draws an explained placeholder — "Map unavailable" with the reason —
 * instead of an empty grey rectangle, because a blank map looks like a loading bug
 * and the user cannot tell whether their plot failed to render.
 *
 * The map does NOT compute area: the server measures the boundary from the actual
 * geometry (`services/geometry.js`). The figure shown while drawing uses the same
 * local estimate the rest of the app uses, and is labelled approximate.
 */
@Composable
fun PlotBoundaryMap(
    vertices: List<GeoVertex>,
    onAddVertex: (GeoVertex) -> Unit,
    onMoveVertex: (index: Int, GeoVertex) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Where to open the camera when the plot has no corners yet. */
    initialCentre: GeoVertex? = null,
    /** Recentre the camera on this point; change the value to re-trigger. */
    recentreOn: GeoVertex? = null,
) {
    // Captured into a local so the tap handler is not recreated on every recompose.
    val addVertex = rememberUpdatedState(onAddVertex)
    val moveVertex = rememberUpdatedState(onMoveVertex)

    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MapUnavailable(
            reason = "No Google Maps API key is configured for this build. Plot boundaries can still be entered by coordinates.",
            modifier = modifier,
        )
        return
    }

    val start = initialCentre
        ?: vertices.firstOrNull()
        ?: Defaults.Start
    // `rememberCameraPositionState()` takes no arguments in this version; the
    // starting camera is set on the state instead. Setting `position` before the
    // map exists is supported and avoids guessing an overload.
    val cameraPositionState = rememberCameraPositionState()
    LaunchedEffect(Unit) {
        cameraPositionState.position =
            CameraPosition.fromLatLngZoom(LatLng(start.latitude, start.longitude), Defaults.Zoom)
    }

    // Recentre only when the caller asks for it, so normal recomposition does not
    // yank the camera away from where the user is working.
    LaunchedEffect(recentreOn) {
        if (recentreOn != null) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(recentreOn.latitude, recentreOn.longitude),
                    Defaults.RecentreZoom,
                ),
            )
        }
    }

    // One remembered marker state per corner. The key includes the coordinates so a
    // corner replaced from outside the map (a GPS capture or a typed coordinate)
    // re-seeds its pin, while a pin the user is dragging keeps its identity.
    val markerStates: List<MarkerState> = vertices.mapIndexed { index, vertex ->
        key(index, vertex.latitude, vertex.longitude) {
            MarkerState(position = LatLng(vertex.latitude, vertex.longitude))
        }
    }

    // A drag is only committed once the finger is lifted. maps-compose 6.x does not
    // expose an `onDragEnd` callback, and `MarkerState.dragState` is internal, so the
    // settled position is read from the marker's own state instead. `distinctUntilChanged`
    // means the parent is rebuilt once per completed drag, not once per pixel.
    LaunchedEffect(markerStates.size) {
        markerStates.forEachIndexed { index, state ->
            snapshotFlow { state.position }
                .distinctUntilChanged()
                .collect { position ->
                    val current = vertices.getOrNull(index) ?: return@collect
                    if (position.latitude != current.latitude || position.longitude != current.longitude) {
                        moveVertex.value(index, GeoVertex(position.latitude, position.longitude))
                    }
                }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        properties = MapProperties(isMyLocationEnabled = false),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = true,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = true,
        ),
        onMapClick = { latLng ->
            if (enabled) addVertex.value(GeoVertex(latLng.latitude, latLng.longitude))
        },
    ) {
        if (vertices.size >= PlotBoundary.MIN_VERTICES) {
            Polygon(
                points = vertices.map { LatLng(it.latitude, it.longitude) },
                strokeColor = AgriColors.ForestGreen,
                strokeWidth = 4f,
                fillColor = AgriColors.GreenContainer.copy(alpha = 0.45f),
            )
        }

        vertices.forEachIndexed { index, vertex ->
            Marker(
                state = markerStates[index],
                title = "Corner ${index + 1}",
                snippet = if (enabled) "Drag to move" else null,
                draggable = enabled,
            )
        }
    }
}

/** Camera and fallback constants for the map. */
private object Defaults {
    const val Zoom = 15f
    const val RecentreZoom = 17f

    /** Gaborone — the app's home market — as the camera origin for a blank plot. */
    val Start = GeoVertex(-24.6282, 25.9231)
}

/** The explained state shown when the build has no Maps key. */
@Composable
private fun MapUnavailable(reason: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AgriColors.DisabledBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(AgriSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
        ) {
            Text(
                text = "Map unavailable",
                style = MaterialTheme.typography.titleSmall,
                color = AgriColors.TextMain,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = AgriColors.TextSecondary,
            )
        }
    }
}

/**
 * Summary strip under the map: corner count, running area and a plain-language
 * hint. Area is an estimate here and is re-measured on the server before it is
 * ever stored.
 */
@Composable
fun MapBoundarySummary(vertices: List<GeoVertex>, modifier: Modifier = Modifier) {
    val areaHectares = remember(vertices) { PlotBoundary.estimateAreaHectares(vertices) }
    val hasBoundary = vertices.size >= PlotBoundary.MIN_VERTICES

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                vertices.isEmpty() -> "Tap the map to add the first corner."
                hasBoundary -> "${vertices.size} corners"
                else -> "${vertices.size} of ${PlotBoundary.MIN_VERTICES} corners"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (hasBoundary) AgriColors.SuccessGreen else AgriColors.WarningAmber,
        )
        if (areaHectares != null) {
            Text(
                text = "~${"%.2f".format(areaHectares)} ha",
                style = MaterialTheme.typography.bodySmall,
                color = AgriColors.TextSecondary,
            )
        }
    }
    Spacer(Modifier.height(AgriSpacing.xs))
}
