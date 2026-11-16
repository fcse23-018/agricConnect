package bw.agriconnect.data.remote.dto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Wire contract for the satellite overview — the response behind the Satellite tab.
 *
 * This exists because of a real production-breaking mismatch: the server writes a
 * boundary centroid as `{"latitude":…,"longitude":…}`
 * (`backend/src/services/geometry.js#ringCentroid`) while the vertices of the same
 * geometry use `{"lat":…,"lon":…}`. The DTO had one type for both, with both fields
 * required, so **every** overview for a plot that had a saved boundary failed to
 * decode and the farmer saw "We couldn't read the server response. Please try again."
 *
 * The fixture below is copied from the actual `getOverview` return object in
 * `backend/src/modules/satellite/satellite.monitoring.service.js`, not invented —
 * same keys, same nesting, same naming split between vertices and centroid.
 */
class SatelliteOverviewContractTest {

    /** Mirrors `NetworkModule`'s production configuration. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `decodes an overview for a plot with a saved boundary`() {
        val dto = json.decodeFromString<SatelliteOverviewDto>(OVERVIEW)

        assertThat(dto.plot.plotId).isEqualTo("3174f6fe-9413-404f-9eef-b5b287ac7a37")
        assertThat(dto.geometry.method).isEqualTo("POLYGON")
        assertThat(dto.geometry.vertices).hasSize(4)
        // The regression: this object is `latitude/longitude`, not `lat/lon`.
        assertThat(dto.geometry.centroid)
            .isEqualTo(CentroidDto(latitude = -24.62795, longitude = 25.92335))
    }

    @Test
    fun `maps the centroid to the domain type the map camera needs`() {
        val overview = json.decodeFromString<SatelliteOverviewDto>(OVERVIEW).toDomain()

        assertThat(overview.geometry.centroid?.latitude).isEqualTo(-24.62795)
        assertThat(overview.geometry.centroid?.longitude).isEqualTo(25.92335)
        assertThat(overview.geometry.vertices.map { it.latitude })
            .containsExactly(-24.6282, -24.6282, -24.6277, -24.6277)
        assertThat(overview.geometry.areaHectares).isEqualTo(0.4322)
        assertThat(overview.geometry.valid).isTrue()
    }

    @Test
    fun `maps the measured series and its summary`() {
        val overview = json.decodeFromString<SatelliteOverviewDto>(OVERVIEW).toDomain()

        assertThat(overview.observations).hasSize(2)
        assertThat(overview.observations.first().observedOn).isEqualTo("2026-10-17")
        assertThat(overview.observations.last().ndvi).isEqualTo(0.61)

        val ndvi = overview.summary.ndvi
        assertThat(ndvi).isNotNull()
        assertThat(ndvi!!.latest).isEqualTo(0.61)
        assertThat(ndvi.latestDate).isEqualTo("2026-10-22")
        assertThat(ndvi.changeVsPrevious).isEqualTo(0.06)
    }

    @Test
    fun `keeps the honesty fields the screen labels with`() {
        val overview = json.decodeFromString<SatelliteOverviewDto>(OVERVIEW).toDomain()

        // Stored series, provider said nothing this time -> the screen must say "Cached".
        assertThat(overview.observationStatus.isCached).isTrue()
        assertThat(overview.observationStatus.isSimulated).isFalse()
        assertThat(overview.observationStatus.newestObservation).isEqualTo("2026-10-22")
        assertThat(overview.observationStatus.newestObservationAgeDays).isEqualTo(5)
        // Modelled weather, never presented as a measurement.
        assertThat(overview.weather?.available).isTrue()
        assertThat(overview.weather?.source).isEqualTo("Open-Meteo")
    }

    @Test
    fun `carries the flat threshold map the legend reads`() {
        val overview = json.decodeFromString<SatelliteOverviewDto>(OVERVIEW).toDomain()

        assertThat(overview.thresholds).containsEntry("stressNdvi", 0.3)
        assertThat(overview.thresholds).containsEntry("dropAbsolute", 0.15)
        assertThat(overview.thresholdDocumentation).isEqualTo("/docs/12-SATELLITE-MONITORING.md")
    }

    @Test
    fun `an unrecognised centroid spelling costs the centroid, not the payload`() {
        // Tolerance is deliberate: if the server ever emits `{lat,lon}` here, the
        // overview must still decode. Losing a centroid only moves the camera.
        val dto = json.decodeFromString<PlotGeometryDto>(
            """{"method":"POLYGON","centroid":{"lat":-1.0,"lon":2.0},"valid":true}""",
        )

        // The DTO still decodes (both centroid fields are optional); it is the
        // mapped domain object that must drop a centroid it cannot understand.
        assertThat(dto.toDomain().centroid).isNull()
        assertThat(dto.valid).isTrue()
    }

    @Test
    fun `a plot without a boundary decodes with no geometry`() {
        val overview = json.decodeFromString<SatelliteOverviewDto>(
            """
            {
              "plot": {"plotId": "p1", "plotName": "Seedbed", "geoJsonBoundary": null},
              "geometry": {"valid": false, "reason": "At least three vertices are required to measure a plot.", "vertexCount": 0},
              "observationStatus": {"isCached": true, "returned": 0, "note": "No boundary saved yet."},
              "summary": {"available": false, "reason": "No valid NDVI reading is available for this plot.", "count": 0, "trend": "INSUFFICIENT"},
              "observations": [],
              "drops": [],
              "alerts": [],
              "thresholds": {},
              "disclaimer": "Indicators only."
            }
            """.trimIndent(),
        ).toDomain()

        assertThat(overview.geometry.valid).isFalse()
        assertThat(overview.geometry.centroid).isNull()
        assertThat(overview.geometry.vertices).isEmpty()
        assertThat(overview.observations).isEmpty()
        assertThat(overview.summary.ndvi?.latest).isNull()
    }

    private companion object {
        /** Verbatim shape of `getOverview()`'s return value. */
        val OVERVIEW = """
        {
          "plot": {
            "plotId": "3174f6fe-9413-404f-9eef-b5b287ac7a37",
            "plotName": "North field",
            "areaHectares": 0.4322,
            "centroidLat": -24.62795,
            "centroidLng": 25.92335,
            "geoJsonBoundary": {
              "type": "Polygon",
              "coordinates": [[[25.9231,-24.6282],[25.9236,-24.6282],[25.9236,-24.6277],[25.9231,-24.6277],[25.9231,-24.6282]]]
            },
            "imageUrl": null
          },
          "geometry": {
            "valid": true,
            "method": "POLYGON",
            "reason": null,
            "vertexCount": 4,
            "areaM2": 4322.1,
            "areaHectares": 0.4322,
            "perimeterM": 263.0,
            "centroid": {"latitude": -24.62795, "longitude": 25.92335},
            "sideLengthsM": [66.1, 65.4, 66.1, 65.4],
            "vertices": [
              {"lat": -24.6282, "lon": 25.9231},
              {"lat": -24.6282, "lon": 25.9236},
              {"lat": -24.6277, "lon": 25.9236},
              {"lat": -24.6277, "lon": 25.9231}
            ]
          },
          "observationStatus": {
            "provider": null,
            "providerError": null,
            "returned": 2,
            "isCached": true,
            "isSimulated": false,
            "coversDays": 180,
            "boundaryUsable": true,
            "newestObservation": "2026-10-22",
            "newestObservationAgeDays": 5,
            "note": "Showing stored observations; the satellite provider was not asked for a new pass."
          },
          "observations": [
            {"date": "2026-10-17", "ndvi": 0.55, "evi": 0.30, "smoothedNdvi": 0.54, "smoothedEvi": 0.29, "validPct": 88.5, "cloudPct": 6.0, "pixels": 401, "quality": "GOOD", "isOutlier": false, "isSimulated": false, "source": "SENTINEL-2"},
            {"date": "2026-10-22", "ndvi": 0.61, "evi": 0.33, "smoothedNdvi": 0.59, "smoothedEvi": 0.32, "validPct": 91.2, "cloudPct": 4.0, "pixels": 412, "quality": "GOOD", "isOutlier": false, "isSimulated": false, "source": "SENTINEL-2"}
          ],
          "summary": {
            "available": true, "reason": null, "index": "NDVI", "count": 2,
            "latest": {"date": "2026-10-22", "ndvi": 0.61, "evi": 0.33},
            "previous": {"date": "2026-10-17", "ndvi": 0.55, "evi": 0.30},
            "min": 0.55, "max": 0.61, "average": 0.58, "change": 0.06, "changePct": 10.9,
            "trend": "RISING", "dataAgeDays": 5,
            "firstDate": "2026-10-17", "lastDate": "2026-10-22",
            "coverageFrom": "2026-10-17", "coverageTo": "2026-10-22"
          },
          "eviSummary": {
            "available": true, "reason": null, "index": "EVI", "count": 2,
            "latest": {"date": "2026-10-22", "ndvi": 0.61, "evi": 0.33},
            "previous": {"date": "2026-10-17", "ndvi": 0.55, "evi": 0.30},
            "min": 0.30, "max": 0.33, "average": 0.315, "change": 0.03, "changePct": 10.0,
            "trend": "RISING", "dataAgeDays": 5,
            "firstDate": "2026-10-17", "lastDate": "2026-10-22",
            "coverageFrom": "2026-10-17", "coverageTo": "2026-10-22"
          },
          "growthCycle": {
            "available": false,
            "reason": "At least 4 valid observations are needed to estimate a growth cycle.",
            "greenUpOn": null, "peakOn": null, "endOn": null,
            "peakValue": null, "amplitude": null, "confidence": "LOW", "evidence": []
          },
          "drops": [],
          "weather": {
            "available": true,
            "provider": "open-meteo",
            "source": "Open-Meteo",
            "dataType": "MODELLED",
            "latitude": -24.62795,
            "longitude": 25.92335,
            "daily": [
              {"date": "2026-10-22", "rainfallMm": 0.0, "tempMaxC": 31.2, "tempMinC": 14.8, "tempMeanC": 23.1, "soilMoisturePct": 18.4}
            ],
            "summary": {
              "periodStart": "2026-10-09", "periodEnd": "2026-10-22",
              "rainfall7dMm": 0.0, "rainfall14dMm": 2.4,
              "tempMax7dC": 33.1, "tempMin7dC": 12.9, "avgTemp7dC": 23.8,
              "soilMoistureMeanPct": 17.9
            },
            "fetchedAt": "2026-10-27T07:59:00.000Z",
            "note": "Daily weather is modelled data from Open-Meteo. It is not a field sensor reading.",
            "cached": false
          },
          "alerts": [
            {
              "alertId": "9f6c1c2e-0000-4000-8000-000000000001",
              "alertKey": "ndvi-drop:2026-10-17",
              "alertType": "NDVI_DROP",
              "severity": "WARNING",
              "title": "Vegetation index fell",
              "body": "NDVI fell from 0.68 to 0.55 between 2026-10-12 and 2026-10-17.",
              "status": "OPEN",
              "raisedAt": "2026-10-18T06:00:00.000Z"
            }
          ],
          "quality": {"readingsReceived": 2, "readingsUsable": 2, "readingsRejected": 0, "outliers": 0, "note": null},
          "modelStatus": {"trained": false, "readyToTrain": false, "labelledEvents": 0, "requiredLabels": 10, "note": "More labelled events are needed before the model can be trained."},
          "thresholds": {
            "dropAbsolute": 0.15, "dropRelative": 0.15, "maxGapDays": 45,
            "minValidPct": 40, "maxCloudPct": 80, "stressNdvi": 0.3,
            "staleDays": 10, "noRecoveryDays": 21, "dryRain14dMm": 10,
            "heatTempMaxC": 32, "minCycleReadings": 4
          },
          "thresholdDocumentation": "/docs/12-SATELLITE-MONITORING.md",
          "disclaimer": "Vegetation indices indicate condition. They do not prove yield, disease or pest damage.",
          "generatedAt": "2026-10-27T08:00:00.000Z"
        }
        """.trimIndent()
    }
}
