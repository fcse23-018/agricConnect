import { Router } from 'express';

import { many, one, withTransaction } from '../../db/index.js';
import { asyncHandler } from '../../utils/asyncHandler.js';
import { validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { farmOwnerOnly, farmOwnerOrFarmer } from '../../middleware/roles.js';
import { notFound } from '../../utils/errors.js';
import { toPlot } from '../../utils/serialize.js';
import { estimateAreaHectares } from '../../services/gee.js';
import { audit } from '../../services/audit.js';
import { resolveOwnerFarmerId } from '../farm/records.service.js';
import {
  boundaryChanged,
  invalidateObservationsForPlot,
} from '../satellite/satellite.service.js';
import { createPlotSchema, updatePlotSchema, idParamSchema } from './farms.schemas.js';

const router = Router();

// Reading plots is shared with the farmer who works the land — they need the plot
// list to record harvests against it. Mapping (create/update/delete) is the owner's
// capability, so those routes carry their own `farmOwnerOnly` guard below.
router.use(authenticate);

/** Pull the outer ring out of the supported GeoJSON shapes. */
function boundaryRing(geoJson) {
  if (!geoJson || typeof geoJson !== 'object') return null;
  if (geoJson.type === 'Feature') return geoJson.geometry?.coordinates?.[0] ?? null;
  if (geoJson.type === 'MultiPolygon') return geoJson.coordinates?.[0]?.[0] ?? null;
  if (geoJson.type === 'Polygon') return geoJson.coordinates?.[0] ?? null;
  return null;
}

/**
 * Centroid = average of the polygon vertices. GeoJSON coordinates are
 * [longitude, latitude]. The closing vertex duplicates the first, so it is
 * dropped before averaging to avoid biasing the result toward the start point.
 */
function centroidOf(geoJson) {
  const ring = boundaryRing(geoJson);
  if (!Array.isArray(ring) || ring.length === 0) return { lat: null, lng: null };

  const first = ring[0];
  const last = ring[ring.length - 1];
  const closed = ring.length > 1 && first?.[0] === last?.[0] && first?.[1] === last?.[1];
  const vertices = (closed ? ring.slice(0, -1) : ring).filter(
    (point) => Array.isArray(point) && Number.isFinite(Number(point[0])) && Number.isFinite(Number(point[1])),
  );
  if (vertices.length === 0) return { lat: null, lng: null };

  const lng = vertices.reduce((sum, point) => sum + Number(point[0]), 0) / vertices.length;
  const lat = vertices.reduce((sum, point) => sum + Number(point[1]), 0) / vertices.length;
  return { lat, lng };
}

function serialiseBoundary(geoJson) {
  return geoJson ? JSON.stringify(geoJson) : null;
}

/** GET /farms/plots — the caller's live plots, newest first. */
router.get(
  '/plots',
  farmOwnerOrFarmer,
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const rows = await many(
      `SELECT * FROM farm_plots
        WHERE farmer_id = $1 AND deleted_at IS NULL
        ORDER BY created_at DESC`,
      [farmerId],
    );
    res.json({ items: rows.map(toPlot) });
  }),
);

/**
 * POST /farms/plots
 *
 * Offline-first replay safety: if the device supplies a `clientId` that already
 * exists for this farmer, the row is updated (and any soft-delete is lifted)
 * rather than duplicated. A fresh row returns 201; a replay returns 200.
 */
router.post(
  '/plots',
  farmOwnerOnly,
  validate(createPlotSchema),
  asyncHandler(async (req, res) => {
    const { plotName, geoJsonBoundary, clientId } = req.body;
    const boundary = geoJsonBoundary ?? null;
    const imageUrl = req.body.imageUrl ?? null;
    const areaHectares = req.body.areaHectares ?? (boundary ? estimateAreaHectares(boundary) : null);
    const { lat, lng } = centroidOf(boundary);

    const { row, created } = await withTransaction(async (client) => {
      if (clientId) {
        const existing = await client.query(
          `SELECT id FROM farm_plots WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
          [req.user.id, clientId],
        );
        if (existing.rows[0]) {
          const updated = await client.query(
            `UPDATE farm_plots
                SET plot_name = $1, area_hectares = $2, geojson_boundary = $3,
                    centroid_lat = $4, centroid_lng = $5, notes = $6, image_url = $7, deleted_at = NULL
              WHERE id = $8 AND farmer_id = $9
              RETURNING *`,
            [plotName, areaHectares, serialiseBoundary(boundary), lat, lng, req.body.notes ?? null, imageUrl, existing.rows[0].id, req.user.id],
          );
          return { row: updated.rows[0], created: false };
        }
      }

      const inserted = await client.query(
        `INSERT INTO farm_plots (farmer_id, client_id, plot_name, area_hectares, geojson_boundary, centroid_lat, centroid_lng, notes, image_url)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
         RETURNING *`,
        [req.user.id, clientId ?? null, plotName, areaHectares, serialiseBoundary(boundary), lat, lng, req.body.notes ?? null, imageUrl],
      );
      return { row: inserted.rows[0], created: true };
    });

    await audit(req, created ? 'PLOT_CREATED' : 'PLOT_UPDATED', {
      entityType: 'FarmPlot',
      entityId: row.id,
      metadata: { clientId: clientId ?? null },
    });

    res.status(created ? 201 : 200).json(toPlot(row));
  }),
);

/** GET /farms/plots/:id — a plot on the caller's farm; others' ids collapse to 404. */
router.get(
  '/plots/:id',
  farmOwnerOrFarmer,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const farmerId = await resolveOwnerFarmerId(req.user);
    const row = await one(
      `SELECT * FROM farm_plots WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL`,
      [req.params.id, farmerId],
    );
    if (!row) throw notFound('Plot not found.');
    res.json(toPlot(row));
  }),
);

/** PATCH /farms/plots/:id — partial update of an owned, live plot. */
router.patch(
  '/plots/:id',
  farmOwnerOnly,
  validate(idParamSchema, 'params'),
  validate(updatePlotSchema),
  asyncHandler(async (req, res) => {
    const body = req.body;
    const assignments = [];
    const params = [];

    if ('plotName' in body) {
      params.push(body.plotName);
      assignments.push(`plot_name = $${params.length}`);
    }
    if ('areaHectares' in body) {
      params.push(body.areaHectares);
      assignments.push(`area_hectares = $${params.length}`);
    }
    if ('geoJsonBoundary' in body) {
      const { lat, lng } = centroidOf(body.geoJsonBoundary);
      params.push(serialiseBoundary(body.geoJsonBoundary));
      assignments.push(`geojson_boundary = $${params.length}`);
      params.push(lat);
      assignments.push(`centroid_lat = $${params.length}`);
      params.push(lng);
      assignments.push(`centroid_lng = $${params.length}`);
    }
    if ('notes' in body) {
      params.push(body.notes);
      assignments.push(`notes = $${params.length}`);
    }
    if ('imageUrl' in body) {
      params.push(body.imageUrl);
      assignments.push(`image_url = $${params.length}`);
    }

    params.push(req.params.id);
    const idPlaceholder = `$${params.length}`;
    params.push(req.user.id);
    const farmerPlaceholder = `$${params.length}`;

    // Read the current row first so the handler can tell an actual geometry
    // change from a no-op save (the app resends the same ring on every save, and
    // an offline replay can resend a payload). Wiping the observation series on
    // an unchanged boundary would destroy history for nothing.
    const existing = await one(
      `SELECT * FROM farm_plots WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL`,
      [req.params.id, req.user.id],
    );
    if (!existing) throw notFound('Plot not found.');

    const row = await one(
      `UPDATE farm_plots SET ${assignments.join(', ')}
        WHERE id = ${idPlaceholder} AND farmer_id = ${farmerPlaceholder} AND deleted_at IS NULL
        RETURNING *`,
      params,
    );
    if (!row) throw notFound('Plot not found.');

    // The same rule as the satellite boundary editor: a reshaped plot invalidates
    // the readings taken over the old outline. Without this, editing the boundary
    // in farm management would leave the satellite screen charting numbers that
    // belong to a shape the plot no longer has.
    let observationsInvalidated = 0;
    if ('geoJsonBoundary' in body && boundaryChanged(existing.geojson_boundary, row.geojson_boundary)) {
      observationsInvalidated = await invalidateObservationsForPlot(row.id);
    }

    await audit(req, 'PLOT_UPDATED', {
      entityType: 'FarmPlot',
      entityId: row.id,
      metadata: observationsInvalidated > 0 ? { observationsInvalidated } : undefined,
    });
    res.json({
      ...toPlot(row),
      observationsInvalidated,
    });
  }),
);

/** DELETE /farms/plots/:id — soft delete; the row is retained for sync/audit. */
router.delete(
  '/plots/:id',
  farmOwnerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `UPDATE farm_plots SET deleted_at = now()
        WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL
        RETURNING id`,
      [req.params.id, req.user.id],
    );
    if (!row) throw notFound('Plot not found.');

    await audit(req, 'PLOT_DELETED', { entityType: 'FarmPlot', entityId: row.id });
    res.status(204).end();
  }),
);

export default router;
