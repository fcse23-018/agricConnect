package bw.agriconnect.data.remote.dto

import bw.agriconnect.domain.model.NdviAdvicePriority
import bw.agriconnect.domain.model.NdviTrendDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The post-reading guidance is produced server-side and only rendered here, so the wire
 * contract is the whole feature. These tests pin that contract, including the cases an
 * older or partial server can produce (a missing `advice`, an unknown enum, a blank
 * action title) — each must degrade to "less guidance", never to a crash or an invented
 * one.
 */
class NdviAdviceMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `maps a full advice block onto the domain model`() {
        val advice = decode(NDVI_WITH_ADVICE).toDomain().advice

        assertThat(advice).isNotNull()
        val a = advice!!
        assertThat(a.headline).isEqualTo("Act now — crop vigour is very low")
        assertThat(a.priority).isEqualTo(NdviAdvicePriority.NOW)
        assertThat(a.label).isEqualTo("High stress")
        assertThat(a.summary).contains("0.12")
        assertThat(a.actions.map { it.title }).containsExactly(
            "Walk the plot within 24–48 hours",
            "Check soil moisture at 20 cm depth",
        )
        assertThat(a.actions.first().detail).isNotEmpty()
        assertThat(a.watchFor).contains("Pale, yellowing or purpling leaves")
        assertThat(a.scaleGuide).hasSize(2)
        assertThat(a.scaleGuide.first().range).isEqualTo("below 0.10")
        assertThat(a.limits).isNotEmpty()
        assertThat(a.disclaimer).contains("not a site-specific agronomic recommendation")
    }

    @Test
    fun `maps the trend, the reading context and the confidence note`() {
        val a = decode(NDVI_WITH_ADVICE).toDomain().advice!!

        assertThat(a.trend).isNotNull()
        assertThat(a.trend!!.direction).isEqualTo(NdviTrendDirection.DECLINING)
        assertThat(a.trend.delta).isEqualTo(-0.18)
        assertThat(a.trend.previousValue).isEqualTo(0.30)
        assertThat(a.trend.previousDate).isEqualTo("2026-06-01")

        assertThat(a.context).isNotNull()
        assertThat(a.context!!.areaHectares).isEqualTo(1.1263)
        assertThat(a.context.daysSinceObservation).isEqualTo(5)
        assertThat(a.context.cloudCoverPercent).isEqualTo(12.0)
        assertThat(a.context.sourceIsSimulated).isFalse()

        assertThat(a.confidence!!.level).isEqualTo("HIGH")
        assertThat(a.confidence.note).isNotEmpty()
    }

    @Test
    fun `advice is null for a reading the server sent no guidance for`() {
        val ndvi = decode(NDVI_WITHOUT_ADVICE).toDomain()

        assertThat(ndvi.advice).isNull()
        // The reading itself must still decode — a failed or in-flight computation is normal.
        assertThat(ndvi.status.name).isEqualTo("FAILED")
        assertThat(ndvi.failureReason).isEqualTo("Cloud cover above 80%.")
    }

    @Test
    fun `an advice block without an assessment is dropped rather than rendered empty`() {
        val advice = decode(NDVI_WITH_EMPTY_ADVICE).toDomain().advice
        assertThat(advice).isNull()
    }

    @Test
    fun `unknown enum values fall back to the safe reading instead of throwing`() {
        val a = decode(NDVI_WITH_UNKNOWN_ENUMS).toDomain().advice!!

        assertThat(a.priority).isEqualTo(NdviAdvicePriority.ROUTINE)
        assertThat(a.trend!!.direction).isEqualTo(NdviTrendDirection.FIRST_READING)
    }

    @Test
    fun `an action without a title is dropped, not shown blank`() {
        val a = decode(NDVI_WITH_UNKNOWN_ENUMS).toDomain().advice!!

        assertThat(a.actions.map { it.title }).containsExactly("Re-run the index in 7–10 days")
    }

    @Test
    fun `a simulated reading is flagged as simulated`() {
        val a = decode(NDVI_WITH_UNKNOWN_ENUMS).toDomain().advice!!

        assertThat(a.context!!.sourceIsSimulated).isTrue()
        assertThat(a.context.source).isEqualTo("MOCK")
    }

    @Test
    fun `a partial advice block decodes with the missing pieces left absent`() {
        val a = decode(NDVI_WITH_PARTIAL_ADVICE).toDomain().advice!!

        assertThat(a.headline).isNotEmpty()
        assertThat(a.actions).isEmpty()
        assertThat(a.watchFor).isEmpty()
        assertThat(a.trend).isNull()
        assertThat(a.context).isNull()
        assertThat(a.confidence).isNull()
        assertThat(a.disclaimer).isNull()
    }

    @Test
    fun `blank bullet entries are dropped from the lists`() {
        val a = decode(NDVI_WITH_UNKNOWN_ENUMS).toDomain().advice!!

        assertThat(a.watchFor).containsExactly("Thin or patchy emergence")
        assertThat(a.limits).containsExactly("It is an average over the whole plot.")
    }

    private fun decode(body: String): NdviDto = json.decodeFromString(NdviDto.serializer(), body)

    private companion object {
        const val NDVI_ID = "\"ndviId\": \"ndvi-1\", \"plotId\": \"plot-1\", \"status\": \"GENERATED\""

        val NDVI_WITH_ADVICE = """
        {
          $NDVI_ID,
          "satelliteDate": "2026-06-15",
          "meanNdviValue": 0.12,
          "stressLevel": "HIGH_STRESS",
          "cloudCover": 12,
          "source": "SENTINEL-2",
          "advice": {
            "assessment": {
              "value": 0.12,
              "stressLevel": "HIGH_STRESS",
              "label": "High stress",
              "colour": "error",
              "headline": "Act now — crop vigour is very low",
              "meaning": "The canopy is reflecting very little near-infrared light.",
              "band": { "min": -1, "max": 0.2 },
              "summary": "The vegetation index for “North field” is 0.12 (high stress). Down 0.18 on the previous reading — keep watching this plot. The observation is 5 days old."
            },
            "priority": "NOW",
            "actions": [
              { "title": "Walk the plot within 24–48 hours", "detail": "Look at the shape of the problem." },
              { "title": "Check soil moisture at 20 cm depth", "detail": "Use a hand auger or the ball test." }
            ],
            "watchFor": ["Pale, yellowing or purpling leaves"],
            "trend": {
              "direction": "DECLINING",
              "delta": -0.18,
              "previousValue": 0.3,
              "previousDate": "2026-06-01",
              "note": "Compared with the previous reading."
            },
            "context": {
              "plotName": "North field",
              "areaHectares": 1.1263,
              "observedOn": "2026-06-15",
              "daysSinceObservation": 5,
              "cloudCoverPercent": 12,
              "source": "SENTINEL-2",
              "sourceIsSimulated": false
            },
            "confidence": { "level": "HIGH", "note": "Cloud cover was 12%." },
            "scaleGuide": [
              { "range": "below 0.10", "meaning": "Bare soil, rock, water or dead residue." },
              { "range": "0.10 – 0.20", "meaning": "Very sparse or severely stressed vegetation." }
            ],
            "limits": ["It is an average over the whole plot."],
            "disclaimer": "This is general guidance from a vegetation index, not a site-specific agronomic recommendation."
          }
        }
        """.trimIndent()

        val NDVI_WITHOUT_ADVICE = """
        {
          $NDVI_ID,
          "status": "FAILED",
          "failureReason": "Cloud cover above 80%.",
          "source": "SENTINEL-2"
        }
        """.trimIndent()

        val NDVI_WITH_EMPTY_ADVICE = """
        { $NDVI_ID, "advice": { "priority": "NOW", "actions": [] } }
        """.trimIndent()

        val NDVI_WITH_UNKNOWN_ENUMS = """
        {
          $NDVI_ID,
          "advice": {
            "assessment": { "value": 0.3, "label": "Moderate stress", "headline": "Watch closely", "meaning": "Thin canopy.", "summary": "0.30" },
            "priority": "SOMEDAY",
            "actions": [
              { "title": "   ", "detail": "has no title, so it is useless" },
              { "title": "Re-run the index in 7–10 days", "detail": "Roughly one satellite revisit." }
            ],
            "watchFor": ["Thin or patchy emergence", "  "],
            "trend": { "direction": "SIDEWAYS" },
            "context": { "source": "MOCK", "sourceIsSimulated": true },
            "limits": ["It is an average over the whole plot.", ""]
          }
        }
        """.trimIndent()

        val NDVI_WITH_PARTIAL_ADVICE = """
        { $NDVI_ID, "advice": { "assessment": { "headline": "On track", "meaning": "Healthy canopy." } } }
        """.trimIndent()
    }
}
