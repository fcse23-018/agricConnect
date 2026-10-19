package bw.agriconnect.ui.satellite

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.domain.model.NdviProcessingStatus
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriFilterChip
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.ErrorState
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.NdviAdviceCard
import bw.agriconnect.ui.components.NdviGauge
import bw.agriconnect.ui.components.OfflineEmptyState
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Satellite (NDVI) insight for one plot.
 *
 * The value is never fabricated: the screen renders exactly what the backend generated,
 * says so when the data came from the cache (offline), and shows an honest empty state
 * when no NDVI has been computed for the plot yet.
 */
@Composable
fun SatelliteScreen(
    plotId: String? = null,
    onBack: (() -> Unit)? = null,
    onOpenPlot: ((String) -> Unit)? = null,
    onAddPlot: (() -> Unit)? = null,
    viewModel: SatelliteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canGenerate by viewModel.canGenerate.collectAsStateWithLifecycle()
    val plots by viewModel.plots.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPlotId.collectAsStateWithLifecycle()
    val errorMessage = state.error

    // `start` is a one-shot: it selects the preferred (or first) plot and loads it. It must
    // NOT depend on `plots`, or every cache emission would reset the user's chip choice.
    LaunchedEffect(plotId) { viewModel.start(plotId) }

    MainScaffold(
        title = "Satellite insights",
        subtitle = state.ndvi?.plotName?.takeIf { it.isNotBlank() },
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            when {
                state.isLoading && state.ndvi == null ->
                    LoadingState(message = "Loading satellite data…")

                state.error != null && state.ndvi == null -> {
                    if (state.offline) {
                        OfflineEmptyState(onRetry = { selected?.let { viewModel.load(it) } })
                    } else {
                        ErrorState(
                            message = errorMessage.orEmpty(),
                            onRetry = { selected?.let { viewModel.load(it) } },
                        )
                    }
                }

                state.ndvi == null -> {
                    val currentId = selected ?: plotId ?: plots.firstOrNull()?.plotId
                    when {
                        plots.isEmpty() -> EmptyState(
                            title = "No plots yet",
                            message = "Add a plot with a boundary to monitor its vegetation health.",
                            actionLabel = "Add a plot",
                            onAction = { onAddPlot?.invoke() },
                        )
                        canGenerate -> EmptyState(
                            title = "No satellite data yet",
                            message = "Generate a fresh NDVI result for this plot. If the plot has no saved boundary, you'll need to add one first.",
                            actionLabel = if (state.isGenerating) "Generating…" else "Generate satellite data",
                            onAction = { if (!state.isGenerating) currentId?.let { viewModel.generate(it) } },
                        )
                        else -> EmptyState(
                            title = "No satellite data yet",
                            message = "Only the farm owner can generate satellite data. The last computed result will appear here if available.",
                        )
                    }
                }

                else -> {
                    val ndvi = state.ndvi ?: return@Column
                    val currentId = selected ?: plotId ?: ndvi.plotId

                    if (plots.size > 1) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
                        ) {
                            plots.forEach { p ->
                                AgriFilterChip(
                                    label = p.plotName.ifBlank { p.plotId },
                                    selected = (currentId == p.plotId),
                                    onClick = { viewModel.selectPlot(p.plotId) },
                                )
                            }
                        }
                    }

                    if (state.error != null) {
                        ErrorBanner(message = errorMessage.orEmpty(), onRetry = { currentId?.let { viewModel.load(it) } })
                    }
                    if (state.infoMessage != null) {
                        InfoBanner(message = state.infoMessage.orEmpty())
                    }
                    if (ndvi.isCached) {
                        InfoBanner(
                            message = "Showing the last saved reading. Reconnect to fetch the latest satellite pass.",
                        )
                    }

                    NdviGauge(
                        meanNdvi = ndvi.meanNdviValue,
                        statusText = buildString {
                            append("Acquisition: ")
                            append(ndvi.satelliteDate?.let { DateFormatters.formatDate(it) } ?: "unknown")
                            append(" • status ")
                            append(ndvi.status.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                        cached = ndvi.isCached,
                    )

                    Spacer(Modifier.height(AgriSpacing.sm))

                    if (ndvi.advice == null) {
                        // Without server guidance, show the raw reading fields verbatim. The
                        // backend field's scale is not documented, so cloud cover is printed as
                        // delivered rather than being reinterpreted as a percentage.
                        Text(
                            text = "Source: ${ndvi.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ndvi.cloudCover?.let {
                            Text(
                                text = "Cloud cover reading: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ndvi.stressLevel?.let {
                            Text(
                                text = "Stress: ${it.name.replace("_", " ").lowercase().replaceFirstChar { c -> c.uppercase() }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    ndvi.failureReason?.let {
                        ErrorBanner(message = it)
                    }

                    // The guidance for this reading: what it means, what to do about it, how
                    // far to trust it, and what it cannot say. It travels with the reading, so
                    // it appears the moment a result does — and afterwards, on the next fetch.
                    ndvi.advice?.let { advice ->
                        Spacer(Modifier.height(AgriSpacing.md))
                        NdviAdviceCard(advice)
                    }
                    if (ndvi.advice == null && ndvi.status == NdviProcessingStatus.GENERATED) {
                        Spacer(Modifier.height(AgriSpacing.sm))
                        InfoBanner(
                            message = if (ndvi.isCached) {
                                "Field guidance will appear with the next connection."
                            } else {
                                "Field guidance is not available for this reading."
                            },
                        )
                    }

                    if (canGenerate) {
                        Spacer(Modifier.height(AgriSpacing.md))
                        AgriPrimaryButton(
                            text = if (state.isGenerating) "Generating…" else "Generate satellite data",
                            onClick = { if (!state.isGenerating) (currentId)?.let { viewModel.generate(it) } },
                            enabled = !state.offline && !state.isGenerating,
                            loading = state.isGenerating,
                            fillWidth = true,
                        )
                    }
                }
            }
        }
    }
}
