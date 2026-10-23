package bw.agriconnect.ui.satellite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.core.util.NdviUtil
import bw.agriconnect.domain.model.satellite.SatelliteOverview
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.NdviAdviceCard
import bw.agriconnect.ui.components.NdviGauge
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.components.StatTile
import bw.agriconnect.ui.components.StatusChip
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * The detailed agricultural insights report for one plot.
 *
 * This is the "open the full report" destination from Satellite Insights. It is
 * deliberately the *same* data the screen already fetched (the overview), re-presented as
 * one scrollable document, rather than a second set of endpoints that could disagree with
 * the screen it was opened from.
 *
 * The report follows the eight required sections. Two rules it must never break:
 *
 *  1. **No diagnosis.** Vegetation-index changes are described as *possible* causes, in
 *     the words the analysis engine supplied, and a drop's own disclaimer travels with it.
 *     Nothing here names a pest, a disease or a nutrient deficiency.
 *  2. **No invented values.** Every section that lacks data says so and why (cloud cover,
 *     no boundary, no reading yet) instead of filling the gap with an estimate.
 */
@Composable
fun SatelliteInsightsScreen(
    plotId: String? = null,
    onBack: (() -> Unit)? = null,
    viewModel: SatelliteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reuse the same one-shot start the satellite screen uses, so opening the report
    // directly (deep link) fetches exactly what the screen would have. Keyed on the
    // requested plot, so navigating to another plot's report reloads rather than showing
    // the previous plot's numbers.
    LaunchedEffect(plotId) { viewModel.start(plotId) }

    MainScaffold(
        title = "Field insights",
        subtitle = state.overview?.plot?.plotName?.takeIf { it.isNotBlank() },
        onBack = onBack,
    ) { padding ->
        val overview = state.overview
        when {
            overview == null && state.isLoading -> LoadingState(message = "Building the report...")
            overview == null -> Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(AgriSpacing.md),
            ) {
                ErrorBanner(
                    message = state.error
                        ?: "No satellite report is available for this plot yet. Set a boundary and wait for a clear pass.",
                )
            }
            else -> InsightsReport(
                overview = overview,
                fromCache = state.overviewFromCache,
                advice = state.ndvi?.advice,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** The report body. Sections that have no data say so rather than being hidden. */
@Composable
private fun InsightsReport(
    overview: SatelliteOverview,
    fromCache: Boolean,
    advice: bw.agriconnect.domain.model.NdviAdvice?,
    modifier: Modifier = Modifier,
) {
    val latest = overview.observations.maxByOrNull { it.observedOn }
    val summary = overview.summary.ndvi

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(AgriSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.lg),
    ) {
        // 1. Plot overview
        item {
            AgriCard {
                Column {
                    SectionHeader(
                        title = "Plot overview",
                        subtitle = overview.plot.plotName.ifBlank { "Unnamed plot" },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                    ) {
                        overview.geometry.areaHectares?.let {
                            StatTile(label = "Area", value = "%.2f ha".format(it), accent = AgriColors.ForestGreen)
                        }
                        overview.geometry.perimeterM?.let {
                            StatTile(label = "Perimeter", value = "%.0f m".format(it), accent = AgriColors.SoilBrown)
                        }
                        StatTile(
                            label = "Boundary",
                            value = if (overview.geometry.valid) "Set" else "Missing",
                            accent = if (overview.geometry.valid) AgriColors.SuccessGreen else AgriColors.WarningAmber,
                        )
                    }
                    if (!overview.geometry.valid) {
                        Spacer(Modifier.height(AgriSpacing.sm))
                        ErrorBanner(
                            message = overview.geometry.reason
                                ?: "This plot has no measurable boundary, so no satellite reading can be attributed to it.",
                        )
                    }
                }
            }
        }

        // Data-freshness banners, before any number is read.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                if (fromCache) {
                    InfoBanner(message = "This report is showing the last data saved on this device. It is not a live fetch.")
                }
                overview.observationStatus.isSimulated.takeIf { it }?.let {
                    InfoBanner(message = "The reading in this report is demonstration data (MOCK), not a real satellite observation.")
                }
                overview.observationStatus.newestObservation?.let { newest ->
                    val age = overview.observationStatus.newestObservationAgeDays
                    InfoBanner(
                        message = "Newest clear image: " + DateFormatters.formatDate(newest) +
                            (age?.let { " ($it days old)" } ?: ""),
                    )
                }
            }
        }

        // 2. Current vegetation-health result
        item {
            AgriCard {
                Column {
                    SectionHeader(
                        title = "Current vegetation health",
                        subtitle = "Measured over your boundary on the observation date",
                    )
                    if (latest == null || latest.ndvi == null) {
                        InfoBanner(
                            message = "There is no usable vegetation reading for this plot yet. " +
                                "A reading needs a clear satellite pass over a saved boundary; " +
                                "cloud cover can delay that for weeks. Nothing is estimated in the meantime.",
                        )
                    } else {
                        NdviGauge(
                            meanNdvi = latest.ndvi,
                            statusText = "Observed ${DateFormatters.formatDate(latest.observedOn)}" +
                                " - ${if (latest.isSimulated) "MOCK" else latest.source ?: "Sentinel-2"}",
                            stressLevel = NdviUtil.classify(latest.ndvi),
                            cached = overview.observationStatus.isCached,
                        )
                        Spacer(Modifier.height(AgriSpacing.sm))
                        Text(
                            NdviUtil.label(NdviUtil.classify(latest.ndvi)) +
                                ". This is an indicative signal from reflected light, not a diagnosis of " +
                                "any particular problem in the field.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 2b. The server's own agronomic advisory block, when one was generated. This is
        // the guidance the backend builds from the same reading, so it is shown verbatim
        // rather than re-derived on the device.
        if (advice != null) {
            item {
                NdviAdviceCard(advice = advice)
            }
        }

        // 3. Summary statistics and spatial variation
        item {
            AgriCard {
                Column {
                    SectionHeader(
                        title = "How the plot varied",
                        subtitle = if (latest?.ndvi != null) {
                            "Lowest and highest single-pixel values inside the boundary"
                        } else {
                            "Not available"
                        },
                    )
                    if (summary?.available != true) {
                        Text(
                            "There are not enough usable readings to describe variation across the plot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                        ) {
                            summary.min?.let { StatTile(label = "Lowest", value = "%.2f".format(it), accent = AgriColors.ErrorRed) }
                            summary.avg?.let { StatTile(label = "Average", value = "%.2f".format(it), accent = AgriColors.ForestGreen) }
                            summary.max?.let { StatTile(label = "Highest", value = "%.2f".format(it), accent = AgriColors.SuccessGreen) }
                        }
                        Spacer(Modifier.height(AgriSpacing.sm))
                        Text(
                            "A gap between the lowest and highest value means parts of the plot are " +
                                "performing differently. It does not say why - walk the low area and look.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 4. Comparison with earlier observations
        item {
            AgriCard {
                Column {
                    SectionHeader(title = "Compared with earlier readings")
                    if (summary?.available != true || summary.changeVsPrevious == null) {
                        Text(
                            "There is not yet a second usable reading to compare against, so no " +
                                "change is reported. A trend is only computed from comparable observations " +
                                "of the same plot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Bound once: `changeVsPrevious` is an interface property, so it
                        // cannot be smart-cast after a null check.
                        val change = summary.changeVsPrevious
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                        ) {
                            StatTile(
                                label = "Change",
                                value = change?.let { "%+.2f".format(it) } ?: "—",
                                accent = if (change != null && change < 0) AgriColors.ErrorRed else AgriColors.SuccessGreen,
                            )
                            StatTile(
                                label = "Direction",
                                value = summary.trend ?: "INSUFFICIENT",
                                accent = AgriColors.InfoBlue,
                            )
                        }
                        summary.changeDate?.let {
                            Spacer(Modifier.height(AgriSpacing.xs))
                            Text(
                                "Compared with the reading of ${DateFormatters.formatDate(it)}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 5. Weather, if a provider is integrated
        item {
            val weather = overview.weather
            AgriCard {
                Column {
                    SectionHeader(
                        title = "Weather at the plot",
                        subtitle = weather?.source?.let { "$it (${weather.dataType.name})" } ?: "Not available",
                    )
                    if (weather == null || !weather.available) {
                        Text(
                            weather?.reason
                                ?: "No reliable weather data is available for this plot yet. " +
                                "It is reported as missing rather than filled with a guess.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        weather.summary?.let { s ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md),
                            ) {
                                s.rainfall7dMm?.let { StatTile(label = "Rain 7d", value = "%.1f mm".format(it), accent = AgriColors.InfoBlue) }
                                s.tempMax7dC?.let { StatTile(label = "Max 7d", value = "%.0f degC".format(it), accent = AgriColors.ErrorRed) }
                                s.tempMin7dC?.let { StatTile(label = "Min 7d", value = "%.0f degC".format(it), accent = AgriColors.InfoBlue) }
                            }
                        }
                        Spacer(Modifier.height(AgriSpacing.xs))
                        Text(
                            "Weather is a modelled estimate for the plot location, not a reading from a " +
                                "sensor in your field.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 6. Possible explanations for changes - never a diagnosis
        item {
            AgriCard {
                Column {
                    SectionHeader(
                        title = "Possible explanations for changes",
                        subtitle = "Possibilities only - none of these is confirmed",
                    )
                    if (overview.drops.isEmpty()) {
                        Text(
                            "No vegetation drop has been detected in the stored series, so there is " +
                                "nothing to explain. This is not a statement that the crop is healthy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        overview.drops.forEach { drop ->
                            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                                    StatusChip(
                                        text = DateFormatters.formatDate(drop.date),
                                        color = AgriColors.InfoBlue,
                                    )
                                    drop.label?.let { StatusChip(text = it, color = AgriColors.WarningAmber) }
                                }
                                drop.explanation?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (drop.evidence.isNotEmpty()) {
                                    Text(
                                        "Evidence: " + drop.evidence.joinToString(" * "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                drop.disclaimer?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (drop != overview.drops.last()) Spacer(Modifier.height(AgriSpacing.md))
                        }
                    }
                }
            }
        }

        // 7. Field-inspection recommendations
        item {
            AgriCard {
                Column {
                    SectionHeader(title = "What to check in the field")
                    val checks = buildRecommendations(overview)
                    checks.forEach { line ->
                        Text("* $line", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(AgriSpacing.xs))
                    }
                    Spacer(Modifier.height(AgriSpacing.sm))
                    Text(
                        "These are inspection suggestions weighted to what the index shows. " +
                            "They are not instructions and they are not a diagnosis - confirm in the field " +
                            "before spending money.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 8. Observation date, source and limitations
        item {
            AgriCard {
                Column {
                    SectionHeader(title = "About this reading")
                    latest?.let { obs ->
                        Text(
                            "Date: ${DateFormatters.formatDate(obs.observedOn)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Source: ${obs.source ?: "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        obs.validPixelPct?.let {
                            Text("Usable pixels in the plot: %.0f%%".format(it), style = MaterialTheme.typography.bodySmall)
                        }
                        obs.cloudCoverPct?.let {
                            Text("Cloud cover: %.0f%%".format(it), style = MaterialTheme.typography.bodySmall)
                        }
                    } ?: Text(
                        "No observation has been recorded for this plot yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            overview.disclaimer?.let { disclaimer ->
                Text(
                    disclaimer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Inspection suggestions derived from what the index actually shows. Each one is an
 * action a farmer can take, not a conclusion: the list never names a pest, a disease or a
 * deficiency, because a vegetation index cannot distinguish them.
 */
internal fun buildRecommendations(overview: SatelliteOverview): List<String> {
    val out = mutableListOf<String>()
    val latest = overview.observations.maxByOrNull { it.observedOn }
    val ndvi = latest?.ndvi

    if (!overview.geometry.valid) {
        out += "Walk the plot boundary with GPS so a real outline is stored. Until then no reading can be trusted to this plot."
        return out
    }

    when {
        ndvi == null -> out += "Wait for a clear satellite pass; there is nothing to inspect from yet."
        ndvi < NdviUtil.HIGH_STRESS_MAX -> {
            out += "Inspect the plants at several points across the plot and note leaf colour, wilting and bare patches."
            out += "Check soil moisture by hand a spade deep in the weakest area before deciding on any input."
            out += "Compare what you see against your last field note to judge whether this is new."
        }
        ndvi < NdviUtil.MODERATE_STRESS_MAX -> {
            out += "Check whether the low areas follow a pattern such as a slope, a shade line or the edge of irrigation coverage."
            out += "Confirm soil moisture in the weakest strip and check the crop for uneven emergence."
        }
        else -> {
            out += "Keep routine field monitoring; the average cover looks healthy but spot-check for localised problems."
            out += "Compare the low and high areas of the plot if the variation across it is wide."
        }
    }

    if (overview.drops.isNotEmpty()) {
        out += "Look again at the area flagged in the drop list and record what you find in the event log."
    }
    out += "Record anything you find as a field event so it can be compared with the next satellite pass."
    return out
}
