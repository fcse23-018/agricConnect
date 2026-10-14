import { describe, it, expect } from '@jest/globals';

import { estimateAreaHectares, buildCompressedVector, computeNdvi } from '../../src/services/gee.js';

describe('estimateAreaHectares — boundary area', () => {
  it('estimates ~100 ha for a 1 km x 1 km square near the equator', () => {
    // At latitude 0: 1 degree latitude ~= 111,320 m.
    // 0.008983 deg lat ~= 1000 m; 0.008983 deg lng ~= 1000 m at the equator.
    const d = 1000 / 111_320;
    const square = {
      type: 'Polygon',
      coordinates: [[[0, 0], [d, 0], [d, d], [0, d], [0, 0]]],
    };
    const ha = estimateAreaHectares(square);
    expect(ha).toBeGreaterThan(95);
    expect(ha).toBeLessThan(105);
  });

  it('scales roughly linearly with the square of the side', () => {
    const d = 1000 / 111_320;
    const one = estimateAreaHectares({
      type: 'Polygon',
      coordinates: [[[0, 0], [d, 0], [d, d], [0, d], [0, 0]]],
    });
    const two = estimateAreaHectares({
      type: 'Polygon',
      coordinates: [[[0, 0], [2 * d, 0], [2 * d, 2 * d], [0, 2 * d], [0, 0]]],
    });
    expect(two / one).toBeGreaterThan(3.5);
    expect(two / one).toBeLessThan(4.5);
  });

  it('returns null for a degenerate/absent ring rather than throwing', () => {
    expect(estimateAreaHectares(null)).toBeNull();
    expect(estimateAreaHectares({ type: 'Polygon', coordinates: [] })).toBeNull();
    expect(estimateAreaHectares({ type: 'Polygon', coordinates: [[[0, 0], [1, 1]]] })).toBeNull();
  });

  it('accepts a GeoJSON Feature wrapper', () => {
    const d = 1000 / 111_320;
    const feature = {
      type: 'Feature',
      properties: {},
      geometry: { type: 'Polygon', coordinates: [[[0, 0], [d, 0], [d, d], [0, d], [0, 0]]] },
    };
    expect(estimateAreaHectares(feature)).toBeGreaterThan(95);
  });
});

describe('buildCompressedVector — compressed vector alert', () => {
  const polygon = {
    type: 'Polygon',
    coordinates: [[[25.9, -24.6], [25.91, -24.6], [25.91, -24.59], [25.9, -24.59], [25.9, -24.6]]],
  };

  it('carries the NDVI value in properties and a polygon geometry', () => {
    const v = buildCompressedVector(polygon, 0.62);
    expect(v.type).toBe('Feature');
    expect(v.properties.ndvi).toBe(0.62);
    expect(v.geometry.type).toBe('Polygon');
    expect(Array.isArray(v.geometry.coordinates[0])).toBe(true);
  });

  it('simplifies the ring (fewer points than the source) and rounds coordinates', () => {
    const v = buildCompressedVector(polygon, 0.5);
    expect(v.geometry.coordinates[0].length).toBeLessThanOrEqual(polygon.coordinates[0].length);
    for (const [lng, lat] of v.geometry.coordinates[0]) {
      expect(String(lng).split('.')[1]?.length ?? 0).toBeLessThanOrEqual(4);
      expect(String(lat).split('.')[1]?.length ?? 0).toBeLessThanOrEqual(4);
    }
  });
});

describe('computeNdvi — validation and honest labelling without Earth Engine', () => {
  it('fails cleanly for a missing boundary instead of throwing', async () => {
    const r = await computeNdvi({ plotId: 'p1', geoJsonBoundary: null });
    expect(r.status).toBe('FAILED');
    expect(r.meanNdviValue).toBeNull();
    expect(r.failureReason).toMatch(/boundary/i);
  });

  it('fails cleanly for a boundary with no coordinates', async () => {
    const r = await computeNdvi({ plotId: 'p1', geoJsonBoundary: { type: 'Polygon', coordinates: [] } });
    expect(r.status).toBe('FAILED');
  });

  it('rejects a non-polygon geometry', async () => {
    const r = await computeNdvi({ plotId: 'p1', geoJsonBoundary: { type: 'Point', coordinates: [25.9, -24.6] } });
    expect(r.status).toBe('FAILED');
  });

  it('produces a result tagged source=MOCK when Earth Engine is not configured', async () => {
    const polygon = {
      type: 'Polygon',
      coordinates: [[[25.9, -24.6], [25.91, -24.6], [25.91, -24.59], [25.9, -24.59], [25.9, -24.6]]],
    };
    const r = await computeNdvi({ plotId: 'plot-demo', geoJsonBoundary: polygon });
    expect(r.status).toBe('GENERATED');
    expect(r.source).toBe('MOCK'); // never claims to be Sentinel-2
    expect(r.meanNdviValue).toBeGreaterThanOrEqual(0);
    expect(r.meanNdviValue).toBeLessThanOrEqual(1);
    expect(typeof r.compressedVectorJson).toBe('object');
  });
});
