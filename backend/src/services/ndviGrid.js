/**
 * NDVI/EVI colour map: a per-pixel grid of a plot, for the "satellite sight" view.
 *
 * ## The honesty problem this module exists to solve
 *
 * The pipeline measures **one number per date per plot** — the mean vegetation
 * index of the pixels inside the boundary. It does not, and without a configured
 * Sentinel-2 processor cannot, return a per-pixel raster. So there are only two
 * truthful things this module may do:
 *
 *   1. when a real per-pixel array is supplied, plot it as-is; or
 *   2. when only a plot-level mean is available, build a **texture** that preserves
 *      that mean exactly and label the texture as simulated.
 *
 * It does (2) in `buildGrid`. The consequence is stated in the response and must be
 * shown in the UI: the **average of this grid is the real measured number** (the
 * grid is constrained so its mean equals it), while the **pattern across the grid
 * is a deterministic artefact of `plotId + date`**, not an observation. Presenting
 * the pattern as a real image would be a fabricated reading, which the project
 * forbids. Hence `isSimulated` / `variationSimulated` on every response.
 *
 * The texture is deterministic, so the same plot and date always draw the same
 * picture — reloading is not a different field.
 */

import { createHash } from 'node:crypto';

import { projectToMetres, ringFromGeoJson } from './geometry.js';

/** Grid resolution. 24×24 is legible on a phone and small enough to inline. */
export const DEFAULT_GRID_SIZE = 24;

/** The index ranges the colour scale is built on (matches the analysis engine). */
export const NDVI_MIN = -0.1;
export const NDVI_MAX = 0.9;

/* ------------------------------- helpers --------------------------------- */

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function round(value, places = 4) {
  if (value === null || value === undefined) return null;
  const factor = 10 ** places;
  return Math.round(value * factor) / factor;
}

/** Deterministic 32-bit PRNG; the same seed always yields the same sequence. */
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

/** A stable seed from the plot and the date, so a reload is not a new field. */
export function seedFrom(plotId, date) {
  return createHash('sha256').update(`${plotId}|${date}`).digest().readUInt32BE(0);
}

/**
 * Classic even-odd point-in-polygon test on the projected plane.
 * `poly` is a list of `{x, y}`; returns true when the point is inside.
 */
export function isInsideRing(x, y, poly) {
  let inside = false;
  for (let i = 0, j = poly.length - 1; i < poly.length; j = i, i += 1) {
    const a = poly[i];
    const b = poly[j];
    const straddles = (a.y > y) !== (b.y > y);
    if (straddles && x < ((b.x - a.x) * (y - a.y)) / (b.y - a.y) + a.x) {
      inside = !inside;
    }
  }
  return inside;
}

/* ------------------------------ the grid --------------------------------- */

/**
 * Build the colour-map grid for one plot and date.
 *
 * @param {object} options
 * @param {string} options.plotId
 * @param {string} options.date                ISO `YYYY-MM-DD` of the observation.
 * @param {object} options.geoJsonBoundary     The stored boundary.
 * @param {number} options.meanValue           The measured mean index for that date.
 * @param {string} [options.index]             `ndvi` or `evi`, for labelling only.
 * @param {number} [options.size]              Grid resolution per side.
 * @param {number|null} [options.validPixelPct] Coverage, carried through for display.
 * @param {boolean} [options.isSimulated]      True when the *mean* itself is mock data.
 * @returns {{grid: number[][], values: number[][], stats: object, coverage: object}|null}
 *          null when the boundary or the mean is unusable.
 */
export function buildGrid({
  plotId,
  date,
  geoJsonBoundary,
  meanValue,
  index = 'ndvi',
  size = DEFAULT_GRID_SIZE,
  validPixelPct = null,
  isSimulated = false,
} = {}) {
  const ring = ringFromGeoJson(geoJsonBoundary);
  const mean = finite(meanValue);
  if (!ring || ring.length < 3 || mean === null) return null;

  const bounded = Math.max(8, Math.min(48, Math.trunc(size)));
  const projected = projectToMetres(ring);

  // Bounding box on the local plane, then a square cell grid that covers it. The
  // cell size is the larger of the two spans so cells stay square on screen.
  const xs = projected.map((p) => p.x);
  const ys = projected.map((p) => p.y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const spanX = Math.max(maxX - minX, 1e-6);
  const spanY = Math.max(maxY - minY, 1e-6);
  const cell = Math.max(spanX, spanY) / bounded;

  // One pass over the grid: sample the texture for every cell and note which
  // cells fall inside the boundary. The texture is drawn for *every* cell (not
  // only the inside ones) so the pattern does not depend on the plot shape,
  // which would make two identical plots look different.
  const random = seededRandom(seedFrom(plotId, date));
  const cells = [];
  const rawInside = [];
  for (let row = 0; row < bounded; row += 1) {
    const line = [];
    for (let col = 0; col < bounded; col += 1) {
      const x = minX + (col + 0.5) * cell;
      const y = minY + (row + 0.5) * cell;
      const r = random();
      const within = isInsideRing(x, y, projected);
      if (within) rawInside.push(r);
      line.push({ within, r });
    }
    cells.push(line);
  }

  if (rawInside.length === 0) {
    // The boundary is too small for even one cell: report it rather than draw a
    // blank square, which would read as "no vegetation".
    return {
      grid: [],
      values: [],
      size: bounded,
      stats: null,
      coverage: { insideCells: 0, totalCells: bounded * bounded, note: 'The plot is too small to resolve at this grid size.' },
      note: 'No grid cells fall inside the boundary.',
    };
  }

  const rawMean = rawInside.reduce((sum, v) => sum + v, 0) / rawInside.length;
  const rawSpread = Math.sqrt(
    rawInside.reduce((sum, v) => sum + (v - rawMean) ** 2, 0) / rawInside.length,
  ) || 1e-6;

  // How much spatial variation to show. Real fields vary; a plot-level mean has no
  // spread of its own, so a modest, documented amplitude is applied. It is capped
  // so no cell invents an extreme reading the mean does not support.
  //
  // The amplitude is then squeezed further if the requested spread would push any
  // cell past the scale limits: clamping would flatten those cells and silently
  // push the grid average away from the measured mean, which is the one thing this
  // grid must not do. So the texture is scaled to whatever amplitude still fits
  // inside the scale, ensuring the inside-cell mean equals `mean` whenever the
  // amplitude is non-zero.
  const maxZ = Math.max(...rawInside.map((r) => Math.abs((r - rawMean) / rawSpread)), 1e-6);
  const room = Math.min(mean - NDVI_MIN, NDVI_MAX - mean);
  const amplitude = room > 0 ? Math.min(0.18, room / maxZ) : 0;

  const values = cells.map((line) =>
    line.map((cellData) => {
      if (!cellData.within) return null;
      // Normalise the texture to zero mean / unit spread, scale it, and centre it
      // on the measured mean. The inside-cell average stays exactly `mean`.
      const z = (cellData.r - rawMean) / rawSpread;
      const value = mean + z * amplitude;
      return round(Math.max(NDVI_MIN, Math.min(NDVI_MAX, value)));
    }),
  );

  const flat = values.flat().filter((v) => v !== null);
  const gridMean = flat.reduce((sum, v) => sum + v, 0) / flat.length;
  const min = Math.min(...flat);
  const max = Math.max(...flat);

  return {
    grid: values,
    values,
    size: bounded,
    index,
    stats: {
      // `mean` is the measured value; `min`/`max` are properties of the simulated
      // texture and are labelled as such by `variationSimulated`.
      mean: round(mean),
      gridMean: round(gridMean),
      min: round(min),
      max: round(max),
      insideCells: flat.length,
      spread: round(max - min),
    },
    coverage: {
      insideCells: flat.length,
      totalCells: bounded * bounded,
      validPixelPct: finite(validPixelPct),
    },
    isSimulated,
    variationSimulated: true,
    note:
      'Cell brightness is a simulated spatial texture: the grid is scaled so its average is the measured '
      + `plot mean (${round(mean)}) for this date, and the pattern across it is generated from the plot and date, `
      + 'not observed per pixel. Read the average, not the pattern.',
  };
}

/**
 * Build a colour-map response for the most recent observation of a series.
 *
 * @param {object} options
 * @param {{plotId: string, geoJsonBoundary: object}} options.plot
 * @param {Array<{date: string, value: number|null, validPct?: number|null, isSimulated?: boolean}>} options.series
 * @param {string} [options.index]
 * @param {number} [options.size]
 * @returns {object} a response body that always states its own honesty flags.
 */
export function buildColourMap({ plot, series, index = 'ndvi', size = DEFAULT_GRID_SIZE, date = null } = {}) {
  const usable = Array.isArray(series)
    ? series.filter((row) => finite(row?.value) !== null).sort((a, b) => String(a.date).localeCompare(String(b.date)))
    : [];

  // Every date the stored series actually has, so the UI can offer a real date
  // picker instead of guessing. Oldest first, matching the series order.
  const availableDates = usable.map((row) => String(row.date).slice(0, 10));

  if (!plot?.geoJsonBoundary) {
    return {
      available: false,
      index,
      availableDates,
      reason: 'This plot has no saved boundary, so there is nothing to draw.',
    };
  }
  if (usable.length === 0) {
    return {
      available: false,
      index,
      availableDates,
      reason: 'No observations are available for this plot yet, so there is no image to show.',
    };
  }

  // Pick the requested date when one was asked for; otherwise the newest reading.
  // A requested date that is not in the series is refused outright — falling back
  // to the nearest day would show a different observation under the requested
  // label, which is exactly the kind of fabrication this module exists to prevent.
  const wanted = date === null || date === undefined ? null : String(date).slice(0, 10);
  const selected = wanted === null
    ? usable[usable.length - 1]
    : usable.find((row) => String(row.date).slice(0, 10) === wanted);

  if (!selected) {
    return {
      available: false,
      index,
      availableDates,
      requestedDate: wanted,
      reason: `There is no stored observation for ${wanted}. Pick one of the dates the plot actually has.`,
    };
  }

  const latest = usable[usable.length - 1];
  const built = buildGrid({
    plotId: plot.plotId,
    date: selected.date,
    geoJsonBoundary: plot.geoJsonBoundary,
    meanValue: selected.value,
    index,
    size,
    validPixelPct: selected.validPct ?? null,
    isSimulated: Boolean(selected.isSimulated),
  });

  if (!built) {
    return {
      available: false,
      index,
      availableDates,
      requestedDate: wanted,
      reason: 'The boundary is too small or too irregular to render a colour map.',
    };
  }

  return {
    available: true,
    index,
    plotId: plot.plotId,
    observedOn: String(selected.date).slice(0, 10),
    // True when this is not the newest reading — the UI says so rather than
    // letting an old observation pass as the current picture.
    isHistorical: String(selected.date).slice(0, 10) !== String(latest.date).slice(0, 10),
    newestObservedOn: String(latest.date).slice(0, 10),
    availableDates,
    // The real, measured number behind the picture.
    meanValue: round(selected.value),
    isSimulated: Boolean(selected.isSimulated),
    variationSimulated: true,
    grid: built.grid,
    size: built.size,
    stats: built.stats,
    coverage: built.coverage,
    scale: { min: NDVI_MIN, max: NDVI_MAX },
    legend: buildLegend(index),
    note: built.note,
  };
}

/** Plain-language legend stops for the colour scale. */
export function buildLegend(index = 'ndvi') {
  const label = index === 'evi' ? 'EVI' : 'NDVI';
  return [
    { from: -0.1, to: 0.2, colour: '#8B2E2E', label: 'Bare soil / very sparse cover' },
    { from: 0.2, to: 0.35, colour: '#C4703A', label: 'Sparse or stressed vegetation' },
    { from: 0.35, to: 0.5, colour: '#D9C15A', label: 'Moderate cover' },
    { from: 0.5, to: 0.65, colour: '#7FA85A', label: 'Healthy, growing cover' },
    { from: 0.65, to: 0.9, colour: '#2E7D4F', label: `Dense, vigorous ${label} canopy` },
  ];
}

export default { buildGrid, buildColourMap, buildLegend, seedFrom, isInsideRing };
