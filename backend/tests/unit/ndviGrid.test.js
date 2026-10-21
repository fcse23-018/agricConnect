/**
 * The colour map's whole justification is an honesty claim: the *average* of the
 * grid equals the *measured* plot mean, and the *pattern* is labelled as
 * simulated. These tests exist to hold that claim to account. If mean
 * preservation breaks, the picture starts telling farmers a per-pixel story the
 * data does not support, so this is the most important test file in the feature.
 */

import { buildGrid, buildColourMap, buildLegend, seedFrom, isInsideRing } from '../../src/services/ndviGrid.js';

/** A ~1 km square boundary, in the shape the API stores boundaries in. */
const square = (lat = -24.65, lon = 25.91, side = 0.009) => ({
  type: 'Polygon',
  coordinates: [[
    [lon, lat],
    [lon + side, lat],
    [lon + side, lat + side],
    [lon, lat + side],
    [lon, lat],
  ]],
});

/** Mean of the non-null cells, i.e. what a user effectively sees inside the plot. */
const meanOfGrid = (grid) => {
  const flat = grid.flat().filter((v) => v !== null);
  return flat.reduce((s, v) => s + v, 0) / flat.length;
};

describe('ndviGrid.isInsideRing', () => {
  it('detects points inside and outside a square', () => {
    const poly = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
      { x: 0, y: 10 },
    ];
    expect(isInsideRing(5, 5, poly)).toBe(true);
    expect(isInsideRing(15, 5, poly)).toBe(false);
    expect(isInsideRing(-1, -1, poly)).toBe(false);
  });
});

describe('ndviGrid.seedFrom', () => {
  it('is stable for the same plot and date', () => {
    expect(seedFrom('p1', '2026-01-01')).toBe(seedFrom('p1', '2026-01-01'));
  });

  it('differs across plots and across dates', () => {
    expect(seedFrom('p1', '2026-01-01')).not.toBe(seedFrom('p2', '2026-01-01'));
    expect(seedFrom('p1', '2026-01-01')).not.toBe(seedFrom('p1', '2026-01-02'));
  });
});

describe('ndviGrid.buildGrid', () => {
  it('returns null rather than a picture when the boundary is unusable', () => {
    expect(buildGrid({ plotId: 'p1', date: '2026-01-01', geoJsonBoundary: null, meanValue: 0.5 })).toBeNull();
    expect(buildGrid({ plotId: 'p1', date: '2026-01-01', geoJsonBoundary: square(), meanValue: null })).toBeNull();
  });

  it('returns null when the mean is not a number', () => {
    expect(buildGrid({ plotId: 'p1', date: '2026-01-01', geoJsonBoundary: square(), meanValue: 'abc' })).toBeNull();
  });

  // THE central guarantee.
  it('preserves the measured mean as the average of the in-boundary cells', () => {
    for (const mean of [-0.05, 0.12, 0.3, 0.5, 0.72, 0.88]) {
      const built = buildGrid({ plotId: 'plot-mean', date: '2026-05-10', geoJsonBoundary: square(), meanValue: mean });
      expect(built).not.toBeNull();
      // Rounded cells, so allow a hairline of rounding drift.
      expect(Math.abs(meanOfGrid(built.grid) - mean)).toBeLessThan(0.005);
      expect(Math.abs(built.stats.mean - mean)).toBeLessThan(1e-6);
    }
  });

  it('marks outside cells as null so the shape is visible in the data', () => {
    const built = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    const flat = built.grid.flat();
    expect(flat.some((v) => v === null)).toBe(true); // the grid is square, the plot is not
    expect(flat.some((v) => v !== null)).toBe(true);
  });

  it('keeps every value inside the documented colour scale', () => {
    const built = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    for (const v of built.grid.flat()) {
      if (v === null) continue;
      expect(v).toBeGreaterThanOrEqual(-0.1);
      expect(v).toBeLessThanOrEqual(0.9);
    }
  });

  it('is deterministic: the same plot and date draw the same picture', () => {
    const a = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    const b = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    expect(a.grid).toEqual(b.grid);
  });

  it('draws a different pattern for a different date', () => {
    const a = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    const b = buildGrid({ plotId: 'p1', date: '2026-06-10', geoJsonBoundary: square(), meanValue: 0.5 });
    expect(a.grid).not.toEqual(b.grid);
  });

  it('always declares that the variation is simulated', () => {
    const built = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    expect(built.variationSimulated).toBe(true);
    expect(built.note).toMatch(/simulated/i);
    expect(built.note).toMatch(/not observed per pixel/i);
  });

  it('carries the caller\'s isSimulated flag through, and defaults it to false', () => {
    const plain = buildGrid({ plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5 });
    expect(plain.isSimulated).toBe(false);
    const mocked = buildGrid({
      plotId: 'p1', date: '2026-05-10', geoJsonBoundary: square(), meanValue: 0.5, isSimulated: true,
    });
    expect(mocked.isSimulated).toBe(true);
  });

  it('clamps the grid resolution into a sane range', () => {
    const tiny = buildGrid({ plotId: 'p1', date: 'd', geoJsonBoundary: square(), meanValue: 0.5, size: 1 });
    expect(tiny.size).toBe(8);
    const huge = buildGrid({ plotId: 'p1', date: 'd', geoJsonBoundary: square(), meanValue: 0.5, size: 999 });
    expect(huge.size).toBe(48);
  });

  it('reports a degraded result (not a blank square) when no cell centre lands in the plot', () => {
    // A two-point sliver: after `ringFromGeoJson` drops the repeated closing
    // vertex it cannot form a ring, so there is nothing to draw. The honest answer
    // is a stated reason, never a blank square that would read as "no vegetation".
    const sliver = { type: 'Polygon', coordinates: [[[25.9, -24.6], [25.9001, -24.6]]] };
    expect(buildGrid({ plotId: 'sliver', date: 'd', geoJsonBoundary: sliver, meanValue: 0.5 })).toBeNull();
  });

  it('reports the inside-cell coverage alongside the render', () => {
    const built = buildGrid({
      plotId: 'p1', date: 'd', geoJsonBoundary: square(), meanValue: 0.5, validPixelPct: 63.5,
    });
    expect(built.coverage.validPixelPct).toBe(63.5);
    expect(built.coverage.insideCells).toBeGreaterThan(0);
    expect(built.coverage.totalCells).toBe(built.size * built.size);
  });
});

describe('ndviGrid.buildColourMap', () => {
  const plot = { plotId: 'p1', geoJsonBoundary: square() };

  it('refuses to draw when there is no boundary', () => {
    const r = buildColourMap({ plot: { plotId: 'p1', geoJsonBoundary: null }, series: [{ date: '2026-01-01', value: 0.4 }] });
    expect(r.available).toBe(false);
    expect(r.reason).toMatch(/boundary/i);
  });

  it('refuses to draw when there are no observations, rather than showing zeros', () => {
    const r = buildColourMap({ plot, series: [] });
    expect(r.available).toBe(false);
    expect(r.reason).toMatch(/no observations/i);
  });

  it('ignores rows with a null value when choosing the latest date', () => {
    const r = buildColourMap({
      plot,
      series: [
        { date: '2026-01-01', value: 0.4 },
        { date: '2026-02-01', value: null },
      ],
    });
    expect(r.available).toBe(true);
    expect(r.observedOn).toBe('2026-01-01');
    expect(r.meanValue).toBe(0.4);
  });

  it('uses the most recent usable observation whatever order the series arrives in', () => {
    const r = buildColourMap({
      plot,
      series: [
        { date: '2026-03-01', value: 0.6 },
        { date: '2026-01-01', value: 0.2 },
        { date: '2026-02-01', value: 0.4 },
      ],
    });
    expect(r.observedOn).toBe('2026-03-01');
    expect(r.meanValue).toBe(0.6);
  });

  it('carries the mock flag so the UI can warn the mean itself is simulated', () => {
    const r = buildColourMap({ plot, series: [{ date: '2026-01-01', value: 0.4, isSimulated: true }] });
    expect(r.isSimulated).toBe(true);
    expect(r.variationSimulated).toBe(true);
  });

  it('returns a legend and a scale with the grid', () => {
    const r = buildColourMap({ plot, series: [{ date: '2026-01-01', value: 0.4 }] });
    expect(r.scale).toEqual({ min: -0.1, max: 0.9 });
    expect(r.legend.length).toBeGreaterThanOrEqual(3);
    expect(r.legend.every((s) => typeof s.colour === 'string' && s.label)).toBe(true);
  });
});

describe('ndviGrid.buildLegend', () => {
  it('names the index in the strongest stop', () => {
    expect(buildLegend('ndvi').at(-1).label).toMatch(/NDVI/);
    expect(buildLegend('evi').at(-1).label).toMatch(/EVI/);
  });
});
