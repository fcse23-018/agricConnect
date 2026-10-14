import { z } from 'zod';

/**
 * Unit enum mirrors the CHECK constraint on `produce_listings.unit`
 * (migrations/003_marketplace.sql). Keeping it here and in SQL means an invalid
 * unit is rejected with a clear 400 rather than a database constraint error.
 */
export const LISTING_UNITS = ['kg', 'tonne', 'bag', 'crate', 'bunch', 'head', 'litre'];

/**
 * Authoritative lifecycle enum (Architecture v1.1 §9, resolved conflict C3).
 * `SYNCING` and `RETRY` exist only on the client between a local publish attempt
 * and the server's PUBLISHED acknowledgement — the server records PUBLISHED
 * directly once its preconditions are met.
 */
export const LISTING_STATUSES = ['DRAFT', 'PENDING_PUBLISH', 'SYNCING', 'RETRY', 'PUBLISHED', 'SOLD'];

/** Numeric(14,2) columns cannot exceed ~1e12; cap well inside that. */
const MAX_MONEY = 999_999_999_999;

const quantitySchema = z.coerce.number().min(0).max(MAX_MONEY);
const moneySchema = z.coerce.number().min(0).max(MAX_MONEY);

export const createListingSchema = z
  .object({
    produce: z.string().trim().min(2).max(120),
    description: z.string().trim().max(2000).nullish(),
    quantity: quantitySchema,
    unit: z.enum(LISTING_UNITS).default('kg'),
    price: moneySchema,
    imageUrl: z.string().trim().max(500).nullish(),
    locationText: z.string().trim().max(200).nullish(),
  })
  // Strict: a brand-new listing can never be created already PUBLISHED/SOLD.
  .strict();

/**
 * Partial update. `status` is deliberately absent from the allowed keys:
 * lifecycle changes happen only through publish/archive. `passthrough` lets us
 * detect (and reject) a smuggled `status` with an explicit message, while other
 * unknown keys are ignored so the schema stays forward-compatible.
 */
export const updateListingSchema = z
  .object({
    produce: z.string().trim().min(2).max(120).optional(),
    description: z.string().trim().max(2000).nullish(),
    quantity: quantitySchema.optional(),
    unit: z.enum(LISTING_UNITS).optional(),
    price: moneySchema.optional(),
    imageUrl: z.string().trim().max(500).nullish(),
    locationText: z.string().trim().max(200).nullish(),
  })
  .partial()
  .passthrough()
  .superRefine((value, ctx) => {
    if (Object.prototype.hasOwnProperty.call(value, 'status')) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['status'],
        message: 'Status cannot be set directly. Use the publish or archive action.',
      });
    }
  });

/** Public listing browse filters. `crop` is an alias for `search` on produce. */
export const listingQuerySchema = z
  .object({
    search: z.string().trim().max(120).optional(),
    crop: z.string().trim().max(120).optional(),
    minPrice: z.coerce.number().min(0).optional(),
    maxPrice: z.coerce.number().min(0).optional(),
    sort: z.enum(['price_asc', 'price_desc', 'recent']).default('recent'),
    page: z.coerce.number().int().min(1).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional(),
  })
  .refine(
    (v) => v.minPrice === undefined || v.maxPrice === undefined || v.minPrice <= v.maxPrice,
    { message: 'minPrice must not be greater than maxPrice.', path: ['minPrice'] },
  );

export const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid listing identifier is required.' }),
});

export default {
  LISTING_UNITS,
  LISTING_STATUSES,
  createListingSchema,
  updateListingSchema,
  listingQuerySchema,
  idParamSchema,
};
