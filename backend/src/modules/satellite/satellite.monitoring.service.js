/**
 * Satellite monitoring: the orchestration behind the unified Satellite screen.
 *
 * This module is the only place that touches the database for monitoring. It
 * does five things, in this order:
 *
 *   1. loads the plot's stored observation series (and, unless told otherwise,
 *      tops it up from the configured provider);
 *   2. runs the pure analysis engine over that series;
 *   3. persists the series, the detected events and the alerts, deduplicated;
 *   4. records the farmer's own event log against the right farmer and plot;
 *   5. returns one response that can power the whole screen.
 *
 * It never fabricates a reading. When the provider is unavailable the stored
 * series is used and the response says the data is cached and how old it is;
 * when there is no stored series either, the response says the observations are
 * unavailable rather than showing an empty chart as if it were zero.
 */

import { many, one, withTransaction } from '../../db/index.js';
import { badRequest, conflict, notFound } from '../../utils/errors.js';
import { dateOnly } from '../../utils/serialize.js';
import { config } from '../../config/index.js';
import { notify } from '../../services/notifications.js';
import { fetchObservations, boundaryCorners, MOCK_SOURCE } from '../../services/observations.js';
import { fetchWeather } from '../../services/weather.js';
import {
  analyseSeries,
  CLASSIFICATION_LABELS,
  DEFAULT_THRESHOLDS,
} from '../../services/satelliteAnalysis.js';
import { getOwnedPlot } from './satellite.service.js';
import { buildColourMap } from '../../services/ndviGrid.js';
import { measureBoundary, rectangleVertices, geoJsonFromRing, validatePolygon } from '../../services/geometry.js';

/**
 * The thresholds actually in force, from config with the engine defaults as the
 * floor. Exposed on every overview response so the farmer can see — and the
 * client can display — exactly which numbers triggered an alert.
 */
export function activeThresholds() {
  return {
    ...DEFAULT_THRESHOLDS,
    dropAbsolute: config.satellite.dropAbsolute,
    dropRelative: config.satellite.dropRelative,
    staleDays: config.satellite.staleDays,
    stressNdvi: config.satellite.stressNdvi,
    noRecoveryDays: config.satellite.noRecoveryDays,
    dryRain14dMm: config.satellite.dryRain14dMm,
    heatTempMaxC: config.satellite.heatTempMaxC,
  };
}

/** Days clamp, shared by every query so a client cannot ask for decades. */
function clampDays(days) {
  const value = Number.isFinite(Number(days)) ? Math.trunc(Number(days)) : config.satellite.defaultDays;
  return Math.max(15, Math.min(config.satellite.maxDays, value));
}

/* --------------------------- boundary management --------------------------- */

/**
 * Turn a rectangle or polygon request into a stored GeoJSON boundary plus its
 * real measurement. A polygon is validated before anything is written: an
 * invalid outline is rejected with a reason rather than saved and analysed.
 */
export function resolveBoundary(input) {
  if (!input || typeof input !== 'object') {
    throw badRequest('A plot boundary is required.', [
      { field: 'boundary', message: 'Provide either a rotated rectangle or a polygon.' },
    ]);
  }

  if (input.method === 'RECTANGLE') {
    const vertices = rectangleVertices({
      centreLat: input.centreLat,
      centreLon: input.centreLon,
      widthM: input.widthM,
      lengthM: input.lengthM,
      bearingDeg: input.bearingDeg,
    });
    if (!vertices) {
      throw badRequest('The rectangle needs a centre, a width, a length and a bearing.', [
        { field: 'boundary', message: 'One or more rectangle dimensions are missing or zero.' },
      ]);
    }
    const measurement = measureBoundary(vertices);
    return {
      method: 'RECTANGLE',
      geoJson: geoJsonFromRing(vertices),
      vertices,
      measurement,
      rectangle: {
        centreLat: Number(input.centreLat),
        centreLon: Number(input.centreLon),
        widthM: Number(input.widthM),
        lengthM: Number(input.lengthM),
        bearingDeg: Number(input.bearingDeg),
      },
    };
  }

  if (input.method === 'POLYGON') {
    const validation = validatePolygon(input.vertices);
    if (!validation.valid) {
      throw badRequest('The plot outline is not valid.', [
        { field: 'vertices', message: validation.reason },
      ]);
    }
    return {
      method: 'POLYGON',
      geoJson: geoJsonFromRing(validation.measurement.vertices),
      vertices: validation.measurement.vertices,
      measurement: validation.measurement,
      rectangle: null,
    };
  }

  throw badRequest('Unknown boundary method.', [
    { field: 'method', message: 'Use RECTANGLE or POLYGON.' },
  ]);
}

/* ----------------------------- series storage ------------------------------ */

/**
 * Merge a provider series into the stored one.
 *
 * The unique key is (plot_id, observed_on), so a re-request updates that day
 * rather than inserting a second row for it. A row fetched from a provider that
 * turned out to be the mock is still flagged `is_simulated`, and a stored real
 * observation is never downgraded to simulated.
 */
async function upsertObservations({ plotId, farmerId, readings }) {
  if (readings.length === 0) return 0;

  let written = 0;
  await withTransaction(async (client) => {
    for (const reading of readings) {
      const date = String(reading.date ?? '').slice(0, 10);
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue;
      if (reading.ndvi === null && reading.evi === null) continue;

      await client.query(
        `INSERT INTO plot_observations
           (plot_id, farmer_id, observed_on, ndvi, evi, valid_pixel_pct, cloud_cover_pct,
            pixel_count, quality, is_simulated, source)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
         ON CONFLICT (plot_id, observed_on) DO UPDATE
            SET ndvi = EXCLUDED.ndvi,
                evi = EXCLUDED.evi,
                valid_pixel_pct = COALESCE(EXCLUDED.valid_pixel_pct, plot_observations.valid_pixel_pct),
                cloud_cover_pct = COALESCE(EXCLUDED.cloud_cover_pct, plot_observations.cloud_cover_pct),
                pixel_count = COALESCE(EXCLUDED.pixel_count, plot_observations.pixel_count),
                quality = EXCLUDED.quality,
                -- Never turn a real observation into a simulated one.
                is_simulated = plot_observations.is_simulated AND EXCLUDED.is_simulated,
                source = CASE
                           WHEN plot_observations.is_simulated AND NOT EXCLUDED.is_simulated
                             THEN EXCLUDED.source
                           ELSE COALESCE(plot_observations.source, EXCLUDED.source)
                         END,
                updated_at = now()`,
        [
          plotId,
          farmerId,
          date,
          reading.ndvi ?? null,
          reading.evi ?? null,
          reading.validPct ?? null,
          reading.cloudPct ?? null,
          reading.pixels ?? null,
          reading.quality ?? 'GOOD',
          Boolean(reading.isSimulated),
          reading.source ?? null,
        ],
      );
      written += 1;
    }
  });
  return written;
}

/** The stored series, oldest first, bounded to the requested window. */
async function loadStoredObservations(plotId, days) {
  return many(
    `SELECT * FROM plot_observations
      WHERE plot_id = $1 AND observed_on >= (CURRENT_DATE - ($2 || ' days')::interval)
      ORDER BY observed_on ASC
      LIMIT 800`,
    [plotId, String(days)],
  );
}

/* ------------------------------- alerts ----------------------------------- */

/**
 * Persist the alerts derived from an analysis.
 *
 * The stable `alert_key` means re-running the analysis for the same ongoing
 * event updates the existing row (body, severity, raised_at) instead of adding a
 * duplicate. An alert the farmer already dismissed is not resurrected: the
 * status is left alone, so dismissing is respected until the key itself changes.
 */
async function persistAlerts({ plotId, farmerId, alerts }) {
  const created = [];
  const updated = [];

  for (const alert of alerts) {
    const row = await one(
      `INSERT INTO plot_alerts (plot_id, farmer_id, alert_key, alert_type, severity, title, body)
       VALUES ($1,$2,$3,$4,$5,$6,$7)
       ON CONFLICT (plot_id, alert_key) DO UPDATE
          SET severity = EXCLUDED.severity,
              title = EXCLUDED.title,
              body = EXCLUDED.body,
              updated_at = now()
        RETURNING *, (xmax = 0) AS is_new`,
      [plotId, farmerId, alert.alertKey, alert.alertType, alert.severity, alert.title, alert.body],
    );
    if (row.is_new) created.push(row);
    else updated.push(row);
  }

  return { created, updated };
}

/** Turn a freshly raised alert into an in-app notification. */
async function notifyCreatedAlerts({ farmerId, alerts }) {
  for (const alert of alerts) {
    // Only WARNING and CRITICAL interrupts the farmer; INFO stays in the feed.
    if (alert.severity === 'INFO') continue;
    await notify({
      userId: farmerId,
      type: 'NDVI_READY',
      title: alert.title,
      body: alert.body,
      relatedEntityType: 'PlotAlert',
      relatedEntityId: alert.id,
    });
  }
}

/* ------------------------- detected events (system) ------------------------ */

/**
 * Persist the system's detected events so the farmer can confirm or correct
 * them. One row per (plot, event_key), where the key is derived from the date
 * and the classification, so re-running the analysis never duplicates a
 * detected drop. A row the farmer has already turned into a confirmed event
 * keeps its classification and confirmation.
 */
async function persistDetectedEvents({ plotId, farmerId, drops }) {
  const rows = [];

  for (const drop of drops) {
    const classification = drop.explanation?.classification ?? null;
    const key = `${drop.date}:${classification ?? 'UNCLEAR'}`;
    const row = await one(
      `INSERT INTO plot_events
         (plot_id, farmer_id, event_date, kind, source, classification, confirmed, ndvi_before,
          ndvi_after, magnitude, confidence, evidence, weather_context, event_key)
       VALUES ($1,$2,$3,'DETECTED_DROP','SYSTEM',$4,false,$5,$6,$7,$8,$9,$10,$11)
       ON CONFLICT (plot_id, event_key) DO UPDATE
          SET magnitude = EXCLUDED.magnitude,
              confidence = EXCLUDED.confidence,
              evidence = EXCLUDED.evidence,
              weather_context = EXCLUDED.weather_context,
              updated_at = now()
        RETURNING *`,
      [
        plotId,
        farmerId,
        drop.date,
        classification,
        drop.ndviBefore ?? null,
        drop.ndviAfter ?? null,
        drop.magnitude ?? null,
        drop.explanation?.confidence ?? null,
        JSON.stringify({
          items: drop.explanation?.evidence ?? drop.evidence ?? [],
          nextStep: drop.explanation?.nextStep ?? null,
          disclaimer: drop.explanation?.disclaimer ?? null,
          recoveryFraction: drop.recoveryFraction ?? null,
          gradual: Boolean(drop.gradual),
          label: classification ? CLASSIFICATION_LABELS[classification] : null,
        }),
        drop.explanation?.weather ? JSON.stringify(drop.explanation.weather) : null,
        key,
      ],
    );
    rows.push(row);
  }

  return rows;
}

/* -------------------------------- overview -------------------------------- */

/**
 * The full picture for one plot.
 *
 * The response deliberately separates three things a client could otherwise
 * blur together:
 *  - `observationStatus`: whether a fresh provider call succeeded, and whether
 *    what is shown is stored (cached) data;
 *  - `summary.lastDate`: the date of the newest clear observation, which is not
 *    the same thing as today;
 *  - `analysis.disclaimer`: indices indicate condition, they do not prove yield
 *    or disease.
 */
export async function getOverview({ farmerId, plotId, days, refresh = false }) {
  const window = clampDays(days);
  const plot = await getOwnedPlot(farmerId, plotId);
  const boundary = plot.geojson_boundary ?? null;
  const corners = boundaryCorners(boundary);

  const measurement = measureBoundary(boundary);
  const rows = await loadStoredObservations(plotId, window);

  // Fetch from the provider when asked, or when there is nothing stored yet.
  const shouldFetch = refresh || rows.length === 0;
  let providerResult = null;
  let providerError = null;

  if (shouldFetch) {
    try {
      providerResult = await fetchObservations({
        plotId,
        geoJsonBoundary: boundary,
        days: window,
      });
    } catch (err) {
      providerError = err?.message ?? 'The satellite processor could not be reached.';
      if (!config.isTest) {
        // eslint-disable-next-line no-console
        console.warn('[satellite] provider fetch failed', { plotId, error: providerError });
      }
    }
    if (providerResult?.available && providerResult.readings?.length > 0) {
      await upsertObservations({
        plotId,
        farmerId,
        readings: providerResult.readings.map((reading) => ({
          ...reading,
          quality: undefined, // recomputed by the analysis engine from coverage
        })),
      });
    }
  }

  const stored = await loadStoredObservations(plotId, window);
  const isSimulated = stored.some((row) => row.is_simulated);
  const newestStored = stored.length > 0 ? stored[stored.length - 1] : null;

  // Weather: modelled, and clearly labelled as such. Failure is reported, never
  // replaced with invented numbers.
  let weather = null;
  const weatherPoint = measurement.centroid
    ? { latitude: measurement.centroid.latitude, longitude: measurement.centroid.longitude }
    : null;
  if (weatherPoint) {
    weather = await fetchWeather({ latitude: weatherPoint.latitude, longitude: weatherPoint.longitude, days: 14 });
  } else {
    weather = { available: false, reason: 'The plot has no boundary, so there is no point to fetch weather for.' };
  }

  const labelCounts = await one(
    'SELECT harvest_or_clearing_labels, total_farmer_labels FROM plot_event_label_counts WHERE plot_id = $1',
    [plotId],
  );

  const analysis = analyseSeries({
    observations: stored.map((row) => ({
      // `dateOnly` normalises a `Date` (or any form) to `YYYY-MM-DD`; the
      // analysis engine rejects anything else outright, which is how a
      // series of 23 rows once produced `count: 0`.
      date: dateOnly(row.observed_on),
      ndvi: row.ndvi === null ? null : Number(row.ndvi),
      evi: row.evi === null ? null : Number(row.evi),
      validPct: row.valid_pixel_pct === null ? null : Number(row.valid_pixel_pct),
      cloudPct: row.cloud_cover_pct === null ? null : Number(row.cloud_cover_pct),
      pixels: row.pixel_count,
      source: row.source,
      isSimulated: row.is_simulated,
    })),
    weather,
    now: new Date(),
    thresholds: activeThresholds(),
    labelledEventCount: labelCounts?.total_farmer_labels ?? 0,
  });

  const persistedAlerts = await persistAlerts({ plotId, farmerId, alerts: analysis.alerts });
  await notifyCreatedAlerts({ farmerId, alerts: persistedAlerts.created });
  const detectedEvents = await persistDetectedEvents({ plotId, farmerId, drops: analysis.drops });

  const deliveredProvider = providerResult;
  const observationStatus = buildObservationStatus({
    providerResult: deliveredProvider,
    providerError,
    storedCount: stored.length,
    newestStored,
    window,
    isSimulated,
    cornersAvailable: Boolean(corners),
  });

  return {
    plot: {
      plotId: plot.id,
      plotName: plot.plot_name,
      areaHectares: plot.area_hectares === null ? null : Number(plot.area_hectares),
      centroidLat: plot.centroid_lat === null ? null : Number(plot.centroid_lat),
      centroidLng: plot.centroid_lng === null ? null : Number(plot.centroid_lng),
      geoJsonBoundary: boundary,
      imageUrl: plot.image_url ?? null,
    },
    geometry: measurement,
    observationStatus,
    observations: analysis.series.map((row) => ({
      date: row.date,
      ndvi: row.ndvi,
      evi: row.evi,
      smoothedNdvi: row.smoothedNdvi,
      smoothedEvi: row.smoothedEvi,
      validPct: row.validPct,
      cloudPct: row.cloudPct,
      pixels: row.pixels,
      quality: row.quality,
      isOutlier: row.isOutlier,
      isSimulated: row.isSimulated,
      source: row.source,
    })),
    summary: analysis.summary,
    eviSummary: analysis.eviSummary,
    growthCycle: analysis.growthCycle,
    drops: analysis.drops.map((drop) => ({
      date: drop.date,
      previousDate: drop.previousDate,
      daysBetween: drop.daysBetween,
      ndviBefore: drop.ndviBefore,
      ndviAfter: drop.ndviAfter,
      eviBefore: drop.eviBefore,
      eviAfter: drop.eviAfter,
      magnitude: drop.magnitude,
      relativePct: drop.relativePct,
      quality: drop.quality,
      recoveryFraction: drop.recoveryFraction,
      recoveredOn: drop.recoveredOn,
      gradual: Boolean(drop.gradual),
      classification: drop.explanation?.classification ?? null,
      label: drop.explanation?.label ?? null,
      explanation: drop.explanation?.explanation ?? null,
      evidence: drop.explanation?.evidence ?? [],
      confidence: drop.explanation?.confidence ?? null,
      nextStep: drop.explanation?.nextStep ?? null,
      weatherContext: drop.explanation?.weather ?? null,
      disclaimer: drop.explanation?.disclaimer ?? null,
      detectedEventId: detectedEvents.find((row) => row.event_date === drop.date)?.id ?? null,
    })),
    weather,
    alerts: persistedAlerts.created.concat(persistedAlerts.updated).map((row) => ({
      alertId: row.id,
      alertKey: row.alert_key,
      alertType: row.alert_type,
      severity: row.severity,
      title: row.title,
      body: row.body,
      status: row.status,
      raisedAt: row.raised_at,
    })),
    quality: analysis.quality,
    modelStatus: analysis.modelStatus,
    thresholds: activeThresholds(),
    thresholdDocumentation: '/docs/12-SATELLITE-MONITORING.md',
    disclaimer: analysis.disclaimer,
    generatedAt: new Date().toISOString(),
  };
}

/**
 * The colour map ("satellite sight") for a plot: a per-pixel grid of the newest
 * usable observation, plus everything needed to read it honestly.
 *
 * This deliberately does NOT call the provider. The picture must show the same
 * number the rest of the screen shows, so it is drawn from the *stored* series.
 * If it triggered a fresh fetch the image and the summary could disagree, and the
 * user would have no way to know which to believe.
 *
 * The response always carries the observation's own honesty flags — whether the
 * data is simulated (MOCK), how old it is, and whether the coverage was good —
 * because the colour map is the most persuasive thing on the screen and it must
 * not persuade anyone of something the data does not say.
 *
 * @param {{farmerId: string, plotId: string, index?: string, size?: number, days?: number}} params
 */
export async function getColourMap({ farmerId, plotId, index = 'ndvi', size, days = config.satellite.defaultDays, date = null }) {
  const plot = await getOwnedPlot(farmerId, plotId);
  const window = clampDays(days);
  const stored = await loadStoredObservations(plotId, window);

  const boundary = plot.geojson_boundary ?? null;
  const field = index === 'evi' ? 'evi' : 'ndvi';
  const series = stored
    .map((row) => ({
      date: dateOnly(row.observed_on),
      value: row[field] === null || row[field] === undefined ? null : Number(row[field]),
      validPct: row.valid_pixel_pct === null ? null : Number(row.valid_pixel_pct),
      cloudPct: row.cloud_cover_pct === null ? null : Number(row.cloud_cover_pct),
      quality: row.quality,
      source: row.source,
      isSimulated: Boolean(row.is_simulated),
    }))
    .filter((row) => row.value !== null);

  const built = buildColourMap({
    plot: { plotId: plot.id, geoJsonBoundary: boundary },
    series,
    index: field,
    size,
    date,
  });

  if (!built.available) {
    return {
      ...built,
      plotId: plot.id,
      plotName: plot.plot_name,
      index: field,
      hasBoundary: Boolean(boundary),
      observationCount: series.length,
    };
  }

  // The staleness context, computed for the *selected* date, so an old picture
  // can never look fresher than the reading behind it.
  const newest = series[series.length - 1];
  const selectedDate = built.observedOn;
  const selectedRow = series.find((row) => dateOnly(row.date) === selectedDate) ?? newest;
  // A missing or malformed date reports unknown age rather than `NaN`, which
  // would otherwise reach `isStale` and the "N days old" sentence.
  const selectedEpoch = selectedDate ? new Date(`${dateOnly(selectedDate)}T00:00:00Z`).getTime() : NaN;
  const ageDays = Number.isNaN(selectedEpoch)
    ? null
    : Math.floor((Date.now() - selectedEpoch) / 86_400_000);
  const staleDays = config.satellite.staleDays;

  return {
    ...built,
    plotId: plot.id,
    plotName: plot.plot_name,
    geoJsonBoundary: boundary,
    hasBoundary: true,
    observationCount: series.length,
    ageDays,
    // `null` age (an unreadable date) is NOT fresh: it is unknown. Reporting it
    // as not-stale would let an undated reading pass as current.
    isStale: ageDays === null ? true : ageDays > staleDays,
    staleDays,
    // What the *selected* reading actually was, so the legend can be read against it.
    latest: {
      date: selectedDate,
      value: selectedRow.value,
      validPixelPct: selectedRow.validPct,
      cloudCoverPct: selectedRow.cloudPct,
      quality: selectedRow.quality,
      source: selectedRow.source,
      isSimulated: selectedRow.isSimulated,
    },
    // A single sentence that says what the *average* means, in plain language.
    interpretation: describeIndex(field, built.meanValue, ageDays, staleDays, Boolean(built.isSimulated)),
    disclaimer:
      'The average of this picture is a measured, field-average index value. The pattern across the '
      + 'picture is a simulated texture, not a per-pixel measurement, and cannot be used to pinpoint a '
      + 'spot in the field. This is an indicator of vegetation condition, not proof of yield or disease.',
  };
}

/** Plain-language reading of an index value, for the "what this means" block. */
export function describeIndex(index, value, ageDays, staleDays, isSimulated) {
  const label = index === 'evi' ? 'EVI' : 'NDVI';
  // Guard the null/undefined/'' cases before `Number()`, which would turn all of
  // them into 0 and read as bare soil — the exact confusion this must avoid.
  const n = value === null || value === undefined || value === '' ? NaN : Number(value);
  if (!Number.isFinite(n)) {
    return `${label} is unavailable. There is no usable reading for this plot, so no condition can be described.`;
  }

  let band;
  if (n < 0.2) band = 'Very little green cover. The ground is mostly bare soil, stubble or newly planted.';
  else if (n < 0.35) band = 'Sparse or struggling vegetation. The crop is thin, or the plants are under stress.';
  else if (n < 0.5) band = 'Moderate cover. The crop is growing but has not closed over the ground yet.';
  else if (n < 0.65) band = 'Healthy, actively growing cover. Typical of a well-developing crop.';
  else band = 'Dense, vigorous canopy. Strong green cover across the plot.';

  // An unreadable age must not be described as fresh. "This is from today" on an
  // undated reading is exactly the false freshness the colour map must never imply.
  let freshness;
  if (ageDays === null || ageDays === undefined || Number.isNaN(ageDays)) {
    freshness = ' The date of this reading could not be read, so treat it as unverified rather than current.';
  } else if (ageDays > staleDays) {
    freshness = ` This is ${ageDays} days old, so treat it as history rather than a current picture.`;
  } else {
    freshness = ` This is ${ageDays === 0 ? 'from today' : `${ageDays} day${ageDays === 1 ? '' : 's'} old`}.`;
  }

  return `${label} is ${n.toFixed(2)}. ${band}${freshness}`;
}

/**
 * Describe honestly where the observations came from.
 *
 * Three distinct states matter to the farmer: fresh from the processor, served
 * from the stored series because the processor was not asked (or failed), and
 * nothing at all. They are never collapsed into one "success".
 */
function buildObservationStatus({
  providerResult,
  providerError,
  storedCount,
  newestStored,
  window,
  isSimulated,
  cornersAvailable,
}) {
  // A date with no usable value must report `null` rather than `NaN`. The
  // earlier code built `new Date('Thu Oct 22T00:00:00Z')` from a mis-formatted
  // column and emitted the literal string "NaN day(s) old" into the note the
  // farmer reads. Guarding here means a bad date degrades to "unknown age",
  // which is honest, instead of a nonsense sentence.
  const newestDate = newestStored ? dateOnly(newestStored.observed_on) : null;
  const ageDays = newestDate && !Number.isNaN(new Date(`${newestDate}T00:00:00Z`).getTime())
    ? Math.max(0, Math.round(
        (Date.UTC(
          new Date().getUTCFullYear(),
          new Date().getUTCMonth(),
          new Date().getUTCDate(),
        ) - new Date(`${newestDate}T00:00:00Z`).getTime()) / 86_400_000,
      ))
    : null;

  const base = {
    /** What the provider said, independent of what is stored. */
    provider: providerResult
      ? {
        available: Boolean(providerResult.available),
        source: providerResult.source ?? null,
        isSimulated: Boolean(providerResult.isSimulated),
        returned: providerResult.readings?.length ?? 0,
        note: providerResult.note ?? null,
        providerFailure: providerResult.providerFailure ?? null,
      }
      : null,
    providerError,
    /** The series actually returned to the client. */
    returned: storedCount,
    isCached: !providerResult || !providerResult.available,
    isSimulated: isSimulated || Boolean(providerResult?.isSimulated),
    coversDays: window,
    boundaryUsable: cornersAvailable,
    newestObservation: newestDate,
    newestObservationAgeDays: ageDays,
    /** The newest observation is not "now"; the client must not imply it is. */
    note: null,
  };

  if (!cornersAvailable) {
    base.note = 'This plot has no usable boundary, so no satellite observation can cover it.';
    return base;
  }
  if (providerResult?.available && providerResult.readings?.length > 0) {
    base.note = providerResult.isSimulated
      ? 'A demonstration series was generated on the server because no Sentinel-2 processor is configured. These are not real observations.'
      : `Sentinel-2 observations were retrieved from the configured processor. The newest is ${base.newestObservation}, which is not necessarily today.`;
    return base;
  }
  if (storedCount > 0) {
    base.note = `The satellite processor returned no new observations, so the stored series is shown. The newest reading is ${base.newestObservation}, ${ageDays} day(s) old. This is not a live reading.`;
    if (providerResult?.providerFailure) base.note += ` Processor response: ${providerResult.providerFailure}`;
    return base;
  }
  base.note = providerError
    ? `No satellite observations are available and the processor could not be reached: ${providerError}`
    : 'No satellite observations cover this plot yet.';
  return base;
}

/* ------------------------------- event log -------------------------------- */

/**
 * Record a farmer's own event.
 *
 * The plot is re-checked against the caller, so a farmer can only ever write
 * against their own plot no matter what id the client sends. When
 * `detectedEventId` is supplied the detected row is *updated* into the farmer's
 * entry rather than duplicated, which is what turns a suggestion into a label.
 */
export async function createEvent({ farmerId, userId, plot,
  eventDate, kind, notes, detectedEventId, clientId }) {
  const owned = plot ?? null;
  if (!owned) throw notFound('Plot not found.');

  if (detectedEventId) {
    const detected = await one(
      'SELECT * FROM plot_events WHERE id = $1 AND plot_id = $2 AND farmer_id = $3',
      [detectedEventId, owned.id, farmerId],
    );
    if (!detected) throw notFound('Detected event not found for this plot.');

    const merged = await one(
      `UPDATE plot_events
          SET kind = $1,
              source = 'FARMER',
              confirmed = true,
              notes = COALESCE($2, notes),
              event_date = $3,
              recorded_by = $4,
              client_id = COALESCE($5, client_id),
              updated_at = now()
        WHERE id = $6
        RETURNING *`,
      [kind, notes ?? null, eventDate, userId ?? null, clientId ?? null, detectedEventId],
    );
    return { event: merged, created: false, mergedFromDetected: true };
  }

  if (clientId) {
    const existing = await one(
      'SELECT * FROM plot_events WHERE plot_id = $1 AND client_id = $2',
      [owned.id, clientId],
    );
    if (existing) return { event: existing, created: false, mergedFromDetected: false };
  }

  // `ON CONFLICT DO NOTHING` (rather than a named target) because two different
  // unique constraints could fire here: the client-id replay guard and the
  // detected-event key. Allowing either to conflict without an error is the
  // point — an offline queue that replays the same event twice must not create
  // two rows, and must not 500 either.
  const inserted = await one(
    `INSERT INTO plot_events
       (plot_id, farmer_id, event_date, kind, source, confirmed, notes, recorded_by, client_id)
     VALUES ($1,$2,$3,$4,'FARMER',true,$5,$6,$7)
     ON CONFLICT DO NOTHING
     RETURNING *`,
    [owned.id, farmerId, eventDate, kind, notes ?? null, userId ?? null, clientId ?? null],
  );
  if (inserted) return { event: inserted, created: true, mergedFromDetected: false };

  // The insert was a duplicate. Return the row that already exists rather than
  // reporting a failure the farmer cannot act on.
  const duplicate = clientId
    ? await one('SELECT * FROM plot_events WHERE plot_id = $1 AND client_id = $2', [owned.id, clientId])
    : null;
  if (duplicate) return { event: duplicate, created: false, mergedFromDetected: false };
  throw conflict('An event for that plot and date already exists.');
}

/** Confirm or correct a detected event, without losing what the system said. */
export async function updateEvent({ farmerId, plotId, eventId, patch }) {
  const existing = await one(
    'SELECT * FROM plot_events WHERE id = $1 AND plot_id = $2 AND farmer_id = $3',
    [eventId, plotId, farmerId],
  );
  if (!existing) throw notFound('Event not found for this plot.');

  const nextKind = patch.kind ?? existing.kind;
  // A farmer correcting a SYSTEM suggestion is what makes it verified data.
  // Supplying a `kind` is itself a correction, so it counts as a confirmation —
  // otherwise a row could be relabelled by the farmer yet never be usable as a
  // training label, which is silently wrong.
  const becomesFarmer = patch.confirmed === true || patch.kind !== undefined;
  const confirmed = patch.confirmed ?? (patch.kind !== undefined ? true : null);

  const row = await one(
    `UPDATE plot_events
        SET kind = $1,
            confirmed = COALESCE($2, confirmed),
            notes = COALESCE($3, notes),
            event_date = COALESCE($4, event_date),
            source = CASE WHEN $5 THEN 'FARMER' ELSE source END,
            updated_at = now()
      WHERE id = $6
      RETURNING *`,
    [nextKind, confirmed, patch.notes ?? null, patch.eventDate ?? null, becomesFarmer, eventId],
  );
  return row;
}

export async function listEvents(farmerId, { plotId, kind, source, limit, offset }) {
  const clauses = ['farmer_id = $1', 'plot_id = $2'];
  const params = [farmerId, plotId];
  if (kind) {
    params.push(kind);
    clauses.push(`kind = $${params.length}`);
  }
  if (source) {
    params.push(source);
    clauses.push(`source = $${params.length}`);
  }
  const where = clauses.join(' AND ');

  const totalRow = await one(`SELECT COUNT(*)::int AS total FROM plot_events WHERE ${where}`, params);
  const rows = await many(
    `SELECT * FROM plot_events WHERE ${where}
      ORDER BY event_date DESC, created_at DESC
      LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
    [...params, limit, offset],
  );
  return { items: rows, total: totalRow.total };
}

export async function deleteEvent({ farmerId, plotId, eventId }) {
  const row = await one(
    `DELETE FROM plot_events
      WHERE id = $1 AND plot_id = $2 AND farmer_id = $3 AND source = 'FARMER'
      RETURNING id`,
    [eventId, plotId, farmerId],
  );
  // A system-detected row is not the farmer's to delete; that is a conflict, not
  // a silent no-op, so the app can tell them to dismiss the suggestion instead.
  if (!row) {
    const exists = await one(
      'SELECT id, source FROM plot_events WHERE id = $1 AND plot_id = $2 AND farmer_id = $3',
      [eventId, plotId, farmerId],
    );
    if (exists) {
      throw conflict('A detected event cannot be deleted. Confirm or correct it instead.');
    }
    throw notFound('Event not found for this plot.');
  }
  return row;
}

/* -------------------------------- alerts ---------------------------------- */

export async function listAlerts(farmerId, { plotId, status, limit, offset }) {
  const clauses = ['farmer_id = $1'];
  const params = [farmerId];
  if (plotId) {
    params.push(plotId);
    clauses.push(`plot_id = $${params.length}`);
  }
  if (status && status !== 'ALL') {
    params.push(status);
    clauses.push(`status = $${params.length}`);
  }
  const where = clauses.join(' AND ');

  const totalRow = await one(`SELECT COUNT(*)::int AS total FROM plot_alerts WHERE ${where}`, params);
  const rows = await many(
    `SELECT * FROM plot_alerts WHERE ${where}
      ORDER BY raised_at DESC
      LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
    [...params, limit, offset],
  );
  return { items: rows, total: totalRow.total };
}

/**
 * Move an alert between OPEN, ACKNOWLEDGED and DISMISSED. The row is never
 * removed, so the history of what the farmer was told — and what they did about
 * it — survives. A dismissed alert is not re-opened by the next analysis run
 * because `persistAlerts` leaves the status alone on conflict.
 */
export async function setAlertStatus({ farmerId, plotId, alertId, action }) {
  const alert = await one(
    'SELECT * FROM plot_alerts WHERE id = $1 AND farmer_id = $2 AND ($3::uuid IS NULL OR plot_id = $3)',
    [alertId, farmerId, plotId ?? null],
  );
  if (!alert) throw notFound('Alert not found.');

  const map = {
    ACKNOWLEDGE: { status: 'ACKNOWLEDGED', column: 'acknowledged_at' },
    DISMISS: { status: 'DISMISSED', column: 'dismissed_at' },
    REOPEN: { status: 'OPEN', column: null },
  };
  const target = map[action];
  if (!target) throw badRequest('Unknown alert action.', [{ field: 'action', message: 'Use ACKNOWLEDGE, DISMISS or REOPEN.' }]);

  const row = await one(
    `UPDATE plot_alerts
        SET status = $1,
            ${target.column ? `${target.column} = now(),` : ''}
            resolved_at = CASE WHEN $1 = 'OPEN' THEN NULL ELSE resolved_at END,
            updated_at = now()
      WHERE id = $2
      RETURNING *`,
    [target.status, alertId],
  );
  return row;
}

/* ------------------------------ housekeeping ------------------------------ */

/**
 * Farm-wide alert and attention summary: one row per plot, so the farmer can see
 * *which* of their farms need attention instead of having to open each one.
 *
 * Why this exists: `listAlerts` answers "what alerts does this one plot have?".
 * A farmer with eight plots was told nothing about the other seven. This function
 * answers the question they actually have — "where in my farm do I need to go?"
 *
 * Two kinds of attention are reported, and they are kept visibly distinct because
 * they mean different things:
 *
 *   1. **Alerts** — the analysis engine raised something (drop, stress, dry spell,
 *      heat, staleness) and it is deduplicated in `plot_alerts`.
 *   2. **No data** — the plot has no usable observation at all (never read, or its
 *      boundary was just redrawn). Silence here is NOT good news, and this function
 *      refuses to let a forgotten plot look healthy by omission.
 *
 * Nothing is invented: every field comes from stored rows. A plot with no alerts
 * and recent data is reported as such rather than dropped from the list.
 *
 * @param {string} farmerId
 * @returns {{plots: object[], totals: object}} grouped-for-display rows plus counts.
 */
export async function alertsByFarm(farmerId, { includeHealthy = true } = {}) {
  // The schema has no separate farm entity: a plot belongs directly to the
  // owner (farm_plots.farmer_id) and the owner's account *is* the farm. So the
  // "farm" this groups by is the owner's own holding, labelled with their name.
  const owner = await one('SELECT id, full_name FROM users WHERE id = $1', [farmerId]);
  const farmName = owner?.full_name ?? 'My farm';

  // Every live plot with the newest usable observation and the open-alert
  // tally, in one round trip. LEFT JOINs so a plot with no observations and no
  // alerts still appears — that is the whole point.
  const rows = await many(
    `SELECT p.id                AS plot_id,
            p.plot_name,
            p.area_hectares,
            (SELECT COUNT(*)::int
               FROM plot_alerts a
              WHERE a.plot_id = p.id AND a.farmer_id = $1 AND a.status = 'OPEN') AS open_alerts,
            (SELECT COUNT(*)::int
               FROM plot_alerts a
              WHERE a.plot_id = p.id AND a.farmer_id = $1) AS total_alerts,
            obs.observed_on,
            obs.ndvi,
            obs.quality,
            obs.is_simulated,
            obs.source
       FROM farm_plots p
       LEFT JOIN LATERAL (
            SELECT observed_on, ndvi, quality, is_simulated, source
              FROM plot_observations
             WHERE plot_id = p.id AND farmer_id = $1
             ORDER BY observed_on DESC
             LIMIT 1
       ) obs ON true
      WHERE p.farmer_id = $1 AND p.deleted_at IS NULL
      ORDER BY p.plot_name`,
    [farmerId],
  );

  const openAlerts = await many(
    `SELECT a.id, a.plot_id, a.alert_type, a.severity, a.title, a.body, a.raised_at, a.status,
            p.plot_name
       FROM plot_alerts a
       JOIN farm_plots p ON p.id = a.plot_id
      WHERE a.farmer_id = $1 AND a.status = 'OPEN'
      ORDER BY a.raised_at DESC`,
    [farmerId],
  );

  const alertsByPlot = new Map();
  for (const alert of openAlerts) {
    const list = alertsByPlot.get(alert.plot_id) ?? [];
    list.push({
      alertId: alert.id,
      kind: alert.alert_type,
      severity: alert.severity,
      title: alert.title,
      body: alert.body,
      raisedAt: alert.raised_at,
      status: alert.status,
    });
    alertsByPlot.set(alert.plot_id, list);
  }

  const staleDays = config.satellite.staleDays;
  const now = Date.now();

  const plots = rows.map((row) => {
    const alerts = alertsByPlot.get(row.plot_id) ?? [];
    // "No data" is a first-class attention state, not an empty list.
    const observedOn = dateOnly(row.observed_on);
    const hasObservation = Boolean(observedOn);
    // Parse the calendar date at UTC midnight. `new Date('Thu Oct 22')` is
    // Invalid Date, and an `NaN` age would make `ageDays > staleDays` false —
    // hiding a stale plot behind a "healthier" answer than the truth.
    const observedEpoch = hasObservation ? new Date(`${observedOn}T00:00:00Z`).getTime() : NaN;
    const ageDays = Number.isFinite(observedEpoch)
      ? Math.floor((now - observedEpoch) / 86_400_000)
      : null;
    const isStale = hasObservation ? (ageDays === null || ageDays > staleDays) : false;
    const noData = !hasObservation;
    const needsAttention = alerts.length > 0 || noData || isStale;

    let reason = null;
    if (noData) reason = 'No satellite reading for this plot yet.';
    else if (isStale) reason = `No clear reading in the last ${staleDays} days (newest is ${ageDays} days old).`;
    else if (alerts.length > 0) reason = alerts[0].title;

    return {
      plotId: row.plot_id,
      plotName: row.plot_name,
      farmId: farmerId,
      farmName,
      areaHectares: row.area_hectares === null ? null : Number(row.area_hectares),
      openAlertCount: row.open_alerts,
      totalAlertCount: row.total_alerts,
      alerts,
      latestObservationDate: observedOn,
      latestObservationAgeDays: ageDays,
      latestNdvi: row.ndvi === null || row.ndvi === undefined ? null : Number(row.ndvi),
      quality: row.quality,
      isSimulated: Boolean(row.is_simulated),
      source: row.source,
      hasObservation,
      isStale,
      noData,
      needsAttention,
      reason,
    };
  });

  const reported = includeHealthy ? plots : plots.filter((p) => p.needsAttention);

  // There is one farm per owner in this schema, so the display group is the
  // owner's holding. It is emitted even when every plot is filtered out, so the
  // client always has a stable label to render.
  const farms = [
    {
      farmId: farmerId,
      farmName,
      plots: reported,
      openAlertCount: reported.reduce((sum, p) => sum + p.openAlertCount, 0),
      plotsNeedingAttention: reported.filter((p) => p.needsAttention).length,
    },
  ];

  return {
    plots: reported,
    farms,
    totals: {
      plots: reported.length,
      plotsNeedingAttention: reported.filter((p) => p.needsAttention).length,
      plotsWithNoData: reported.filter((p) => p.noData).length,
      plotsStale: reported.filter((p) => p.isStale).length,
      openAlerts: reported.reduce((sum, p) => sum + p.openAlertCount, 0),
      farmsAffected: farms.filter((f) => f.plotsNeedingAttention > 0).length,
    },
    thresholds: activeThresholds(),
    note:
      'Every plot is listed, including plots with no data at all, so a forgotten '
      + 'field cannot look healthy by being left out. Totals count open alerts only.',
  };
}

/**
 * The most recent observation per plot, for the plots screen. Kept separate
 * from the overview so listing ten plots does not trigger ten provider calls.
 */
export async function latestObservationByPlot(farmerId, plotIds) {
  if (!Array.isArray(plotIds) || plotIds.length === 0) return new Map();
  const rows = await many(
    `SELECT DISTINCT ON (plot_id) *
       FROM plot_observations
      WHERE farmer_id = $1 AND plot_id = ANY($2::uuid[])
      ORDER BY plot_id, observed_on DESC`,
    [farmerId, plotIds],
  );
  return new Map(rows.map((row) => [row.plot_id, row]));
}

export const MONITORING_SOURCE_LABEL = {
  [MOCK_SOURCE]: 'Demonstration data (not real observations)',
};

export default {
  getOverview,
  createEvent,
  updateEvent,
  listEvents,
  deleteEvent,
  listAlerts,
  setAlertStatus,
  resolveBoundary,
  activeThresholds,
  latestObservationByPlot,
};
