package bw.agriconnect.ui.satellite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import bw.agriconnect.core.util.CurrentLocation
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.domain.model.satellite.SatelliteOverview
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.BoundaryEditor
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.NdviGauge
import bw.agriconnect.ui.components.PlotMapView
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.components.StatusChip
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing
import kotlinx.coroutines.launch

/**
 * The farm map card: every registered plot drawn on a real interactive Google map.
 *
 * It is the geographic answer the satellite screen previously could not give. A plot
 * with a stored boundary is drawn as a polygon tinted by its measured NDVI; a plot with
 * only a pin is drawn as a marker and plainly said to have no boundary yet, which is the
 * honest state — the app never invents an outline from a single point.
 */
@Composable
fun FarmMapCard(
    state: SatelliteUiState,
    selectedPlotId: String?,
    onSelectPlot: (String) -> Unit,
    onAddPlot: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var myLocation by remember { mutableStateOf<GeoVertex?>(null) }
    var locating by remember { mutableStateOf(false) }

    suspend fun captureMe() {
        locating = true
        myLocation = CurrentLocation.capture(context) ?: myLocation
        locating = false
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) scope.launch { captureMe() } }

    AgriCard {
        Column {
            SectionHeader(
                title = "My farms on the map",
                subtitle = if (state.plotMapShapes.isEmpty()) {
                    "No plots registered yet"
                } else {
                    "Satellite imagery of your own plot boundaries"
                },
            )

            Spacer(Modifier.height(AgriSpacing.md))

            if (state.plotMapShapes.isEmpty()) {
                // Empty state, but the map itself is still shown when a key exists, so a
                // farmer can look at their land and place the first plot.
                NoPlotsOnMap(onAddPlot = onAddPlot)
            } else {
                PlotMapView(
                    plots = state.plotMapShapes,
                    selectedPlotId = selectedPlotId,
                    onSelectPlot = onSelectPlot,
                    locationEnabled = myLocation != null,
                    myLocation = myLocation,
                    onLocate = {
                        // Real capture, not a decorative button: asks for the permission
                        // the first time and degrades to nothing if it is refused.
                        if (CurrentLocation.hasPermission(context)) {
                            scope.launch { captureMe() }
                        } else {
                            permission.launch(CurrentLocation.FINE_PERMISSION)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )

                Spacer(Modifier.height(AgriSpacing.sm))
                LegendRow()

                if (locating) {
                    Spacer(Modifier.height(AgriSpacing.xs))
                    Text(
                        "Reading your position...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val selected = state.plotMapShapes.firstOrNull { it.plotId == selectedPlotId }
                if (selected != null) {
                    Spacer(Modifier.height(AgriSpacing.sm))
                    Text(
                        selected.plotName +
                            if (selected.hasBoundary) {
                                " — boundary stored, ${selected.vertices.size} corners"
                            } else {
                                " — no boundary stored yet"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(AgriSpacing.sm))
            AgriSecondaryButton(
                text = "Add a plot",
                onClick = onAddPlot,
                fillWidth = true,
            )
        }
    }
}

/** Legend explaining the polygon tint and the honesty caveat about simulation. */
@Composable
private fun LegendRow() {
    Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            LegendChip("NDVI 0.45+", AgriColors.SuccessGreen)
            LegendChip("0.20-0.45", AgriColors.WarningAmber)
            LegendChip("under 0.20", AgriColors.ErrorRed)
            LegendChip("No reading", AgriColors.TextSecondary)
        }
        Text(
            "Each plot is shaded by its measured average. The pattern inside a plot is not " +
                "measured per pixel, so it is not drawn.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendChip(label: String, colour: androidx.compose.ui.graphics.Color) {
    StatusChip(text = label, color = colour)
}

/** Shown when the farmer has no plots at all — offers the one action that fixes it. */
@Composable
private fun NoPlotsOnMap(onAddPlot: () -> Unit) {
    Column {
        InfoBanner(
            message = "You have not registered a plot yet. Add one with its boundary and " +
                "it will appear here on the satellite map.",
        )
        Spacer(Modifier.height(AgriSpacing.sm))
        AgriPrimaryButton(text = "Add your first plot", onClick = onAddPlot, fillWidth = true)
    }
}

/**
 * The plot status card: name, the measured NDVI, the health band, the date it was
 * measured, a plain-language interpretation and a way into the full report.
 *
 * Colour comes from [bw.agriconnect.core.util.NdviUtil], the single source of the
 * thresholds, so this card can never disagree with the gauge below it or with the server.
 */
@Composable
fun PlotStatusCard(
    overview: SatelliteOverview,
    fromCache: Boolean,
    onOpenInsights: () -> Unit,
) {
    val latest = overview.observations.maxByOrNull { it.observedOn }
    val ndvi = latest?.ndvi

    AgriCard {
        Column {
            SectionHeader(
                title = overview.plot.plotName.ifBlank { "Unnamed plot" },
                subtitle = overview.geometry.areaHectares?.let { "%.2f hectares".format(it) }
                    ?: "Area not measured yet",
            )

            Spacer(Modifier.height(AgriSpacing.md))

            if (ndvi == null) {
                InfoBanner(
                    message = "No vegetation reading yet. One appears once a clear satellite " +
                        "pass covers this plot's saved boundary.",
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "%.2f".format(ndvi),
                            style = MaterialTheme.typography.displayMedium,
                            color = bw.agriconnect.core.util.NdviUtil.colour(
                                bw.agriconnect.core.util.NdviUtil.classify(ndvi),
                            ),
                        )
                        Text(
                            bw.agriconnect.core.util.NdviUtil.label(
                                bw.agriconnect.core.util.NdviUtil.classify(ndvi),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    StatusChip(
                        text = if (latest?.isSimulated == true) "DEMO" else (latest?.source ?: "SATELLITE"),
                        color = if (latest?.isSimulated == true) AgriColors.SoilBrown else AgriColors.InfoBlue,
                    )
                }

                Spacer(Modifier.height(AgriSpacing.sm))
                Text(
                    "Measured over your boundary on " +
                        bw.agriconnect.core.util.DateFormatters.formatDate(latest?.observedOn) +
                        ". A vegetation index is an indication, not a diagnosis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(AgriSpacing.md))
            AgriPrimaryButton(
                text = "Open full insights report",
                onClick = onOpenInsights,
                fillWidth = true,
            )
            if (fromCache) {
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    "The report will open from data saved on this device until you reconnect.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The "latest satellite observation" card.
 *
 * This is the screen's contract of honesty, so the state label is computed first and the
 * numbers second:
 *
 *  - **Updated** — this payload came from the network and carries a usable reading.
 *  - **Cached** — this payload came from the local mirror; it is real data, but not a
 *    live fetch, and it is labelled with the mirror's write time as "saved", never as an
 *    observation date.
 *  - **Offline** — there is no payload at all and the device cannot reach the server.
 *  - **No observation available** — the server answered, but this plot has no usable pass.
 *  - **Update failed** — the request failed for a reason other than connectivity.
 *
 * A reading is never re-dated. The date shown is always the server's own `observedOn`.
 */
@Composable
fun LatestObservationCard(
    state: SatelliteUiState,
    overview: SatelliteOverview,
    onRetry: () -> Unit,
) {
    val latest = overview.observations.maxByOrNull { it.observedOn }
    val status = overview.observationStatus

    val label = observationStateLabel(
        isLoading = state.isLoading,
        hasError = state.error != null,
        isOffline = state.offline,
        fromCache = state.overviewFromCache,
        hasReading = latest?.ndvi != null,
    )
    val labelColour = when (label) {
        "Updated" -> AgriColors.SuccessGreen
        "Cached" -> AgriColors.WarningAmber
        "Offline" -> AgriColors.TextSecondary
        "Update failed" -> AgriColors.ErrorRed
        "Checking for a new pass" -> AgriColors.InfoBlue
        else -> AgriColors.WarningAmber
    }
    val labelIcon = when (label) {
        "Updated" -> Icons.Filled.CheckCircle
        "Cached" -> Icons.Filled.Refresh
        "Offline" -> Icons.Filled.CloudOff
        "Update failed" -> Icons.Filled.ErrorOutline
        "Checking for a new pass" -> Icons.Filled.Refresh
        else -> Icons.Filled.Warning
    }

    AgriCard {
        Column {
            SectionHeader(
                title = "Latest satellite observation",
                subtitle = "The newest clear image of this plot",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(labelIcon, contentDescription = null, tint = labelColour, modifier = Modifier.height(16.dp))
                        Box(Modifier.padding(start = AgriSpacing.xs)) {
                            StatusChip(text = label, color = labelColour)
                        }
                    }
                },
            )

            Spacer(Modifier.height(AgriSpacing.md))

            if (latest == null || latest.ndvi == null) {
                Text(
                    status.note
                        ?: "There is no usable observation for this plot yet. A reading needs a " +
                        "clear pass over a saved boundary; cloud cover can delay that for weeks. " +
                        "Nothing is estimated in the meantime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                NdviGauge(
                    meanNdvi = latest.ndvi,
                    statusText = buildString {
                        append("Observed ")
                        append(bw.agriconnect.core.util.DateFormatters.formatDate(latest.observedOn))
                        append(" - ")
                        append(if (latest.isSimulated) "MOCK" else latest.source ?: "Sentinel-2")
                        status.newestObservationAgeDays?.let { append(" ($it days old)") }
                    },
                    stressLevel = bw.agriconnect.core.util.NdviUtil.classify(latest.ndvi),
                    cached = status.isCached || state.overviewFromCache,
                )

                Spacer(Modifier.height(AgriSpacing.sm))
                ComparisonLine(overview = overview)

                state.overviewCachedAtMillis?.takeIf { state.overviewFromCache }?.let { millis ->
                    Spacer(Modifier.height(AgriSpacing.xs))
                    Text(
                        "Saved on this device " +
                            bw.agriconnect.core.util.DateFormatters.relativeMillis(millis) +
                            ". This is when the app last received it, not when the satellite passed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (latest != null && latest.isSimulated) {
                Spacer(Modifier.height(AgriSpacing.sm))
                InfoBanner(
                    message = "This reading is demonstration data (MOCK) because no live satellite " +
                        "provider was reached. It is labelled so it can never be mistaken for a real pass.",
                )
            }

            if (state.error != null) {
                Spacer(Modifier.height(AgriSpacing.sm))
                ErrorBanner(
                    message = state.error.orEmpty(),
                    // A real re-fetch, not a cosmetic dismiss: the only way an old cache
                    // becomes "Updated" is the network actually answering.
                    onRetry = onRetry,
                )
            } else if (state.overviewFromCache) {
                Spacer(Modifier.height(AgriSpacing.sm))
                AgriSecondaryButton(
                    text = "Try for a fresh pass",
                    onClick = onRetry,
                    fillWidth = true,
                )
            }
        }
    }
}

/**
 * The one place the card's state word is decided, extracted so the precedence is
 * unit-testable rather than buried in a composable.
 *
 * Order matters: a payload restored from the device mirror is always **Cached** — even if
 * the request that produced it then failed — because the farmer is looking at real stored
 * data and must not be told it is a fresh download. `isLoading` outranks everything so a
 * half-finished refresh never reads as settled.
 */
internal fun observationStateLabel(
    isLoading: Boolean,
    hasError: Boolean,
    isOffline: Boolean,
    fromCache: Boolean,
    hasReading: Boolean,
): String = when {
    isLoading -> "Checking for a new pass"
    fromCache -> "Cached"
    hasError && isOffline -> "Offline"
    hasError -> "Update failed"
    !hasReading -> "No observation available"
    else -> "Updated"
}

/** One line comparing the newest reading with the previous one, when both exist. */
@Composable
private fun ComparisonLine(overview: SatelliteOverview) {
    val summary = overview.summary.ndvi ?: return
    val change = summary.changeVsPrevious ?: return
    val previousDate = summary.changeDate

    Text(
        buildString {
            append(if (change < 0) "Down " else "Up ")
            append("%.2f".format(kotlin.math.abs(change)))
            append(" versus the previous reading")
            previousDate?.let { append(" of ${bw.agriconnect.core.util.DateFormatters.formatDate(it)}") }
            append(". ")
            append(summary.trend?.let { "Trend: ${it.lowercase()}." } ?: "")
        }.trim(),
        style = MaterialTheme.typography.bodySmall,
        color = if (change < 0) AgriColors.ErrorRed else AgriColors.SuccessGreen,
    )
}

/**
 * Bottom sheet for creating a plot from the map.
 *
 * It writes through the **same** repository call Farm Management uses, so the plot it
 * saves is the same record both screens show. A boundary of at least three corners is
 * required before it can be monitored; a farmer may instead drop a single pin, which is
 * saved with **no** boundary rather than a polygon invented around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlotSheet(
    state: SatelliteUiState,
    viewModel: SatelliteViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var vertices by remember { mutableStateOf<List<GeoVertex>>(emptyList()) }
    var manualLat by remember { mutableStateOf("") }
    var manualLng by remember { mutableStateOf("") }
    var isLocating by remember { mutableStateOf(false) }
    var pinOnly by remember { mutableStateOf(false) }
    var centre by remember { mutableStateOf<GeoVertex?>(null) }

    suspend fun captureLocation(asPin: Boolean) {
        isLocating = true
        val vertex = CurrentLocation.capture(context)
        isLocating = false
        if (vertex == null) {
            viewModel.dismissCreatePlotMessage()
            return
        }
        if (asPin) centre = vertex else vertices = vertices + vertex
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scope.launch { captureLocation(pinOnly) }
    }

    fun locate(asPin: Boolean) {
        pinOnly = asPin
        if (CurrentLocation.hasPermission(context)) {
            scope.launch { captureLocation(asPin) }
        } else {
            locationPermission.launch(CurrentLocation.FINE_PERMISSION)
        }
    }

    val boundaryComplete = vertices.size >= PlotBoundary.MIN_VERTICES

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
        ) {
            SectionHeader(
                title = "Add a plot",
                subtitle = "Saved to Farm Management as well, as one shared record",
            )

            AgriTextField(
                value = name,
                onValueChange = { name = it },
                label = "Plot name",
                placeholder = "e.g. North field",
            )

            Spacer(Modifier.height(AgriSpacing.sm))

            BoundaryEditor(
                vertices = vertices,
                manualLat = manualLat,
                onManualLatChange = { manualLat = it },
                manualLng = manualLng,
                onManualLngChange = { manualLng = it },
                onAddManual = {
                    val lat = manualLat.trim().toDoubleOrNull()
                    val lng = manualLng.trim().toDoubleOrNull()
                    if (lat != null && lng != null && PlotBoundary.isValid(GeoVertex(lat, lng))) {
                        vertices = vertices + GeoVertex(lat, lng)
                        manualLat = ""
                        manualLng = ""
                    }
                },
                onUseMyLocation = { locate(asPin = false) },
                onRemoveVertex = { index -> vertices = vertices.filterIndexed { i, _ -> i != index } },
                onClear = { vertices = emptyList() },
                isLocating = isLocating,
                enabled = !state.isCreatingPlot,
                onAddVertex = { vertex -> vertices = vertices + vertex },
                // Without this, the map's corner markers still look draggable but the move
                // is discarded — the drawn shape and the saved shape would disagree.
                onMoveVertex = { index, moved ->
                    vertices = vertices.mapIndexed { i, v -> if (i == index) moved else v }
                },
            )

            Spacer(Modifier.height(AgriSpacing.sm))
            Text(
                "No boundary yet? You can still save the plot with a single pin and survey the " +
                    "edges later. A pin is a location, not a boundary, so it cannot be measured.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                AgriSecondaryButton(
                    text = if (centre == null) "Drop a pin here" else "Pin placed",
                    onClick = { locate(asPin = true) },
                    enabled = !isLocating && !state.isCreatingPlot,
                    fillWidth = false,
                )
                if (centre != null) {
                    AgriTextButton(text = "Remove pin", onClick = { centre = null })
                }
            }

            state.createPlotMessage?.let { message ->
                Spacer(Modifier.height(AgriSpacing.sm))
                InfoBanner(message = message)
            }

            Spacer(Modifier.height(AgriSpacing.md))
            AgriPrimaryButton(
                text = "Save plot",
                onClick = {
                    viewModel.createPlotFromMap(
                        name = name,
                        boundary = vertices,
                        centreOnly = centre,
                    )
                },
                enabled = name.isNotBlank() && (boundaryComplete || centre != null) && !state.isCreatingPlot,
                loading = state.isCreatingPlot,
                fillWidth = true,
            )
            AgriTextButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(AgriSpacing.lg))
        }
    }

    // Close the sheet once the plot has actually been saved, so the farmer lands back on
    // the map with the new plot selected rather than on a stale form.
    LaunchedEffect(state.createPlotMessage) {
        if (state.createPlotMessage?.startsWith("Plot saved") == true) onDismiss()
    }
}
