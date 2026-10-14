import { z } from 'zod';

import { ROLES } from '../../middleware/roles.js';
import {
  toExpense,
  toHarvest,
  toListing,
  toLivestock,
  toPlot,
  toSale,
} from '../../utils/serialize.js';

/**
 * Registry of every entity a device may push through /sync/push.
 *
 * Each descriptor tells the sync engine, for one entity type:
 *  - `table`        — the target table (fixed, never user-controlled);
 *  - `roles`        — roles allowed to write it (a disallowed role gets a
 *                     per-item FAILED/FORBIDDEN, never a batch failure);
 *  - `serialize`    — the public API shape (reused from utils/serialize.js);
 *  - `validate`     — zod parse of the (already JSON-decoded) payload;
 *  - `buildInsert`  — parameterised INSERT … RETURNING *;
 *  - `buildUpdate`  — parameterised UPDATE … RETURNING * (resurrects soft-deletes);
 *  - `softDelete`   — whether DELETE soft-deletes rather than removes;
 *  - `clientIdBased`— whether the row is keyed by the device `client_id`, or by a
 *                     server uuid (`entityId`) when no `client_id` column exists.
 *
 * Column names here mirror migrations/002_domain.sql and 003_marketplace.sql
 * exactly. Builders receive already-validated values only, and every value is a
 * bind parameter — the SQL text itself is static.
 */

const MAX_MONEY = 999_999_999_999;

const currencySchema = z
  .string()
  .trim()
  .regex(/^[A-Za-z]{3}$/, 'Currency must be a 3-letter code')
  .transform((v) => v.toUpperCase())
  .default('BWP');

/** Calendar date normalised to `YYYY-MM-DD` for `date` columns. */
const calendarDateSchema = z
  .string()
  .transform((value, ctx) => {
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Must be a valid date.' });
      return z.NEVER;
    }
    return /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : d.toISOString().slice(0, 10);
  });

/**
 * GeoJSON boundary arrives either as an object or as a stringified polygon.
 * We accept both but validate that a string is parseable, then decode it in the
 * insert/update builder so the jsonb column stores a real object, not a string.
 */
const boundarySchema = z
  .union([z.record(z.any()), z.string().max(200_000)])
  .nullish()
  .superRefine((value, ctx) => {
    if (typeof value === 'string') {
      try {
        JSON.parse(value);
      } catch {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Must be valid GeoJSON.' });
      }
    }
  });

function decodeBoundary(value) {
  if (value === null || value === undefined) return null;
  return typeof value === 'string' ? JSON.parse(value) : value;
}

// --- payload schemas -------------------------------------------------------

const farmPlotPayload = z.object({
  clientId: z.string().trim().max(128).nullish(),
  plotName: z.string().trim().min(1).max(120),
  areaHectares: z.coerce.number().min(0).max(1_000_000_000).nullish(),
  geoJsonBoundary: boundarySchema,
  centroidLat: z.coerce.number().min(-90).max(90).nullish(),
  centroidLng: z.coerce.number().min(-180).max(180).nullish(),
  notes: z.string().max(4000).nullish(),
});

const harvestPayload = z.object({
  clientId: z.string().trim().max(128).nullish(),
  plotId: z.string().uuid().nullish(),
  cropType: z.string().trim().min(1).max(80),
  quantityKg: z.coerce.number().min(0).max(MAX_MONEY),
  harvestDate: calendarDateSchema,
  notes: z.string().max(4000).nullish(),
});

const livestockPayload = z.object({
  clientId: z.string().trim().max(128).nullish(),
  animalType: z.string().trim().min(1).max(80),
  headCount: z.coerce.number().int().min(0).max(2_000_000_000),
  recordDate: calendarDateSchema,
  notes: z.string().max(4000).nullish(),
});

const salePayload = z.object({
  clientId: z.string().trim().max(128).nullish(),
  item: z.string().trim().min(1).max(120),
  quantity: z.coerce.number().min(0).max(MAX_MONEY),
  unitPrice: z.coerce.number().min(0).max(MAX_MONEY).nullish(),
  amount: z.coerce.number().min(0).max(MAX_MONEY),
  currency: currencySchema,
  buyerName: z.string().trim().max(120).nullish(),
  saleDate: calendarDateSchema,
  notes: z.string().max(4000).nullish(),
});

const expensePayload = z.object({
  clientId: z.string().trim().max(128).nullish(),
  category: z.string().trim().min(1).max(80),
  description: z.string().max(4000).nullish(),
  amount: z.coerce.number().min(0).max(MAX_MONEY),
  currency: currencySchema,
  expenseDate: calendarDateSchema,
});

/**
 * ProduceListing has no `client_id` column, so it is keyed by the server-side
 * uuid. Status is deliberately not accepted: a listing only becomes PUBLISHED /
 * SOLD through the marketplace publish/confirm endpoints, never through sync.
 */
const produceListingPayload = z.object({
  produce: z.string().trim().min(2).max(120),
  description: z.string().trim().max(2000).nullish(),
  quantity: z.coerce.number().min(0).max(MAX_MONEY),
  unit: z.enum(['kg', 'tonne', 'bag', 'crate', 'bunch', 'head', 'litre']).default('kg'),
  price: z.coerce.number().min(0).max(MAX_MONEY),
  imageUrl: z.string().trim().max(500).nullish(),
  locationText: z.string().trim().max(200).nullish(),
});

// --- descriptors -----------------------------------------------------------

const FarmPlot = {
  table: 'farm_plots',
  clientIdBased: true,
  softDelete: true,
  roles: [ROLES.FARMER],
  serialize: toPlot,
  validate: (payload) => farmPlotPayload.parse(payload),
  buildInsert: ({ payload, ownerFarmerId, clientId }) => ({
    sql: `INSERT INTO farm_plots
            (farmer_id, client_id, plot_name, area_hectares, geojson_boundary, centroid_lat, centroid_lng, notes)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
          RETURNING *`,
    params: [
      ownerFarmerId,
      clientId,
      payload.plotName,
      payload.areaHectares ?? null,
      decodeBoundary(payload.geoJsonBoundary),
      payload.centroidLat ?? null,
      payload.centroidLng ?? null,
      payload.notes ?? null,
    ],
  }),
  buildUpdate: ({ payload, existingId }) => ({
    sql: `UPDATE farm_plots
             SET plot_name = $1, area_hectares = $2, geojson_boundary = $3,
                 centroid_lat = $4, centroid_lng = $5, notes = $6, deleted_at = NULL
           WHERE id = $7
           RETURNING *`,
    params: [
      payload.plotName,
      payload.areaHectares ?? null,
      decodeBoundary(payload.geoJsonBoundary),
      payload.centroidLat ?? null,
      payload.centroidLng ?? null,
      payload.notes ?? null,
      existingId,
    ],
  }),
};

const HarvestRecord = {
  table: 'harvest_records',
  clientIdBased: true,
  softDelete: true,
  roles: [ROLES.FARMER, ROLES.FARM_EMPLOYEE],
  serialize: toHarvest,
  validate: (payload) => harvestPayload.parse(payload),
  buildInsert: ({ payload, ownerFarmerId, userId, clientId }) => ({
    sql: `INSERT INTO harvest_records
            (farmer_id, plot_id, crop_type, quantity_kg, harvest_date, notes, client_id, created_by)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
          RETURNING *`,
    params: [
      ownerFarmerId,
      payload.plotId ?? null,
      payload.cropType,
      payload.quantityKg,
      payload.harvestDate,
      payload.notes ?? null,
      clientId,
      userId,
    ],
  }),
  buildUpdate: ({ payload, existingId }) => ({
    sql: `UPDATE harvest_records
             SET plot_id = $1, crop_type = $2, quantity_kg = $3,
                 harvest_date = $4, notes = $5, deleted_at = NULL
           WHERE id = $6
           RETURNING *`,
    params: [
      payload.plotId ?? null,
      payload.cropType,
      payload.quantityKg,
      payload.harvestDate,
      payload.notes ?? null,
      existingId,
    ],
  }),
};

const LivestockCount = {
  table: 'livestock_counts',
  clientIdBased: true,
  softDelete: true,
  roles: [ROLES.FARMER, ROLES.FARM_EMPLOYEE],
  serialize: toLivestock,
  validate: (payload) => livestockPayload.parse(payload),
  buildInsert: ({ payload, ownerFarmerId, userId, clientId }) => ({
    sql: `INSERT INTO livestock_counts
            (farmer_id, animal_type, head_count, record_date, notes, client_id, created_by)
          VALUES ($1,$2,$3,$4,$5,$6,$7)
          RETURNING *`,
    params: [
      ownerFarmerId,
      payload.animalType,
      payload.headCount,
      payload.recordDate,
      payload.notes ?? null,
      clientId,
      userId,
    ],
  }),
  buildUpdate: ({ payload, existingId }) => ({
    sql: `UPDATE livestock_counts
             SET animal_type = $1, head_count = $2, record_date = $3,
                 notes = $4, deleted_at = NULL
           WHERE id = $5
           RETURNING *`,
    params: [
      payload.animalType,
      payload.headCount,
      payload.recordDate,
      payload.notes ?? null,
      existingId,
    ],
  }),
};

const SaleTransaction = {
  table: 'sale_transactions',
  clientIdBased: true,
  softDelete: true,
  roles: [ROLES.FARMER],
  serialize: toSale,
  validate: (payload) => salePayload.parse(payload),
  buildInsert: ({ payload, ownerFarmerId, clientId }) => ({
    sql: `INSERT INTO sale_transactions
            (farmer_id, item, quantity, unit_price, amount, currency, buyer_name, sale_date, notes, client_id)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
          RETURNING *`,
    params: [
      ownerFarmerId,
      payload.item,
      payload.quantity,
      payload.unitPrice ?? null,
      payload.amount,
      payload.currency,
      payload.buyerName ?? null,
      payload.saleDate,
      payload.notes ?? null,
      clientId,
    ],
  }),
  buildUpdate: ({ payload, existingId }) => ({
    sql: `UPDATE sale_transactions
             SET item = $1, quantity = $2, unit_price = $3, amount = $4, currency = $5,
                 buyer_name = $6, sale_date = $7, notes = $8, deleted_at = NULL
           WHERE id = $9
           RETURNING *`,
    params: [
      payload.item,
      payload.quantity,
      payload.unitPrice ?? null,
      payload.amount,
      payload.currency,
      payload.buyerName ?? null,
      payload.saleDate,
      payload.notes ?? null,
      existingId,
    ],
  }),
};

const Expense = {
  table: 'expenses',
  clientIdBased: true,
  softDelete: true,
  roles: [ROLES.FARMER],
  serialize: toExpense,
  validate: (payload) => expensePayload.parse(payload),
  buildInsert: ({ payload, ownerFarmerId, clientId }) => ({
    sql: `INSERT INTO expenses
            (farmer_id, category, description, amount, currency, expense_date, client_id)
          VALUES ($1,$2,$3,$4,$5,$6,$7)
          RETURNING *`,
    params: [
      ownerFarmerId,
      payload.category,
      payload.description ?? null,
      payload.amount,
      payload.currency,
      payload.expenseDate,
      clientId,
    ],
  }),
  buildUpdate: ({ payload, existingId }) => ({
    sql: `UPDATE expenses
             SET category = $1, description = $2, amount = $3, currency = $4,
                 expense_date = $5, deleted_at = NULL
           WHERE id = $6
           RETURNING *`,
    params: [
      payload.category,
      payload.description ?? null,
      payload.amount,
      payload.currency,
      payload.expenseDate,
      existingId,
    ],
  }),
};

const ProduceListing = {
  table: 'produce_listings',
  clientIdBased: false,
  softDelete: true,
  roles: [ROLES.FARMER],
  serialize: toListing,
  validate: (payload) => produceListingPayload.parse(payload),
  buildInsert: ({ payload, ownerFarmerId, id }) => ({
    sql: `INSERT INTO produce_listings
            (id, farmer_id, produce, description, quantity, unit, price, image_url, location_text)
          VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
          RETURNING *`,
    params: [
      id,
      ownerFarmerId,
      payload.produce,
      payload.description ?? null,
      payload.quantity,
      payload.unit,
      payload.price,
      payload.imageUrl ?? null,
      payload.locationText ?? null,
    ],
  }),
  buildUpdate: ({ payload, existingId }) => ({
    sql: `UPDATE produce_listings
             SET produce = $1, description = $2, quantity = $3, unit = $4, price = $5,
                 image_url = $6, location_text = $7, deleted_at = NULL
           WHERE id = $8
           RETURNING *`,
    params: [
      payload.produce,
      payload.description ?? null,
      payload.quantity,
      payload.unit,
      payload.price,
      payload.imageUrl ?? null,
      payload.locationText ?? null,
      existingId,
    ],
  }),
};

export const ENTITIES = Object.freeze({
  FarmPlot,
  HarvestRecord,
  LivestockCount,
  SaleTransaction,
  Expense,
  ProduceListing,
});

/** Look up a descriptor, or `null` for an unknown/unpushable entity type. */
export function entityFor(entityType) {
  return Object.prototype.hasOwnProperty.call(ENTITIES, entityType)
    ? ENTITIES[entityType]
    : null;
}

export default ENTITIES;
