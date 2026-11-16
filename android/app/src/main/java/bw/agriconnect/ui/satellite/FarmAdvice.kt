package bw.agriconnect.ui.satellite

import bw.agriconnect.core.util.NdviUtil
import bw.agriconnect.domain.model.satellite.AlertStatus
import bw.agriconnect.domain.model.satellite.GrowthCycle
import bw.agriconnect.domain.model.satellite.IndexSummary
import bw.agriconnect.domain.model.satellite.PlotAlert
import bw.agriconnect.domain.model.satellite.SatelliteOverview
import bw.agriconnect.domain.model.satellite.WeatherData
import java.util.Locale

/**
 * Plain-language, actionable reading of each satellite analysis.
 *
 * These builders only rearrange what the server already reported (or the shared,
 * non-diagnostic recommendation list). Nothing here names a pest, disease or nutrient
 * deficiency: a vegetation index cannot tell those apart. Every action is something a
 * farmer can do with their own eyes and hands.
 */
internal data class AnalysisAdvice(
    val title: String,
    val reading: String,
    val actions: List<String>,
    val caution: String? = null,
)

private fun Double.oneDecimal(): String = String.format(Locale.getDefault(), "%.1f", this)

internal fun vegetationAdvice(overview: SatelliteOverview): AnalysisAdvice {
    val latest = overview.observations.maxByOrNull { it.observedOn }
    val ndvi = latest?.ndvi
    val reading = when {
        ndvi == null -> "There is no usable vegetation reading for this plot yet."
        ndvi < NdviUtil.HIGH_STRESS_MAX ->
            "The newest NDVI reading is ${ndvi.oneDecimal()} - in the high-stress band. Cover is much lower than a healthy crop."
        ndvi < NdviUtil.MODERATE_STRESS_MAX ->
            "The newest NDVI reading is ${ndvi.oneDecimal()} - in the moderate-stress band. Some parts of the plot need a closer look."
        else -> "The newest NDVI reading is ${ndvi.oneDecimal()} - in the healthy band. Average cover is good."
    }
    return AnalysisAdvice(
        title = "Vegetation health - what to do",
        reading = reading,
        actions = buildRecommendations(overview),
        caution = "A vegetation index cannot say what is causing a change. Check the crop yourself before deciding on any input.",
    )
}

internal fun trendAdvice(summary: IndexSummary?): AnalysisAdvice {
    val trend = summary?.trend?.trim()?.uppercase(Locale.ROOT)
    val change = summary?.changeVsPrevious
    val changeText = change?.let {
        val sign = if (it > 0) "+" else ""
        " Change since the previous pass: ${sign}${it.oneDecimal()}."
    }.orEmpty()

    val reading = when (trend) {
        "DECLINING" -> "Cover is falling between satellite passes.$changeText"
        "RISING" -> "Cover is increasing between satellite passes.$changeText"
        "STABLE" -> "Cover is holding steady between satellite passes.$changeText"
        else -> "There are not yet enough comparable passes to judge a direction.$changeText"
    }

    val actions = when (trend) {
        "DECLINING" -> listOf(
            "Walk the weakest part of the plot and compare it with the strongest part.",
            "Check soil moisture a spade deep before deciding on water or inputs.",
            "Record what you find as a field event so the next pass can be compared.",
        )
        "RISING" -> listOf(
            "Keep routine field monitoring - growth alone does not rule out local problems.",
            "Spot-check the edges and shaded areas where the average can hide a weak patch.",
        )
        "STABLE" -> listOf(
            "Compare the field with your last note and keep routine monitoring.",
            "Check the low and high areas of the plot if the range across it is wide.",
        )
        else -> listOf(
            "Wait for the next clear satellite pass before drawing a conclusion.",
            "Keep recording field events so a trend can be judged later.",
        )
    }

    return AnalysisAdvice(
        title = "Trend - what to do",
        reading = reading,
        actions = actions,
        caution = "A trend describes change in cover, not its cause. A cloudy or missed pass can also move the line.",
    )
}

internal fun weatherAdvice(weather: WeatherData?): AnalysisAdvice {
    val summary = weather?.summary
    val reading = buildString {
        summary?.rainfall7dMm?.let { append("Rain in the last 7 days: ${it.oneDecimal()} mm. ") }
        summary?.rainfall14dMm?.let { append("14-day total: ${it.oneDecimal()} mm. ") }
        summary?.soilMoistureMeanPct?.let { append("Mean top-soil moisture: ${it.oneDecimal()}%. ") }
        if (isEmpty()) append("Weather totals are not available for this plot yet.")
    }.trim()

    val rain7 = summary?.rainfall7dMm
    val moisture = summary?.soilMoistureMeanPct
    val actions = mutableListOf<String>()
    when {
        rain7 != null && rain7 < 10 ->
            actions += "The last week was dry: check soil moisture by hand in the weakest strip before deciding to water."
        rain7 != null && rain7 > 60 ->
            actions += "There was heavy rain: check for pooling, erosion and damaged plants on the lowest ground."
        else -> actions += "Check soil moisture by hand once a week and compare it with what the crop looks like."
    }
    if (moisture != null && moisture < 20) {
        actions += "Top-soil moisture is low - prioritise the weakest area when you irrigate."
    }
    actions += "Record any irrigation, drainage or stress you observe in the field event log."

    return AnalysisAdvice(
        title = "Weather - what to do",
        reading = reading,
        actions = actions,
        caution = "This weather is modelled from Open-Meteo for the plot centroid, not measured in your field. Use it as a prompt to check.",
    )
}

internal fun growthAdvice(cycle: GrowthCycle?): AnalysisAdvice {
    if (cycle == null || !cycle.available) {
        return AnalysisAdvice(
            title = "Growth stage - what to do",
            reading = cycle?.reason ?: "There is not enough evidence yet to estimate the growth stages.",
            actions = listOf("Keep taking field notes; the estimate appears when the series is long enough."),
        )
    }

    val reading = buildString {
        cycle.greenUp?.let { append("Green-up around $it. ") }
        cycle.peak?.let { append("Peak vegetation around $it. ") }
        cycle.endOfSeason?.let { append("End of season around $it. ") }
        cycle.confidence?.let { append("Confidence: ${it.name.lowercase(Locale.ROOT)}.") }
        if (isEmpty()) append("The growth stages are still being estimated.")
    }.trim()

    val actions = mutableListOf<String>()
    if (cycle.peak == null) {
        actions += "The peak has not been detected yet - keep field notes so it can be placed accurately."
    } else if (cycle.endOfSeason == null) {
        actions += "The end of season is not estimated yet - keep monitoring and record harvest dates."
    } else {
        actions += "Use the estimated end of season to plan harvesting, then confirm the date from the field."
    }
    actions += "Check the crop at the estimated stage and confirm what you see as a field event."

    return AnalysisAdvice(
        title = "Growth stage - what to do",
        reading = reading,
        actions = actions,
        caution = "Every stage date is an estimate between satellite passes, not an observed date.",
    )
}

internal fun alertsAdvice(alerts: List<PlotAlert>): AnalysisAdvice {
    val open = alerts.filter { it.status == AlertStatus.OPEN }
    val reading = if (open.isEmpty()) {
        "No open alerts for this plot. Plots with no reading are still worth a visit - silence is not the same as healthy."
    } else {
        "${open.size} open alert(s): " + open.joinToString("; ") { it.title }
    }
    val actions = if (open.isEmpty()) {
        listOf("Visit the plot on your normal round and record anything unusual.")
    } else {
        open.take(3).map { "${it.title}: ${it.body}" } +
            "Acknowledge an alert only after you have checked the plot; dismiss it when you have."
    }
    return AnalysisAdvice(
        title = "Alerts - what to do",
        reading = reading,
        actions = actions,
        caution = "An alert is a prompt to inspect, not a confirmed problem.",
    )
}

/** All analyses in one ordered list, used by the farm summary card. */
internal fun buildAnalysisAdvice(overview: SatelliteOverview): List<AnalysisAdvice> = listOf(
    vegetationAdvice(overview),
    trendAdvice(overview.summary?.ndvi),
    weatherAdvice(overview.weather),
    growthAdvice(overview.growthCycle),
    alertsAdvice(overview.alerts),
)
