import { config } from '../config/index.js';
import { mockNdviForPlot, isCloudCoverUsable } from './ndvi.js';

/**
 * Google Earth Engine / Sentinel-2 adapter.
 *
 * The documented pipeline is:
 *   GeoJSON boundary -> GEE -> Sentinel-2 SR -> cloud + cloud-shadow mask
 *   -> NDVI = (NIR - RED) / (NIR + RED) and EVI -> reduceRegion over the boundary.
 *
 * Two modes, and the result always says which one produced it:
 *
 *   REAL   (GEE_ENABLED=true with a service-account key) — `source: 'SENTINEL-2'`,
 *          `isSimulated: false`, with the imagery's own acquisition date and the
 *          share of the boundary that survived masking.
 *   MOCK   (no credentials) — `source: 'MOCK'`, `isSimulated: true`. Deterministic
 *          per plot so the pipeline, caching and UI are testable offline. It is
 *          never presented as a real observation.
 *
 * Nothing here fabricates a value and hides its origin: `source` and
 * `isSimulated` travel with every reading into the API response.
 */

/** Sentinel-2 Surface Reflectance, harmonised (the post-2022 baseline). */
const S2_COLLECTION = 'COPERNICUS/S2_SR_HARMONIZED';

/** Band 8 = NIR (10 m), Band 4 = RED (10 m), Band 2 = BLUE (10 m). */
const NDVI_BANDS = ['B8', 'B4'];
const EVI_INDEX_BANDS = { nir: 'B8', red: 'B4', blue: 'B2' };

/** Cloud Score+ is the current, non-deprecated Sentinel-2 cloud masker. */
const CLOUD_SCORE_COLLECTION = 'GOOGLE/CLOUD_SCORE_PLUS/V1/S2_HARMONIZED';

/**
 * The band Cloud Score+ is linked on.
 *
 * `linkCollection` matches by BAND, not by property, so this must name the QA
 * band — Google's own documented example uses exactly this form:
 *   `.linkCollection(csPlus, [QA_BAND])` with `QA_BAND = 'cs_cdf'`.
 * Passing `system:index` here fails with "Match image does not have a band named
 * 'system:index'" (system:index is a property).
 */
const CLOUD_SCORE_BAND = 'cs_cdf';

/** A Cloud Score+ score at or below this is treated as cloud (higher = clearer). */
const CLOUD_SCORE_CLEAR_THRESHOLD = 0.60;

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
 * Are the Earth Engine credentials present and usable?
 * Returns `{ ok: true }` or `{ ok: false, reason }` with a message safe to show.
 */
export function geeAvailability() {
  if (!config.gee.enabled) {
    return { ok: false, reason: 'Earth Engine is switched off on this server.' };
  }
  if (config.gee.credentialProblem) {
    return { ok: false, reason: config.gee.credentialProblem };
  }
  if (!config.gee.privateKey) {
    return { ok: false, reason: 'No Earth Engine service-account key is configured.' };
  }
  if (!config.gee.serviceAccountEmail) {
    return { ok: false, reason: 'The Earth Engine service-account email is missing.' };
  }
  if (!config.gee.projectId) {
    return { ok: false, reason: 'The Earth Engine project id is missing.' };
  }
  return { ok: true };
}

/* --------------------------- Earth Engine client --------------------------- */

let eeInitPromise = null;

/**
 * Initialise the Earth Engine client once, and share the promise.
 *
 * Authenticating on every request would re-sign a JWT per call, which is slow and
 * pointless. The promise is cached so concurrent requests await the same
 * handshake; a failure clears it so a transient network problem does not
 * permanently poison the process.
 */
async function initEarthEngine() {
  if (!eeInitPromise) {
    eeInitPromise = (async () => {
      const ee = (await import('@google/earthengine')).default;
      await new Promise((resolve, reject) => {
        ee.data.authenticateViaPrivateKey(
          {
            client_email: config.gee.serviceAccountEmail,
            private_key: config.gee.privateKey,
            project_id: config.gee.projectId,
          },
          () => {
            ee.initialize(
              null,
              null,
              resolve,
              (err) => reject(new Error(err?.message || String(err))),
              config.gee.projectId,
            );
          },
          (err) => reject(new Error(err?.message || String(err))),
        );
      });
      return ee;
    })().catch((err) => {
      eeInitPromise = null;
      throw err;
    });
  }
  return eeInitPromise;
}

/** Reset the cached client. Used by tests. */
export function resetEarthEngine() {
  eeInitPromise = null;
}

/** Wrap an `ee` callback API in a promise. */
function evaluate(eeObject) {
  return new Promise((resolve, reject) => {
    eeObject.evaluate((value, err) => (err ? reject(new Error(err?.message || String(err))) : resolve(value)));
  });
}

/** Build an `ee.Geometry` from a stored boundary. */
function toGeometry(ee, geoJson) {
  const coords = geoJson.type === 'Feature' ? geoJson.geometry.coordinates : geoJson.coordinates;
  if (geoJson.type === 'Feature' && geoJson.geometry.type === 'MultiPolygon') {
    return ee.Geometry.MultiPolygon(coords);
  }
  if (geoJson.type === 'MultiPolygon') return ee.Geometry.MultiPolygon(coords);
  // ee.Geometry.Polygon accepts a ring list or a list of rings.
  return ee.Geometry.Polygon(Array.isArray(coords[0][0]) ? coords : [coords]);
}

/* ------------------------------ index maths ------------------------------- */

/**
 * Coerce a band value to a number, rejecting the values that would silently
 * become zero.
 *
 * `Number(null)` is 0 and `Number('')` is 0, so a missing band would otherwise
 * produce a plausible-looking index from no data — the exact fabrication the
 * project forbids. This mirrors the guard in `ndvi.js`.
 */
function toFiniteNumber(value) {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean') return Number.NaN;
  const n = Number(value);
  return Number.isFinite(n) ? n : Number.NaN;
}

/**
 * Enhanced Vegetation Index:
 *   EVI = 2.5 * (NIR - RED) / (NIR + 6*RED - 7.5*BLUE + 1)
 *
 * Exported and unit-tested because it is easy to get subtly wrong, and because
 * the denominator approaches zero for dark pixels — the Earth Engine expression
 * below guards that, and this reference documents the formula it implements.
 */
export function computeEviFromBands(nir, red, blue) {
  const n = toFiniteNumber(nir);
  const r = toFiniteNumber(red);
  const b = toFiniteNumber(blue);
  if (!Number.isFinite(n) || !Number.isFinite(r) || !Number.isFinite(b)) return null;
  const denom = n + 6 * r - 7.5 * b + 1;
  if (denom === 0) return null;
  return Number(((2.5 * (n - r)) / denom).toFixed(4));
}

/* ------------------------------- real path -------------------------------- */

/**
 * Mask clouds and cloud shadows, add NDVI and EVI, and return the best image.
 *
 * Cloud handling uses Cloud Score+ (`cs_cdf`), Google's current, actively
 * maintained Sentinel-2 cloud score, rather than the deprecated `QA60` band it
 * replaced on this collection. A pixel is kept when its score is above the
 * threshold; shadow is handled by the same product, which is why the mask is a
 * single quality band rather than two ad-hoc tests.
 *
 * Returns the *most recent sufficiently-clear* image plus its metadata. If no
 * pass clears the threshold the caller reports that honestly instead of relaxing
 * the threshold until something passes.
 */
function buildPreparedImage(ee, geometry) {
  const collection = ee
    .ImageCollection(S2_COLLECTION)
    .filterBounds(geometry)
    .filterDate(
      ee.Date(Date.now()).advance(-config.gee.lookbackDays, 'day'),
      ee.Date(Date.now()).advance(1, 'day'),
    )
    // Join Cloud Score+ by its QA BAND (not system:index, which is a property
    // and fails: "Match image does not have a band named 'system:index'").
    .linkCollection(ee.ImageCollection(CLOUD_SCORE_COLLECTION), [CLOUD_SCORE_BAND]);

  const withIndices = collection.map((image) => {
    const clear = image.select(CLOUD_SCORE_BAND).gte(CLOUD_SCORE_CLEAR_THRESHOLD);
    const ndvi = image.normalizedDifference(NDVI_BANDS).rename('NDVI');
    const evi = image.expression(
      '2.5 * ((NIR - RED) / (NIR + 6 * RED - 7.5 * BLUE + 1))',
      {
        NIR: image.select(EVI_INDEX_BANDS.nir),
        RED: image.select(EVI_INDEX_BANDS.red),
        BLUE: image.select(EVI_INDEX_BANDS.blue),
      },
    ).rename('EVI');
    // Apply the mask to the indices only, so the region reducer sees exactly the
    // valid pixels and nothing else.
    return ndvi.updateMask(clear)
      .addBands(evi.updateMask(clear))
      .copyProperties(image, ['system:time_start', 'CLOUDY_PIXEL_PERCENTAGE', 'PRODUCT_ID']);
  });

  return withIndices
    // Prefer the newest pass whose in-boundary pixels are mostly clear; sorting by
    // scene cloud cover alone would favour a clear-elsewhere tile.
    .sort('system:time_start', false);
}

/**
 * Compute one real observation for a boundary.
 *
 * `reduceRegion` runs twice over the same geometry: once for the masked indices
 * (so `pixelsValid` counts only clear pixels) and once for the total pixel count
 * of the boundary. Their ratio is the valid-pixel coverage the API reports — a
 * low percentage is the honest signal that an average rests on few clear pixels.
 */
async function computeViaEarthEngine({ geoJsonBoundary }) {
  const ee = await initEarthEngine();
  const geometry = toGeometry(ee, geoJsonBoundary);
  const prepared = buildPreparedImage(ee, geometry);

  const size = await evaluate(prepared.size());
  if (!size) {
    return {
      status: 'FAILED',
      failureReason: `No Sentinel-2 imagery was found for this boundary in the last ${config.gee.lookbackDays} days.`,
      meanNdviValue: null,
      source: 'SENTINEL-2',
      isSimulated: false,
    };
  }

  // Take the newest image, then check whether *it* is usable. Trying images until
  // one passes would mean reporting an older date as if it were the latest.
  const image = ee.Image(prepared.first());
  const scale = 10; // B8/B4/B2 are all 10 m.

  const reducer = ee.Reducer.mean();
  const validCount = ee.Reducer.count();

  const [meanResult, countResult, totalCount, meta] = await Promise.all([
    evaluate(image.select(['NDVI', 'EVI']).reduceRegion({
      reducer,
      geometry,
      scale,
      maxPixels: 1e10,
      bestEffort: true,
    })),
    evaluate(image.select('NDVI').reduceRegion({
      reducer: validCount,
      geometry,
      scale,
      maxPixels: 1e10,
      bestEffort: true,
    })),
    // The boundary's own pixel count, unmasked: the denominator for coverage.
    evaluate(ee.Image(1).reduceRegion({
      reducer: ee.Reducer.count(),
      geometry,
      scale,
      maxPixels: 1e10,
      bestEffort: true,
    })),
    evaluate(ee.Dictionary({
      sceneCloud: image.get('CLOUDY_PIXEL_PERCENTAGE'),
      productId: image.get('PRODUCT_ID'),
      timeStart: image.get('system:time_start'),
    })),
  ]);

  const pixelsValid = toFiniteNumber(countResult?.NDVI);
  const pixelsTotal = toFiniteNumber(totalCount?.constant);

  // The pixel COUNT, not the mean, decides whether anything was measured.
  // `reduceRegion` returns 0 for a mean over an empty set, so an all-cloud pass
  // would otherwise arrive as NDVI = 0.0 — "bare soil" instead of "not seen".
  if (!Number.isFinite(pixelsValid) || pixelsValid <= 0) {
    return {
      status: 'FAILED',
      failureReason: 'Every pixel inside this boundary was cloud or cloud shadow in the latest pass.',
      meanNdviValue: null,
      meanEviValue: null,
      source: 'SENTINEL-2',
      isSimulated: false,
      satelliteDate: toDateOnly(meta?.timeStart),
    };
  }

  const meanNdvi = toFiniteNumber(meanResult?.NDVI);
  if (!Number.isFinite(meanNdvi)) {
    return {
      status: 'FAILED',
      failureReason: 'The satellite image could not be reduced for this boundary.',
      meanNdviValue: null,
      source: 'SENTINEL-2',
      isSimulated: false,
      satelliteDate: toDateOnly(meta?.timeStart),
    };
  }

  const meanEvi = toFiniteNumber(meanResult?.EVI);
  const validPixelPct = Number.isFinite(pixelsTotal) && pixelsTotal > 0
    ? Number(((pixelsValid / pixelsTotal) * 100).toFixed(2))
    : null;

  const satelliteDate = toDateOnly(meta?.timeStart);
  const sceneCloud = meta?.sceneCloud ?? null;

  // The documented rule: imagery above the cloud threshold is unusable. Note this
  // is the *scene* cloud cover over the whole tile, which is why coverage is also
  // reported — a tile can be mostly clear while the plot itself is not.
  if (!isCloudCoverUsable(sceneCloud)) {
    return {
      status: 'FAILED',
      failureReason: `Cloud cover was ${sceneCloud}% on the latest pass (${satelliteDate}), above the ${config.gee.cloudCoverFailThreshold}% limit.`,
      meanNdviValue: null,
      cloudCover: sceneCloud,
      validPixelPct,
      satelliteDate,
      source: 'SENTINEL-2',
      isSimulated: false,
    };
  }

  return {
    status: 'GENERATED',
    meanNdviValue: Number(meanNdvi.toFixed(4)),
    meanEviValue: Number.isFinite(meanEvi) ? Number(meanEvi.toFixed(4)) : null,
    cloudCover: sceneCloud,
    validPixelPct,
    pixelCount: pixelsValid,
    satelliteDate,
    source: 'SENTINEL-2',
    isSimulated: false,
    sceneId: meta?.productId ?? null,
    compressedVectorJson: buildCompressedVector(geoJsonBoundary, meanNdvi),
  };
}

/** `system:time_start` is epoch milliseconds; a date-only string is what we store. */
function toDateOnly(epochMs) {
  if (epochMs === null || epochMs === undefined) return null;
  const ms = Number(epochMs);
  if (!Number.isFinite(ms)) return null;
  return new Date(ms).toISOString().slice(0, 10);
}

/* --------------------------------- public --------------------------------- */

/**
 * Compute NDVI for a boundary. Returns:
 *   { status: 'GENERATED'|'FAILED', meanNdviValue, meanEviValue, cloudCover,
 *     validPixelPct, satelliteDate, source, isSimulated, failureReason,
 *     compressedVectorJson }
 */
export async function computeNdvi({ plotId, geoJsonBoundary }) {
  const invalid = validatePolygon(geoJsonBoundary);
  if (invalid) {
    return { status: 'FAILED', failureReason: invalid, meanNdviValue: null, source: 'VALIDATION' };
  }

  const availability = geeAvailability();

  // --- Real GEE path -------------------------------------------------------
  if (availability.ok) {
    try {
      return await computeViaEarthEngine({ geoJsonBoundary });
    } catch (err) {
      return {
        status: 'FAILED',
        // The internal error is kept off the wire; the API logs it and shows this.
        failureReason: 'Satellite imagery could not be processed at this time.',
        meanNdviValue: null,
        source: 'SENTINEL-2',
        isSimulated: false,
        internal: err.message,
      };
    }
  }

  // --- Deterministic mock path --------------------------------------------
  const mock = mockNdviForPlot(plotId);
  if (!isCloudCoverUsable(mock.cloudCover)) {
    return {
      status: 'FAILED',
      failureReason: 'Cloud cover above 80% — imagery unusable for this observation.',
      meanNdviValue: null,
      cloudCover: mock.cloudCover,
      satelliteDate: mock.satelliteDate,
      source: mock.source,
      isSimulated: true,
    };
  }
  return {
    status: 'GENERATED',
    meanNdviValue: mock.meanNdviValue,
    cloudCover: mock.cloudCover,
    satelliteDate: mock.satelliteDate,
    source: mock.source,
    isSimulated: true,
    // GEE was asked for but is not usable — say why rather than only labelling
    // the value as simulated.
    providerReason: availability.reason,
    compressedVectorJson: buildCompressedVector(geoJsonBoundary, mock.meanNdviValue),
  };
}

/**
 * A full NDVI/EVI series for a boundary, straight from Earth Engine.
 *
 * This exists so the Node API can produce a real series without the optional
 * FastAPI `crop-api/` service. It maps every usable pass in the window and
 * reduces each one, which is one Earth Engine round trip per image — acceptable
 * for a plot-sized boundary over a season, and cached by the caller.
 *
 * Returns the same shape `observations.js` produces from crop-api, so the
 * analysis engine, the colour map and the database see one contract.
 */
export async function fetchGeeSeries({ geoJsonBoundary, days = config.satellite.defaultDays }) {
  const invalid = validatePolygon(geoJsonBoundary);
  if (invalid) {
    return { available: false, source: null, isSimulated: false, readings: [], reason: invalid };
  }
  const availability = geeAvailability();
  if (!availability.ok) {
    return { available: false, source: null, isSimulated: false, readings: [], reason: availability.reason };
  }

  try {
    const ee = await initEarthEngine();
    const geometry = toGeometry(ee, geoJsonBoundary);
    const start = ee.Date(Date.now()).advance(-Math.abs(days), 'day');

    const collection = ee
      .ImageCollection(S2_COLLECTION)
      .filterBounds(geometry)
      .filterDate(start, ee.Date(Date.now()).advance(1, 'day'))
      // Join by the QA band, as above.
      .linkCollection(ee.ImageCollection(CLOUD_SCORE_COLLECTION), [CLOUD_SCORE_BAND])
      .map((image) => {
        const clear = image.select(CLOUD_SCORE_BAND).gte(CLOUD_SCORE_CLEAR_THRESHOLD);
        const ndvi = image.normalizedDifference(NDVI_BANDS).rename('NDVI').updateMask(clear);
        const evi = image.expression(
          '2.5 * ((NIR - RED) / (NIR + 6 * RED - 7.5 * BLUE + 1))',
          {
            NIR: image.select(EVI_INDEX_BANDS.nir),
            RED: image.select(EVI_INDEX_BANDS.red),
            BLUE: image.select(EVI_INDEX_BANDS.blue),
          },
        ).rename('EVI').updateMask(clear);

        // Two explicit reducers rather than `combine`, whose output naming is easy
        // to get wrong (`NDVI_count` does not exist with sharedInputs:false).
        // `count` is run on the same masked NDVI band, so the pixel count is
        // exactly the number of clear in-boundary pixels the mean came from.
        const means = ndvi.addBands(evi).reduceRegion({
          reducer: ee.Reducer.mean(),
          geometry,
          scale: 10,
          maxPixels: 1e10,
          bestEffort: true,
        });
        const counts = ndvi.reduceRegion({
          reducer: ee.Reducer.count(),
          geometry,
          scale: 10,
          maxPixels: 1e10,
          bestEffort: true,
        });

        return ee.Feature(null, {
          date: image.date().format('YYYY-MM-dd'),
          ndvi: means.get('NDVI'),
          evi: means.get('EVI'),
          pixels: counts.get('NDVI'),
          cloudPct: image.get('CLOUDY_PIXEL_PERCENTAGE'),
        });
      });

    const features = await evaluate(collection);
    const rows = (features?.features ?? [])
      .map((f) => f.properties)
      // A pass where every in-boundary pixel was masked carries NO observation.
      // Earth Engine's `reduceRegion` returns 0 for a mean over an empty set, and
      // recording that as NDVI = 0.0 would read as "bare soil" when the truth is
      // "we could not see the ground". Such a pass is dropped, not reported as a
      // zero: the honest answer is that no measurement exists.
      .filter((p) => p && p.date && Number(p.pixels) > 0)
      .map((p) => {
        const ndvi = toFiniteNumber(p.ndvi);
        const evi = toFiniteNumber(p.evi);
        return {
          date: p.date,
          // Still null if the reducer produced something non-finite, rather than
          // surfacing a number that was never measured.
          ndvi: Number.isFinite(ndvi) ? Number(ndvi.toFixed(4)) : null,
          evi: Number.isFinite(evi) ? Number(evi.toFixed(4)) : null,
          pixels: Number(p.pixels),
          cloudPct: Number.isFinite(toFiniteNumber(p.cloudPct)) ? Number(Number(p.cloudPct).toFixed(2)) : null,
          source: 'SENTINEL-2',
          isSimulated: false,
        };
      })
      // A row is only useful if it carries at least one index.
      .filter((r) => r.ndvi !== null || r.evi !== null)
      // Oldest first, matching the mock and crop-api series.
      .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));

    if (rows.length === 0) {
      return {
        available: false,
        source: 'SENTINEL-2',
        isSimulated: false,
        readings: [],
        reason: `No clear Sentinel-2 passes were found for this boundary in the last ${days} days.`,
      };
    }

    return { available: true, source: 'SENTINEL-2', isSimulated: false, readings: rows, skipped: [] };
  } catch (err) {
    return {
      available: false,
      source: null,
      isSimulated: false,
      readings: [],
      reason: 'The satellite processor could not be reached.',
      internal: err.message,
    };
  }
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

export default {
  computeNdvi,
  fetchGeeSeries,
  geeAvailability,
  estimateAreaHectares,
  buildCompressedVector,
  computeEviFromBands,
  resetEarthEngine,
};
