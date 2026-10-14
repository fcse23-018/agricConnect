import { config } from '../config/index.js';
import { mockNdviForPlot, isCloudCoverUsable } from './ndvi.js';

/**
 * Google Earth Engine / Sentinel-2 adapter.
 *
 * The documented pipeline is:
 *   GeoJSON boundary -> GEE -> Sentinel-2 Band 8 (NIR) + Band 4 (RED)
 *   -> NDVI = (NIR - RED) / (NIR + RED) -> compressed vector alert.
 *
 * When GEE credentials are absent (GEE_ENABLED=false, the default) we return a
 * deterministic MOCK result so the rest of the system can be exercised end to
 * end. The result is always tagged with `source` so nothing pretends to be real
 * satellite data. See docs/07-EXTERNAL-CREDENTIALS.md.
 */

function validatePolygon(geoJson) {
  if (!geoJson || typeof geoJson !== 'object') return 'Boundary is required.';
  const type = geoJson.type;
  if (!['Polygon', 'MultiPolygon', 'Feature'].includes(type)) {
    return 'Boundary must be a GeoJSON Polygon, MultiPolygon or Feature.';
  }
  const coords = type === 'Feature' ? geoJson.geometry?.coordinates : geoJson.coordinates;
  if (!Array.isArray(coords) || coords.length === 0) return 'Boundary has no coordinates.';
  return null;
}

/** Simple planar area estimate (hectares) — replaced by GEE's geodesic area when enabled. */
export function estimateAreaHectares(geoJson) {
  try {
    const ring = geoJson.type === 'Feature'
      ? geoJson.geometry?.coordinates?.[0]
      : geoJson.type === 'MultiPolygon'
        ? geoJson.coordinates?.[0]?.[0]
        : geoJson.coordinates?.[0];
    if (!Array.isArray(ring) || ring.length < 3) return null;
    // Shoelace on lat/lng with a local metres approximation (good enough for plots).
    const latRef = ring[0][1];
    const mPerDegLat = 111_320;
    const mPerDegLng = 111_320 * Math.cos((latRef * Math.PI) / 180);
    let area = 0;
    for (let i = 0; i < ring.length; i += 1) {
      const [x1, y1] = ring[i];
      const [x2, y2] = ring[(i + 1) % ring.length];
      area += x1 * mPerDegLng * (y2 * mPerDegLat) - x2 * mPerDegLng * (y1 * mPerDegLat);
    }
    return Number((Math.abs(area) / 2 / 10_000).toFixed(4));
  } catch {
    return null;
  }
}

/**
 * Compute NDVI for a boundary. Returns:
 *   { status: 'GENERATED'|'FAILED', meanNdviValue, cloudCover, satelliteDate,
 *     source, failureReason, compressedVectorJson }
 */
export async function computeNdvi({ plotId, geoJsonBoundary }) {
  const invalid = validatePolygon(geoJsonBoundary);
  if (invalid) {
    return { status: 'FAILED', failureReason: invalid, meanNdviValue: null, source: 'VALIDATION' };
  }

  // --- Real GEE path (requires configured credentials) ---------------------
  if (config.gee.enabled && config.gee.serviceAccountEmail && config.gee.projectId) {
    try {
      // The Earth Engine call is intentionally isolated. In a deployment with
      // credentials this function performs the Sentinel-2 collection + reduceRegion.
      return await computeViaEarthEngine({ plotId, geoJsonBoundary });
    } catch (err) {
      return {
        status: 'FAILED',
        failureReason: 'Satellite imagery could not be processed at this time.',
        meanNdviValue: null,
        source: 'SENTINEL-2',
        internal: err.message,
      };
    }
  }

  // --- Deterministic mock path (no credentials configured) -----------------
  const mock = mockNdviForPlot(plotId);
  if (!isCloudCoverUsable(mock.cloudCover)) {
    return {
      status: 'FAILED',
      failureReason: 'Cloud cover above 80% — imagery unusable for this observation.',
      meanNdviValue: null,
      cloudCover: mock.cloudCover,
      satelliteDate: mock.satelliteDate,
      source: mock.source,
    };
  }
  return {
    status: 'GENERATED',
    meanNdviValue: mock.meanNdviValue,
    cloudCover: mock.cloudCover,
    satelliteDate: mock.satelliteDate,
    source: mock.source,
    compressedVectorJson: buildCompressedVector(geoJsonBoundary, mock.meanNdviValue),
  };
}

/** Compressed vector alert: a compact ring plus a scalar value (docs: "compressed vector alert"). */
export function buildCompressedVector(geoJson, meanNdvi) {
  const ring = geoJson.type === 'Feature'
    ? geoJson.geometry?.coordinates?.[0]
    : geoJson.type === 'MultiPolygon'
      ? geoJson.coordinates?.[0]?.[0]
      : geoJson.coordinates?.[0];
  const simplified = (ring || []).filter((_, i) => i % 2 === 0).map(([lng, lat]) => [
    Number(lng.toFixed(4)),
    Number(lat.toFixed(4)),
  ]);
  return { type: 'Feature', properties: { ndvi: meanNdvi }, geometry: { type: 'Polygon', coordinates: [simplified] } };
}

/**
 * Real Earth Engine implementation placeholder. Wiring this requires the `@google/earthengine`
 * package and a service-account key; both are documented in docs/07.
 */
async function computeViaEarthEngine({ geoJsonBoundary }) {
  // eslint-disable-next-line global-require
  const ee = (await import('@google/earthengine')).default;
  if (!ee?.data) throw new Error('Earth Engine client not initialised');

  ee.data.setPrivateKey(config.gee.privateKey);
  await new Promise((resolve, reject) => {
    ee.data.authenticateViaPrivateKey(
      config.gee.privateKey,
      () => ee.initialize(null, null, resolve, reject, config.gee.projectId),
      reject,
    );
  });
  // Sentinel-2 Surface Reflectance: B8 = NIR, B4 = RED.
  const geometry = ee.Geometry.Polygon(
    geoJsonBoundary.type === 'Feature'
      ? geoJsonBoundary.geometry.coordinates
      : geoJsonBoundary.coordinates,
  );
  const collection = ee
    .ImageCollection('COPERNICUS/S2_SR_HARMONIZED')
    .filterBounds(geometry)
    .filterDate(ee.Date(Date.now()).advance(-60, 'day'), ee.Date(Date.now()))
    .sort('CLOUDY_PIXEL_PERCENTAGE');
  const image = ee.Image(collection.first());
  const cloudCover = await new Promise((res, rej) => image.get('CLOUDY_PIXEL_PERCENTAGE').evaluate((v, e) => (e ? rej(e) : res(v))));
  if (!isCloudCoverUsable(cloudCover)) {
    return { status: 'FAILED', failureReason: 'Cloud cover above 80%.', meanNdviValue: null, cloudCover, source: 'SENTINEL-2' };
  }
  const ndvi = image.normalizedDifference(['B8', 'B4']).rename('NDVI');
  const reduced = await new Promise((res, rej) =>
    ndvi.reduceRegion({ reducer: ee.Reducer.mean(), geometry, scale: 10, maxPixels: 1e9 }).evaluate((v, e) => (e ? rej(e) : res(v))));
  return {
    status: 'GENERATED',
    meanNdviValue: Number(Number(reduced.NDVI).toFixed(4)),
    cloudCover,
    satelliteDate: new Date().toISOString().slice(0, 10),
    source: 'SENTINEL-2',
    compressedVectorJson: buildCompressedVector(geoJsonBoundary, Number(reduced.NDVI)),
  };
}

export default { computeNdvi, estimateAreaHectares, buildCompressedVector };
