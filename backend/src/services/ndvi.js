import { config } from '../config/index.js';

/**
 * NDVI crop-stress classification.
 * Thresholds are the documented ones (Architecture v1.1 §9.6):
 *   High Stress          NDVI < 0.20
 *   Moderate Stress      0.20 <= NDVI < 0.45
 *   Healthy Vegetation   NDVI >= 0.45
 */
export const NDVI_THRESHOLDS = Object.freeze({
  HIGH_STRESS_MAX: 0.2,
  MODERATE_STRESS_MAX: 0.45,
});

export const StressLevel = Object.freeze({
  HIGH_STRESS: 'HIGH_STRESS',
  MODERATE_STRESS: 'MODERATE_STRESS',
  HEALTHY_VEGETATION: 'HEALTHY_VEGETATION',
});

/**
 * Reference implementation of the documented index:
 *   NDVI = (NIR - RED) / (NIR + RED)   — Sentinel-2 Band 8 (NIR), Band 4 (RED)
 *
 * The production path delegates this arithmetic to Earth Engine's
 * `image.normalizedDifference(['B8','B4'])`, which is the same formula. This
 * function exists so the formula is (a) explicit in our own codebase and
 * (b) unit-testable without Earth Engine credentials. Returns null for
 * non-finite inputs or the degenerate NIR+RED = 0 case (which would divide by
 * zero) so callers never record a NaN NDVI as if it were a reading.
 */
function toFiniteNumber(value) {
  // `Number(null)` is 0 and `Number('')` is 0, which would silently produce a
  // plausible-looking NDVI from missing data. Only accept real numbers and
  // non-empty numeric strings (PostgreSQL hands numeric columns back as strings).
  if (value === null || value === undefined || value === '' || typeof value === 'boolean') return Number.NaN;
  const n = Number(value);
  return Number.isFinite(n) ? n : Number.NaN;
}

export function computeNdviFromBands(nir, red) {
  const n = toFiniteNumber(nir);
  const r = toFiniteNumber(red);
  if (!Number.isFinite(n) || !Number.isFinite(r)) return null;
  if (n + r === 0) return null;
  return Number(((n - r) / (n + r)).toFixed(4));
}

export function classifyNdvi(mean) {
  if (mean === null || mean === undefined || Number.isNaN(Number(mean))) return null;
  const v = Number(mean);
  if (v < NDVI_THRESHOLDS.HIGH_STRESS_MAX) return StressLevel.HIGH_STRESS;
  if (v < NDVI_THRESHOLDS.MODERATE_STRESS_MAX) return StressLevel.MODERATE_STRESS;
  return StressLevel.HEALTHY_VEGETATION;
}

/** Human-facing label + colour token name, shared with the Android client. */
export function stressDescriptor(level) {
  switch (level) {
    case StressLevel.HIGH_STRESS:
      return { level, label: 'High stress', colour: 'error', advice: 'Vegetation vigour is very low. Inspect the plot.' };
    case StressLevel.MODERATE_STRESS:
      return { level, label: 'Moderate stress', colour: 'warning', advice: 'Vegetation vigour is below average. Monitor closely.' };
    case StressLevel.HEALTHY_VEGETATION:
      return { level, label: 'Healthy vegetation', colour: 'success', advice: 'Vegetation vigour looks healthy.' };
    default:
      return { level: null, label: 'Not available', colour: 'neutral', advice: 'No satellite result has been computed yet.' };
  }
}

/**
 * Decide whether imagery is usable. Documented rule: cloud cover above 80%
 * marks processing as FAILED (Architecture v1.1 §9.6, State Machine 2).
 */
export function isCloudCoverUsable(cloudCoverPercent) {
  if (cloudCoverPercent === null || cloudCoverPercent === undefined) return true;
  return Number(cloudCoverPercent) <= config.gee.cloudCoverFailThreshold;
}

/**
 * Deterministic mock NDVI generator used when GEE is not configured.
 * It derives a stable pseudo-value from the plot id + date so the full pipeline
 * (queue → compute → cache → offline display) is testable without credentials.
 * It is clearly labelled with source 'MOCK' and never claims to be Sentinel-2.
 */
export function mockNdviForPlot(plotId, date = new Date()) {
  const seed = [...String(plotId)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) % 100000, 7);
  const dayOfYear = Math.floor((date - new Date(date.getFullYear(), 0, 0)) / 86_400_000);
  const raw = ((seed + dayOfYear * 13) % 1000) / 1000; // 0..1
  const mean = Number((0.08 + raw * 0.72).toFixed(4)); // 0.08 .. 0.80
  return {
    meanNdviValue: mean,
    cloudCover: Number(((seed % 40) + 5).toFixed(2)),
    satelliteDate: date.toISOString().slice(0, 10),
    source: 'MOCK',
  };
}

export default {
  NDVI_THRESHOLDS, StressLevel, classifyNdvi, stressDescriptor,
  isCloudCoverUsable, mockNdviForPlot, computeNdviFromBands,
};
