/**
 * Plot observations: the NDVI/EVI series that the analysis engine consumes.
 *
 * Two sources, in priority order:
 *
 *  1. **crop-api** (`CROP_API_ENABLED=true` + `CROP_API_URL`) — the FastAPI
 *     Sentinel-2 service in this repository. It does the cloud/cloud-shadow
 *     masking, the NDVI + EVI reduction and the quality control, and returns
 *     `source: "gee"` for live data.
 *  2. **deterministic mock** — when no service is configured, a series derived
 *     from the plot id is returned so the rest of the pipeline can be exercised
 *     offline. It is tagged `MOCK` and `isSimulated: true` and is never
 *     presented as a real observation.
 *
 * Nothing here fabricates a value and hides where it came from: the `source`
 * and `isSimulated` fields travel with every observation into the API response.
 */

import { config } from '../config/index.js';
import { createHash } from 'node:crypto';

export const MOCK_SOURCE = 'MOCK';
export const GEE_SOURCE = 'SENTINEL-2';

/** Turn a stored GeoJSON boundary into an ordered lat/lon corner list. */
export function boundaryCorners(geoJson) {
  if (!geoJson || typeof geoJson !== 'object') return null;
  let coordinates;
  if (geoJson.type === 'Feature') coordinates = geoJson.geometry?.coordinates;
  else if (geoJson.type === 'MultiPolygon') coordinates = geoJson.coordinates?.[0];
  else coordinates = geoJson.coordinates;
  const ring = Array.isArray(coordinates) ? coordinates[0] : null;
  if (!Array.isArray(ring)) return null;

  const points = ring
    .filter((pair) => Array.isArray(pair) && Number.isFinite(Number(pair[0])) && Number.isFinite(Number(pair[1])))
    .map(([lng, lat]) => ({ lat: Number(lat), lon: Number(lng) }));
  if (points.length < 3) return null;

  // Drop the duplicated closing vertex if present — the API wants 3+ distinct.
  const first = points[0];
  const last = points[points.length - 1];
  if (points.length > 3 && first.lat === last.lat && first.lon === last.lon) points.pop();
  return points.length >= 3 ? points : null;
}

/** Arithmetic mean of the ring vertices — good enough for a weather lookup. */
export function boundaryCentre(geoJson) {
  const corners = boundaryCorners(geoJson);
  if (!corners) return null;
  const lat = corners.reduce((sum, point) => sum + point.lat, 0) / corners.length;
  const lon = corners.reduce((sum, point) => sum + point.lon, 0) / corners.length;
  return { latitude: Number(lat.toFixed(6)), longitude: Number(lon.toFixed(6)) };
}

/* ------------------------------- mock series ------------------------------ */

/** Small deterministic PRNG so the same plot always yields the same series. */
function seededRandom(seed) {
  let state = seed >>> 0 || 1;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    return state / 0xffffffff;
  };
}

function seedFrom(plotId) {
  const hash = createHash('sha256').update(String(plotId)).digest();
  return hash.readUInt32BE(0);
}

/**
 * A synthetic growing season: a smooth rise to a peak, a mid-season dip and a
 * harvest decline, sampled every ~5 days with gaps and some cloud cover. The
 * numbers are *not* real Sentinel-2 values and are labelled accordingly.
 */
export function mockSeries(plotId, { days = config.satellite.defaultDays, now = new Date() } = {}) {
  const random = seededRandom(seedFrom(plotId));
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const startMs = end.getTime() - days * 86_400_000;
  const seasonLength = days;
  const phase = random() * Math.PI;
  const amplitude = 0.35 + random() * 0.3;
  const baseline = 0.18 + random() * 0.12;

  const readings = [];
  // The step is 4-7 days, so the loop lands on or before today and can miss it
  // by up to 6 days. A mock series whose newest sample is nearly a week old
  // would raise a spurious "stale observation" alert against itself, so the
  // final reading is snapped to today.
  const offsets = [];
  for (let dayOffset = 0; dayOffset <= seasonLength; dayOffset += 4 + Math.floor(random() * 4)) {
    offsets.push(dayOffset);
  }
  if (offsets.length > 1 && offsets[offsets.length - 1] < seasonLength) {
    offsets[offsets.length - 1] = seasonLength;
  }

  for (const dayOffset of offsets) {
    const date = new Date(startMs + dayOffset * 86_400_000);
    const progress = dayOffset / seasonLength;
    let ndvi = baseline + amplitude * Math.sin(Math.PI * progress + phase * 0.2);
    // A dry spell in the middle of the season.
    if (progress > 0.45 && progress < 0.58) ndvi -= 0.22;
    // Harvest/clear at the end.
    if (progress > 0.85) ndvi -= 0.3 * (progress - 0.85) / 0.15;
    ndvi = Math.max(-0.1, Math.min(0.92, ndvi + (random() - 0.5) * 0.05));

    const evi = Math.max(-0.1, Math.min(0.9, ndvi * 0.82 - 0.03 + (random() - 0.5) * 0.03));
    const cloudPct = Math.round(random() * 45);
    // A couple of deliberately poor passes so quality handling is exercised.
    const validPct = dayOffset % 24 === 0 ? Math.round(15 + random() * 15) : Math.round(65 + random() * 30);

    readings.push({
      date: date.toISOString().slice(0, 10),
      ndvi: Number(ndvi.toFixed(4)),
      evi: Number(evi.toFixed(4)),
      pixels: Math.round(18 + random() * 40),
      cloudPct,
      validPct,
      source: MOCK_SOURCE,
      isSimulated: true,
    });
  }

  return {
    available: true,
    source: MOCK_SOURCE,
    isSimulated: true,
    readings,
    note: 'Demonstration series generated on the server because no Sentinel-2 processor is configured. These are not real observations.',
  };
}

/* ------------------------------ crop-api call ----------------------------- */

async function fetchFromCropApi({ geoJsonBoundary, days, fetchImpl }) {
  const url = `${config.cropApi.url}/v1/indices`;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.cropApi.timeoutMs);

  try {
    const corners = boundaryCorners(geoJsonBoundary);
    const response = await fetchImpl(url, {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'content-type': 'application/json',
        ...(config.cropApi.apiKey ? { 'x-api-key': config.cropApi.apiKey } : {}),
      },
      body: JSON.stringify({ corners, days_back: Math.max(15, Math.min(730, days)) }),
    });

    if (!response?.ok) {
      return { available: false, reason: `The satellite processor returned HTTP ${response?.status ?? 'unknown'}.` };
    }

    const body = await response.json();
    const readings = (Array.isArray(body?.readings) ? body.readings : []).map((row) => ({
      date: row.date,
      ndvi: row.ndvi,
      evi: row.evi,
      pixels: row.pixels,
      source: body?.source === 'gee' ? GEE_SOURCE : MOCK_SOURCE,
      isSimulated: body?.source !== 'gee',
    }));

    return {
      available: true,
      source: body?.source === 'gee' ? GEE_SOURCE : MOCK_SOURCE,
      isSimulated: body?.source !== 'gee',
      readings,
      skipped: Array.isArray(body?.skipped) ? body.skipped : [],
    };
  } catch (err) {
    return {
      available: false,
      reason: err?.name === 'AbortError'
        ? 'The satellite processor did not respond in time.'
        : 'The satellite processor could not be reached.',
    };
  } finally {
    clearTimeout(timeout);
  }
}

/**
 * The observation set for a plot.
 *
 * Never throws: a provider failure is reported as `available: false` with a
 * reason, so the caller can decide whether to fall back to a cached database
 * row rather than showing a fabricated series.
 */
export async function fetchObservations({
  plotId,
  geoJsonBoundary,
  days = config.satellite.defaultDays,
  fetchImpl = globalThis.fetch,
  now = new Date(),
} = {}) {
  if (config.cropApi.enabled && config.cropApi.url) {
    const corners = boundaryCorners(geoJsonBoundary);
    if (!corners) {
      return {
        available: false,
        source: null,
        isSimulated: false,
        readings: [],
        reason: 'The plot boundary is not a usable polygon, so no area could be measured.',
      };
    }
    const remote = await fetchFromCropApi({ geoJsonBoundary, days, fetchImpl });
    if (remote.available) return remote;
    // Fall through to the mock so the screen still has something honest to show,
    // but say why the real processor was not used.
    const mock = mockSeries(plotId, { days, now });
    return { ...mock, providerFailure: remote.reason };
  }

  return mockSeries(plotId, { days, now });
}

export default {
  fetchObservations,
  mockSeries,
  boundaryCorners,
  boundaryCentre,
  MOCK_SOURCE,
  GEE_SOURCE,
};
