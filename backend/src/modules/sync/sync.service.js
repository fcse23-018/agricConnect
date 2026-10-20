import { many, one, withTransaction } from '../../db/index.js';
import { ROLES } from '../../middleware/roles.js';
import {
  iso,
  toExpense,
  toHarvest,
  toListing,
  toLivestock,
  toNdvi,
  toNotification,
  toOrder,
  toPlot,
  toSale,
} from '../../utils/serialize.js';
import { entityFor } from './sync.entities.js';

/**
 * Offline-first sync engine (server side).
 *
 * PUSH — transactional choice: **one transaction per item**, processed strictly
 * in queue order. A whole-batch transaction would let a single unexpected failure
 * roll back the ledger rows of items that already succeeded, destroying the
 * idempotency guarantee; per-item commits keep every accepted item durable while
 * still processing the batch in order.
 *
 * Conflict rule — **Last-Write-Wins (documented, v1.1 §9.5)**: if the stored
 * server row is *newer* than the incoming `clientTimestamp`, we never overwrite it
 * silently. The item is reported as `CONFLICT` with the current `serverEntity`, and
 * the client resolves the timelines and re-pushes. A newer-or-equal client write
 * is applied.
 *
 * Idempotency — `sync_queue_items` is unique on `(user_id, queue_id)`; a replayed
 * `queueId` returns the stored outcome and is never applied twice.
 */

export const SyncStatus = Object.freeze({
  SYNCED: 'SYNCED',
  CONFLICT: 'CONFLICT',
  FAILED: 'FAILED',
});

const REASON = Object.freeze({
  FORBIDDEN: 'FORBIDDEN',
  VALIDATION_ERROR: 'VALIDATION_ERROR',
  UNKNOWN_ENTITY_TYPE: 'UNKNOWN_ENTITY_TYPE',
  NOT_FOUND: 'NOT_FOUND',
  INTERNAL: 'INTERNAL',
});

/** PostgreSQL error codes that represent bad input rather than a server fault. */
const CONSTRAINT_CODES = new Set(['23505', '23514', '23503', '23502', '22P02', '22001']);

const PULL_LIMIT = 500;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value);
}

function mapDbError(err) {
  if (err && CONSTRAINT_CODES.has(err.code)) return REASON.VALIDATION_ERROR;
  return REASON.INTERNAL;
}

/** Decode the payload, which the client may send as an object or a JSON string. */
function decodePayload(raw) {
  if (raw === null || raw === undefined) return {};
  if (typeof raw === 'object') return raw;
  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    return trimmed ? JSON.parse(trimmed) : {};
  }
  return {};
}

/** Never let a malformed payload abort the ledger insert (jsonb must be valid). */
function payloadForStorage(raw) {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }
  return raw;
}

async function writeLedger(client, { userId, deviceId, item, status, serverEntityId = null, error = null }) {
  await client.query(
    `INSERT INTO sync_queue_items
       (user_id, queue_id, device_id, entity_type, entity_id, operation, payload,
        client_timestamp, status, retry_count, server_entity_id, error, processed_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12, now())
     ON CONFLICT (user_id, queue_id) DO NOTHING`,
    [
      userId,
      item.queueId,
      deviceId ?? null,
      item.entityType,
      item.entityId ?? null,
      item.operation,
      payloadForStorage(item.payload),
      item.clientTimestamp ? new Date(item.clientTimestamp) : null,
      status,
      item.retryCount ?? 0,
      serverEntityId,
      error,
    ],
  );
}

async function failInTx(client, ledgerArgs, reason) {
  await writeLedger(client, { ...ledgerArgs, status: SyncStatus.FAILED, error: reason });
  return { queueId: ledgerArgs.item.queueId, status: SyncStatus.FAILED, reason };
}

/**
 * Record a pre-transaction failure (unknown entity, forbidden role, malformed
 * payload, no owner). Best-effort ledger write so a replayed queueId still learns
 * the outcome; the failure is always reported regardless.
 */
async function recordFailure({ userId, deviceId, item, reason }) {
  try {
    await withTransaction(async (client) => {
      await writeLedger(client, { userId, deviceId, item, status: SyncStatus.FAILED, error: reason });
    });
  } catch {
    /* best-effort: the response still carries the failure reason */
  }
  return { queueId: item.queueId, status: SyncStatus.FAILED, reason };
}

async function applyUpsert({ client, descriptor, ownerFarmerId, userId, clientId, entityId, parsed, item, ledgerArgs }) {
  const clientTimestamp = new Date(item.clientTimestamp);

  let existing = null;
  if (descriptor.clientIdBased) {
    const res = await client.query(
      `SELECT * FROM ${descriptor.table} WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
      [ownerFarmerId, clientId],
    );
    existing = res.rows[0] ?? null;
  } else {
    const res = await client.query(`SELECT * FROM ${descriptor.table} WHERE id = $1`, [entityId]);
    existing = res.rows[0] ?? null;
    // A server-uuid-keyed entity may only be written by its owner.
    if (existing && existing.farmer_id !== ownerFarmerId) {
      return failInTx(client, ledgerArgs, REASON.FORBIDDEN);
    }
  }

  if (existing) {
    const serverUpdatedAt = new Date(existing.updated_at);
    if (serverUpdatedAt.getTime() > clientTimestamp.getTime()) {
      // Last-Write-Wins: hand the newer server copy back, do not overwrite it.
      await writeLedger(client, {
        ...ledgerArgs,
        status: SyncStatus.CONFLICT,
        serverEntityId: existing.id,
        error: 'SERVER_NEWER',
      });
      return {
        queueId: item.queueId,
        status: SyncStatus.CONFLICT,
        serverEntityId: existing.id,
        serverEntity: descriptor.serialize(existing),
        serverUpdatedAt: iso(existing.updated_at),
      };
    }

    const { sql, params } = descriptor.buildUpdate({
      payload: parsed,
      ownerFarmerId,
      userId,
      existingId: existing.id,
    });
    const res = await client.query(sql, params);
    const row = res.rows[0];

    // Some entities have dependents that stop being valid when the row changes —
    // reshaping a plot invalidates the satellite observations taken over the old
    // outline. The descriptor owns that rule so the sync path behaves exactly like
    // the direct API path; otherwise editing a boundary offline would quietly keep
    // readings that describe a shape the plot no longer has.
    let afterUpdate;
    if (typeof descriptor.afterUpdate === 'function') {
      afterUpdate = await descriptor.afterUpdate({
        client,
        previous: existing,
        updated: row,
        payload: parsed,
      });
    }

    await writeLedger(client, { ...ledgerArgs, status: SyncStatus.SYNCED, serverEntityId: row.id });
    return {
      queueId: item.queueId,
      status: SyncStatus.SYNCED,
      serverEntityId: row.id,
      serverUpdatedAt: iso(row.updated_at),
      ...(afterUpdate ? { details: afterUpdate } : {}),
    };
  }

  const { sql, params } = descriptor.buildInsert({
    payload: parsed,
    ownerFarmerId,
    userId,
    clientId,
    id: descriptor.clientIdBased ? null : entityId,
  });
  const res = await client.query(sql, params);
  const row = res.rows[0];
  await writeLedger(client, { ...ledgerArgs, status: SyncStatus.SYNCED, serverEntityId: row.id });
  return {
    queueId: item.queueId,
    status: SyncStatus.SYNCED,
    serverEntityId: row.id,
    serverUpdatedAt: iso(row.updated_at),
  };
}

async function applyDelete({ client, descriptor, ownerFarmerId, clientId, entityId, ledgerArgs }) {
  let existing = null;
  if (descriptor.clientIdBased) {
    const res = await client.query(
      `SELECT * FROM ${descriptor.table} WHERE farmer_id = $1 AND client_id = $2 LIMIT 1`,
      [ownerFarmerId, clientId],
    );
    existing = res.rows[0] ?? null;
  } else {
    const res = await client.query(`SELECT * FROM ${descriptor.table} WHERE id = $1`, [entityId]);
    existing = res.rows[0] ?? null;
    if (existing && existing.farmer_id !== ownerFarmerId) {
      return failInTx(client, ledgerArgs, REASON.FORBIDDEN);
    }
  }

  if (!existing) return failInTx(client, ledgerArgs, REASON.NOT_FOUND);

  // Soft delete only where the table supports it (all registered entities do);
  // an already-deleted row is an idempotent success.
  if (descriptor.softDelete && !existing.deleted_at) {
    await client.query(`UPDATE ${descriptor.table} SET deleted_at = now() WHERE id = $1`, [existing.id]);
  }
  await writeLedger(client, { ...ledgerArgs, status: SyncStatus.SYNCED, serverEntityId: existing.id });
  return { queueId: ledgerArgs.item.queueId, status: SyncStatus.SYNCED, serverEntityId: existing.id };
}

async function replayStored(row) {
  if (row.status === SyncStatus.SYNCED) {
    return {
      queueId: row.queue_id,
      status: SyncStatus.SYNCED,
      serverEntityId: row.server_entity_id,
      serverUpdatedAt: iso(row.processed_at),
    };
  }
  if (row.status === SyncStatus.CONFLICT) {
    const descriptor = entityFor(row.entity_type);
    let serverEntity = null;
    let serverUpdatedAt = null;
    if (descriptor && row.server_entity_id) {
      const current = await one(`SELECT * FROM ${descriptor.table} WHERE id = $1`, [row.server_entity_id]);
      if (current) {
        serverEntity = descriptor.serialize(current);
        serverUpdatedAt = iso(current.updated_at);
      }
    }
    return {
      queueId: row.queue_id,
      status: SyncStatus.CONFLICT,
      serverEntityId: row.server_entity_id,
      serverEntity,
      serverUpdatedAt,
    };
  }
  return { queueId: row.queue_id, status: SyncStatus.FAILED, reason: row.error || 'FAILED' };
}

async function processItem({ userId, role, deviceId, item }) {
  // 1. Idempotent replay of an already-processed queue id.
  const prior = await one(
    `SELECT * FROM sync_queue_items WHERE user_id = $1 AND queue_id = $2`,
    [userId, item.queueId],
  );
  if (prior) return replayStored(prior);

  // 2. Structural checks — these fail one item, never the batch.
  const descriptor = entityFor(item.entityType);
  if (!descriptor) {
    return recordFailure({ userId, deviceId, item, reason: REASON.UNKNOWN_ENTITY_TYPE });
  }
  if (!descriptor.roles.includes(role)) {
    return recordFailure({ userId, deviceId, item, reason: REASON.FORBIDDEN });
  }

  let payload;
  try {
    payload = decodePayload(item.payload);
  } catch {
    return recordFailure({ userId, deviceId, item, reason: REASON.VALIDATION_ERROR });
  }

  let parsed;
  try {
    parsed = descriptor.validate(payload);
  } catch {
    return recordFailure({ userId, deviceId, item, reason: REASON.VALIDATION_ERROR });
  }

  // 3. Ownership — a user can never write another user's record.
  let ownerFarmerId = null;
  if (role === ROLES.FARM_OWNER) {
    ownerFarmerId = userId;
  } else if (role === ROLES.FARMER) {
    const employer = await one(`SELECT employer_farmer_id FROM users WHERE id = $1`, [userId]);
    ownerFarmerId = employer?.employer_farmer_id ?? null;
  }
  if (!ownerFarmerId) {
    return recordFailure({ userId, deviceId, item, reason: REASON.FORBIDDEN });
  }

  // 4. Stable key: client_id where the table has one, otherwise the server uuid.
  const clientId = descriptor.clientIdBased ? parsed.clientId ?? item.entityId ?? null : null;
  const entityId = item.entityId ?? null;
  if (descriptor.clientIdBased && !clientId) {
    return recordFailure({ userId, deviceId, item, reason: REASON.VALIDATION_ERROR });
  }
  if (!descriptor.clientIdBased && !isUuid(entityId)) {
    return recordFailure({ userId, deviceId, item, reason: REASON.VALIDATION_ERROR });
  }

  // 5. Apply in a per-item transaction so one bad item cannot roll back others.
  const ledgerArgs = { userId, deviceId, item };
  try {
    return await withTransaction(async (client) => {
      if (item.operation === 'DELETE') {
        return applyDelete({ client, descriptor, ownerFarmerId, clientId, entityId, ledgerArgs });
      }
      return applyUpsert({
        client,
        descriptor,
        ownerFarmerId,
        userId,
        clientId,
        entityId,
        parsed,
        item,
        ledgerArgs,
      });
    });
  } catch (err) {
    return recordFailure({ userId, deviceId, item, reason: mapDbError(err) });
  }
}

/** Accept a batch of queued writes and return a per-item outcome for each. */
export async function pushItems({ userId, role, deviceId, items }) {
  const results = [];
  // Sequential, in order: later items may depend on earlier ones (e.g. a plot
  // before the harvest recorded against it).
  for (const item of items) {
    results.push(await processItem({ userId, role, deviceId, item }));
  }
  return { serverTime: new Date().toISOString(), results };
}

// --- pull ------------------------------------------------------------------

async function rowsUpdatedSince(table, column, value, since) {
  return many(
    `SELECT * FROM ${table}
      WHERE ${column} = $1 AND updated_at > $2
      ORDER BY updated_at ASC, id ASC
      LIMIT ${PULL_LIMIT}`,
    [value, since],
  );
}

async function rowsCreatedSince(table, column, value, since) {
  return many(
    `SELECT * FROM ${table}
      WHERE ${column} = $1 AND created_at > $2
      ORDER BY created_at ASC, id ASC
      LIMIT ${PULL_LIMIT}`,
    [value, since],
  );
}

/**
 * Orders the caller is party to on either side. All roles can buy, so a user may be
 * the buyer of one order and the seller of another; matching on identity rather than
 * role is what keeps both visible.
 */
async function ordersSinceForUser(userId, since) {
  return many(
    `SELECT o.*, pl.produce, b.full_name AS buyer_name
       FROM orders o
       JOIN produce_listings pl ON pl.id = o.listing_id
       JOIN users b ON b.id = o.buyer_id
      WHERE (o.buyer_id = $1 OR o.farmer_id = $1) AND o.updated_at > $2
      ORDER BY o.updated_at ASC, o.id ASC
      LIMIT ${PULL_LIMIT}`,
    [userId, since],
  );
}

/**
 * Server-authoritative deltas for the caller's scope.
 * Farm owner: own plots, records, finances, listings, NDVI and every order they are
 * party to. Farmer: the employer's plots/harvests/livestock, plus their own purchases
 * and feed. Buyer: their own orders and feed.
 */
export async function pullChanges({ userId, role, since }) {
  const sinceTs = since ? new Date(since) : new Date(0);
  const changes = {
    plots: [],
    harvests: [],
    livestock: [],
    sales: [],
    expenses: [],
    listings: [],
    orders: [],
    notifications: [],
    ndvi: [],
  };

  if (role === ROLES.FARM_OWNER) {
    changes.plots = (await rowsUpdatedSince('farm_plots', 'farmer_id', userId, sinceTs)).map(toPlot);
    changes.harvests = (await rowsUpdatedSince('harvest_records', 'farmer_id', userId, sinceTs)).map(toHarvest);
    changes.livestock = (await rowsUpdatedSince('livestock_counts', 'farmer_id', userId, sinceTs)).map(toLivestock);
    changes.sales = (await rowsUpdatedSince('sale_transactions', 'farmer_id', userId, sinceTs)).map(toSale);
    changes.expenses = (await rowsUpdatedSince('expenses', 'farmer_id', userId, sinceTs)).map(toExpense);
    changes.listings = (await rowsUpdatedSince('produce_listings', 'farmer_id', userId, sinceTs)).map(toListing);
    changes.orders = (await ordersSinceForUser(userId, sinceTs)).map(toOrder);
    changes.notifications = (await rowsCreatedSince('notifications', 'user_id', userId, sinceTs)).map(toNotification);
    changes.ndvi = (await rowsCreatedSince('satellite_ndvi', 'farmer_id', userId, sinceTs)).map(toNdvi);
  } else if (role === ROLES.FARMER) {
    const employer = await one(`SELECT employer_farmer_id FROM users WHERE id = $1`, [userId]);
    const farmerId = employer?.employer_farmer_id ?? null;
    if (farmerId) {
      changes.plots = (await rowsUpdatedSince('farm_plots', 'farmer_id', farmerId, sinceTs)).map(toPlot);
      changes.harvests = (await rowsUpdatedSince('harvest_records', 'farmer_id', farmerId, sinceTs)).map(toHarvest);
      changes.livestock = (await rowsUpdatedSince('livestock_counts', 'farmer_id', farmerId, sinceTs)).map(toLivestock);
    }
    // Everyone can buy, so the farmer's own purchases sync on their device too.
    changes.orders = (await ordersSinceForUser(userId, sinceTs)).map(toOrder);
    changes.notifications = (await rowsCreatedSince('notifications', 'user_id', userId, sinceTs)).map(toNotification);
  } else if (role === ROLES.BUYER) {
    changes.orders = (await ordersSinceForUser(userId, sinceTs)).map(toOrder);
    changes.notifications = (await rowsCreatedSince('notifications', 'user_id', userId, sinceTs)).map(toNotification);
  }

  return { serverTime: new Date().toISOString(), changes };
}

export default { SyncStatus, pushItems, pullChanges };
