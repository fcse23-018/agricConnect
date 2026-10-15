package bw.agriconnect.ui.satellite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.ErrorState
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.LoadingState
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
    plotId: String,
    onBack: () -> Unit,
    viewModel: SatelliteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errorMessage = state.error

    LaunchedEffect(plotId) { viewModel.load(plotId) }

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
                        OfflineEmptyState(onRetry = { viewModel.load(plotId) })
                    } else {
                        ErrorState(
                            message = errorMessage.orEmpty(),
                            onRetry = { viewModel.load(plotId) },
                        )
                    }
                }

                state.ndvi == null -> OfflineEmptyState(
                    message = "No NDVI has been generated for this plot yet. Once the backend processes a satellite pass it will appear here.",
                    onRetry = { viewModel.load(plotId) },
                )

                else -> {
                    val ndvi = state.ndvi ?: return@Column

                    if (state.error != null) {
                        ErrorBanner(message = errorMessage.orEmpty(), onRetry = { viewModel.load(plotId) })
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

                    Text(
                        text = "Source: ${ndvi.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ndvi.cloudCover?.let {
                        // The backend field's scale is not documented, so show it verbatim
                        // rather than inventing a percentage interpretation.
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
                    ndvi.failureReason?.let {
                        ErrorBanner(message = it)
                    }
                }
            }
        }
    }
}
