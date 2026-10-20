import { describe, it, expect } from '@jest/globals';

import { resolveBoundary, activeThresholds } from '../../src/modules/satellite/satellite.monitoring.service.js';
import { ringFromGeoJson, measureBoundary } from '../../src/services/geometry.js';
import { overviewQuerySchema, compareQuerySchema } from '../../src/modules/satellite/satellite.schemas.js';
import { config } from '../../src/config/index.js';

describe('resolveBoundary', () => {
  it('turns a rectangle request into measured geometry', () => {
    const resolved = resolveBoundary({
      method: 'RECTANGLE',
      centreLat: -24.625,
      centreLon: 25.91,
      widthM: 400,
      lengthM: 800,
      bearingDeg: 30,
    });

    expect(resolved.method).toBe('RECTANGLE');
    expect(resolved.vertices).toHaveLength(4);
    expect(resolved.geoJson.type).toBe('Polygon');
    expect(resolved.measurement.areaHectares).toBeCloseTo(32, 1);
    expect(resolved.measurement.perimeterM).toBeCloseTo(2_400, -2);
    expect(resolved.rectangle.bearingDeg).toBe(30);
    // The preview and the stored boundary must be the same geometry.
    expect(ringFromGeoJson(resolved.geoJson)).toEqual(resolved.vertices);
  });

  it('accepts a polygon, measures it and closes the stored ring', () => {
    const resolved = resolveBoundary({
      method: 'POLYGON',
      vertices: [
        { lat: -24.6300, lon: 25.9000 },
        { lat: -24.6300, lon: 25.9200 },
        { lat: -24.6200, lon: 25.9200 },
        { lat: -24.6200, lon: 25.9000 },
      ],
    });

    expect(resolved.method).toBe('POLYGON');
    expect(resolved.vertices).toHaveLength(4);
    expect(resolved.geoJson.coordinates[0]).toHaveLength(5);
    expect(resolved.measurement.areaHectares).toBeGreaterThan(180);
    expect(resolved.rectangle).toBeNull();
  });

  it('rejects a self-intersecting polygon with a reason the app can show', () => {
    expect(() => resolveBoundary({
      method: 'POLYGON',
      vertices: [
        { lat: -24.630, lon: 25.900 },
        { lat: -24.620, lon: 25.920 },
        { lat: -24.620, lon: 25.900 },
        { lat: -24.630, lon: 25.920 },
      ],
    })).toThrow(/not valid/i);
  });

  it('rejects a polygon with too few vertices', () => {
    expect(() => resolveBoundary({
      method: 'POLYGON',
      vertices: [{ lat: -24.63, lon: 25.9 }, { lat: -24.62, lon: 25.92 }],
    })).toThrow(/not valid/i);
  });

  it('rejects a rectangle with no usable dimensions', () => {
    expect(() => resolveBoundary({
      method: 'RECTANGLE', centreLat: -24.6, centreLon: 25.9, widthM: 0, lengthM: 400, bearingDeg: 0,
    })).toThrow(/rectangle needs/i);
  });

  it('rejects an unknown method and a missing boundary', () => {
    expect(() => resolveBoundary({ method: 'CIRCLE' })).toThrow(/Unknown boundary method/i);
    expect(() => resolveBoundary(null)).toThrow(/boundary is required/i);
  });

  it('produces geometry consistent with a direct measurement', () => {
    const resolved = resolveBoundary({
      method: 'RECTANGLE', centreLat: -24.5, centreLon: 25.5, widthM: 250, lengthM: 500, bearingDeg: 120,
    });
    const direct = measureBoundary(resolved.vertices);
    expect(direct.areaM2).toBeCloseTo(resolved.measurement.areaM2, 1);
    expect(direct.perimeterM).toBeCloseTo(resolved.measurement.perimeterM, 1);
  });
});

describe('activeThresholds', () => {
  it('reports the configured values, documented, not hidden defaults', () => {
    const thresholds = activeThresholds();
    expect(thresholds.dropAbsolute).toBe(config.satellite.dropAbsolute);
    expect(thresholds.dropRelative).toBe(config.satellite.dropRelative);
    expect(thresholds.staleDays).toBe(config.satellite.staleDays);
    expect(thresholds.stressNdvi).toBe(config.satellite.stressNdvi);
    expect(thresholds.noRecoveryDays).toBe(config.satellite.noRecoveryDays);
    expect(thresholds.dryRain14dMm).toBe(config.satellite.dryRain14dMm);
    expect(thresholds.heatTempMaxC).toBe(config.satellite.heatTempMaxC);
    // Engine-level defaults that config does not override still come through.
    expect(thresholds.maxGapDays).toBeGreaterThan(0);
    expect(thresholds.minCycleReadings).toBeGreaterThan(0);
  });
});

describe('observation window parameters', () => {
  const PLOT_ID = '11111111-2222-4333-8444-555555555555';

  it('accepts `days` on the overview query', () => {
    const parsed = overviewQuerySchema.parse({ plotId: PLOT_ID, days: '30' });
    expect(parsed.days).toBe(30);
    expect(parsed.daysBack).toBeUndefined();
  });

  it('accepts `daysBack` as an alias, so a provider-style name still works', () => {
    const parsed = overviewQuerySchema.parse({ plotId: PLOT_ID, daysBack: '45' });
    expect(parsed.daysBack).toBe(45);
  });

  it('accepts `daysBack` on the weather query too', () => {
    const parsed = compareQuerySchema.parse({ plotId: PLOT_ID, daysBack: '90' });
    expect(parsed.daysBack).toBe(90);
  });

  it('rejects an out-of-range window however it is named', () => {
    expect(overviewQuerySchema.safeParse({ plotId: PLOT_ID, days: '5' }).success).toBe(false);
    expect(overviewQuerySchema.safeParse({ plotId: PLOT_ID, daysBack: '5' }).success).toBe(false);
    expect(overviewQuerySchema.safeParse({ plotId: PLOT_ID, daysBack: '9000' }).success).toBe(false);
  });
});
