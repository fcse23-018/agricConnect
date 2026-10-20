package bw.agriconnect.ui.satellite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.components.StatusChip
import bw.agriconnect.ui.components.StatTile
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.core.util.PlotBoundary
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.satellite.*
import bw.agriconnect.ui.satellite.SatelliteEvent
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriBarChart
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriFilterChip
import bw.agriconnect.ui.components.AgriLineChart
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.ConfirmationDialog
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.ErrorState
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.InlineLoading
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.MapBoundarySummary
import bw.agriconnect.ui.components.PlotBoundaryMap
import bw.agriconnect.data.remote.dto.EventCreateRequest
import bw.agriconnect.data.remote.dto.EventUpdateRequest
import bw.agriconnect.data.remote.dto.BoundaryDto
import bw.agriconnect.data.remote.dto.BoundaryPreviewRequest
import bw.agriconnect.data.remote.dto.LatLonDto
import bw.agriconnect.data.remote.dto.SetBoundaryRequest
import bw.agriconnect.ui.components.NdviAdviceCard
import bw.agriconnect.ui.components.NdviGauge
import bw.agriconnect.ui.components.OfflineEmptyState
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Unified Satellite Monitoring Dashboard.
 *
 * One screen combining: plot boundary map, NDVI/EVI time series, weather history,
 * crop growth cycle, vegetation drop explanations, farmer event log, and alerts.
 * All data comes from GET /api/v1/satellite/overview - no fabricated values,
 * simulated data is labelled, gaps are reported honestly.
 */
@Composable
fun SatelliteScreen(
    plotId: String? = null,
    onBack: (() -> Unit)? = null,
    /** Opens the farm detail screen for a plot (optional, navigation-owned). */
    onOpenPlot: ((String) -> Unit)? = null,
    /** Sends the user to the farms list to add a plot (optional, navigation-owned). */
    onAddPlot: (() -> Unit)? = null,
    viewModel: SatelliteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canGenerate by viewModel.canGenerate.collectAsStateWithLifecycle()
    val plots by viewModel.plots.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPlotId.collectAsStateWithLifecycle()

    val currentPlotId = selected ?: plotId ?: plots.firstOrNull()?.plotId

    // Tab state
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "Overview",
        "Chart",
        "Weather",
        "Growth",
        "Events",
        "Alerts",
    )

    // One-shot start effect
    LaunchedEffect(plotId) { viewModel.start(plotId) }

    MainScaffold(
        title = "Satellite Monitoring",
        subtitle = state.overview?.plot?.plotName?.takeIf { it.isNotBlank() },
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Tab row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                tabs.forEachIndexed { index, name ->
                    val isSelected = index == selectedTab
                    androidx.compose.material3.TextButton(
                        onClick = { selectedTab = index },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                        shape = RoundedCornerShape(0.dp),
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Tab content
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AgriSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
                ) {
                    when {
                        state.isLoading && state.overview == null -> {
                            LoadingState(message = "Loading satellite monitoring...")
                        }

                        state.error != null && state.overview == null -> {
                            if (state.offline) {
                                OfflineEmptyState(onRetry = { currentPlotId?.let { viewModel.onEvent(SatelliteEvent.RefreshOverview(it)) } })
                            } else {
                                ErrorState(
                                    message = state.error.orEmpty(),
                                    onRetry = { currentPlotId?.let { viewModel.onEvent(SatelliteEvent.RefreshOverview(it)) } },
                                )
                            }
                        }

                        state.overview == null -> {
                            when {
                                plots.isEmpty() -> EmptyState(
                                    title = "No plots yet",
                                    message = "Add a plot with a boundary to monitor its vegetation health.",
                                    actionLabel = "Add a plot",
                                    onAction = { onAddPlot?.invoke() },
                                )
                                canGenerate -> EmptyState(
                                    title = "No monitoring data yet",
                                    message = "Generate a fresh NDVI result for this plot. If the plot has no saved boundary, you'll need to add one first.",
                                    actionLabel = if (state.isGenerating) "Generating..." else "Generate satellite data",
                                    onAction = { if (!state.isGenerating) currentPlotId?.let { viewModel.onEvent(SatelliteEvent.GenerateLegacyNdvi(it)) } },
                                )
                                else -> EmptyState(
                                    title = "No monitoring data yet",
                                    message = "Only the farm owner can generate satellite data. The last computed result will appear here if available.",
                                )
                            }
                        }

                        else -> {
                            val overview = state.overview!!

                            // Error/Info banners
                            if (state.error != null) {
                                ErrorBanner(message = state.error.orEmpty(), onRetry = { currentPlotId?.let { viewModel.onEvent(SatelliteEvent.RefreshOverview(it)) } })
                            }
                            if (state.infoMessage != null) {
                                InfoBanner(message = state.infoMessage.orEmpty())
                            }
                            if (overview.observationStatus.isCached) {
                                InfoBanner(message = "Showing cached data. Reconnect to fetch the latest satellite pass.")
                            }
                            if (overview.observationStatus.isSimulated) {
                                InfoBanner(
                                    message = "Showing simulated data (MOCK). Real Sentinel-2 observations require Earth Engine configuration.",
                                    icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = AgriColors.DarkBrown) },
                                )
                            }
                            // Never let an old clear image read as "live": say when the
                            // newest usable observation actually is.
                            overview.observationStatus.newestObservation?.let { newest ->
                                val age = overview.observationStatus.newestObservationAgeDays
                                InfoBanner(
                                    message = "Newest clear satellite image: " +
                                        DateFormatters.formatDate(newest) +
                                        (age?.let { " ($it days old)" } ?: ""),
                                )
                            }
                            overview.observationStatus.note?.let { note ->
                                InfoBanner(message = note)
                            }
                            overview.disclaimer?.let { disclaimer ->
                                InfoBanner(message = disclaimer)
                            }

                            // Plot selector (if multiple plots)
                            if (plots.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                                ) {
                                    plots.forEach { p ->
                                        androidx.compose.material3.TextButton(
                                            onClick = { viewModel.onEvent(SatelliteEvent.SelectPlot(p.plotId)) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp)
                                                .background(if (currentPlotId == p.plotId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                                            shape = RoundedCornerShape(AgriRadius.md),
                                        ) {
                                            Text(
                                                p.plotName.ifBlank { p.plotId },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (currentPlotId == p.plotId) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            // Render selected tab
                            if (onOpenPlot != null && currentPlotId != null) {
                                androidx.compose.material3.TextButton(
                                    onClick = { onOpenPlot(currentPlotId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Open plot details",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            when (selectedTab) {
                                0 -> OverviewTab(
                                    overview = overview,
                                    plotId = currentPlotId,
                                    viewModel = viewModel,
                                )
                                1 -> ChartTab(overview)
                                2 -> WeatherTab(overview)
                                3 -> GrowthCycleTab(overview)
                                4 -> EventsTab(state, currentPlotId, viewModel)
                                5 -> AlertsTab(overview, viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================
 * TAB CONTENT COMPOSABLES
 * ========================================================================= */

@Composable
private fun OverviewTab(
    overview: SatelliteOverview,
    plotId: String?,
    viewModel: SatelliteViewModel,
) {
    val geometry = overview.geometry
    val obsStatus = overview.observationStatus
    val summary = overview.summary
    var showBoundaryEditor by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        // Plot info + geometry card
        item {
            AgriCard {
                Column {
                    SectionHeader(
                        title = overview.plot.plotName,
                        subtitle = "Method: ${geometry.method.name} - ${geometry.areaHectares?.let { "%.2f ha".format(it) } ?: "area unknown"}",
                    )
                    if (!geometry.valid && !geometry.reason.isNullOrBlank()) {
                        ErrorBanner(message = geometry.reason.orEmpty())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                    ) {
                        if (geometry.areaM2 != null) {
                            StatTile(label = "Area", value = "${geometry.areaM2!!.format1()} m2", accent = AgriColors.ForestGreen)
                        }
                        if (geometry.perimeterM != null) {
                            StatTile(label = "Perimeter", value = "${geometry.perimeterM!!.format0()} m", accent = AgriColors.SoilBrown)
                        }
                        if (geometry.sideLengthsM?.isNotEmpty() == true) {
                            StatTile(label = "Sides", value = geometry.sideLengthsM!!.map { "${it.format0()}m" }.joinToString(", "), accent = AgriColors.InfoBlue)
                        }
                    }

                    if (geometry.vertices.isNotEmpty()) {
                        Spacer(Modifier.height(AgriSpacing.sm))
                        Text(
                            "Corners: " + geometry.vertices.take(6).joinToString("  ") {
                                "%.5f, %.5f".format(it.latitude, it.longitude)
                            } + if (geometry.vertices.size > 6) "  +${geometry.vertices.size - 6} more" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(AgriSpacing.sm))
                    AgriSecondaryButton(
                        text = if (geometry.valid) "Edit boundary" else "Set boundary",
                        onClick = { showBoundaryEditor = true },
                        enabled = plotId != null,
                        fillWidth = true,
                    )
                    Text(
                        "Area and perimeter are measured from the real polygon on the server. Saving a new outline drops observations that covered the old one, so the series is recalculated instead of reused.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // NDVI Gauge + latest observation
        item {
            // The series is returned oldest-first; the gauge shows the newest row.
            val latestObs = overview.observations.maxByOrNull { it.observedOn }
            if (latestObs != null) {
                NdviGauge(
                    meanNdvi = latestObs.ndvi,
                    statusText = buildString {
                        append("Observed: ")
                        append(DateFormatters.formatDate(latestObs.observedOn))
                        append(" - ")
                        append(if (latestObs.isSimulated) "MOCK" else latestObs.source ?: "Sentinel-2")
                        if (latestObs.isOutlier) append(" - OUTLIER")
                    },
                    stressLevel = when {
                        latestObs.ndvi == null -> null
                        latestObs.ndvi!! < 0.3 -> bw.agriconnect.domain.model.StressLevel.HIGH_STRESS
                        latestObs.ndvi!! < 0.5 -> bw.agriconnect.domain.model.StressLevel.MODERATE_STRESS
                        else -> bw.agriconnect.domain.model.StressLevel.HEALTHY_VEGETATION
                    },
                    cached = obsStatus.isCached,
                )
            } else {
                InfoBanner(message = "No observations available for this plot yet.")
            }
        }

        // Summary stats
        item {
            if (summary.ndvi != null || summary.evi != null) {
                AgriCard {
                    Column {
                        SectionHeader(title = "Index Summary")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                        ) {
                            summary.ndvi?.let { s ->
                                Column(Modifier.weight(1f)) {
                                    SummaryRow(label = "NDVI", summary = s, color = AgriColors.SuccessGreen)
                                }
                            }
                            summary.evi?.let { s ->
                                Column(Modifier.weight(1f)) {
                                    SummaryRow(label = "EVI", summary = s, color = AgriColors.SoilBrown)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Latest drops
        item {
            if (overview.drops.isNotEmpty()) {
                AgriCard {
                    Column {
                        SectionHeader(title = "Detected Vegetation Drops", subtitle = "Possible explanations - never a confirmed diagnosis")
                        overview.drops.forEach { drop ->
                            DropCard(drop = drop)
                            if (drop != overview.drops.last()) Spacer(Modifier.height(AgriSpacing.sm))
                        }
                    }
                }
            }
        }

        // Active alerts
        item {
            val activeAlerts = overview.alerts.filter { it.status == AlertStatus.OPEN }
            if (activeAlerts.isNotEmpty()) {
                AgriCard {
                    Column {
                        SectionHeader(title = "Active Alerts (${activeAlerts.size})")
                        activeAlerts.forEach { alert ->
                            AlertCard(alert = alert)
                            if (alert != activeAlerts.last()) Spacer(Modifier.height(AgriSpacing.sm))
                        }
                    }
                }
            }
        }

        // Quality & model status
        item {
            AgriCard {
                Column {
                    SectionHeader(title = "Data Quality & Model Status")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                    ) {
                        overview.quality?.let { q ->
                            Column(Modifier.weight(1f)) {
                                QualityRow(label = "Readings received", value = q.readingsReceived.toString())
                                QualityRow(label = "Readings usable", value = q.readingsUsable.toString())
                                QualityRow(label = "Rejected / outliers", value = "${q.readingsRejected} / ${q.outliers}")
                                if (!q.note.isNullOrBlank()) {
                                    Text(
                                        q.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        overview.modelStatus?.let { m ->
                            Column(Modifier.weight(1f)) {
                                QualityRow(
                                    label = "Verified labels",
                                    value = "${m.labelledEvents}/${m.requiredLabels}",
                                )
                                QualityRow(
                                    label = "Trained model",
                                    value = if (m.trained) "Yes" else "No",
                                )
                                Text(
                                    m.note
                                        ?: "Rule-based classification is active; model training is pending.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Boundary editor: a modal sheet, but hosted from an item so it is only
        // composed while the overview tab is on screen.
        if (showBoundaryEditor && plotId != null) {
            item {
                BoundaryEditorSheet(
                    plotId = plotId,
                    plotName = overview.plot.plotName,
                    existing = geometry,
                    state = viewModel.state.collectAsState().value,
                    viewModel = viewModel,
                    onDismiss = {
                        showBoundaryEditor = false
                        viewModel.onEvent(SatelliteEvent.ClearBoundaryPreview)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChartTab(overview: SatelliteOverview) {
    val observations = overview.observations

    if (observations.isEmpty()) {
        EmptyState(
            title = "No observation data",
            message = "Generate or wait for satellite observations to see the time series chart.",
        )
        return
    }

    // Newest first, so the most recent pass is at the top of the table.
    val sorted = observations.sortedByDescending { it.observedOn }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        AgriCard {
            Column {
                SectionHeader(title = "NDVI / EVI Time Series", subtitle = "Solid = raw, Dashed = smoothed")
                AgriLineChart(
                    observations = observations,
                    height = 220.dp,
                    showNdvi = true,
                    showEvi = true,
                    showSmoothed = true,
                )
            }
        }

        // Observation table
        AgriCard {
            Column {
                SectionHeader(title = "Observation Details")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
                ) {
                    items(sorted.size) { idx ->
                        ObservationRow(obs = sorted[idx])
                        if (idx != sorted.lastIndex) Spacer(Modifier.height(AgriSpacing.xs))
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------
 * Boundary editor (Method A rotated rectangle, Method B custom polygon)
 * ---------------------------------------------------------------------- */

/** Which method the farmer is drawing. */
private enum class BoundaryMethod { RECTANGLE, POLYGON }

/**
 * Bottom sheet that lets the farmer draw a boundary and shows the **server's**
 * measurements for it before saving.
 *
 * Method A is a rotated rectangle: centre, width, length and bearing - the shape
 * most smallholder plots actually have. Method B is a free polygon: at least
 * three corners, add/remove/move, validated before it is sent. Nothing is saved
 * until "Save boundary", and every measurement shown comes from the preview
 * endpoint, so the number on screen is the number that is stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoundaryEditorSheet(
    plotId: String,
    plotName: String,
    existing: PlotGeometry,
    state: SatelliteUiState,
    viewModel: SatelliteViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Seed the editor from the saved geometry where possible, so an edit starts
    // from what is really stored rather than an arbitrary default.
    val seedRectangle = existing.method == GeometryMethod.RECTANGLE && existing.vertices.size == 4
    var method by remember { mutableStateOf(if (seedRectangle) BoundaryMethod.RECTANGLE else BoundaryMethod.POLYGON) }
    var centreLat by remember { mutableStateOf(existing.centroid?.latitude?.format5() ?: "") }
    var centreLon by remember { mutableStateOf(existing.centroid?.longitude?.format5() ?: "") }
    var widthM by remember { mutableStateOf(existing.sideLengthsM?.getOrNull(0)?.format1() ?: "100") }
    var lengthM by remember { mutableStateOf(existing.sideLengthsM?.getOrNull(1)?.format1() ?: "100") }
    var bearingDeg by remember { mutableStateOf("0") }
    var vertices by remember {
        mutableStateOf(existing.vertices.map { LatLonDto(it.latitude, it.longitude) })
    }
    var manualLat by remember { mutableStateOf("") }
    var manualLng by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    /** The boundary as it is currently drawn, or null when it is still invalid. */
    fun currentBoundary(): BoundaryDto? = if (method == BoundaryMethod.RECTANGLE) {
        val lat = centreLat.trim().toDoubleOrNull()
        val lon = centreLon.trim().toDoubleOrNull()
        val width = widthM.trim().toDoubleOrNull()
        val length = lengthM.trim().toDoubleOrNull()
        val bearing = bearingDeg.trim().toDoubleOrNull()
        if (lat == null || lon == null || width == null || length == null || bearing == null) {
            null
        } else {
            BoundaryDto.rectangle(lat, lon, width, length, bearing)
        }
    } else {
        if (vertices.size >= MIN_POLYGON_VERTICES) BoundaryDto.polygon(vertices) else null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgriSpacing.lg)
                .padding(bottom = AgriSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            SectionHeader(
                title = "Plot boundary",
                subtitle = "$plotName - measurements are computed from the real geometry on the server",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                AgriFilterChip(
                    label = "Rotated rectangle",
                    selected = method == BoundaryMethod.RECTANGLE,
                    onClick = { method = BoundaryMethod.RECTANGLE; validationError = null },
                )
                AgriFilterChip(
                    label = "Custom polygon",
                    selected = method == BoundaryMethod.POLYGON,
                    onClick = { method = BoundaryMethod.POLYGON; validationError = null },
                )
            }

            if (method == BoundaryMethod.RECTANGLE) {
                Text(
                    "Method A: a rectangle you can rotate. Drop a pin at the centre of the plot, then set the sides and the direction the long side runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                        AgriTextField(
                            value = centreLat,
                            onValueChange = { centreLat = it },
                            label = "Centre latitude",
                            placeholder = "-24.6543",
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                        AgriTextField(
                            value = centreLon,
                            onValueChange = { centreLon = it },
                            label = "Centre longitude",
                            placeholder = "25.9123",
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                        AgriTextField(
                            value = widthM,
                            onValueChange = { widthM = it },
                            label = "Width (m)",
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                        AgriTextField(
                            value = lengthM,
                            onValueChange = { lengthM = it },
                            label = "Length (m)",
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                        AgriTextField(
                            value = bearingDeg,
                            onValueChange = { bearingDeg = it },
                            label = "Bearing (deg)",
                            helper = "0-360 deg, clockwise from north",
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                }
            } else {
                Text(
                    "Method B: tap the map to add at least $MIN_POLYGON_VERTICES corners around the plot, in order, or type coordinates. Drag a numbered pin to move it. Corners are joined in the order you add them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // The map is the primary way to draw the polygon; the coordinate
                // fields below let you fine-tune or work without a Maps key.
                PlotBoundaryMap(
                    vertices = vertices.map { GeoVertex(it.lat, it.lon) },
                    onAddVertex = { vertex ->
                        vertices = vertices + LatLonDto(vertex.latitude, vertex.longitude)
                        validationError = null
                    },
                    onMoveVertex = { index, vertex ->
                        if (index in vertices.indices &&
                            vertex.latitude in -90.0..90.0 &&
                            vertex.longitude in -180.0..180.0
                        ) {
                            vertices = vertices.toMutableList().apply {
                                this[index] = LatLonDto(vertex.latitude, vertex.longitude)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )

                MapBoundarySummary(vertices = vertices.map { GeoVertex(it.lat, it.lon) })

                Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    AgriTextField(
                        value = manualLat,
                        onValueChange = { manualLat = it },
                        label = "Corner latitude",
                        placeholder = "-24.6543",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Text,
                    )
                    AgriTextField(
                        value = manualLng,
                        onValueChange = { manualLng = it },
                        label = "Corner longitude",
                        placeholder = "25.9123",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Text,
                    )
                }
                AgriSecondaryButton(
                    text = "Add corner",
                    enabled = manualLat.trim().toDoubleOrNull() != null &&
                        manualLng.trim().toDoubleOrNull() != null,
                    fillWidth = false,
                    onClick = {
                        val lat = manualLat.trim().toDoubleOrNull()
                        val lon = manualLng.trim().toDoubleOrNull()
                        if (lat == null || lon == null) {
                            validationError = "Latitude and longitude must be numbers."
                        } else if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            validationError = "Latitude must be -90 to 90 and longitude -180 to 180."
                        } else {
                            vertices = vertices + LatLonDto(lat, lon)
                            manualLat = ""
                            manualLng = ""
                            validationError = null
                        }
                    },
                )

                if (vertices.isEmpty()) {
                    Text(
                        "No corners yet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    vertices.forEachIndexed { index, vertex ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${vertex.lat.format5()}, ${vertex.lon.format5()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            AgriTextButton(
                                text = "Remove",
                                onClick = { vertices = vertices.filterIndexed { i, _ -> i != index } },
                            )
                        }
                    }
                }
            }

            validationError?.let { ErrorBanner(message = it) }
            state.boundaryPreviewError?.let { ErrorBanner(message = it) }

            AgriSecondaryButton(
                text = "Measure this boundary",
                enabled = currentBoundary() != null,
                loading = state.isPreviewingBoundary,
                fillWidth = true,
                onClick = {
                    val boundary = currentBoundary()
                    if (boundary == null) {
                        validationError = if (method == BoundaryMethod.RECTANGLE) {
                            "Centre, width, length and bearing are all required."
                        } else {
                            "A polygon needs at least $MIN_POLYGON_VERTICES corners."
                        }
                    } else {
                        viewModel.onEvent(
                            SatelliteEvent.PreviewBoundary(BoundaryPreviewRequest(boundary)),
                        )
                    }
                },
            )

            state.boundaryPreview?.let { preview ->
                AgriCard(containerColour = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
                        SectionHeader(
                            title = if (preview.geometry.valid) "Measured on the server" else "Cannot measure this outline",
                        )
                        if (!preview.geometry.valid) {
                            ErrorBanner(
                                message = preview.geometry.reason
                                    ?: "The server could not measure this outline. Adjust the corners and try again.",
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                                preview.geometry.areaM2?.let {
                                    StatTile(label = "Area", value = "${it.format1()} m2", accent = AgriColors.ForestGreen)
                                }
                                preview.geometry.areaHectares?.let {
                                    StatTile(label = "Hectares", value = "%.4f".format(it), accent = AgriColors.SoilBrown)
                                }
                                preview.geometry.perimeterM?.let {
                                    StatTile(label = "Perimeter", value = "${it.format0()} m", accent = AgriColors.InfoBlue)
                                }
                            }
                            preview.note?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            AgriPrimaryButton(
                text = "Save boundary",
                enabled = currentBoundary() != null && state.boundaryPreview?.geometry?.valid != false,
                fillWidth = true,
                onClick = {
                    val boundary = currentBoundary()
                    if (boundary == null) {
                        validationError = "Complete the outline before saving."
                    } else {
                        viewModel.onEvent(
                            SatelliteEvent.SaveBoundary(
                                plotId = plotId,
                                request = SetBoundaryRequest(boundary = boundary),
                            ),
                        )
                        onDismiss()
                    }
                },
            )

            Text(
                "Saving replaces the stored outline. Observations taken over the previous outline are deleted and will be recalculated, so old readings are never attributed to the new shape.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AgriTextButton(text = "Cancel", onClick = onDismiss)
        }
    }
}

/** An outline is a polygon only once it has at least three corners. */
private const val MIN_POLYGON_VERTICES = 3
private fun Double.format5(): String = "%.5f".format(this)

@Composable
private fun WeatherTab(overview: SatelliteOverview) {
    val weather = overview.weather

    if (weather == null || !weather.available || weather.daily.isEmpty()) {
        EmptyState(
            title = "No weather data",
            message = weather?.reason
                ?: "Modelled weather from Open-Meteo is not available for this plot yet. Missing weather is reported as missing - it is never replaced with made-up values.",
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        weather.summary?.let { summary ->
            AgriCard {
                Column {
                    SectionHeader(
                        title = "Weather Summary",
                        subtitle = if (summary.periodStart != null) {
                            DateFormatters.formatDate(summary.periodStart) + " - " +
                                (summary.periodEnd?.let { DateFormatters.formatDate(it) } ?: "...")
                        } else {
                            "Totals for the returned window"
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                    ) {
                        summary.rainfall7dMm?.let { StatTile(label = "Rain 7d", value = "${it.format1()} mm", accent = AgriColors.InfoBlue) }
                        summary.rainfall14dMm?.let { StatTile(label = "Rain 14d", value = "${it.format1()} mm", accent = AgriColors.InfoBlue) }
                        summary.tempMax7dC?.let { StatTile(label = "Max 7d", value = "${it.format0()} degC", accent = AgriColors.ErrorRed) }
                        summary.tempMin7dC?.let { StatTile(label = "Min 7d", value = "${it.format0()} degC", accent = AgriColors.InfoBlue) }
                    }
                    summary.soilMoistureMeanPct?.let {
                        Spacer(Modifier.height(AgriSpacing.xs))
                        QualityRow(label = "Mean top-soil moisture (0-7 cm)", value = "${it.format0()}%")
                    }
                }
            }
        }

        AgriCard {
            Column {
                SectionHeader(
                    title = "Weather History",
                    subtitle = "Source: ${weather.source ?: "Open-Meteo"} (${weather.dataType.name}) - modelled estimate, not a field measurement",
                )
                weather.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
                ) {
                    val sorted = weather.daily.sortedByDescending { it.date }
                    items(sorted.size) { idx ->
                        WeatherRow(day = sorted[idx])
                        if (idx != sorted.lastIndex) Spacer(Modifier.height(AgriSpacing.xs))
                    }
                }
            }
        }
    }
}

@Composable
private fun GrowthCycleTab(overview: SatelliteOverview) {
    val cycle = overview.growthCycle

    if (cycle == null || !cycle.available) {
        EmptyState(
            title = "No growth cycle estimate",
            message = cycle?.reason
                ?: "There are not enough valid observations yet to estimate green-up, peak and end of season. No dates are estimated without evidence.",
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        AgriCard {
            Column {
                SectionHeader(
                    title = "Crop Growth Cycle",
                    subtitle = buildString {
                        append("Estimated from the smoothed series - every date is an estimate")
                        cycle.confidence?.let { append(" - confidence ${it.name}") }
                    },
                )
                if (!cycle.reason.isNullOrBlank()) {
                    InfoBanner(message = cycle.reason)
                }

                GrowthPhaseRow(
                    label = "Green-up",
                    date = cycle.greenUp,
                    confidence = cycle.confidence,
                )
                Spacer(Modifier.height(AgriSpacing.md))
                GrowthPhaseRow(
                    label = "Peak vegetation",
                    date = cycle.peak,
                    confidence = cycle.confidence,
                )
                Spacer(Modifier.height(AgriSpacing.md))
                GrowthPhaseRow(
                    label = "End of season",
                    date = cycle.endOfSeason,
                    confidence = cycle.confidence,
                )

                if (cycle.evidence.isNotEmpty()) {
                    Spacer(Modifier.height(AgriSpacing.md))
                    Text(
                        "Evidence: " + cycle.evidence.joinToString(" * "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cycle.strategy?.let { strategy ->
                    Spacer(Modifier.height(AgriSpacing.xs))
                    Text(
                        "Method: $strategy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventsTab(
    state: SatelliteUiState,
    plotId: String?,
    viewModel: SatelliteViewModel,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlotEvent?>(null) }
    var pendingDelete by remember { mutableStateOf<PlotEvent?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        AgriCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
            ) {
                SectionHeader(
                    title = "Farmer Event Log",
                    subtitle = "Confirm what the satellite detected and record harvests, clearing and stress for this plot",
                )

                AgriPrimaryButton(
                    text = "Add event",
                    onClick = { showAddDialog = true },
                    fillWidth = true,
                )

                when {
                    state.isLoadingEvents && state.events.isEmpty() -> {
                        InlineLoading(message = "Loading events...")
                    }

                    state.events.isEmpty() -> {
                        Text(
                            "No events recorded yet. System-detected vegetation drops appear here automatically once they are analysed; you can then confirm or correct them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        state.events.forEach { event ->
                            EventRow(
                                event = event,
                                onEdit = { editing = event },
                                onDelete = { pendingDelete = event },
                            )
                        }
                    }
                }

                Text(
                    "Only confirmed farmer labels are used for model training, and training stays pending until enough of them exist. Until then classification remains the documented rule-based method.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showAddDialog || editing != null) {
        EventDialog(
            event = editing,
            plotId = plotId,
            viewModel = viewModel,
            onDismiss = {
                showAddDialog = false
                editing = null
            },
        )
    }

    pendingDelete?.let { event ->
        ConfirmationDialog(
            visible = true,
            title = "Delete this event?",
            message = buildString {
                append("This removes your entry for ")
                append(DateFormatters.formatDate(event.eventDate))
                append(" from the log. System-detected entries cannot be deleted - confirm or correct them instead.")
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                viewModel.onEvent(SatelliteEvent.DeleteEvent(event.eventId))
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** One row of the event log with its confirm/correct and delete actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventRow(event: PlotEvent, onEdit: () -> Unit, onDelete: () -> Unit) {
    val kindColour = when (event.kind) {
        PlotEventKind.HARVEST, PlotEventKind.CLEARING -> AgriColors.WarningAmber
        PlotEventKind.HEAT_STRESS, PlotEventKind.WATER_STRESS -> AgriColors.ErrorRed
        PlotEventKind.DETECTED_DROP -> AgriColors.InfoBlue
        else -> AgriColors.DarkBrown
    }

    AgriCard(containerColour = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
            ) {
                Text(
                    DateFormatters.formatDate(event.eventDate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = AgriSpacing.xs),
                )
                StatusChip(text = event.kind.readable(), color = kindColour)
                if (event.source == PlotEventSource.SYSTEM) {
                    StatusChip(text = "DETECTED", color = AgriColors.InfoBlue)
                }
                if (event.confirmed) {
                    StatusChip(text = "CONFIRMED", color = AgriColors.SuccessGreen)
                }
                event.confidence?.let { confidence ->
                    StatusChip(text = "CONF ${confidence.name}", color = AgriColors.DarkBrown)
                }
            }

            event.classification?.let { classification ->
                Text(
                    "Suggested: ${classification.readable()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            event.notes?.let { notes ->
                if (notes.isNotBlank()) {
                    Text(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            val indices = listOfNotNull(
                event.ndviBefore?.let { before ->
                    val after = event.ndviAfter
                    if (after != null) {
                        "NDVI %.2f -> %.2f (%+.2f)".format(before, after, after - before)
                    } else {
                        "NDVI %.2f".format(before)
                    }
                },
            )
            if (indices.isNotEmpty()) {
                Text(
                    indices.joinToString(" - "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgriTextButton(
                    text = if (event.source == PlotEventSource.SYSTEM) "Confirm / correct" else "Edit",
                    onClick = onEdit,
                )
                if (event.source == PlotEventSource.FARMER) {
                    AgriTextButton(text = "Delete", onClick = onDelete)
                }
                Spacer(Modifier.weight(1f))
                event.updatedAt?.let { updated ->
                    Text(
                        "updated ${DateFormatters.formatDate(updated.take(10))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Create/edit sheet for an event.
 *
 * Editing a SYSTEM row and choosing a kind is exactly the confirm/correct flow
 * the server treats as a verified label; the dialog says so instead of implying
 * the system already knew.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDialog(
    event: PlotEvent?,
    plotId: String?,
    viewModel: SatelliteViewModel,
    onDismiss: () -> Unit,
) {
    val isEdit = event != null
    val editableKinds = listOf(
        PlotEventKind.HARVEST,
        PlotEventKind.CLEARING,
        PlotEventKind.HEAT_STRESS,
        PlotEventKind.WATER_STRESS,
        PlotEventKind.OBSERVATION,
        PlotEventKind.OTHER,
    )
    val initialKind = event?.kind?.takeIf { it in editableKinds } ?: PlotEventKind.HARVEST

    var kind by remember(event?.eventId) { mutableStateOf(initialKind) }
    var date by remember(event?.eventId) {
        mutableStateOf(event?.eventDate ?: java.time.LocalDate.now().toString())
    }
    var notes by remember(event?.eventId) { mutableStateOf(event?.notes.orEmpty()) }
    var dateError by remember(event?.eventId) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            Text(
                when {
                    event?.source == PlotEventSource.SYSTEM -> "Confirm or correct a detected event"
                    isEdit -> "Edit event"
                    else -> "Record a field event"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
            ) {
                if (event?.source == PlotEventSource.SYSTEM) {
                    InfoBanner(
                        message = "Detected as: " +
                            (event.classification?.readable() ?: "no explanation available") +
                            ". Picking a kind below confirms the system's suggestion or corrects it, and marks it as your verified label.",
                    )
                }

                Text("What happened?", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
                ) {
                    editableKinds.forEach { option ->
                        AgriFilterChip(
                            label = option.readable(),
                            selected = kind == option,
                            onClick = { kind = option },
                        )
                    }
                }

                AgriTextField(
                    value = date,
                    onValueChange = { value ->
                        date = value.trim()
                        dateError = null
                    },
                    label = "Date",
                    placeholder = "YYYY-MM-DD",
                    helper = if (dateError == null) "Satellite dates use the format YYYY-MM-DD." else null,
                    error = dateError,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )

                AgriTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Notes (optional)",
                    placeholder = "What did you see in the field?",
                    singleLine = false,
                    maxLines = 3,
                    helper = "${notes.length}/2000 characters",
                    error = if (notes.length > 2_000) "Notes must be 2000 characters or fewer." else null,
                )
            }
        },
        confirmButton = {
            AgriPrimaryButton(
                text = if (isEdit) "Save" else "Add event",
                fillWidth = false,
                enabled = plotId != null || isEdit,
                onClick = {
                    when {
                        !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date) -> {
                            dateError = "Use the format YYYY-MM-DD."
                        }
                        notes.length > 2_000 -> Unit // error shown inline
                        isEdit -> {
                            viewModel.onEvent(
                                SatelliteEvent.UpdateEvent(
                                    eventId = event!!.eventId,
                                    request = EventUpdateRequest(
                                        kind = kind.name,
                                        confirmed = true,
                                        notes = notes.ifBlank { null },
                                        eventDate = date,
                                    ),
                                ),
                            )
                            onDismiss()
                        }
                        plotId != null -> {
                            viewModel.onEvent(
                                SatelliteEvent.CreateEvent(
                                    EventCreateRequest(
                                        plotId = plotId,
                                        eventDate = date,
                                        kind = kind.name,
                                        notes = notes.ifBlank { null },
                                    ),
                                ),
                            )
                            onDismiss()
                        }
                    }
                },
            )
        },
        dismissButton = {
            AgriTextButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

/** Readable label for an event kind (HEAT_STRESS -> Heat stress). */
private fun PlotEventKind.readable(): String = when (this) {
    PlotEventKind.DETECTED_DROP -> "Detected drop"
    PlotEventKind.HARVEST -> "Harvest"
    PlotEventKind.CLEARING -> "Clearing"
    PlotEventKind.HEAT_STRESS -> "Heat stress"
    PlotEventKind.WATER_STRESS -> "Water stress"
    PlotEventKind.OBSERVATION -> "Observation"
    PlotEventKind.OTHER -> "Other"
}

/** POSSIBLE_HARVEST -> Possible harvest. */
private fun String.readable(): String =
    lowercase().replaceFirstChar { it.uppercase() }.replace("_", " ")

@Composable
private fun AlertsTab(overview: SatelliteOverview, viewModel: SatelliteViewModel) {
    val alerts = overview.alerts

    if (alerts.isEmpty()) {
        EmptyState(
            title = "No alerts",
            message = "Alerts appear when vegetation drops, weather stress, or stale observations are detected.",
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        AgriCard {
            Column {
                SectionHeader(title = "All Alerts (${alerts.size})")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                ) {
                    items(alerts) { alert ->
                        AlertCardFull(alert = alert, viewModel = viewModel)
                        if (alert != alerts.last()) Spacer(Modifier.height(AgriSpacing.sm))
                    }
                }
            }
        }
    }
}

/* =========================================================================
 * HELPER COMPOSABLES
 * ========================================================================= */

@Composable
private fun SummaryRow(label: String, summary: IndexSummary, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(Modifier.height(AgriSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
            summary.min?.let { StatTile(label = "Min", value = "%.2f".format(it), accent = color) }
            summary.max?.let { StatTile(label = "Max", value = "%.2f".format(it), accent = color) }
            summary.avg?.let { StatTile(label = "Avg", value = "%.2f".format(it), accent = color) }
            summary.latest?.let { StatTile(label = "Latest", value = "%.2f".format(it), accent = color) }
            summary.changeVsPrevious?.let { c ->
                StatTile(
                    label = "Change",
                    value = (if (c >= 0) "+" else "") + "%.2f".format(c),
                    accent = if (c >= 0) AgriColors.SuccessGreen else AgriColors.ErrorRed,
                    delta = c,
                )
            }
        }
        Spacer(Modifier.height(AgriSpacing.xs))
        Text(
            buildString {
                summary.trend?.let { append("Trend: $it") }
                summary.latestDate?.let {
                    if (isNotEmpty()) append(" - ")
                    append("Newest: ")
                    append(DateFormatters.formatDate(it))
                }
                summary.changeDate?.let {
                    if (isNotEmpty()) append(" - ")
                    append("change vs ")
                    append(DateFormatters.formatDate(it))
                }
            }.ifBlank { "Not enough usable observations to summarise" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DropCard(drop: VegetationDrop) {
    val classificationColor = when (drop.classification) {
        VegetationDropClassification.POSSIBLE_HEAT_OR_DRY_STRESS -> AgriColors.ErrorRed
        VegetationDropClassification.POSSIBLE_HARVEST -> AgriColors.WarningAmber
        VegetationDropClassification.GRADUAL_DECLINE -> AgriColors.InfoBlue
        else -> AgriColors.DarkBrown
    }

    AgriCard(containerColour = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DateFormatters.formatDate(drop.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusChip(
                    text = (drop.classification?.name ?: "NO_EXPLANATION").replace("_", " "),
                    color = classificationColor,
                )
                StatusChip(
                    text = drop.index.name,
                    color = if (drop.index == VegetationIndex.NDVI) AgriColors.SuccessGreen else AgriColors.SoilBrown,
                )
            }
            Text(
                drop.label
                    ?: "No possible explanation could be derived from the available observations for this date.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            drop.explanation?.let { explanation ->
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(AgriSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            ) {
                Text(
                    "Before: ${drop.valueBefore?.let { "%.2f".format(it) } ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "After: ${drop.valueAfter?.let { "%.2f".format(it) } ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Magnitude: ${drop.magnitude?.let { "%.2f".format(it) } ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = classificationColor,
                    fontWeight = FontWeight.Medium,
                )
                drop.confidence?.let { confidence ->
                    StatusChip(
                        text = confidence.name,
                        color = when (confidence) {
                            ConfidenceLevel.HIGH -> AgriColors.SuccessGreen
                            ConfidenceLevel.MEDIUM -> AgriColors.WarningAmber
                            else -> AgriColors.DarkBrown
                        },
                    )
                }
            }
            drop.previousDate?.let { previous ->
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    "Compared with ${DateFormatters.formatDate(previous)}" +
                        (drop.daysBetween?.let { " ($it days)" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!drop.nextStep.isNullOrBlank()) {
                Spacer(Modifier.height(AgriSpacing.xs))
                Text("Next: ${drop.nextStep}", style = MaterialTheme.typography.bodySmall, color = AgriColors.DarkBrown)
            }
            if (drop.evidence.isNotEmpty()) {
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    "Evidence: " + drop.evidence.joinToString(" * "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            drop.weatherContext?.let { ctx ->
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    if (ctx["available"] == true) {
                        "Weather context: " + listOfNotNull(
                            ctx["rainfallMm"]?.let { "$it mm rain" },
                            ctx["tempMaxC"]?.let { "max $it degC" },
                            ctx["tempMinC"]?.let { "min $it degC" },
                            ctx["soilMoisturePct"]?.let { "soil $it %" },
                        ).joinToString(", ") + listOfNotNull(
                            ctx["periodStart"]?.let { " ($it" },
                            ctx["periodEnd"]?.let { "$it)" },
                        ).joinToString("")
                    } else {
                        "Weather context unavailable: " +
                            (ctx["reason"]?.toString() ?: "no modelled weather covers that period.")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            drop.disclaimer?.let { disclaimer ->
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    disclaimer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlertCard(alert: PlotAlert) {
    val severityColor = when (alert.severity) {
        AlertSeverity.CRITICAL -> AgriColors.ErrorRed
        AlertSeverity.WARNING -> AgriColors.WarningAmber
        else -> AgriColors.InfoBlue
    }

    AgriCard(containerColour = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(alert.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                StatusChip(text = alert.severity.name, color = severityColor)
                StatusChip(text = alert.status.name, color = when (alert.status) {
                    AlertStatus.DISMISSED -> AgriColors.SuccessGreen
                    AlertStatus.ACKNOWLEDGED -> AgriColors.InfoBlue
                    else -> AgriColors.WarningAmber
                })
            }
            Text(alert.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            alert.raisedAt?.let { date ->
                Text("Raised: ${DateFormatters.formatDate(date)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AlertCardFull(alert: PlotAlert, viewModel: SatelliteViewModel) {
    val severityColor = when (alert.severity) {
        AlertSeverity.CRITICAL -> AgriColors.ErrorRed
        AlertSeverity.WARNING -> AgriColors.WarningAmber
        else -> AgriColors.InfoBlue
    }

    AgriCard(containerColour = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(alert.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                StatusChip(text = alert.severity.name, color = severityColor)
                StatusChip(text = alert.status.name, color = when (alert.status) {
                    AlertStatus.DISMISSED -> AgriColors.SuccessGreen
                    AlertStatus.ACKNOWLEDGED -> AgriColors.InfoBlue
                    else -> AgriColors.WarningAmber
                })
            }
            Text(alert.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            ) {
                alert.raisedAt?.let { Text("Raised: ${DateFormatters.formatDate(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                alert.acknowledgedAt?.let { Text("Acknowledged: ${DateFormatters.formatDate(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                alert.dismissedAt?.let { Text("Dismissed: ${DateFormatters.formatDate(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (alert.status == AlertStatus.OPEN) {
                Spacer(Modifier.height(AgriSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                ) {
                    AgriSecondaryButton(
                        text = "Acknowledge",
                        onClick = { viewModel.onEvent(SatelliteEvent.UpdateAlertStatus(alert.alertId, AlertStatus.ACKNOWLEDGED)) },
                        fillWidth = true,
                    )
                    AgriSecondaryButton(
                        text = "Dismiss",
                        onClick = { viewModel.onEvent(SatelliteEvent.UpdateAlertStatus(alert.alertId, AlertStatus.DISMISSED)) },
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ObservationRow(obs: PlotObservation) {
    AgriCard(
        containerColour = if (obs.isOutlier) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                DateFormatters.formatDate(obs.observedOn),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
            )
            obs.ndvi?.let {
                Text("NDVI: ${"%.2f".format(it)}", style = MaterialTheme.typography.bodyMedium, color = AgriColors.SuccessGreen, modifier = Modifier.weight(1f))
            }
            obs.evi?.let {
                Text("EVI: ${"%.2f".format(it)}", style = MaterialTheme.typography.bodyMedium, color = AgriColors.SoilBrown, modifier = Modifier.weight(1f))
            }
            obs.smoothedNdvi?.let {
                Text("Sm.N: ${"%.2f".format(it)}", style = MaterialTheme.typography.bodySmall, color = AgriColors.SuccessGreen.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
            }
            obs.validPixelPct?.let {
                Text("Valid: ${"%.0f".format(it)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
            }
            if (obs.isSimulated) {
                StatusChip(text = "MOCK", color = AgriColors.DarkBrown, modifier = Modifier.width(50.dp))
            }
            if (obs.isOutlier) {
                StatusChip(text = "OUTLIER", color = AgriColors.ErrorRed, modifier = Modifier.width(60.dp))
            }
        }
    }
}

@Composable
private fun WeatherRow(day: WeatherDaily) {
    AgriCard(containerColour = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                DateFormatters.formatDate(day.date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(80.dp),
            )
            day.rainfallMm?.let {
                Text("Rain: ${"%.1f".format(it)} mm", style = MaterialTheme.typography.bodySmall, color = AgriColors.InfoBlue, modifier = Modifier.weight(1f))
            }
            day.tempMinC?.let { min ->
                day.tempMaxC?.let { max ->
                    Text("T: ${min.format0()}-${max.format0()} degC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            day.soilMoisturePct?.let {
                Text("Soil: ${it.format0()}%", style = MaterialTheme.typography.bodySmall, color = AgriColors.SoilBrown, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GrowthPhaseRow(label: String, date: String?, confidence: ConfidenceLevel?) {
    val confidenceColor = when (confidence) {
        ConfidenceLevel.HIGH -> AgriColors.SuccessGreen
        ConfidenceLevel.MEDIUM -> AgriColors.WarningAmber
        else -> AgriColors.DarkBrown
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            date?.let { DateFormatters.formatDate(it) } ?: "-",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (confidence != null) {
            StatusChip(text = confidence.name, color = confidenceColor)
        }
    }
}

@Composable
private fun QualityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/* =========================================================================
 * EXTENSIONS
 * ========================================================================= */

private fun Double.format0(): String = String.format(java.util.Locale.US, "%.0f", this)

private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
