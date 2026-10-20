import { Router } from 'express';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate, pagination } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmOwnerOnly, farmOwnerOrFarmer } from '../../middleware/roles.js';
import { resolveOwnerFarmerId } from '../farm/records.service.js';
import { notFound } from '../../utils/errors.js';
import { paginated, toPlotEvent, toPlotAlert } from '../../utils/serialize.js';
import { getOwnedPlot, invalidateObservationsForPlot } from './satellite.service.js';
import {
  overviewQuerySchema,
  compareQuerySchema,
  plotBoundarySchema,
  plotNameSchema,
  createEventSchema,
  updateEventSchema,
  eventListQuerySchema,
  alertListQuerySchema,
  alertActionSchema,
  idParamSchema,
} from './satellite.schemas.js';
import {
  getOverview,
  createEvent,
  updateEvent,
  listEvents,
  deleteEvent,
  listAlerts,
  setAlertStatus,
  resolveBoundary,
  latestObservationByPlot,
  activeThresholds,
} from './satellite.monitoring.service.js';
import { one } from '../../db/index.js';
import { z } from 'zod';

const router = Router();

// Satellite monitoring is a view of the same plots farm management shows. Reading
// them — the overview, the weather, the event log, the alerts — is therefore open to
// whoever may read the plot: the owner AND the farmer employed to work it, matching
// `GET /farms/plots`. Without this a hired farmer could see a plot in farm management
// and get a 403 one tap later on satellite, which reads as two different systems.
//
// Writing stays the owner's: mapping and reshaping a boundary, requesting a new
// computation, and creating/editing/deleting events and alert states each carry their
// own `farmOwnerOnly` guard below.
//
// Every handler resolves the *owner's* farmer id via `resolveOwnerFarmerId`, so an
// employed farmer reads the employer's plots and the caller still cannot reach a
// farm they are not linked to.
router.use(authenticate, farmOwnerOrFarmer);

/**
 * GET /satellite/overview
 *
 * Everything the unified Satellite screen needs for one plot in a single round
 * trip: the boundary and its real area/perimeter, the observation series, the
 * summary, the estimated growth cycle, the explained drops, the weather, the
 * alerts and the honest status of the pipeline itself.
 */
router.get(
  '/overview',
  validate(overviewQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const overview = await getOverview({
      farmerId: await resolveOwnerFarmerId(req.user),
      plotId: req.query.plotId,
      days: req.query.days ?? req.query.daysBack,
      refresh: req.query.refresh === 'true' || req.query.refresh === '1',
    });
    res.json(overview);
  }),
);

/**
 * POST /satellite/boundary/preview
 *
 * Measures a boundary the farmer is still drawing, without saving anything. The
 * app calls this on every drag so the area shown is always the real geometry,
 * never a client-side bounding box.
 */
router.post(
  '/boundary/preview',
  farmOwnerOnly,
  validate(z.object({ boundary: plotBoundarySchema })),
  asyncHandler(async (req, res) => {
    const resolved = resolveBoundary(req.body.boundary);
    res.json({
      method: resolved.method,
      geometry: resolved.measurement,
      vertices: resolved.vertices,
      geoJsonBoundary: resolved.geoJson,
      rectangle: resolved.rectangle,
      areaHectares: resolved.measurement.areaHectares,
      areaM2: resolved.measurement.areaM2,
      perimeterM: resolved.measurement.perimeterM,
      note: 'Measurements are computed from the drawn geometry on the server, so the preview matches what will be saved.',
    });
  }),
);

/**
 * PUT /satellite/plots/:id/boundary
 *
 * Save a redrawn boundary and recalculate. The plot is re-checked against the
 * caller, so this cannot be used to reshape someone else's land. All satellite
 * and weather analysis afterwards uses this stored geometry.
 */
router.put(
  '/plots/:id/boundary',
  farmOwnerOnly,
  validate(idParamSchema, 'params'),
  validate(z.object({ boundary: plotBoundarySchema, plotName: plotNameSchema.optional() })),
  asyncHandler(async (req, res) => {
    const plot = await getOwnedPlot(req.user.id, req.params.id);
    const resolved = resolveBoundary(req.body.boundary);

    const updated = await one(
      `UPDATE farm_plots
          SET geojson_boundary = $1::jsonb,
              area_hectares = $2,
              centroid_lat = $3,
              centroid_lng = $4,
              plot_name = COALESCE($5, plot_name),
              updated_at = now()
        WHERE id = $6 AND farmer_id = $7 AND deleted_at IS NULL
        RETURNING *`,
      [
        JSON.stringify(resolved.geoJson),
        resolved.measurement.areaHectares,
        resolved.measurement.centroid?.latitude ?? null,
        resolved.measurement.centroid?.longitude ?? null,
        req.body.plotName ?? null,
        plot.id,
        req.user.id,
      ],
    );

    // A reshaped plot no longer matches the observations taken over the old
    // shape, so the stored series is dropped rather than silently reused. Being
    // explicit here is what stops a boundary edit from corrupting history.
    // Shared with `PATCH /farms/plots/:id`, so both screens invalidate alike.
    const observationsInvalidated = await invalidateObservationsForPlot(plot.id);

    res.json({
      plot: {
        plotId: updated.id,
        plotName: updated.plot_name,
        areaHectares: Number(updated.area_hectares),
        centroidLat: updated.centroid_lat === null ? null : Number(updated.centroid_lat),
        centroidLng: updated.centroid_lng === null ? null : Number(updated.centroid_lng),
        geoJsonBoundary: updated.geojson_boundary,
      },
      geometry: resolved.measurement,
      observationsInvalidated,
      note: 'The boundary was saved and the area recalculated. Previous observations were cleared because they covered the old outline; request a new reading.',
    });
  }),
);

/* ------------------------------- weather --------------------------------- */

/**
 * GET /satellite/weather
 *
 * Weather for a plot's centroid. Modelled data from the configured provider,
 * clearly labelled, and explicitly unavailable rather than invented when the
 * provider cannot be reached.
 */
router.get(
  '/weather',
  validate(compareQuerySchema, 'query'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const plot = await getOwnedPlot(farmerId, req.query.plotId);
    const overview = await getOverview({ farmerId, plotId: plot.id, days: 15 });
    res.json({
      plotId: plot.id,
      weather: overview.weather,
      source: overview.weather?.source ?? null,
      dataType: overview.weather?.dataType ?? null,
    });
  }),
);

/* ------------------------------ event log -------------------------------- */

router.get(
  '/events',
  validate(eventListQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listEvents(await resolveOwnerFarmerId(req.user), {
      plotId: req.query.plotId,
      kind: req.query.kind,
      source: req.query.source,
      limit: pageSize,
      offset,
    });
    res.json(paginated(items.map(toPlotEvent), { page, pageSize, total }));
  }),
);

router.post(
  '/events',
  farmOwnerOnly,
  validate(createEventSchema),
  asyncHandler(async (req, res) => {
    const plot = await getOwnedPlot(req.user.id, req.body.plotId);
    const result = await createEvent({
      farmerId: req.user.id,
      userId: req.user.id,
      plot,
      eventDate: req.body.eventDate,
      kind: req.body.kind,
      notes: req.body.notes,
      detectedEventId: req.body.detectedEventId,
      clientId: req.body.clientId,
    });
    res.status(result.created ? 201 : 200).json({
      ...toPlotEvent(result.event),
      mergedFromDetected: result.mergedFromDetected,
      note: result.mergedFromDetected
        ? 'Your entry replaced the suggested event for that date, so the record is now a verified farmer label.'
        : null,
    });
  }),
);

router.patch(
  '/events/:id',
  farmOwnerOnly,
  validate(idParamSchema, 'params'),
  validate(updateEventSchema),
  asyncHandler(async (req, res) => {
    // The plot is resolved from the event itself and re-checked against the
    // caller, so an id alone is never enough to touch someone else's log.
    const owned = await one(
      'SELECT plot_id FROM plot_events WHERE id = $1 AND farmer_id = $2',
      [req.params.id, req.user.id],
    );
    if (!owned) throw notFound('Event not found.');
    const row = await updateEvent({
      farmerId: req.user.id,
      plotId: owned.plot_id,
      eventId: req.params.id,
      patch: {
        kind: req.body.kind,
        confirmed: req.body.confirmed,
        notes: req.body.notes,
        eventDate: req.body.eventDate,
      },
    });
    res.json(toPlotEvent(row));
  }),
);

router.delete(
  '/events/:id',
  farmOwnerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const owned = await one(
      'SELECT plot_id FROM plot_events WHERE id = $1 AND farmer_id = $2',
      [req.params.id, req.user.id],
    );
    if (!owned) throw notFound('Event not found.');
    await deleteEvent({ farmerId: req.user.id, plotId: owned.plot_id, eventId: req.params.id });
    res.status(204).end();
  }),
);

/* -------------------------------- alerts --------------------------------- */

router.get(
  '/alerts',
  validate(alertListQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;
    const { items, total } = await listAlerts(await resolveOwnerFarmerId(req.user), {
      plotId: req.query.plotId,
      status: req.query.status,
      limit: pageSize,
      offset,
    });
    res.json({
      ...paginated(items.map(toPlotAlert), { page, pageSize, total }),
      thresholds: activeThresholds(),
      thresholdDocumentation: '/docs/12-SATELLITE-MONITORING.md',
      note: 'Alerts are deduplicated by a stable key, so one ongoing event raises one alert. Acknowledging or dismissing keeps the history.',
    });
  }),
);

router.post(
  '/alerts/:id/status',
  farmOwnerOnly,
  validate(idParamSchema, 'params'),
  validate(alertActionSchema),
  asyncHandler(async (req, res) => {
    const row = await setAlertStatus({
      farmerId: req.user.id,
      alertId: req.params.id,
      action: req.body.action,
    });
    res.json(toPlotAlert(row));
  }),
);

/**
 * GET /satellite/plots/latest
 *
 * The newest stored observation for each of the farmer's plots, in one query.
 * Deliberately does not call the provider: opening the plots screen must not
 * fan out into one satellite request per plot.
 */
router.get(
  '/plots/latest',
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const plots = await one(
      'SELECT ARRAY_AGG(id) AS ids FROM farm_plots WHERE farmer_id = $1 AND deleted_at IS NULL',
      [farmerId],
    );
    const ids = plotIdsOrThrow(plots);
    const map = await latestObservationByPlot(farmerId, ids);
    res.json({
      items: ids.map((id) => {
        const row = map.get(id) ?? null;
        return {
          plotId: id,
          observedOn: row ? String(row.observed_on).slice(0, 10) : null,
          ndvi: row?.ndvi === null || row?.ndvi === undefined ? null : Number(row.ndvi),
          evi: row?.evi === null || row?.evi === undefined ? null : Number(row.evi),
          quality: row?.quality ?? null,
          isSimulated: row ? Boolean(row.is_simulated) : null,
          source: row?.source ?? null,
        };
      }),
      note: 'The newest stored observation per plot. This is not a live reading.',
    });
  }),
);

function plotIdsOrThrow(row) {
  const ids = row?.ids;
  if (!Array.isArray(ids)) return [];
  return ids;
}

export default router;
