import { many, one, withTransaction } from '../../db/index.js';
import { badRequest, forbidden } from '../../utils/errors.js';
import { ROLES } from '../../middleware/roles.js';

/**
 * Resolve which farm a caller's field records belong to.
 *
 * A FARM_OWNER owns their own records. A FARMER works someone else's land and writes
 * on behalf of the owner who employs them (migration 005 links a farmer to exactly one
 * employing owner). An unlinked farmer is refused with a clear, actionable 403 rather
 * than silently writing orphaned rows.
 *
 * Only those two roles may resolve to a farm. A BUYER has no farm and is refused
 * explicitly: without this branch a buyer would fall through to the employer
 * lookup, and a stray `employer_farmer_id` on their row would hand them someone
 * else's land. The route guards already keep buyers out, but this is the ownership
 * primitive, so it enforces the rule itself rather than relying on its callers.
 */
export async function resolveOwnerFarmerId(user) {
  if (!user) throw forbidden();
  if (user.role === ROLES.FARM_OWNER) return user.id;
  if (user.role !== ROLES.FARMER) {
    throw forbidden('Only a farm owner or an employed farmer has a farm to work on.');
  }

  const row = await one('SELECT employer_farmer_id FROM users WHERE id = $1', [user.id]);
  if (!row?.employer_farmer_id) {
    throw forbidden(
      'Your account is not linked to a farm yet. Ask the farm owner to link you, or use Profile → Link to farm.',
    );
  }
  return row.employer_farmer_id;
}

/**
 * A harvest may reference a plot. Only the owning farmer's live plots are
 * acceptable — otherwise the reference would leak or cross farm boundaries.
 */
async function assertPlotOwned(farmerId, plotId) {
  if (!plotId) return;
  const plot = await one(
    'SELECT 1 FROM farm_plots WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL',
    [plotId, farmerId],
  );
  if (!plot) {
    throw badRequest('The selected plot was not found.', [
      { field: 'plotId', message: 'That plot does not belong to this farm.' },
    ]);
  }
}

function pushDateFilters(clauses, params, column, { from, to }) {
  if (from) {
    params.push(from);
    clauses.push(`${column} >= $${params.length}`);
  }
  if (to) {
    params.push(to);
    clauses.push(`${column} <= $${params.length}`);
  }
}

/* ------------------------------- harvests -------------------------------- */

export async function listHarvests(farmerId, { from, to, cropType: type, limit, offset }) {
  const clauses = ['farmer_id = $1', 'deleted_at IS NULL'];
  const params = [farmerId];
  pushDateFilters(clauses, params, 'harvest_date', { from, to });
  if (type) {
    params.push(type);
    clauses.push(`crop_type ILIKE $${params.length}`);
  }
  const where = clauses.join(' AND ');

  const totalRow = await one(`SELECT COUNT(*)::int AS total FROM harvest_records WHERE ${where}`, params);
  const rows = await many(
    `SELECT * FROM harvest_records WHERE ${where}
      ORDER BY harvest_date DESC, created_at DESC
      LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
    [...params, limit, offset],
  );
  return { items: rows, total: totalRow.total };
}

export async function getHarvest(farmerId, id) {
  return one(
    `SELECT * FROM harvest_records WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL`,
    [id, farmerId],
  );
}

export async function createHarvest(farmerId, actorId, data) {
  await assertPlotOwned(farmerId, data.plotId);
  return withTransaction(async (client) => {
    if (data.clientId) {
      const existing = await client.query(
        `SELECT id FROM harvest_records WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
        [farmerId, data.clientId],
      );
      if (existing.rows[0]) {
        const updated = await client.query(
          `UPDATE harvest_records
              SET plot_id = $1, crop_type = $2, quantity_kg = $3, harvest_date = $4,
                  notes = $5, created_by = COALESCE(created_by, $6), deleted_at = NULL
            WHERE id = $7 AND farmer_id = $8
            RETURNING *`,
          [data.plotId ?? null, data.cropType, data.quantityKg, data.harvestDate, data.notes ?? null, actorId, existing.rows[0].id, farmerId],
        );
        return { row: updated.rows[0], created: false };
      }
    }

    const inserted = await client.query(
      `INSERT INTO harvest_records (farmer_id, plot_id, crop_type, quantity_kg, harvest_date, notes, client_id, created_by)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
       RETURNING *`,
      [farmerId, data.plotId ?? null, data.cropType, data.quantityKg, data.harvestDate, data.notes ?? null, data.clientId ?? null, actorId],
    );
    return { row: inserted.rows[0], created: true };
  });
}

export async function updateHarvest(farmerId, id, data) {
  if ('plotId' in data) await assertPlotOwned(farmerId, data.plotId);

  const assignments = [];
  const params = [];
  if ('plotId' in data) {
    params.push(data.plotId);
    assignments.push(`plot_id = $${params.length}`);
  }
  if ('cropType' in data) {
    params.push(data.cropType);
    assignments.push(`crop_type = $${params.length}`);
  }
  if ('quantityKg' in data) {
    params.push(data.quantityKg);
    assignments.push(`quantity_kg = $${params.length}`);
  }
  if ('harvestDate' in data) {
    params.push(data.harvestDate);
    assignments.push(`harvest_date = $${params.length}`);
  }
  if ('notes' in data) {
    params.push(data.notes);
    assignments.push(`notes = $${params.length}`);
  }

  params.push(id);
  const idPlaceholder = `$${params.length}`;
  params.push(farmerId);
  const farmerPlaceholder = `$${params.length}`;

  return one(
    `UPDATE harvest_records SET ${assignments.join(', ')}
      WHERE id = ${idPlaceholder} AND farmer_id = ${farmerPlaceholder} AND deleted_at IS NULL
      RETURNING *`,
    params,
  );
}

export async function deleteHarvest(farmerId, id) {
  return one(
    `UPDATE harvest_records SET deleted_at = now()
      WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL
      RETURNING id`,
    [id, farmerId],
  );
}

/* ------------------------------- livestock ------------------------------- */

export async function listLivestock(farmerId, { from, to, limit, offset }) {
  const clauses = ['farmer_id = $1', 'deleted_at IS NULL'];
  const params = [farmerId];
  pushDateFilters(clauses, params, 'record_date', { from, to });
  const where = clauses.join(' AND ');

  const totalRow = await one(`SELECT COUNT(*)::int AS total FROM livestock_counts WHERE ${where}`, params);
  const rows = await many(
    `SELECT * FROM livestock_counts WHERE ${where}
      ORDER BY record_date DESC, created_at DESC
      LIMIT $${params.length + 1} OFFSET $${params.length + 2}`,
    [...params, limit, offset],
  );
  return { items: rows, total: totalRow.total };
}

export async function getLivestock(farmerId, id) {
  return one(
    `SELECT * FROM livestock_counts WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL`,
    [id, farmerId],
  );
}

export async function createLivestock(farmerId, actorId, data) {
  return withTransaction(async (client) => {
    if (data.clientId) {
      const existing = await client.query(
        `SELECT id FROM livestock_counts WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
        [farmerId, data.clientId],
      );
      if (existing.rows[0]) {
        const updated = await client.query(
          `UPDATE livestock_counts
              SET animal_type = $1, head_count = $2, record_date = $3, notes = $4,
                  created_by = COALESCE(created_by, $5), deleted_at = NULL
            WHERE id = $6 AND farmer_id = $7
            RETURNING *`,
          [data.animalType, data.headCount, data.recordDate, data.notes ?? null, actorId, existing.rows[0].id, farmerId],
        );
        return { row: updated.rows[0], created: false };
      }
    }

    const inserted = await client.query(
      `INSERT INTO livestock_counts (farmer_id, animal_type, head_count, record_date, notes, client_id, created_by)
       VALUES ($1,$2,$3,$4,$5,$6,$7)
       RETURNING *`,
      [farmerId, data.animalType, data.headCount, data.recordDate, data.notes ?? null, data.clientId ?? null, actorId],
    );
    return { row: inserted.rows[0], created: true };
  });
}

export async function updateLivestock(farmerId, id, data) {
  const assignments = [];
  const params = [];
  if ('animalType' in data) {
    params.push(data.animalType);
    assignments.push(`animal_type = $${params.length}`);
  }
  if ('headCount' in data) {
    params.push(data.headCount);
    assignments.push(`head_count = $${params.length}`);
  }
  if ('recordDate' in data) {
    params.push(data.recordDate);
    assignments.push(`record_date = $${params.length}`);
  }
  if ('notes' in data) {
    params.push(data.notes);
    assignments.push(`notes = $${params.length}`);
  }

  params.push(id);
  const idPlaceholder = `$${params.length}`;
  params.push(farmerId);
  const farmerPlaceholder = `$${params.length}`;

  return one(
    `UPDATE livestock_counts SET ${assignments.join(', ')}
      WHERE id = ${idPlaceholder} AND farmer_id = ${farmerPlaceholder} AND deleted_at IS NULL
      RETURNING *`,
    params,
  );
}

export async function deleteLivestock(farmerId, id) {
  return one(
    `UPDATE livestock_counts SET deleted_at = now()
      WHERE id = $1 AND farmer_id = $2 AND deleted_at IS NULL
      RETURNING id`,
    [id, farmerId],
  );
}
