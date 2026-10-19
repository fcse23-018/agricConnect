/**
 * Pure satellite time-series analysis.
 *
 * Everything in this module is deterministic and side-effect free: no database,
 * no network, no clock unless `now` is passed in. That is deliberate — the
 * classifications a farmer sees must be reproducible and testable, and a rule
 * engine that can be read in one place is easier to audit than one smeared
 * across a route and a UI.
 *
 * The rules never claim a cause. A vegetation drop is reported as a
 * **possible** explanation with the evidence that supports it and a confidence
 * level. Nothing here says "harvest", "drought" or "disease" as a fact.
 */

export const CLASSIFICATION = Object.freeze({
  TRANSIENT_DIP: 'TRANSIENT_DIP',
  POSSIBLE_HARVEST: 'POSSIBLE_HARVEST',
  POSSIBLE_HEAT_OR_DRY_STRESS: 'POSSIBLE_HEAT_OR_DRY_STRESS',
  GRADUAL_DECLINE: 'GRADUAL_DECLINE',
  UNCLEAR: 'UNCLEAR',
});

export const CLASSIFICATION_LABELS = Object.freeze({
  TRANSIENT_DIP: 'Transient dip or noisy observation',
  POSSIBLE_HARVEST: 'Possible harvest or clearing',
  POSSIBLE_HEAT_OR_DRY_STRESS: 'Possible heat or dry stress',
  GRADUAL_DECLINE: 'Gradual vegetation decline',
  UNCLEAR: 'Unclear or insufficient evidence',
});

export const DEFAULT_THRESHOLDS = Object.freeze({
  /** Absolute NDVI fall (0.15) that counts as a detected drop. */
  dropAbsolute: 0.15,
  /** Relative NDVI fall (15%) that must also be met. */
  dropRelative: 0.15,
  /** Never bridge a gap longer than this between readings when comparing. */
  maxGapDays: 45,
  /** A reading with less valid coverage than this is low quality. */
  minValidPct: 40,
  /** Cloud cover above this is a low-quality observation. */
  maxCloudPct: 80,
  /** Latest NDVI below this suggests vegetation stress (indicative only). */
  stressNdvi: 0.3,
  /** No observation for longer than this is "outdated". */
  staleDays: 10,
  /** After a drop, this many days without recovery raises an alert. */
  noRecoveryDays: 21,
  /** 14-day rainfall below this (mm) with high heat suggests dry stress. */
  dryRain14dMm: 10,
  /** 7-day maximum temperature above this (°C) suggests heat stress. */
  heatTempMaxC: 32,
  /** Minimum readings before a growth cycle is attempted. */
  minCycleReadings: 4,
});

const DAY_MS = 86_400_000;

/* --------------------------------- helpers -------------------------------- */

/** Date-only string (`YYYY-MM-DD`) → UTC midnight epoch millis, or null. */
export function toEpoch(dateOnly) {
  if (typeof dateOnly !== 'string') return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(dateOnly);
  if (!match) return null;
  const ms = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  return Number.isFinite(ms) ? ms : null;
}

export function addDays(dateOnly, days) {
  const ms = toEpoch(dateOnly);
  if (ms === null) return null;
  return new Date(ms + days * DAY_MS).toISOString().slice(0, 10);
}

export function daysBetween(fromDate, toDate) {
  const a = toEpoch(fromDate);
  const b = toEpoch(toDate);
  if (a === null || b === null) return null;
  return Math.round((b - a) / DAY_MS);
}

function round(value, places = 4) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return null;
  const factor = 10 ** places;
  return Math.round(Number(value) * factor) / factor;
}

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function clampIndex(value) {
  if (value === null) return null;
  return Math.max(-1, Math.min(1, value));
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 0) return null;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

/* ------------------------------ normalisation ----------------------------- */

/**
 * Turn any provider shape into one canonical observation list.
 *
 * Accepts the crop-api (`date`/`ndvi`/`evi`/`pixels`) and the internal
 * (`observedOn`/`ndviValue`) shapes. Invalid rows are dropped rather than
 * guessed at, duplicate dates are collapsed, and the list is sorted oldest
 * first so every later rule can rely on ordering.
 */
export function sanitiseObservations(raw = []) {
  const byDate = new Map();

  for (const row of Array.isArray(raw) ? raw : []) {
    const date = String(row?.date ?? row?.observedOn ?? row?.observed_on ?? '').slice(0, 10);
    if (toEpoch(date) === null) continue;

    const ndvi = clampIndex(finite(row?.ndvi ?? row?.ndviValue ?? row?.ndvi_value));
    const evi = clampIndex(finite(row?.evi ?? row?.eviValue ?? row?.evi_value));
    if (ndvi === null && evi === null) continue;

    const validPct = finite(row?.validPixelPct ?? row?.valid_pixel_pct ?? row?.validPct);
    const cloudPct = finite(row?.cloudPct ?? row?.cloud_pct ?? row?.cloudCover);
    const pixels = finite(row?.pixels ?? row?.pixelCount);

    byDate.set(date, {
      date,
      ndvi: round(ndvi),
      evi: round(evi),
      validPct: round(validPct, 2),
      cloudPct: round(cloudPct, 2),
      pixels: pixels === null ? null : Math.round(pixels),
      source: row?.source ?? null,
      isSimulated: Boolean(row?.isSimulated ?? row?.is_simulated ?? false),
      quality: qualityOf({ validPct, cloudPct, ndvi }),
    });
  }

  return [...byDate.values()].sort((a, b) => toEpoch(a.date) - toEpoch(b.date));
}

function qualityOf({ validPct, cloudPct, ndvi }) {
  if (ndvi === null) return 'POOR';
  if (validPct !== null && validPct < 25) return 'POOR';
  if (cloudPct !== null && cloudPct > 60) return 'POOR';
  if (validPct !== null && validPct < 55) return 'FAIR';
  if (cloudPct !== null && cloudPct > 35) return 'FAIR';
  return 'GOOD';
}

/* ------------------------------ quality control --------------------------- */

/**
 * Flag isolated spikes with a Hampel-style test: a point is an outlier when it
 * disagrees with a local median by more than `max(0.18, 4 × MAD)` **and** both
 * neighbours agree with each other. This keeps a genuine, sustained decline
 * (where neighbours also move) while rejecting a single noisy pixel aggregate.
 */
export function flagOutliers(series, { window = 3, minAbs = 0.18, madFactor = 4, maxGapDays = 60 } = {}) {
  if (series.length < 3) return series.map((row) => ({ ...row, isOutlier: false, outlierReason: null }));

  return series.map((row, index) => {
    if (row.ndvi === null) return { ...row, isOutlier: false, outlierReason: null };

    const neighbours = [];
    for (let offset = -window; offset <= window; offset += 1) {
      if (offset === 0) continue;
      const other = series[index + offset];
      if (!other || other.ndvi === null) continue;
      const gap = Math.abs(daysBetween(row.date, other.date) ?? Number.MAX_SAFE_INTEGER);
      if (gap <= maxGapDays) neighbours.push(other);
    }
    if (neighbours.length < 2) return { ...row, isOutlier: false, outlierReason: null };

    const localMedian = median(neighbours.map((n) => n.ndvi));
    const deviations = neighbours.map((n) => Math.abs(n.ndvi - localMedian));
    const mad = median(deviations) ?? 0;
    const previous = series[index - 1];
    const next = series[index + 1];
    const neighboursAgree = Boolean(
      previous && next &&
      previous.ndvi !== null && previous.ndvi !== undefined &&
      next.ndvi !== null && next.ndvi !== undefined &&
      Math.abs(previous.ndvi - next.ndvi) <= 0.08,
    );

    const deviation = Math.abs(row.ndvi - localMedian);
    const threshold = Math.max(minAbs, madFactor * mad);
    const isOutlier = neighboursAgree && deviation > threshold;

    return {
      ...row,
      isOutlier,
      outlierReason: isOutlier
        ? `Isolated value ${row.ndvi.toFixed(2)} against a local median of ${localMedian.toFixed(2)}`
        : null,
    };
  });
}

/**
 * Distance-weighted centred moving average over usable readings, carrying the
 * raw value where a point has no usable neighbours.
 *
 * Two deliberate choices:
 *
 * 1. Weighting is by time distance. Sentinel-2 passes are ~5 days apart during
 *    overlap season and ~10-15 days apart otherwise, so an unweighted 5-point
 *    window can straddle six weeks and drag a peer's value far from the
 *    observation it is meant to smooth - which attenuates every real peak and
 *    trough. A reading twice as far away contributes half as much.
 * 2. Outliers are excluded from the window but remain in the series (flagged,
 *    with their raw value) so history is never rewritten.
 */
export function smoothSeries(series, { window = 3, maxGapDays = 60 } = {}) {
  return series.map((row, index) => {
    if (row.ndvi === null || row.isOutlier) {
      return { ...row, smoothedNdvi: row.ndvi, smoothedEvi: row.evi };
    }
    let weightSum = 0;
    let weightedSum = 0;
    let eviWeightSum = 0;
    let eviWeightedSum = 0;
    for (let offset = -window + 1; offset <= window - 1; offset += 1) {
      const other = series[index + offset];
      if (!other || other.isOutlier || other.ndvi === null) continue;
      const gap = Math.abs(daysBetween(row.date, other.date) ?? Number.MAX_SAFE_INTEGER);
      if (gap > maxGapDays) continue;
      const weight = 1 / (1 + gap / 5);
      weightSum += weight;
      weightedSum += weight * other.ndvi;
      if (other.evi !== null) {
        eviWeightSum += weight;
        eviWeightedSum += weight * other.evi;
      }
    }
    return {
      ...row,
      smoothedNdvi: weightSum > 0 ? round(weightedSum / weightSum) : row.ndvi,
      smoothedEvi: eviWeightSum > 0 ? round(eviWeightedSum / eviWeightSum) : row.evi,
    };
  });
}

/* -------------------------------- summary -------------------------------- */

export function summarise(series, { now = null } = {}) {
  const usable = series.filter((row) => row.ndvi !== null && !row.isOutlier);
  if (usable.length === 0) {
    return {
      available: false,
      reason: 'No valid vegetation-index reading is available for this plot.',
      count: 0,
      latest: null,
      previous: null,
      min: null,
      max: null,
      average: null,
      change: null,
      changePct: null,
      trend: 'INSUFFICIENT',
      dataAgeDays: null,
    };
  }

  const latest = usable[usable.length - 1];
  const previous = usable.length > 1 ? usable[usable.length - 2] : null;
  const values = usable.map((row) => row.ndvi);
  const average = values.reduce((sum, value) => sum + value, 0) / values.length;
  const change = previous && previous.ndvi !== null ? latest.ndvi - previous.ndvi : null;
  const changePct = change !== null && previous.ndvi ? (change / previous.ndvi) * 100 : null;
  const today = now ? String(now).slice(0, 10) : new Date().toISOString().slice(0, 10);
  const dataAgeDays = daysBetween(latest.date, today);

  let trend = 'INSUFFICIENT';
  if (change !== null) {
    if (change >= 0.05) trend = 'RISING';
    else if (change <= -0.05) trend = 'DECLINING';
    else trend = 'STABLE';
  }

  return {
    available: true,
    reason: null,
    count: usable.length,
    latest,
    previous,
    min: round(Math.min(...values)),
    max: round(Math.max(...values)),
    average: round(average),
    change: round(change),
    changePct: round(changePct, 1),
    trend,
    dataAgeDays,
    firstDate: usable[0].date,
    lastDate: latest.date,
    coverageFrom: usable[0].date,
    coverageTo: latest.date,
  };
}

/* ------------------------------- growth cycle ----------------------------- */

/**
 * Estimate green-up, peak and end-of-season from the smoothed series.
 *
 * Returns `available: false` with a reason when the record is too short. The
 * dates are **estimates** interpolated between satellite passes, not
 * observations, and the response says so.
 */
export function detectGrowthCycle(series, thresholds = DEFAULT_THRESHOLDS) {
  const usable = series.filter((row) => row.smoothedNdvi !== null && !row.isOutlier);
  if (usable.length < thresholds.minCycleReadings) {
    return {
      available: false,
      reason: `At least ${thresholds.minCycleReadings} valid observations are needed to estimate a growth cycle.`,
      greenUpOn: null,
      peakOn: null,
      endOn: null,
      peakValue: null,
      amplitude: null,
      confidence: 'LOW',
      evidence: [],
    };
  }

  // Composite period: every second valid observation, the standard way to
  // regularise an irregular optical series.
  //
  // Peak, baseline and end-of-season are read off the **full** smoothed
  // series. Decimation would drop the date of the highest reading whenever it
  // lands on an odd index - which is exactly the number a farmer acts on. An
  // earlier version decimated for the locator rules only, and the end-of-season
  // check then compared a decimated value against a full-series threshold and
  // missed the end of the season altogether.
  const decimated = usable.filter((_, index) => index % 2 === 0);
  const firstThird = Math.max(1, Math.floor(decimated.length / 3));
  const baseline = Math.min(...decimated.slice(0, firstThird).map((row) => row.smoothedNdvi));

  // Green-up = first reading that clears the dormant baseline by a real margin
  // *and* keeps going. Position alone would call a noisy early point green-up.
  const greenUpThreshold = baseline + 0.1;
  const quietBefore = Math.max(1, Math.floor(usable.length * 0.1));
  let greenUpIndex = usable.findIndex(
    (row, index) =>
      index >= quietBefore &&
      row.smoothedNdvi >= greenUpThreshold &&
      (usable[index + 1]?.smoothedNdvi ?? Infinity) >= greenUpThreshold,
  );
  if (greenUpIndex < 0) {
    greenUpIndex = usable.findIndex((row) => row.smoothedNdvi >= greenUpThreshold);
  }
  if (greenUpIndex < 0) greenUpIndex = 0;

  const afterGreenUp = usable.slice(greenUpIndex);
  let peakIndex = 0;
  afterGreenUp.forEach((row, index) => {
    if (row.smoothedNdvi > afterGreenUp[peakIndex].smoothedNdvi) peakIndex = index;
  });
  const peak = afterGreenUp[peakIndex];
  const peakValue = peak.smoothedNdvi;
  const amplitude = round(peakValue - baseline);

  // End of season: the first reading after the peak that reaches the end
  // threshold and does not come back above it. "Reaches" rather than "falls
  // below": end-of-season is the point that has returned to near-dormant
  // levels, so a reading that lands exactly on the threshold counts.
  let endOn = null;
  const endThreshold = Math.max(peakValue - 0.15, baseline + 0.05);
  for (let index = peakIndex + 1; index < afterGreenUp.length; index += 1) {
    if (index === afterGreenUp.length - 1) break;
    const row = afterGreenUp[index];
    const below = row.smoothedNdvi <= endThreshold;
    const sustained = !afterGreenUp[index + 1] || afterGreenUp[index + 1].smoothedNdvi <= endThreshold;
    if (below && sustained) {
      endOn = row.date;
      break;
    }
  }

  const confidence = amplitude >= 0.35 && usable.length >= 6
    ? 'HIGH'
    : amplitude >= 0.2 && usable.length >= 4
      ? 'MEDIUM'
      : 'LOW';

  return {
    available: true,
    reason: null,
    greenUpOn: afterGreenUp[0]?.date ?? null,
    peakOn: peak.date,
    endOn,
    peakValue: round(peakValue),
    baseline: round(baseline),
    amplitude,
    confidence,
    strategy: 'Composite-period (every second valid observation), the standard approach for irregular optical series.',
    evidence: [
      `Baseline vegetation index ${round(baseline)} before green-up.`,
      `Peak ${round(peakValue)} on ${peak.date}.`,
      endOn
        ? `Index returned to ${round(endThreshold)} by ${endOn}.`
        : 'No end-of-season decline is visible in the available observations yet.',
    ],
    note: 'Dates are estimates between satellite passes, not observations.',
  };
}

/* ------------------------------- drop detection --------------------------- */

export function detectDrops(series, thresholds = DEFAULT_THRESHOLDS) {
  const usable = series.filter((row) => row.ndvi !== null && !row.isOutlier);
  const drops = [];

  for (let index = 1; index < usable.length; index += 1) {
    const previous = usable[index - 1];
    const current = usable[index];
    const gap = daysBetween(previous.date, current.date);
    if (gap === null || gap > thresholds.maxGapDays || gap <= 0) continue;

    const delta = current.ndvi - previous.ndvi;
    const relative = previous.ndvi > 0 ? delta / previous.ndvi : 0;

    // The previous reading must be a real vegetation signal; a fall from
    // near-zero NDVI (bare soil) is not a vegetation drop.
    const fromSignal = previous.ndvi >= 0.35;
    const clearsAbsolute = delta <= -thresholds.dropAbsolute;
    const clearsRelative = relative <= -thresholds.dropRelative;
    if (!fromSignal || !clearsAbsolute || !clearsRelative) continue;

    // Recovery: the best value in the following window.
    const recoveryWindowEnd = addDays(current.date, thresholds.maxGapDays);
    const subsequent = usable.filter(
      (row) => toEpoch(row.date) > toEpoch(current.date) && toEpoch(row.date) <= toEpoch(recoveryWindowEnd),
    );
    const bestAfter = subsequent.length > 0 ? Math.max(...subsequent.map((row) => row.ndvi)) : null;
    const recoveryFraction = bestAfter === null
      ? null
      : Math.max(0, Math.min(1, (bestAfter - current.ndvi) / Math.abs(delta)));

    drops.push({
      date: current.date,
      previousDate: previous.date,
      daysBetween: gap,
      ndviBefore: round(previous.ndvi),
      ndviAfter: round(current.ndvi),
      eviBefore: round(previous.evi),
      eviAfter: round(current.evi),
      delta: round(delta),
      magnitude: round(Math.abs(delta)),
      relativePct: round(relative * 100, 1),
      quality: current.quality,
      cloudPct: current.cloudPct,
      validPct: current.validPct,
      recoveryFraction: round(recoveryFraction, 2),
      recoveredOn: recoveryFraction !== null && recoveryFraction >= 0.6
        ? subsequent[subsequent.length - 1].date
        : null,
      evidence: [
        `NDVI fell from ${round(previous.ndvi)} on ${previous.date} to ${round(current.ndvi)} on ${current.date} ` +
          `(${round(Math.abs(relative) * 100, 1)}% over ${gap} days).`,
        `Observation quality: ${current.quality.toLowerCase()}` +
          (current.validPct !== null ? ` (${round(current.validPct, 0)}% valid pixels).` : '.'),
        recoveryFraction === null
          ? 'No later observation is available yet to judge recovery.'
          : `Best recovery in the following ${thresholds.maxGapDays} days recovered ${round(recoveryFraction * 100, 0)}% of the drop.`,
      ],
    });
  }

  return drops;
}

/** A slow decline over several readings, where no single step is significant. */
export function detectGradualDecline(series, thresholds = DEFAULT_THRESHOLDS) {
  const usable = series.filter((row) => row.smoothedNdvi !== null && !row.isOutlier);
  if (usable.length < 4) return null;

  const minStepDays = Math.min(10, Math.max(3, Math.floor(usable.length / 2)));
  const recent = [];
  for (let index = usable.length - 1; index >= 0; index -= 1) {
    const row = usable[index];
    const later = recent[recent.length - 1];
    const gap = later ? daysBetween(row.date, later.date) : null;
    if (later && gap !== null && gap < minStepDays) continue;
    recent.push(row);
    if (recent.length === 4) break;
  }
  recent.reverse();
  if (recent.length < 4) return null;

  const first = recent[0];
  const last = recent[recent.length - 1];
  const total = last.smoothedNdvi - first.smoothedNdvi;
  const steps = recent.slice(1).map((row, index) => row.smoothedNdvi - recent[index].smoothedNdvi);
  // "Every step down" with a small tolerance, because real passes jitter.
  const everyStepDown = steps.every((step) => step < 0.02);

  if (total <= -0.15 && everyStepDown) {
    return {
      date: last.date,
      previousDate: first.date,
      daysBetween: daysBetween(first.date, last.date),
      ndviBefore: round(first.smoothedNdvi),
      ndviAfter: round(last.smoothedNdvi),
      eviBefore: round(first.smoothedEvi),
      eviAfter: round(last.smoothedEvi),
      delta: round(total),
      magnitude: round(Math.abs(total)),
      relativePct: round((total / first.smoothedNdvi) * 100, 1),
      quality: last.quality,
      recoveryFraction: null,
      gradual: true,
      evidence: [
        `Smoothed NDVI declined at every observation over ${recent.length} readings, from ${round(first.smoothedNdvi)} ` +
          `on ${first.date} to ${round(last.smoothedNdvi)} on ${last.date}.`,
        'No single step crossed the sharp-drop threshold, so this is a gradual decline.',
      ],
    };
  }
  return null;
}

/* ------------------------------- explanation ------------------------------ */

/** Aggregate the weather observations in the `days` before `endDate`. */
export function weatherWindow(weather, endDate, days = 14) {
  if (!weather?.available || !Array.isArray(weather.daily) || weather.daily.length === 0) {
    return { available: false, reason: weather?.reason ?? 'Weather data is not available.' };
  }
  const end = toEpoch(endDate);
  if (end === null) return { available: false, reason: 'No event date to compare weather against.' };
  const start = end - days * DAY_MS;

  const rows = weather.daily.filter((row) => {
    const at = toEpoch(row.date);
    return at !== null && at <= end && at > start;
  }).slice(-days);
  if (rows.length === 0) return { available: false, reason: 'No weather observations cover this period.' };

  const rainfall = rows.reduce((sum, row) => sum + (finite(row.rainfallMm) ?? 0), 0);
  const tempsMax = rows.map((row) => finite(row.tempMaxC)).filter((value) => value !== null);
  const tempsMin = rows.map((row) => finite(row.tempMinC)).filter((value) => value !== null);
  const soil = rows.map((row) => finite(row.soilMoisturePct)).filter((value) => value !== null);

  return {
    available: true,
    days: rows.length,
    rainfallMm: round(rainfall, 1),
    tempMaxC: tempsMax.length ? round(Math.max(...tempsMax), 1) : null,
    tempMinC: tempsMin.length ? round(Math.min(...tempsMin), 1) : null,
    soilMoisturePct: soil.length ? round(soil.reduce((sum, value) => sum + value, 0) / soil.length, 1) : null,
    source: weather.source ?? null,
    dataType: weather.dataType ?? null,
    periodStart: rows[0].date,
    periodEnd: rows[rows.length - 1].date,
  };
}

/**
 * Turn a detected drop into a *possible* explanation with evidence.
 *
 * The order matters: a low-quality observation or a quick recovery outranks
 * weather, because acting on a noisy pass as if it were a drought would be
 * worse than saying "unclear".
 */
export function explainDrop(drop, { weather = null, thresholds = DEFAULT_THRESHOLDS } = {}) {
  const context = weatherWindow(weather, drop.date, 14);
  const evidence = [...(drop.evidence ?? [])];
  let classification = CLASSIFICATION.UNCLEAR;
  let confidence = 'LOW';
  let nextStep = 'Walk the plot and record what you see. Confirm or correct this explanation in the event log.';

  if (drop.quality === 'POOR' || (drop.cloudPct !== null && drop.cloudPct > 40)) {
    classification = CLASSIFICATION.TRANSIENT_DIP;
    confidence = 'LOW';
    evidence.push('The observation that recorded the drop was low quality, so it may be noise rather than a real change.');
    nextStep = 'Wait for the next clear satellite pass before acting. No field intervention is recommended on this reading alone.';
  } else if (drop.recoveryFraction !== null && drop.recoveryFraction >= 0.6) {
    classification = CLASSIFICATION.TRANSIENT_DIP;
    confidence = drop.recoveryFraction >= 0.85 ? 'HIGH' : 'MEDIUM';
    evidence.push('Vegetation recovered most of the fall in the following weeks, which is more consistent with a temporary dip than a lasting loss.');
    nextStep = 'No action is needed if the plot looks normal on the ground. Keep monitoring the next pass.';
  } else if (drop.gradual) {
    classification = CLASSIFICATION.GRADUAL_DECLINE;
    confidence = 'MEDIUM';
    evidence.push('The decline is spread across several observations rather than one sharp event.');
  } else if (
    context.available &&
    context.rainfallMm !== null &&
    context.rainfallMm < thresholds.dryRain14dMm &&
    ((context.tempMaxC !== null && context.tempMaxC >= thresholds.heatTempMaxC) || context.soilMoisturePct !== null)
  ) {
    classification = CLASSIFICATION.POSSIBLE_HEAT_OR_DRY_STRESS;
    confidence = context.rainfallMm < thresholds.dryRain14dMm / 2 ? 'MEDIUM' : 'LOW';
    evidence.push(
      `Only ${context.rainfallMm} mm of rainfall was recorded in the 14 days before the drop` +
        (context.tempMaxC !== null ? `, with a maximum temperature of ${context.tempMaxC}°C.` : '.'),
    );
    if (context.soilMoisturePct !== null) {
      evidence.push(`Top-soil moisture averaged ${context.soilMoisturePct}% over the same period (modelled estimate).`);
    }
    nextStep = 'Check soil moisture and leaf condition in the field. If the crop is wilting, consider irrigation or seek extension advice.';
  } else if (drop.magnitude >= 0.3 && drop.quality !== 'POOR') {
    classification = CLASSIFICATION.POSSIBLE_HARVEST;
    confidence = 'LOW';
    evidence.push(
      `A single large fall (${round(drop.magnitude)} NDVI) can follow harvest or clearing, but this reading cannot confirm that.`,
      context.available
        ? `Weather before the drop: ${context.rainfallMm} mm rainfall and a maximum of ${context.tempMaxC ?? '—'}°C.`
        : 'No weather observations were available to rule heat or dry stress in or out.',
    );
    nextStep = 'If you harvested or cleared this plot around this date, record it in the event log. That turns this into a confirmed event.';
  } else if (context.available) {
    evidence.push(
      `Weather in the 14 days before the drop: ${context.rainfallMm} mm rainfall, maximum ${context.tempMaxC ?? '—'}°C, ` +
        `mean top-soil moisture ${context.soilMoisturePct ?? '—'}%.`,
    );
  }

  return {
    classification,
    label: CLASSIFICATION_LABELS[classification],
    explanation: docFor(classification),
    evidence,
    confidence,
    nextStep,
    weather: context,
    disclaimer:
      'This is a possible explanation from satellite and weather signals, not a confirmed diagnosis. Field observation is required.',
  };
}

function docFor(classification) {
  switch (classification) {
    case CLASSIFICATION.TRANSIENT_DIP:
      return 'The vegetation index fell and then partly recovered, or the reading itself was uncertain. This is often a temporary dip or a noisy observation.';
    case CLASSIFICATION.POSSIBLE_HARVEST:
      return 'A large, sudden fall can be consistent with harvest or land clearing. Satellite data alone cannot confirm that a harvest took place.';
    case CLASSIFICATION.POSSIBLE_HEAT_OR_DRY_STRESS:
      return 'Low rainfall and high temperature around the fall are consistent with heat or moisture stress. The satellite index cannot confirm the cause.';
    case CLASSIFICATION.GRADUAL_DECLINE:
      return 'Several consecutive observations declined. This may reflect a slow seasonal change, grazing, disease or another pressure; the data cannot identify which.';
    default:
      return 'There is not enough evidence to suggest a cause. Treat this as a signal to inspect the plot, not as a diagnosis.';
  }
}

/* --------------------------------- alerts --------------------------------- */

/**
 * Build the alert set for a plot. `alertKey` is stable for the same ongoing
 * event, so re-running the analysis updates an existing alert instead of
 * creating a duplicate.
 */
export function buildAlerts({ series, summary, drops, growthCycle, weather, now = null, thresholds = DEFAULT_THRESHOLDS }) {
  const alerts = [];
  const today = now ? String(now).slice(0, 10) : new Date().toISOString().slice(0, 10);
  const latestDrop = drops.length > 0 ? drops[drops.length - 1] : null;

  if (latestDrop && !latestDrop.gradual) {
    alerts.push({
      alertKey: `NDVI_DECLINE:${latestDrop.date}`,
      alertType: 'NDVI_DECLINE',
      severity: 'WARNING',
      title: 'Vegetation index declined',
      body: `NDVI fell from ${latestDrop.ndviBefore} to ${latestDrop.ndviAfter} between ${latestDrop.previousDate} and ${latestDrop.date}. ` +
        `Possible explanation: ${CLASSIFICATION_LABELS[latestDrop.explanation?.classification ?? CLASSIFICATION.UNCLEAR].toLowerCase()}.`,
    });
  }

  if (summary.available && summary.latest.ndvi !== null && summary.latest.ndvi < thresholds.stressNdvi) {
    alerts.push({
      alertKey: `POSSIBLE_STRESS:${summary.latest.date}`,
      alertType: 'POSSIBLE_STRESS',
      severity: 'WARNING',
      title: 'Vegetation index is low',
      body: `The latest NDVI is ${summary.latest.ndvi} (below ${thresholds.stressNdvi}). This is an indicator of low vegetation vigour, not a diagnosis. Inspect the plot and check soil moisture.`,
    });
  }

  if (latestDrop && latestDrop.recoveryFraction !== null && latestDrop.recoveryFraction < 0.4) {
    const daysSince = daysBetween(latestDrop.date, today);
    if (daysSince !== null && daysSince >= thresholds.noRecoveryDays) {
      alerts.push({
        alertKey: `NO_RECOVERY:${latestDrop.date}`,
        alertType: 'NO_RECOVERY',
        severity: 'WARNING',
        title: 'No recovery after the vegetation drop',
        body: `Vegetation has not recovered ${daysSince} days after the drop on ${latestDrop.date}. Record what you observe so the event can be classified.`,
      });
    }
  }

  if (summary.available) {
    const age = daysBetween(summary.latest.date, today);
    if (age !== null && age > thresholds.staleDays) {
      alerts.push({
        alertKey: `STALE_OBSERVATION:${summary.latest.date}`,
        alertType: 'STALE_OBSERVATION',
        severity: 'INFO',
        title: 'Satellite observation is out of date',
        body: `The newest clear observation is ${age} days old (${summary.latest.date}). This is the latest available data, not a live reading.`,
      });
    }
  } else {
    alerts.push({
      alertKey: 'NO_OBSERVATION',
      alertType: 'NO_OBSERVATION',
      severity: 'INFO',
      title: 'No usable satellite observation yet',
      body: 'No clear observation covers this plot. Check the boundary and request a new reading when imagery is available.',
    });
  }

  if (weather?.available && weather.summary) {
    const rain = finite(weather.summary.rainfall14dMm);
    const tempMax = finite(weather.summary.tempMax7dC);
    if (rain !== null && rain < thresholds.dryRain14dMm && tempMax !== null && tempMax >= thresholds.heatTempMaxC) {
      alerts.push({
        alertKey: `SATELLITE_WEATHER_RISK:${weather.summary.periodEnd ?? today}`,
        alertType: 'SATELLITE_WEATHER_RISK',
        severity: 'WARNING',
        title: 'Dry and hot conditions',
        body: `Only ${rain} mm of rain in the last 14 days with a maximum of ${tempMax}°C. Combined with the vegetation trend, this is worth checking in the field. Weather is modelled data.`,
      });
    }
  }

  if (growthCycle?.available && growthCycle.greenUpOn) {
    alerts.push({
      alertKey: `GREEN_UP:${growthCycle.greenUpOn}`,
      alertType: 'GREEN_UP',
      severity: 'INFO',
      title: 'Green-up detected',
      body: `Vegetation began to increase around ${growthCycle.greenUpOn} (estimate). Peak so far: ${growthCycle.peakValue ?? '—'}.`,
    });
  }

  return alerts;
}

/* ------------------------------- orchestration ---------------------------- */

/**
 * Full, pure analysis of one plot's observation record.
 *
 * `labelledEventCount` is the number of farmer-confirmed events available as
 * training labels. A model is only claimed when that reaches a defensible
 * sample size; until then the response says rule-based classification is in
 * use and model training is pending.
 */
export function analyseSeries({
  observations = [],
  weather = null,
  now = null,
  thresholds = DEFAULT_THRESHOLDS,
  labelledEventCount = 0,
} = {}) {
  const clean = flagOutliers(sanitiseObservations(observations));
  const series = smoothSeries(clean);
  const summary = summarise(series, { now });
  const growthCycle = detectGrowthCycle(series, thresholds);
  const rawDrops = detectDrops(series, thresholds);
  const gradual = detectGradualDecline(series, thresholds);
  const drops = rawDrops.map((drop) => ({ ...drop, explanation: explainDrop(drop, { weather, thresholds }) }));
  if (gradual) {
    drops.push({ ...gradual, explanation: explainDrop(gradual, { weather, thresholds }) });
    drops.sort((a, b) => toEpoch(a.date) - toEpoch(b.date));
  }
  const alerts = buildAlerts({ series, summary, drops, growthCycle, weather, now, thresholds });

  const usableCount = series.filter((row) => row.ndvi !== null && !row.isOutlier).length;
  const modelReady = labelledEventCount >= 30;

  return {
    series,
    summary,
    growthCycle,
    drops,
    alerts,
    quality: {
      readingsReceived: observations.length,
      readingsUsable: usableCount,
      readingsRejected: observations.length - usableCount,
      outliers: series.filter((row) => row.isOutlier).length,
      note: weather?.available
        ? 'Weather is modelled data from the configured provider. Satellite indices are observations, not yield or disease measurements.'
        : 'Weather data was unavailable; explanations rely on satellite observations only.',
    },
    modelStatus: {
      trained: false,
      readyToTrain: modelReady,
      labelledEvents: labelledEventCount,
      requiredLabels: 30,
      note: modelReady
        ? 'Enough verified farmer events exist to evaluate a model. No model has been trained or validated in this release.'
        : `Rule-based classifications are in use. ${labelledEventCount} of 30 verified farmer events are available; model training is pending.`,
    },
    disclaimer:
      'Vegetation indices indicate vegetation condition. They are not direct proof of yield, disease or the cause of a change.',
  };
}

export default {
  CLASSIFICATION,
  CLASSIFICATION_LABELS,
  DEFAULT_THRESHOLDS,
  sanitiseObservations,
  flagOutliers,
  smoothSeries,
  summarise,
  detectGrowthCycle,
  detectDrops,
  detectGradualDecline,
  weatherWindow,
  explainDrop,
  buildAlerts,
  analyseSeries,
  daysBetween,
  addDays,
  toEpoch,
};
