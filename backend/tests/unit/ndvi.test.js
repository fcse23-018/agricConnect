import { describe, it, expect } from '@jest/globals';

import {
  classifyNdvi,
  computeNdviFromBands,
  isCloudCoverUsable,
  mockNdviForPlot,
  NDVI_THRESHOLDS,
  StressLevel,
  stressDescriptor,
} from '../../src/services/ndvi.js';

describe('NDVI arithmetic — (NIR - RED) / (NIR + RED)', () => {
  it('computes the documented formula for Sentinel-2 B8/B4 reflectance', () => {
    // (0.5 - 0.1) / (0.5 + 0.1) = 0.4 / 0.6 = 0.6667
    expect(computeNdviFromBands(0.5, 0.1)).toBeCloseTo(0.6667, 4);
  });

  it('returns 0 when NIR and RED are equal', () => {
    expect(computeNdviFromBands(0.3, 0.3)).toBe(0);
  });

  it('returns a negative value when RED exceeds NIR (bare soil / water)', () => {
    expect(computeNdviFromBands(0.1, 0.4)).toBeLessThan(0);
  });

  it('refuses to divide by zero instead of returning NaN/Infinity', () => {
    expect(computeNdviFromBands(0, 0)).toBeNull();
  });

  it('returns null for non-numeric input', () => {
    expect(computeNdviFromBands(null, 0.2)).toBeNull();
    expect(computeNdviFromBands(undefined, 0.2)).toBeNull();
    expect(computeNdviFromBands('abc', 0.2)).toBeNull();
  });
});

describe('classifyNdvi — documented thresholds', () => {
  it('exposes the documented boundary values', () => {
    expect(NDVI_THRESHOLDS.HIGH_STRESS_MAX).toBe(0.2);
    expect(NDVI_THRESHOLDS.MODERATE_STRESS_MAX).toBe(0.45);
  });

  it('High Stress below 0.20', () => {
    expect(classifyNdvi(0.0)).toBe(StressLevel.HIGH_STRESS);
    expect(classifyNdvi(0.19)).toBe(StressLevel.HIGH_STRESS);
    expect(classifyNdvi(0.199)).toBe(StressLevel.HIGH_STRESS);
  });

  it('Moderate Stress from 0.20 up to (not including) 0.45', () => {
    expect(classifyNdvi(0.2)).toBe(StressLevel.MODERATE_STRESS);
    expect(classifyNdvi(0.44)).toBe(StressLevel.MODERATE_STRESS);
    expect(classifyNdvi(0.4499)).toBe(StressLevel.MODERATE_STRESS);
  });

  it('Healthy Vegetation at 0.45 and above', () => {
    expect(classifyNdvi(0.45)).toBe(StressLevel.HEALTHY_VEGETATION);
    expect(classifyNdvi(0.8)).toBe(StressLevel.HEALTHY_VEGETATION);
  });

  it('returns null when there is no value (never guesses)', () => {
    expect(classifyNdvi(null)).toBeNull();
    expect(classifyNdvi(undefined)).toBeNull();
    expect(classifyNdvi(Number.NaN)).toBeNull();
  });

  it('accepts numeric strings (values arrive from PG as strings)', () => {
    expect(classifyNdvi('0.5')).toBe(StressLevel.HEALTHY_VEGETATION);
    expect(classifyNdvi('0.10')).toBe(StressLevel.HIGH_STRESS);
  });

  it('every level has a human label and a colour token', () => {
    for (const level of Object.values(StressLevel)) {
      const d = stressDescriptor(level);
      expect(typeof d.label).toBe('string');
      expect(d.label.length).toBeGreaterThan(0);
      expect(typeof d.colour).toBe('string');
    }
    // Unknown/null level must degrade to a neutral descriptor, not crash.
    expect(stressDescriptor(null).label).toBe('Not available');
  });
});

describe('isCloudCoverUsable — >80% fails processing', () => {
  it('accepts exactly 80% (threshold is inclusive)', () => {
    expect(isCloudCoverUsable(80)).toBe(true);
  });

  it('rejects above 80%', () => {
    expect(isCloudCoverUsable(80.1)).toBe(false);
    expect(isCloudCoverUsable(100)).toBe(false);
  });

  it('treats missing cloud cover as usable rather than failing the request', () => {
    expect(isCloudCoverUsable(null)).toBe(true);
    expect(isCloudCoverUsable(undefined)).toBe(true);
  });
});

describe('mockNdviForPlot — deterministic, bounded, and clearly labelled', () => {
  it('is deterministic for the same plot id and date', () => {
    const date = new Date('2026-03-01T00:00:00Z');
    const a = mockNdviForPlot('plot-abc', date);
    const b = mockNdviForPlot('plot-abc', date);
    expect(a).toEqual(b);
  });

  it('stays within the documented 0.08..0.80 band and never exceeds valid NDVI', () => {
    for (let i = 0; i < 200; i += 1) {
      const r = mockNdviForPlot(`plot-${i}`);
      expect(r.meanNdviValue).toBeGreaterThanOrEqual(0.08);
      expect(r.meanNdviValue).toBeLessThanOrEqual(0.8);
      expect(r.meanNdviValue).toBeLessThanOrEqual(1);
    }
  });

  it('labels itself as MOCK so it can never be mistaken for Sentinel-2', () => {
    expect(mockNdviForPlot('plot-abc').source).toBe('MOCK');
  });

  it('produces a cloud cover the usability rule can act on', () => {
    const r = mockNdviForPlot('plot-abc');
    expect(Number.isFinite(r.cloudCover)).toBe(true);
    expect(r.cloudCover).toBeGreaterThanOrEqual(0);
    expect(r.cloudCover).toBeLessThanOrEqual(100);
  });
});
