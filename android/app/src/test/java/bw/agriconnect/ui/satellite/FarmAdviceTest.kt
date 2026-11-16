package bw.agriconnect.ui.satellite

import bw.agriconnect.domain.model.satellite.AlertSeverity
import bw.agriconnect.domain.model.satellite.AlertStatus
import bw.agriconnect.domain.model.satellite.ConfidenceLevel
import bw.agriconnect.domain.model.satellite.GrowthCycle
import bw.agriconnect.domain.model.satellite.NdviMonitoringSummary
import bw.agriconnect.domain.model.satellite.PlotAlert
import bw.agriconnect.domain.model.satellite.WeatherData
import bw.agriconnect.domain.model.satellite.WeatherDataType
import bw.agriconnect.domain.model.satellite.WeatherSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The merged farm screen shows an actionable card with every analysis. These tests pin the
 * rule that matters most: the wording must tell a farmer what to *do*, must stay tied to the
 * reading it explains, and must never claim to know the cause of a change.
 */
class FarmAdviceTest {

    private fun summary(trend: String?, change: Double?) = NdviMonitoringSummary(
        available = true,
        count = 8,
        min = 0.12,
        max = 0.66,
        avg = 0.4,
        latest = 0.3,
        latestDate = "2026-11-10",
        changeVsPrevious = change,
        changeDate = "2026-11-03",
        trend = trend,
        dataAgeDays = 3,
    )

    private fun weather(rain7: Double?, moisture: Double?) = WeatherData(
        available = true,
        reason = null,
        source = "Open-Meteo",
        dataType = WeatherDataType.MODELLED,
        daily = emptyList(),
        summary = WeatherSummary(
            periodStart = "2026-11-01",
            periodEnd = "2026-11-10",
            rainfall7dMm = rain7,
            rainfall14dMm = rain7?.let { it * 2 },
            tempMax7dC = 31.0,
            tempMin7dC = 18.0,
            avgTemp7dC = 24.0,
            soilMoistureMeanPct = moisture,
        ),
        note = "Modelled estimate",
        fetchedAt = "2026-11-10T06:00:00Z",
        cached = false,
    )

    private fun growth(available: Boolean, peak: String?) = GrowthCycle(
        available = available,
        reason = if (available) null else "Not enough valid observations",
        greenUp = if (available) "2026-09-10" else null,
        peak = peak,
        endOfSeason = if (available && peak != null) "2026-12-01" else null,
        peakValue = null,
        baseline = null,
        amplitude = null,
        confidence = if (available) ConfidenceLevel.MEDIUM else null,
        strategy = "smoothed series",
        evidence = emptyList(),
    )

    private fun alert(title: String) = PlotAlert(
        alertId = "a1",
        plotId = "p1",
        farmerId = "f1",
        alertKey = "k",
        alertType = "NDVI_DROP",
        severity = AlertSeverity.WARNING,
        title = title,
        body = "Vegetation dropped in the north block",
        status = AlertStatus.OPEN,
        raisedAt = null,
        resolvedAt = null,
        acknowledgedAt = null,
        dismissedAt = null,
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `declining trend gives a field check, not a diagnosis`() {
        val advice = trendAdvice(summary("DECLINING", -0.14))
        assertThat(advice.reading).contains("falling")
        assertThat(advice.reading).contains("-0.1")
        assertThat(advice.actions).isNotEmpty()
        assertThat(advice.actions.joinToString(" ")).contains("soil moisture")
        assertThat(advice.caution!!).contains("not")
        assertNoDiagnosis(advice)
    }

    @Test
    fun `rising trend keeps routine monitoring`() {
        val advice = trendAdvice(summary("RISING", 0.09))
        assertThat(advice.reading).contains("increasing")
        assertThat(advice.actions.joinToString(" ")).contains("routine")
        assertNoDiagnosis(advice)
    }

    @Test
    fun `missing trend does not invent a direction`() {
        val advice = trendAdvice(summary("INSUFFICIENT", null))
        assertThat(advice.reading).contains("not yet enough")
        assertThat(advice.actions.joinToString(" ")).contains("Wait")
    }

    @Test
    fun `dry week prompts a soil moisture check`() {
        val advice = weatherAdvice(weather(4.0, 14.0))
        assertThat(advice.reading).contains("4.0 mm")
        assertThat(advice.actions.joinToString(" ")).contains("soil moisture by hand")
        assertThat(advice.actions.joinToString(" ")).contains("Top-soil moisture is low")
        assertThat(advice.caution!!).contains("modelled")
        assertNoDiagnosis(advice)
    }

    @Test
    fun `heavy rain prompts a drainage check`() {
        val advice = weatherAdvice(weather(80.0, 40.0))
        assertThat(advice.actions.joinToString(" ")).contains("pooling")
    }

    @Test
    fun `growth advice never invents stage dates`() {
        val advice = growthAdvice(growth(available = false, peak = null))
        assertThat(advice.reading).contains("Not enough valid observations")
        assertThat(advice.actions.joinToString(" ")).contains("field notes")
        assertThat(advice.reading).doesNotContain("2026-")
    }

    @Test
    fun `growth advice uses the estimated end of season`() {
        val advice = growthAdvice(growth(available = true, peak = "2026-10-20"))
        assertThat(advice.reading).contains("2026-10-20")
        assertThat(advice.actions.joinToString(" ")).contains("harvesting")
        assertThat(advice.caution!!).contains("estimate")
    }

    @Test
    fun `alerts advice quotes the alert and asks for confirmation`() {
        val advice = alertsAdvice(listOf(alert("North block drop")))
        assertThat(advice.reading).contains("North block drop")
        assertThat(advice.actions.joinToString(" ")).contains("Vegetation dropped")
        assertThat(advice.actions.joinToString(" ")).contains("Acknowledge")
        assertNoDiagnosis(advice)
    }

    @Test
    fun `no alerts still gives an action and does not claim all is well`() {
        val advice = alertsAdvice(emptyList())
        assertThat(advice.reading).contains("not the same as healthy")
        assertThat(advice.actions.joinToString(" ")).contains("Visit the plot")
    }

    private fun assertNoDiagnosis(advice: AnalysisAdvice) {
        val text = (listOf(advice.reading, advice.caution.orEmpty()) + advice.actions).joinToString(" ").lowercase()
        for (word in listOf("pest", "disease", "fungus", "deficien", "blight")) {
            assertThat(text).doesNotContain(word)
        }
    }
}
