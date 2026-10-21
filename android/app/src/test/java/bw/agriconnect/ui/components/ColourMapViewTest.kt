package bw.agriconnect.ui.components

import androidx.compose.ui.graphics.Color
import bw.agriconnect.domain.model.satellite.ColourMap
import bw.agriconnect.domain.model.satellite.ColourMapLegendStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour map's presentation rules.
 *
 * These matter because the picture is the most persuasive element on the Satellite
 * screen: if a value were mapped to the wrong colour, or a value outside the scale
 * were silently painted as bare soil, the image would mislead in a way no caption
 * could undo.
 */
class ColourMapViewTest {

    private fun legend() = listOf(
        ColourMapLegendStop(-0.1, 0.2, "#8B2E2E", "Bare soil"),
        ColourMapLegendStop(0.2, 0.35, "#C4703A", "Sparse"),
        ColourMapLegendStop(0.35, 0.5, "#D9C15A", "Moderate"),
        ColourMapLegendStop(0.5, 0.65, "#7FA85A", "Healthy"),
        ColourMapLegendStop(0.65, 0.9, "#2E7D4F", "Dense"),
    )

    private fun map(legend: List<ColourMapLegendStop>) = ColourMap(
        available = true,
        reason = null,
        index = "ndvi",
        plotId = "p1",
        plotName = "North field",
        observedOn = "2026-05-10",
        meanValue = 0.42,
        isSimulated = false,
        variationSimulated = true,
        grid = emptyList(),
        size = 0,
        min = 0.3,
        max = 0.6,
        insideCells = 100,
        totalCells = 576,
        validPixelPct = 88.0,
        scaleMin = -0.1,
        scaleMax = 0.9,
        legend = legend,
        note = null,
        disclaimer = null,
        interpretation = null,
        ageDays = 2,
        isStale = false,
        staleDays = 14,
        latestQuality = "GOOD",
        latestCloudCoverPct = 5.0,
        latestSource = "SENTINEL-2",
    )

    @Test
    fun `maps a value into its legend band`() {
        val m = map(legend())
        assertEquals(Color(0xFF8B2E2E), indexColour(m, 0.0))
        assertEquals(Color(0xFFC4703A), indexColour(m, 0.25))
        assertEquals(Color(0xFFD9C15A), indexColour(m, 0.4))
        assertEquals(Color(0xFF7FA85A), indexColour(m, 0.55))
        assertEquals(Color(0xFF2E7D4F), indexColour(m, 0.8))
    }

    @Test
    fun `the boundary of a band belongs to the higher band`() {
        val m = map(legend())
        // 0.2 is the edge: the lower stop ends there and the next begins, so it
        // must render as the next band up, not the one below.
        assertEquals(Color(0xFFC4703A), indexColour(m, 0.2))
        assertEquals(Color(0xFFD9C15A), indexColour(m, 0.35))
    }

    @Test
    fun `a value above the scale clamps to the strongest band rather than crashing`() {
        val m = map(legend())
        assertEquals(Color(0xFF2E7D4F), indexColour(m, 5.0))
    }

    @Test
    fun `a value below the scale clamps to the weakest band`() {
        val m = map(legend())
        assertEquals(Color(0xFF8B2E2E), indexColour(m, -5.0))
    }

    @Test
    fun `an empty legend falls back to a neutral colour instead of throwing`() {
        val m = map(emptyList())
        val colour = indexColour(m, 0.5)
        assertTrue("expected a colour, got $colour", colour.alpha > 0f)
    }

    @Test
    fun `parses a six digit hex colour`() {
        assertEquals(Color(0xFF2E7D4F), parseHexColour("#2E7D4F"))
        assertEquals(Color(0xFF2E7D4F), parseHexColour("2E7D4F"))
    }

    @Test
    fun `an unparseable colour falls back rather than crashing`() {
        val fallback = parseHexColour("#ZZZZZZ")
        assertTrue(fallback.alpha > 0f)
        assertTrue(parseHexColour(null).alpha > 0f)
        assertTrue(parseHexColour("").alpha > 0f)
        assertTrue(parseHexColour("#12345").alpha > 0f)
    }
}
