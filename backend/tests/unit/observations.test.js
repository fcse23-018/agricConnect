import { describe, it, expect, beforeEach, afterEach } from '@jest/globals';

import {
  boundaryCorners,
  boundaryCentre,
  mockSeries,
  fetchObservations,
  MOCK_SOURCE,
  GEE_SOURCE,
} from '../../src/services/observations.js';
import { config } from '../../src/config/index.js';

/** A closed GeoJSON square around Gaborone. */
const SQUARE = {
  type: 'Polygon',
  coordinates: [[
    [25.90, -24.64],
    [25.92, -24.64],
    [25.92, -24.62],
    [25.90, -24.62],
    [25.90, -24.64],
  ]],
};

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return { ok, status, json: async () => body };
}

describe('boundaryCorners', () => {
  it('reads an ordered corner list and drops the closing vertex', () => {
    const corners = boundaryCorners(SQUARE);
    expect(corners).toHaveLength(4);
    expect(corners[0]).toEqual({ lat: -24.64, lon: 25.9 });
  });

  it('accepts a Feature and a MultiPolygon', () => {
    expect(boundaryCorners({ type: 'Feature', geometry: SQUARE })).toHaveLength(4);
    expect(boundaryCorners({ type: 'MultiPolygon', coordinates: [SQUARE.coordinates] })).toHaveLength(4);
  });

  it('rejects a boundary with fewer than three usable points', () => {
    expect(boundaryCorners(null)).toBeNull();
    expect(boundaryCorners({})).toBeNull();
    expect(boundaryCorners({ type: 'Polygon', coordinates: [[[25.9, -24.6], [25.91, -24.6]]] })).toBeNull();
    expect(boundaryCorners({ type: 'Polygon', coordinates: [[['x', 'y'], [1, 2], [3, 4]]] })).toBeNull();
  });
});

describe('boundaryCentre', () => {
  it('averages the vertices', () => {
    expect(boundaryCentre(SQUARE)).toEqual({ latitude: -24.63, longitude: 25.91 });
  });

  it('returns null without a usable boundary', () => {
    expect(boundaryCentre(null)).toBeNull();
  });
});

describe('mockSeries', () => {
  it('is deterministic for the same plot and differs between plots', () => {
    const now = new Date(Date.UTC(2026, 5, 30));
    const a = mockSeries('plot-a', { days: 120, now });
    const b = mockSeries('plot-a', { days: 120, now });
    const c = mockSeries('plot-b', { days: 120, now });

    expect(a).toEqual(b);
    expect(a.readings).not.toEqual(c.readings);
  });

  it('labels every reading as simulated rather than real', () => {
    const series = mockSeries('plot-a', { days: 90, now: new Date(Date.UTC(2026, 5, 30)) });
    expect(series.source).toBe(MOCK_SOURCE);
    expect(series.isSimulated).toBe(true);
    expect(series.note).toMatch(/not real observations/);
    expect(series.readings.length).toBeGreaterThan(5);
    for (const reading of series.readings) {
      expect(reading.source).toBe(MOCK_SOURCE);
      expect(reading.isSimulated).toBe(true);
      expect(reading.ndvi).toBeGreaterThanOrEqual(-0.1);
      expect(reading.ndvi).toBeLessThanOrEqual(0.92);
      expect(reading.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    }
  });

  it('produces an ordered series that ends on the current date', () => {
    const now = new Date(Date.UTC(2026, 5, 30));
    const { readings } = mockSeries('plot-a', { days: 60, now });
    const dates = readings.map((row) => row.date);
    expect([...dates].sort()).toEqual(dates);
    expect(dates[dates.length - 1]).toBe('2026-06-30');
  });
});

describe('fetchObservations', () => {
  const originalCropApi = { ...config.cropApi };
  const originalGee = { enabled: config.gee.enabled, problem: config.gee.credentialProblem };

  beforeEach(() => {
    config.cropApi.enabled = false;
    config.cropApi.url = originalCropApi.url;
    // Turn Earth Engine off for this block. These tests describe the crop-api ->
    // mock fallback, so they must not depend on whether the machine running them
    // happens to have Earth Engine credentials configured. Leaving GEE on would
    // make them reach the real network and change the source under test.
    config.gee.enabled = false;
  });
  afterEach(() => {
    config.cropApi.enabled = originalCropApi.enabled;
    config.cropApi.url = originalCropApi.url;
    config.gee.enabled = originalGee.enabled;
    config.gee.credentialProblem = originalGee.problem;
  });

  it('falls back to the labelled mock when no processor is configured', async () => {
    const result = await fetchObservations({
      plotId: 'plot-a',
      geoJsonBoundary: SQUARE,
      now: new Date(Date.UTC(2026, 5, 30)),
    });
    expect(result.available).toBe(true);
    expect(result.source).toBe(MOCK_SOURCE);
    expect(result.isSimulated).toBe(true);
  });

  it('refuses a plot without a usable boundary when a processor is configured', async () => {
    config.cropApi.enabled = true;
    config.cropApi.url = 'https://crop.example.test';
    const result = await fetchObservations({ plotId: 'plot-a', geoJsonBoundary: null });
    expect(result.available).toBe(false);
    expect(result.readings).toEqual([]);
    expect(result.reason).toMatch(/not a usable polygon/);
  });

  it('maps a real processor response and marks it as an observation', async () => {
    config.cropApi.enabled = true;
    config.cropApi.url = 'https://crop.example.test';
    let body = null;
    const result = await fetchObservations({
      plotId: 'plot-a',
      geoJsonBoundary: SQUARE,
      fetchImpl: async (url, options) => {
        body = JSON.parse(options.body);
        return jsonResponse({
          area_m2: 4_000_000,
          source: 'gee',
          readings: [
            { date: '2026-06-01', ndvi: 0.61, evi: 0.4, pixels: 42 },
            { date: '2026-06-11', ndvi: 0.55, evi: 0.36, pixels: 38 },
          ],
        });
      },
    });

    expect(body.corners).toHaveLength(4);
    expect(body.days_back).toBe(config.satellite.defaultDays);
    expect(result.available).toBe(true);
    expect(result.source).toBe(GEE_SOURCE);
    expect(result.isSimulated).toBe(false);
    expect(result.readings).toHaveLength(2);
    expect(result.readings[0]).toEqual({
      date: '2026-06-01',
      ndvi: 0.61,
      evi: 0.4,
      pixels: 42,
      source: GEE_SOURCE,
      isSimulated: false,
    });
  });

  it('never passes a processor response off as real when it was mocked', async () => {
    config.cropApi.enabled = true;
    config.cropApi.url = 'https://crop.example.test';
    const result = await fetchObservations({
      plotId: 'plot-a',
      geoJsonBoundary: SQUARE,
      fetchImpl: async () => jsonResponse({
        source: 'mock',
        readings: [{ date: '2026-06-01', ndvi: 0.61, evi: 0.4, pixels: 42 }],
      }),
    });
    expect(result.source).toBe(MOCK_SOURCE);
    expect(result.isSimulated).toBe(true);
    expect(result.readings[0].source).toBe(MOCK_SOURCE);
    expect(result.readings[0].isSimulated).toBe(true);
  });

  it('sends the crop-api key as a header when configured and reports a failure', async () => {
    config.cropApi.enabled = true;
    config.cropApi.url = 'https://crop.example.test';
    config.cropApi.apiKey = 'test-key';
    let headers = null;
    const failing = await fetchObservations({
      plotId: 'plot-a',
      geoJsonBoundary: SQUARE,
      now: new Date(Date.UTC(2026, 5, 30)),
      fetchImpl: async (url, options) => {
        headers = options.headers;
        return jsonResponse({}, { ok: false, status: 502 });
      },
    });

    expect(headers['x-api-key']).toBe('test-key');
    // The real processor failed, so the fallback is used, but the reason is
    // recorded and the data stays labelled as simulated.
    expect(failing.available).toBe(true);
    expect(failing.source).toBe(MOCK_SOURCE);
    expect(failing.isSimulated).toBe(true);
    expect(failing.providerFailure).toContain('502');
    config.cropApi.apiKey = originalCropApi.apiKey;
  });

  it('reports an unreachable processor without throwing', async () => {
    config.cropApi.enabled = true;
    config.cropApi.url = 'https://crop.example.test';
    const result = await fetchObservations({
      plotId: 'plot-a',
      geoJsonBoundary: SQUARE,
      now: new Date(Date.UTC(2026, 5, 30)),
      fetchImpl: async () => {
        throw new Error('ENOTFOUND');
      },
    });
    expect(result.available).toBe(true);
    expect(result.providerFailure).toMatch(/could not be reached/);
  });
});
