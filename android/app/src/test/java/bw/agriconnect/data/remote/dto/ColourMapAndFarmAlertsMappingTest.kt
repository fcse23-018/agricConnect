package bw.agriconnect.data.remote.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Wire contract for the two additions behind the Satellite screen's imagery and its
 * farm-wide alerts.
 *
 * The important assertions here are the ones about **honesty flags** and about
 * **null vs zero**. A grid cell that is `null` means "outside the plot"; if the
 * mapping ever turned that into `0.0`, the picture would invent bare soil along the
 * plot's edge. Likewise, a plot with no reading must map to `noData = true`, not to
 * a healthy-looking default.
 */
class ColourMapAndFarmAlertsMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `maps a colour map, preserving null cells as null`() {
        val dto = json.decodeFromString<ColourMapDto>(COLOUR_MAP)
        val map = dto.toDomain()

        assertThat(map.available).isTrue()
        assertThat(map.index).isEqualTo("ndvi")
        assertThat(map.meanValue).isEqualTo(0.42)
        assertThat(map.size).isEqualTo(2)

        // Row 1: one real cell, one outside the boundary.
        assertThat(map.grid[0][0]).isEqualTo(0.35)
        assertThat(map.grid[0][1]).isNull()
        // Row 2: a real zero-ish value must survive as a number, not become null.
        assertThat(map.grid[1][0]).isEqualTo(0.01)
    }

    @Test
    fun `carries both honesty flags independently`() {
        val map = json.decodeFromString<ColourMapDto>(COLOUR_MAP).toDomain()
        assertThat(map.isSimulated).isFalse()
        assertThat(map.variationSimulated).isTrue()

        val mocked = json.decodeFromString<ColourMapDto>(COLOUR_MAP_MOCK).toDomain()
        assertThat(mocked.isSimulated).isTrue()
    }

    @Test
    fun `reports staleness and coverage from the nested blocks`() {
        val map = json.decodeFromString<ColourMapDto>(COLOUR_MAP).toDomain()
        assertThat(map.isStale).isTrue()
        assertThat(map.ageDays).isEqualTo(21)
        assertThat(map.staleDays).isEqualTo(14)
        assertThat(map.validPixelPct).isEqualTo(88.0)
        assertThat(map.insideCells).isEqualTo(2)
        assertThat(map.latestSource).isEqualTo("SENTINEL-2")
    }

    @Test
    fun `an unavailable colour map keeps its reason and draws nothing`() {
        val dto = json.decodeFromString<ColourMapDto>(
            """{"available":false,"reason":"This plot has no saved boundary, so there is nothing to draw."}""",
        )
        val map = dto.toDomain()
        assertThat(map.available).isFalse()
        assertThat(map.reason).contains("no saved boundary")
        assertThat(map.grid).isEmpty()
    }

    @Test
    fun `maps the observation dates and the historical flag`() {
        val map = json.decodeFromString<ColourMapDto>(COLOUR_MAP_HISTORICAL).toDomain()
        assertThat(map.observedOn).isEqualTo("2026-05-01")
        assertThat(map.newestObservedOn).isEqualTo("2026-05-10")
        assertThat(map.isHistorical).isTrue()
        assertThat(map.availableDates)
            .containsExactly("2026-05-01", "2026-05-10")
            .inOrder()
    }

    @Test
    fun `a newest-date map is not flagged historical`() {
        val map = json.decodeFromString<ColourMapDto>(COLOUR_MAP).toDomain()
        assertThat(map.isHistorical).isFalse()
        // An older server that does not send the field defaults to "not historical",
        // which is the safe reading for the newest reading it would have sent.
        assertThat(map.availableDates).isEmpty()
    }

    @Test
    fun `a refused date carries the requested date and no grid`() {
        val map = json.decodeFromString<ColourMapDto>(COLOUR_MAP_REFUSED_DATE).toDomain()
        assertThat(map.available).isFalse()
        assertThat(map.requestedDate).isEqualTo("2026-05-15")
        assertThat(map.availableDates).containsExactly("2026-05-01", "2026-05-10")
        assertThat(map.grid).isEmpty()
    }

    @Test
    fun `a plot with no data is flagged, never defaulted to healthy`() {
        val summary = json.decodeFromString<AlertsByFarmDto>(ALERTS_BY_FARM).toDomain()

        val noData = summary.plots.first { it.plotId == "plot-nodata" }
        assertThat(noData.noData).isTrue()
        assertThat(noData.hasObservation).isFalse()
        assertThat(noData.needsAttention).isTrue()
        assertThat(noData.latestNdvi).isNull()
        assertThat(noData.reason).contains("No satellite reading")

        val healthy = summary.plots.first { it.plotId == "plot-ok" }
        assertThat(healthy.noData).isFalse()
        assertThat(healthy.needsAttention).isFalse()
        assertThat(healthy.reason).isNull()    }

    @Test
    fun `maps the totals and the farm grouping`() {
        val summary = json.decodeFromString<AlertsByFarmDto>(ALERTS_BY_FARM).toDomain()

        assertThat(summary.totals.plots).isEqualTo(3)
        assertThat(summary.totals.plotsNeedingAttention).isEqualTo(2)
        assertThat(summary.totals.plotsWithNoData).isEqualTo(1)
        assertThat(summary.totals.openAlerts).isEqualTo(1)
        assertThat(summary.totals.farmsAffected).isEqualTo(1)

        assertThat(summary.farms).hasSize(1)
        assertThat(summary.farms.first().farmName).isEqualTo("River Farm")
        assertThat(summary.farms.first().plots).hasSize(3)
    }

    @Test
    fun `maps an alert nested inside a farm plot`() {
        val summary = json.decodeFromString<AlertsByFarmDto>(ALERTS_BY_FARM).toDomain()
        val withAlert = summary.plots.first { it.plotId == "plot-alert" }
        assertThat(withAlert.alerts).hasSize(1)
        assertThat(withAlert.alerts.first().title).isEqualTo("Vegetation dropped sharply")
        assertThat(withAlert.openAlertCount).isEqualTo(1)
    }

    private companion object {
        const val COLOUR_MAP = """
        {
          "available": true,
          "index": "ndvi",
          "plotId": "p1",
          "observedOn": "2026-05-10",
          "meanValue": 0.42,
          "isSimulated": false,
          "variationSimulated": true,
          "grid": [[0.35, null], [0.01, null]],
          "size": 2,
          "stats": {"mean": 0.42, "gridMean": 0.42, "min": 0.01, "max": 0.35, "insideCells": 2, "spread": 0.34},
          "coverage": {"insideCells": 2, "totalCells": 4, "validPixelPct": 88.0},
          "scale": {"min": -0.1, "max": 0.9},
          "legend": [{"from": -0.1, "to": 0.2, "colour": "#8B2E2E", "label": "Bare soil"}],
          "ageDays": 21,
          "isStale": true,
          "staleDays": 14,
          "latest": {"date": "2026-05-10", "value": 0.42, "source": "SENTINEL-2", "isSimulated": false}
        }
        """

        const val COLOUR_MAP_MOCK = """
        {
          "available": true,
          "index": "ndvi",
          "meanValue": 0.42,
          "isSimulated": true,
          "variationSimulated": true,
          "grid": [[0.4, null]],
          "size": 1
        }
        """

        const val COLOUR_MAP_HISTORICAL = """
        {
          "available": true,
          "index": "ndvi",
          "observedOn": "2026-05-01",
          "isHistorical": true,
          "newestObservedOn": "2026-05-10",
          "availableDates": ["2026-05-01", "2026-05-10"],
          "meanValue": 0.31,
          "variationSimulated": true,
          "grid": [[0.31, null]],
          "size": 1
        }
        """

        const val COLOUR_MAP_REFUSED_DATE = """
        {
          "available": false,
          "index": "ndvi",
          "requestedDate": "2026-05-15",
          "availableDates": ["2026-05-01", "2026-05-10"],
          "reason": "There is no stored observation for 2026-05-15. Pick one of the dates the plot actually has."
        }
        """

        const val ALERTS_BY_FARM = """
        {
          "plots": [
            {
              "plotId": "plot-alert", "plotName": "North field", "farmName": "River Farm",
              "openAlertCount": 1, "totalAlertCount": 2,
              "alerts": [{"alertId": "a1", "alertKey": "k1", "alertType": "NDVI_DROP", "severity": "WARNING",
                          "title": "Vegetation dropped sharply", "body": "Possible heat or dry stress.",
                          "status": "OPEN"}],
              "latestObservationDate": "2026-05-10", "latestNdvi": 0.31,
              "hasObservation": true, "isStale": false, "noData": false, "needsAttention": true,
              "reason": "Vegetation dropped sharply"
            },
            {
              "plotId": "plot-nodata", "plotName": "South field", "farmName": "River Farm",
              "openAlertCount": 0, "totalAlertCount": 0, "alerts": [],
              "hasObservation": false, "isStale": false, "noData": true, "needsAttention": true,
              "reason": "No satellite reading for this plot yet."
            },
            {
              "plotId": "plot-ok", "plotName": "East field", "farmName": "River Farm",
              "openAlertCount": 0, "totalAlertCount": 0, "alerts": [],
              "latestObservationDate": "2026-05-18", "latestNdvi": 0.62,
              "hasObservation": true, "isStale": false, "noData": false, "needsAttention": false,
              "reason": null
            }
          ],
          "farms": [
            {
              "farmName": "River Farm", "openAlertCount": 1, "plotsNeedingAttention": 1,
              "plots": [
                {"plotId": "plot-alert", "plotName": "North field", "openAlertCount": 1, "totalAlertCount": 2,
                 "hasObservation": true, "needsAttention": true},
                {"plotId": "plot-nodata", "plotName": "South field", "openAlertCount": 0, "totalAlertCount": 0,
                 "hasObservation": false, "noData": true, "needsAttention": true},
                {"plotId": "plot-ok", "plotName": "East field", "openAlertCount": 0, "totalAlertCount": 0,
                 "hasObservation": true, "needsAttention": false}
              ]
            }
          ],
          "totals": {"plots": 3, "plotsNeedingAttention": 2, "plotsWithNoData": 1, "plotsStale": 0,
                     "openAlerts": 1, "farmsAffected": 1},
          "note": "Every plot is listed."
        }
        """
    }
}
