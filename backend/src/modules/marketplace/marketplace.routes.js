import { Router } from 'express';

import { many, one } from '../../db/index.js';
import { asyncHandler } from '../../utils/asyncHandler.js';
import { badRequest, conflict, notFound } from '../../utils/errors.js';
import { paginated, toListing } from '../../utils/serialize.js';
import { pagination, validate } from '../../middleware/errorHandler.js';
import { authenticate } from '../../middleware/auth.js';
import { buyerOrFarmer, farmerOnly } from '../../middleware/roles.js';
import { audit } from '../../services/audit.js';
import {
  createListingSchema,
  idParamSchema,
  listingQuerySchema,
  updateListingSchema,
} from './marketplace.schemas.js';

const router = Router();

// Every marketplace route requires a verified bearer identity.
router.use(authenticate);

/** Fixed, non-user-controlled ORDER BY fragments. */
const SORTS = {
  price_asc: 'l.price ASC, l.created_at DESC',
  price_desc: 'l.price DESC, l.created_at DESC',
  recent: 'l.published_at DESC NULLS LAST, l.created_at DESC',
};

/**
 * GET /marketplace/listings
 * Public catalogue. Only PUBLISHED, non-deleted listings are ever visible here —
 * a listing is never publicly discoverable before the server confirms PUBLISHED.
 */
router.get(
  '/listings',
  buyerOrFarmer,
  validate(listingQuerySchema, 'query'),
  pagination,
  asyncHandler(async (req, res) => {
    const { search, crop, minPrice, maxPrice, sort } = req.query;
    const { page, pageSize, offset } = req.pagination;

    const where = ["l.status = 'PUBLISHED'", 'l.deleted_at IS NULL'];
    const params = [];

    const term = search || crop;
    if (term) {
      params.push(`%${term}%`);
      where.push(`l.produce ILIKE $${params.length}`);
    }
    if (minPrice !== undefined) {
      params.push(minPrice);
      where.push(`l.price >= $${params.length}`);
    }
    if (maxPrice !== undefined) {
      params.push(maxPrice);
      where.push(`l.price <= $${params.length}`);
    }

    const whereSql = where.join(' AND ');
    const orderSql = SORTS[sort] ?? SORTS.recent;

    const totalRow = await one(
      `SELECT count(*)::int AS total FROM produce_listings l WHERE ${whereSql}`,
      params,
    );

    params.push(pageSize, offset);
    const rows = await many(
      `SELECT l.*, u.full_name AS farmer_name
         FROM produce_listings l
         JOIN users u ON u.id = l.farmer_id
        WHERE ${whereSql}
        ORDER BY ${orderSql}
        LIMIT $${params.length - 1} OFFSET $${params.length}`,
      params,
    );

    res.json(paginated(rows.map(toListing), { page, pageSize, total: totalRow.total }));
  }),
);

/**
 * GET /marketplace/my-listings
 * A farmer's own listings in every state (including drafts and archived/sold).
 */
router.get(
  '/my-listings',
  farmerOnly,
  pagination,
  asyncHandler(async (req, res) => {
    const { page, pageSize, offset } = req.pagination;

    const totalRow = await one(
      `SELECT count(*)::int AS total
         FROM produce_listings
        WHERE farmer_id = $1 AND deleted_at IS NULL`,
      [req.user.id],
    );

    const rows = await many(
      `SELECT *
         FROM produce_listings
        WHERE farmer_id = $1 AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT $2 OFFSET $3`,
      [req.user.id, pageSize, offset],
    );

    res.json(paginated(rows.map(toListing), { page, pageSize, total: totalRow.total }));
  }),
);

/**
 * GET /marketplace/listings/:id
 * Visible if PUBLISHED, or if the requesting farmer owns it in any state.
 */
router.get(
  '/listings/:id',
  buyerOrFarmer,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const row = await one(
      `SELECT l.*, u.full_name AS farmer_name
         FROM produce_listings l
         JOIN users u ON u.id = l.farmer_id
        WHERE l.id = $1 AND l.deleted_at IS NULL`,
      [req.params.id],
    );
    if (!row) throw notFound('The requested listing was not found.');

    const isPublic = row.status === 'PUBLISHED';
    const isOwner = req.user.role === 'FARMER' && row.farmer_id === req.user.id;
    if (!isPublic && !isOwner) throw notFound('The requested listing was not found.');

    res.json(toListing(row));
  }),
);

/**
 * POST /marketplace/listings
 * Farmers create drafts. The listing only becomes public after an explicit
 * publish, never implicitly.
 */
router.post(
  '/listings',
  farmerOnly,
  validate(createListingSchema, 'body'),
  asyncHandler(async (req, res) => {
    const b = req.body;
    const row = await one(
      `INSERT INTO produce_listings
         (farmer_id, produce, description, quantity, unit, price, image_url, location_text, status)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,'DRAFT')
       RETURNING *`,
      [
        req.user.id,
        b.produce,
        b.description ?? null,
        b.quantity,
        b.unit,
        b.price,
        b.imageUrl ?? null,
        b.locationText ?? null,
      ],
    );

    await audit(req, 'LISTING_CREATED', {
      entityType: 'produce_listing',
      entityId: row.id,
      metadata: { produce: row.produce },
    });

    res.status(201).json(toListing(row));
  }),
);

/**
 * PATCH /marketplace/listings/:id
 * Partial update of an owned listing. Status changes are rejected (400) — they
 * flow exclusively through publish/archive so stock and visibility stay honest.
 */
router.patch(
  '/listings/:id',
  farmerOnly,
  validate(idParamSchema, 'params'),
  validate(updateListingSchema, 'body'),
  asyncHandler(async (req, res) => {
    const existing = await one(
      `SELECT * FROM produce_listings WHERE id = $1 AND deleted_at IS NULL`,
      [req.params.id],
    );
    if (!existing || existing.farmer_id !== req.user.id) {
      throw notFound('The requested listing was not found.');
    }

    const b = req.body;
    const fieldToColumn = {
      produce: 'produce',
      description: 'description',
      quantity: 'quantity',
      unit: 'unit',
      price: 'price',
      imageUrl: 'image_url',
      locationText: 'location_text',
    };

    const sets = [];
    const params = [];
    for (const [field, column] of Object.entries(fieldToColumn)) {
      if (Object.prototype.hasOwnProperty.call(b, field)) {
        params.push(b[field] ?? null);
        sets.push(`${column} = $${params.length}`);
      }
    }
    if (sets.length === 0) throw badRequest('Provide at least one field to update.');

    params.push(req.params.id);
    const row = await one(
      `UPDATE produce_listings
          SET ${sets.join(', ')}
        WHERE id = $${params.length}
        RETURNING *`,
      params,
    );

    await audit(req, 'LISTING_UPDATED', {
      entityType: 'produce_listing',
      entityId: row.id,
      metadata: { fields: Object.keys(b) },
    });

    res.json(toListing(row));
  }),
);

/**
 * POST /marketplace/listings/:id/publish
 * The client models DRAFT→PENDING_PUBLISH→SYNCING locally; the server is the
 * authority that flips the row to PUBLISHED and stamps published_at. A published
 * listing with no stock is rejected — you cannot sell what you do not have.
 */
router.post(
  '/listings/:id/publish',
  farmerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const existing = await one(
      `SELECT * FROM produce_listings WHERE id = $1 AND deleted_at IS NULL`,
      [req.params.id],
    );
    if (!existing || existing.farmer_id !== req.user.id) {
      throw notFound('The requested listing was not found.');
    }

    // Re-publishing an already-published listing is idempotent.
    if (existing.status === 'PUBLISHED') return res.json(toListing(existing));

    const publishable = ['DRAFT', 'PENDING_PUBLISH', 'RETRY'];
    if (!publishable.includes(existing.status)) {
      throw conflict('This listing cannot be published from its current state.');
    }
    if (Number(existing.quantity) <= 0) {
      throw badRequest('A listing needs stock before it can be published.');
    }

    const row = await one(
      `UPDATE produce_listings
          SET status = 'PUBLISHED', published_at = now()
        WHERE id = $1
        RETURNING *`,
      [existing.id],
    );

    await audit(req, 'LISTING_PUBLISHED', {
      entityType: 'produce_listing',
      entityId: row.id,
    });

    res.json(toListing(row));
  }),
);

/**
 * POST /marketplace/listings/:id/archive
 * Unpublishes (returns to DRAFT, clears published_at) so it disappears from the
 * public catalogue. Archiving is never a delete: the row and its stock history
 * are preserved so historical orders remain auditable.
 */
router.post(
  '/listings/:id/archive',
  farmerOnly,
  validate(idParamSchema, 'params'),
  asyncHandler(async (req, res) => {
    const existing = await one(
      `SELECT * FROM produce_listings WHERE id = $1 AND deleted_at IS NULL`,
      [req.params.id],
    );
    if (!existing || existing.farmer_id !== req.user.id) {
      throw notFound('The requested listing was not found.');
    }

    const row = await one(
      `UPDATE produce_listings
          SET status = 'DRAFT', published_at = NULL
        WHERE id = $1
        RETURNING *`,
      [existing.id],
    );

    await audit(req, 'LISTING_ARCHIVED', {
      entityType: 'produce_listing',
      entityId: row.id,
    });

    res.json(toListing(row));
  }),
);

export default router;
