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
      date: row.observed_on,
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
  const ageDays = newestStored
    ? Math.max(0, Math.round(
        (Date.UTC(
          new Date().getUTCFullYear(),
          new Date().getUTCMonth(),
          new Date().getUTCDate(),
        ) - new Date(`${newestStored.observed_on}`.slice(0, 10) + 'T00:00:00Z').getTime()) / 86_400_000,
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
    newestObservation: newestStored ? String(newestStored.observed_on).slice(0, 10) : null,
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
