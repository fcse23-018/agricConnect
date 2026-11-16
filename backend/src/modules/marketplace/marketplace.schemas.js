import { z } from 'zod';

/**
 * Unit enum mirrors the CHECK constraint on `produce_listings.unit`
 * (migrations/003_marketplace.sql, extended by 008_livestock_units.sql).
 * Keeping it here and in SQL means an invalid unit is rejected with a clear 400
 * rather than a database constraint error.
 *
 * Produce is sold by weight, volume or pack. Livestock is sold two ways, and both
 * are valid: `head` for one animal at a time (e.g. 20 head of cattle) and `herd` /
 * `flock` for a whole group sold as a single lot (quantity 1, one price). A buyer
 * seeing "1 herd" is buying the group, not an animal.
 */
export const LISTING_UNITS = ['kg', 'tonne', 'bag', 'crate', 'bunch', 'head', 'herd', 'flock', 'litre'];

/**
 * Units that describe a whole animal or group rather than a measured amount. The
 * app groups its picker with this, and it is the honest split between "how much"
 * and "how many".
 */
export const LIVESTOCK_UNITS = ['head', 'herd', 'flock'];

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

/**
 * Per-animal listing attributes (report CR2). An empty or whitespace-only
 * string is normalised to null rather than rejected, so a form that clears the
 * field can save without inventing an error. Everything is optional: these
 * describe livestock, and a sack of maize must not be forced to carry them.
 */
const optionalTrimmed = (max) =>
  z.preprocess(
    (v) => (typeof v === 'string' && v.trim() === '' ? null : v),
    z.string().trim().min(1).max(max).nullish(),
  );

const animalClass = optionalTrimmed(60);
const tagNumber = optionalTrimmed(60);
const ageMonths = z.coerce.number().int().min(0).max(600).nullish();
const weightKg = z.coerce.number().min(0).max(1_000_000).nullish();

export const createListingSchema = z
  .object({
    produce: z.string().trim().min(2).max(120),
    description: z.string().trim().max(2000).nullish(),
    quantity: quantitySchema,
    unit: z.enum(LISTING_UNITS).default('kg'),
    price: moneySchema,
    imageUrl: z.string().trim().max(500).nullish(),
    locationText: z.string().trim().max(200).nullish(),
    animalClass,
    ageMonths,
    weightKg,
    tagNumber,
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
    animalClass,
    ageMonths,
    weightKg,
    tagNumber,
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
