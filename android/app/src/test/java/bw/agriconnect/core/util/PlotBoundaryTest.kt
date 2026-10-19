package bw.agriconnect.core.util

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * A plot boundary is the one input NDVI cannot work without, so the conversion to GeoJSON
 * (and back for re-editing) is verified without a device.
 */
class PlotBoundaryTest {

    /** ~0.001° square at Botswana latitudes: about 101 m (lng) × 111 m (lat) ≈ 1.126 ha. */
    private val square = listOf(
        GeoVertex(latitude = -24.6500, longitude = 25.9100),
        GeoVertex(latitude = -24.6500, longitude = 25.9110),
        GeoVertex(latitude = -24.6490, longitude = 25.9110),
        GeoVertex(latitude = -24.6490, longitude = 25.9100),
    )

    @Test
    fun `fewer than three corners cannot form a boundary`() {
        assertThat(PlotBoundary.toGeoJsonPolygon(emptyList())).isNull()
        assertThat(PlotBoundary.toGeoJsonPolygon(square.take(2))).isNull()
        assertThat(PlotBoundary.estimateAreaHectares(square.take(2))).isNull()
        assertThat(PlotBoundary.centroid(emptyList())).isNull()
    }

    @Test
    fun `polygon is a closed ring in longitude latitude order`() {
        val parsed = Json.parseToJsonElement(PlotBoundary.toGeoJsonPolygon(square)!!).jsonObject

        assertThat(parsed["type"]?.jsonPrimitive?.content).isEqualTo("Polygon")

        val ring = parsed["coordinates"]!!.jsonArray.first().jsonArray
        // Four corners plus the closing vertex the GeoJSON spec requires.
        assertThat(ring).hasSize(5)
        assertThat(ring.first()).isEqualTo(ring.last())

        val first = ring.first().jsonArray
        assertThat(first[0].jsonPrimitive.double).isEqualTo(25.91) // longitude first
        assertThat(first[1].jsonPrimitive.double).isEqualTo(-24.65) // latitude second
    }

    @Test
    fun `round trip preserves the corners without duplicating the closure`() {
        val json = PlotBoundary.toGeoJsonPolygon(square)!!

        // Re-saving an edited plot must not accumulate closure vertices on every edit.
        assertThat(PlotBoundary.fromGeoJson(json)).isEqualTo(square)
        assertThat(PlotBoundary.fromGeoJson(PlotBoundary.toGeoJsonPolygon(PlotBoundary.fromGeoJson(json)))).isEqualTo(square)
    }

    @Test
    fun `reads a Feature wrapper and a MultiPolygon`() {
        val feature = """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[25.91,-24.65],[25.911,-24.65],[25.911,-24.649],[25.91,-24.65]]]}}"""
        assertThat(PlotBoundary.fromGeoJson(feature))
            .containsExactly(
                GeoVertex(-24.65, 25.91),
                GeoVertex(-24.65, 25.911),
                GeoVertex(-24.649, 25.911),
            )
            .inOrder()

        val multiPolygon = """{"type":"MultiPolygon","coordinates":[[[[25.91,-24.65],[25.911,-24.65],[25.911,-24.649],[25.91,-24.65]]]]}"""
        assertThat(PlotBoundary.fromGeoJson(multiPolygon)).hasSize(3)
    }

    @Test
    fun `a corrupt stored boundary does not crash the editor`() {
        assertThat(PlotBoundary.fromGeoJson(null)).isEmpty()
        assertThat(PlotBoundary.fromGeoJson("")).isEmpty()
        assertThat(PlotBoundary.fromGeoJson("not json at all")).isEmpty()
        assertThat(PlotBoundary.fromGeoJson("""{"type":"Polygon"}""")).isEmpty()
        assertThat(PlotBoundary.fromGeoJson("""{"type":"Point","coordinates":[25.91,-24.65]}""")).isEmpty()
    }

    @Test
    fun `area estimate matches the backend planar model`() {
        assertThat(PlotBoundary.estimateAreaHectares(square)!!).isWithin(0.02).of(1.1262)
    }

    @Test
    fun `centroid averages the corners`() {
        val centroid = PlotBoundary.centroid(square)!!
        assertThat(centroid.latitude).isWithin(1e-9).of(-24.6495)
        assertThat(centroid.longitude).isWithin(1e-9).of(25.9105)
    }

    @Test
    fun `rejects coordinates outside the globe`() {
        assertThat(PlotBoundary.isValid(GeoVertex(-24.65, 25.91))).isTrue()
        assertThat(PlotBoundary.isValid(GeoVertex(91.0, 25.91))).isFalse()
        assertThat(PlotBoundary.isValid(GeoVertex(-24.65, 181.0))).isFalse()
    }
}
