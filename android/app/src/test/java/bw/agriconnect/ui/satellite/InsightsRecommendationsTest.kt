package bw.agriconnect.ui.satellite

import bw.agriconnect.core.util.GeoVertex
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.satellite.GeometryMethod
import bw.agriconnect.domain.model.satellite.MonitoringSummary
import bw.agriconnect.domain.model.satellite.NdviMonitoringSummary
import bw.agriconnect.domain.model.satellite.ObservationQuality
import bw.agriconnect.domain.model.satellite.ObservationStatus
import bw.agriconnect.domain.model.satellite.PlotGeometry
import bw.agriconnect.domain.model.satellite.PlotObservation
import bw.agriconnect.domain.model.satellite.SatelliteOverview
import bw.agriconnect.domain.model.satellite.VegetationDrop
import bw.agriconnect.domain.model.satellite.VegetationDropClassification
import bw.agriconnect.domain.model.satellite.VegetationIndex
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The report's inspection advice is the one part of the insights screen that is generated on
 * the device rather than sent by the server, so it is the part most able to say something the
 * data does not support. These tests pin the two rules it must never break:
 *
 *  1. It never names a pest, a disease, a nutrient deficiency or drought — a vegetation index
 *     cannot distinguish them. It only ever suggests going and looking.
 *  2. A plot with no trustworthy geometry or no reading gets advice about *that*, rather than a
 *     crop-care suggestion built on a number that does not exist.
 */
class InsightsRecommendationsTest {

    @Test
    fun `advice is about surveying the boundary when the geometry is not usable`() {
        val advice = buildRecommendations(overview(geometryValid = false, ndvi = 0.6))

        assertThat(advice).hasSize(1)
        assertThat(advice.first()).contains("boundary")
        // It must not slide into crop advice for a plot it cannot measure.
        assertThat(advice.first()).doesNotContain("soil moisture")
    }

    @Test
    fun `advice sends the farmer to look when the reading is very low`() {
        val advice = buildRecommendations(overview(ndvi = 0.10))

        assertThat(advice.any { it.contains("Inspect the plants") }).isTrue()
        assertThat(advice.any { it.contains("soil moisture") }).isTrue()
    }

    @Test
    fun `advice is moderate for a mid-range reading`() {
        val advice = buildRecommendations(overview(ndvi = 0.30))

        assertThat(advice.any { it.contains("low areas") || it.contains("uneven emergence") }).isTrue()
        // A mid-band reading must not be escalated into the "very low" checklist.
        assertThat(advice.any { it.contains("Inspect the plants") }).isFalse()
    }

    @Test
    fun `advice is routine for a healthy reading`() {
        val advice = buildRecommendations(overview(ndvi = 0.70))

        assertThat(advice.any { it.contains("routine field monitoring") }).isTrue()
    }

    @Test
    fun `advice waits rather than guessing when there is no reading`() {
        val advice = buildRecommendations(overview(ndvi = null))

        assertThat(advice.any { it.contains("Wait for a clear satellite pass") }).isTrue()
        assertThat(advice.any { it.contains("soil moisture") }).isFalse()
    }

    @Test
    fun `advice points at the flagged drop area when one was detected`() {
        val advice = buildRecommendations(
            overview(ndvi = 0.55, drops = listOf(sampleDrop())),
        )

        assertThat(advice.any { it.contains("drop list") }).isTrue()
    }

    @Test
    fun `advice never diagnoses a specific cause`() {
        // Whatever the reading, the wording must stay in "go and look" territory.
        val forbidden = listOf(
            "pest", "disease", "fungus", "deficiency", "nitrogen", "phosphorus",
            "drought", "infestation", "blight", "virus",
        )
        for (ndvi in listOf(0.05, 0.30, 0.70, null)) {
            val text = buildRecommendations(overview(ndvi = ndvi)).joinToString(" ").lowercase()
            for (word in forbidden) {
                assertThat(text).doesNotContain(word)
            }
        }
    }

    @Test
    fun `every reading always ends with a request to record what was found`() {
        for (ndvi in listOf(0.05, 0.30, 0.70, null)) {
            val advice = buildRecommendations(overview(ndvi = ndvi))
            assertThat(advice.last()).contains("field event")
        }
    }

    // --- fixtures -----------------------------------------------------------

    private fun overview(
        ndvi: Double?,
        geometryValid: Boolean = true,
        drops: List<VegetationDrop> = emptyList(),
    ): SatelliteOverview {
        val observations = if (ndvi == null) {
            emptyList()
        } else {
            listOf(
                PlotObservation(
                    observationId = "obs-1",
                    plotId = "plot-1",
                    farmerId = "farmer-1",
                    observedOn = "2026-10-22",
                    ndvi = ndvi,
                    evi = null,
                    smoothedNdvi = null,
                    smoothedEvi = null,
                    validPixelPct = 92.0,
                    cloudCoverPct = 4.0,
                    pixelCount = 1200,
                    quality = ObservationQuality.GOOD,
                    isOutlier = false,
                    isSimulated = false,
                    source = "SENTINEL-2",
                ),
            )
        }
        return SatelliteOverview(
            plot = FarmPlot(
                plotId = "plot-1",
                farmerId = "farmer-1",
                plotName = "North Field",
                areaHectares = 2.0,
                geoJsonBoundary = null,
                createdAt = "2026-01-01T08:00:00Z",
                updatedAt = "2026-01-01T08:00:00Z",
            ),
            geometry = PlotGeometry(
                method = GeometryMethod.POLYGON,
                vertices = listOf(
                    GeoVertex(-24.6, 25.9),
                    GeoVertex(-24.6, 25.91),
                    GeoVertex(-24.61, 25.91),
                ),
                areaM2 = 20_000.0,
                areaHectares = 2.0,
                perimeterM = 600.0,
                centroid = GeoVertex(-24.6, 25.9),
                sideLengthsM = null,
                valid = geometryValid,
                reason = if (geometryValid) null else "Boundary has fewer than three corners.",
            ),
            observationStatus = ObservationStatus(
                isCached = false,
                isSimulated = false,
                newestObservation = "2026-10-22",
                newestObservationAgeDays = 0,
                provider = "SENTINEL-2",
                providerError = null,
                note = null,
            ),
            observations = observations,
            summary = MonitoringSummary(
                ndvi = NdviMonitoringSummary(
                    available = ndvi != null,
                    count = if (ndvi == null) 0 else 1,
                    min = ndvi,
                    max = ndvi,
                    avg = ndvi,
                    latest = ndvi,
                    latestDate = if (ndvi == null) null else "2026-10-22",
                    changeVsPrevious = null,
                    changeDate = null,
                    trend = null,
                    dataAgeDays = 0,
                ),
                evi = null,
            ),
            growthCycle = null,
            drops = drops,
            weather = null,
            alerts = emptyList(),
            quality = null,
            modelStatus = null,
            thresholds = emptyMap(),
            thresholdDocumentation = null,
        )
    }

    private fun sampleDrop() = VegetationDrop(
        date = "2026-10-22",
        previousDate = "2026-10-10",
        daysBetween = 12,
        index = VegetationIndex.NDVI,
        magnitude = -0.12,
        valueBefore = 0.60,
        valueAfter = 0.48,
        relativePct = -20.0,
        classification = VegetationDropClassification.UNCLEAR,
        label = "Vegetation drop",
        explanation = "Possible causes include harvest or grazing.",
        evidence = listOf("Drop of 0.12 between the two dates."),
        confidence = null,
        nextStep = null,
        disclaimer = "This is not a diagnosis.",
        weatherContext = null,
        recoveryFraction = null,
        recoveredOn = null,
        gradual = false,
        detectedEventId = null,
    )
}
