import { many, one } from '../../db/index.js';
import { badRequest, notFound } from '../../utils/errors.js';
import { computeNdvi, estimateAreaHectares } from '../../services/gee.js';
import { classifyNdvi } from '../../services/ndvi.js';
import { buildNdviAdvice } from '../../services/ndviAdvice.js';
import { notify } from '../../services/notifications.js';
import { toNdvi } from '../../utils/serialize.js';

/**
 * NDVI is a vegetation index — an indicative signal, not an agronomic
 * diagnosis. Every response carries this disclaimer so the client can never
 * present it as a definitive finding (docs/02-API-CONTRACT.md §2.6).
 */
export const NDVI_DISCLAIMER =
  'Satellite vegetation index is an indicative signal, not an agronomic diagnosis.';

/**
 * Serialise an NDVI row.
 *
 * `advice` is attached only where we computed it (a fresh computation, the latest
 * observation, or a single record) because building it costs two extra queries —
 * too much for every item in a history page. It is always present as `null` when
 * absent, so the client can branch on it without a key check.
 */
export function presentNdvi(row, advice = null) {
  return { ...toNdvi(row), disclaimer: NDVI_DISCLAIMER, advice: advice ?? null };
}

/**
 * Build the post-computation advisory block for a generated reading.
 *
 * It compares against the plot's *previous* generated reading so the advice can
 * speak about direction rather than a single value, and it uses the plot boundary
 * only to state the area the average covers. It never invents a measurement.
 * Returns null for anything that is not a usable generated reading.
 */
export async function adviceForNdvi(row) {
  if (!row || row.status !== 'GENERATED' || row.mean_ndvi_value === null || row.mean_ndvi_value === undefined) {
    return null;
  }

  const [previous, plot] = await Promise.all([
    one(
      `SELECT mean_ndvi_value, satellite_date FROM satellite_ndvi
        WHERE plot_id = $1 AND farmer_id = $2 AND status = 'GENERATED'
          AND mean_ndvi_value IS NOT NULL AND id <> $3
        ORDER BY satellite_date DESC NULLS LAST, created_at DESC
        LIMIT 1`,
      [row.plot_id, row.farmer_id, row.id],
    ),
    one('SELECT name, geojson_boundary FROM farm_plots WHERE id = $1', [row.plot_id]),
  ]);

  return buildNdviAdvice({
    meanNdviValue: Number(row.mean_ndvi_value),
    stressLevel: row.stress_level ?? classifyNdvi(row.mean_ndvi_value),
    previous: previous
      ? { value: Number(previous.mean_ndvi_value), date: previous.satellite_date ?? null }
      : null,
    areaHectares: plot?.geojson_boundary ? estimateAreaHectares(plot.geojson_boundary) : null,
    plotName: plot?.name ?? null,
    satelliteDate: row.satellite_date ?? null,
    cloudCover: row.cloud_cover === null || row.cloud_cover === undefined ? null : Number(row.cloud_cover),
    source: row.source ?? null,
  });
}

/** Load a plot, enforcing farmer ownership. Others' plots collapse to 404. */
export async function getOwnedPlot(farmerId, plotId) {
  const plot = await one(
    'SELECT * FROM farm_plots WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL',
    [plotId, farmerId],
  );
  if (!plot) throw notFound('Plot not found.');
  return plot;
}

/**
 * Run the NDVI pipeline for a plot.
 *
 * A REQUESTED row is persisted first so an interrupted computation is visible
 * in history. A cloud-covered or otherwise unusable observation is a normal
 * `FAILED` outcome — we never invent a value to make it look successful.
 */
export async function requestNdvi({ farmerId, plotId, geoJsonBoundary }) {
  const plot = await getOwnedPlot(farmerId, plotId);
  const boundary = geoJsonBoundary ?? plot.geojson_boundary;
  if (!boundary) {
    throw badRequest('A plot boundary is required to compute NDVI.', [
      { field: 'geoJsonBoundary', message: 'This plot has no saved boundary.' },
    ]);
  }

  const requested = await one(
    `INSERT INTO satellite_ndvi (plot_id, farmer_id, status, source)
     VALUES ($1, $2, 'REQUESTED', 'SENTINEL-2')
     RETURNING *`,
    [plotId, farmerId],
  );

  let result;
  try {
    result = await computeNdvi({ plotId, geoJsonBoundary: boundary });
  } catch {
    // Never surface an internal failure as a 500 — record it as FAILED.
    result = {
      status: 'FAILED',
      failureReason: 'Satellite imagery could not be processed at this time.',
      source: 'SENTINEL-2',
    };
  }

  if (result.status === 'GENERATED') {
    const row = await one(
      `UPDATE satellite_ndvi
          SET mean_ndvi_value = $1, stress_level = $2, status = 'GENERATED',
              satellite_date = $3, cloud_cover = $4, compressed_vector_json = $5, source = $6
        WHERE id = $7
        RETURNING *`,
      [
        result.meanNdviValue,
        classifyNdvi(result.meanNdviValue),
        result.satelliteDate ?? null,
        result.cloudCover ?? null,
        result.compressedVectorJson ? JSON.stringify(result.compressedVectorJson) : null,
        result.source ?? 'SENTINEL-2',
        requested.id,
      ],
    );

    // A failed notification must never fail the request (notify swallows).
    await notify({
      userId: farmerId,
      type: 'NDVI_READY',
      title: 'Satellite crop-health result ready',
      body: 'A new satellite vegetation index result is available for your plot.',
      relatedEntityType: 'SatelliteNDVI',
      relatedEntityId: requested.id,
    });

    return row;
  }

  return one(
    `UPDATE satellite_ndvi
        SET status = 'FAILED', failure_reason = $1, cloud_cover = $2,
            satellite_date = $3, source = $4
      WHERE id = $5
      RETURNING *`,
    [
      result.failureReason ?? 'Satellite imagery could not be processed.',
      result.cloudCover ?? null,
      result.satelliteDate ?? null,
      result.source ?? 'SENTINEL-2',
      requested.id,
    ],
  );
}

/** History for the caller's own records, newest first. */
export async function listNdvi(farmerId, { plotId, limit, offset }) {
  const clauses = ['farmer_id = $1'];
  const params = [farmerId];
  if (plotId) {
    params.push(plotId);
    clauses.push(`plot_id = $${params.length}`);
  }
  const where = clauses.join(' AND ');

  const totalRow = await one(`SELECT COUNT(*)::int AS total FROM satellite_ndvi WHERE ${where}`, params);
  const rows = await many(
    `SELECT * FROM satellite_ndvi WHERE ${where}
      ORDER BY created_at DESC
      LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
    [...params, limit, offset],
  );
  return { items: rows, total: totalRow.total };
}

export async function getNdvi(farmerId, id) {
  return one('SELECT * FROM satellite_ndvi WHERE id = $1 AND farmer_id = $2', [id, farmerId]);
}

/** Most recent record for a plot (drives the offline "cached" display). */
export async function latestNdvi(farmerId, plotId) {
  return one(
    'SELECT * FROM satellite_ndvi WHERE plot_id = $1 AND farmer_id = $2 ORDER BY created_at DESC LIMIT 1',
    [plotId, farmerId],
  );
}

export default { requestNdvi, listNdvi, getNdvi, latestNdvi, presentNdvi, adviceForNdvi, NDVI_DISCLAIMER };
