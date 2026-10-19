package bw.agriconnect.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.cos

/**
 * A single WGS-84 corner of a plot boundary. Latitude/longitude (not lng/lat) so call
 * sites read in the order the user types them; the GeoJSON writer flips to `[lng, lat]`
 * as the spec requires.
 */
data class GeoVertex(val latitude: Double, val longitude: Double)

/**
 * Converts between the UI's list of corners and the GeoJSON `Polygon` the backend stores
 * in `farm_plots.geojson_boundary` (jsonb) and hands to the NDVI pipeline.
 *
 * NDVI is computed by reducing satellite reflectance *inside* the plot polygon, so the
 * boundary is not decoration — without it `POST /satellite/ndvi` answers
 * "A plot boundary is required to compute NDVI." This object is deliberately pure (no
 * Android types) so the geometry can be unit-tested off-device.
 *
 * The area estimate mirrors `backend/src/services/gee.js#estimateAreaHectares` (planar
 * shoelace with a local metres-per-degree approximation) so an offline plot shows the
 * same figure the server would derive from the same ring.
 */
object PlotBoundary {

    /** Fewer than three corners cannot enclose an area, so NDVI is impossible. */
    const val MIN_VERTICES = 3

    private const val METRES_PER_DEGREE_LAT = 111_320.0
    private const val SQUARE_METRES_PER_HECTARE = 10_000.0

    /** True when [vertex] is a plausible WGS-84 coordinate. */
    fun isValid(vertex: GeoVertex): Boolean =
        vertex.latitude in -90.0..90.0 && vertex.longitude in -180.0..180.0

    /**
     * Serialises the corners as a closed GeoJSON `Polygon` ring string, or `null` when
     * there are too few corners to form one. The ring is closed (first == last) because
     * the GeoJSON spec requires it and PostGIS/GEE readers assume it.
     */
    fun toGeoJsonPolygon(vertices: List<GeoVertex>): String? {
        if (vertices.size < MIN_VERTICES) return null
        val ring = buildJsonArray {
            vertices.forEach { add(coordinate(it)) }
            add(coordinate(vertices.first()))
        }
        return buildJsonObject {
            put("type", "Polygon")
            put("coordinates", buildJsonArray { add(ring) })
        }.toString()
    }

    /**
     * Reads corners back out of a stored boundary so an existing plot can be re-edited.
     * Accepts a bare `Polygon`/`MultiPolygon` or a `Feature` wrapper, and tolerates
     * malformed input by returning an empty list rather than throwing — a corrupt stored
     * value must not crash the plot editor.
     */
    fun fromGeoJson(raw: String?): List<GeoVertex> {
        val root = toJsonElement(raw) as? JsonObject ?: return emptyList()
        return runCatching {
            val geometry = if (root["type"]?.jsonPrimitive?.content == "Feature") {
                root["geometry"]?.jsonObject
            } else {
                root
            }
            val coordinates = geometry?.get("coordinates")?.jsonArray ?: return emptyList()
            val ring = when (geometry["type"]?.jsonPrimitive?.content) {
                "MultiPolygon" -> coordinates.firstOrNull()?.jsonArray?.firstOrNull()?.jsonArray
                "Polygon" -> coordinates.firstOrNull()?.jsonArray
                else -> null
            } ?: return emptyList()

            ring.mapNotNull { point ->
                val pair = point.jsonArray
                val longitude = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                val latitude = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                if (latitude != null && longitude != null) GeoVertex(latitude, longitude) else null
            }.dropClosingDuplicate()
        }.getOrDefault(emptyList())
    }

    /**
     * Approximate area in hectares, or `null` when there are too few corners. Same planar
     * model as the backend, so device and server agree on the figure to within rounding.
     */
    fun estimateAreaHectares(vertices: List<GeoVertex>): Double? {
        if (vertices.size < MIN_VERTICES) return null
        val metresPerDegreeLng = METRES_PER_DEGREE_LAT * cos(Math.toRadians(vertices.first().latitude))
        var accumulator = 0.0
        for (index in vertices.indices) {
            val current = vertices[index]
            val next = vertices[(index + 1) % vertices.size]
            accumulator += (current.longitude * metresPerDegreeLng) * (next.latitude * METRES_PER_DEGREE_LAT) -
                (next.longitude * metresPerDegreeLng) * (current.latitude * METRES_PER_DEGREE_LAT)
        }
        return round4(abs(accumulator) / 2.0 / SQUARE_METRES_PER_HECTARE)
    }

    /** Mean of the corners; `null` for an empty list. Used as the plot's stored centroid. */
    fun centroid(vertices: List<GeoVertex>): GeoVertex? {
        if (vertices.isEmpty()) return null
        return GeoVertex(
            latitude = vertices.map { it.latitude }.average(),
            longitude = vertices.map { it.longitude }.average(),
        )
    }

    /**
     * The stored boundary as a JSON element ready to send back to the API, or `null` when
     * there is none or it is unparseable.
     *
     * Used to attach the boundary to an NDVI request: a plot drawn moments ago may not
     * have synced yet, and the backend needs a boundary to compute anything. Sending the
     * local copy is the difference between NDVI working immediately and failing until the
     * next sync.
     */
    fun toJsonElement(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    }

    /** True if [element] is a usable GeoJSON boundary that the backend can accept. */
    fun isUsableBoundary(element: JsonElement?): Boolean {
        val obj = element as? JsonObject ?: return false
        val type = obj["type"]?.jsonPrimitive?.content ?: return false
        if (type != "Polygon" && type != "MultiPolygon" && type != "Feature") return false
        val coordinates = when (type) {
            "Feature" -> obj["geometry"]?.jsonObject?.get("coordinates")
            else -> obj["coordinates"]
        }?.jsonArray ?: return false
        return coordinates.size > 0
    }

    private fun coordinate(vertex: GeoVertex): JsonArray = buildJsonArray {
        // GeoJSON positions are [longitude, latitude].
        add(JsonPrimitive(round6(vertex.longitude)))
        add(JsonPrimitive(round6(vertex.latitude)))
    }

    /**
     * Removes the duplicated closing vertex a valid GeoJSON ring carries, so reopening a
     * stored boundary shows the same corner count the user drew (and re-saving does not
     * accumulate closure points on each edit).
     */
    private fun List<GeoVertex>.dropClosingDuplicate(): List<GeoVertex> =
        if (size > 1 && first() == last()) dropLast(1) else this

    private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0

    private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0
}
