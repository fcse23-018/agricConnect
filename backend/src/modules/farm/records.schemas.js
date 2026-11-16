import { z } from 'zod';

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const dateOnly = z.string().regex(DATE_ONLY, 'Date must be in YYYY-MM-DD format.');
const clientId = z.string().trim().min(1).max(120);
const notes = z.string().trim().max(4_000);
const cropType = z.string().trim().min(1).max(80);
const animalType = z.string().trim().min(1).max(80);
const quantityKg = z.number().finite().nonnegative();
const headCount = z.number().int().nonnegative();
const plotId = z.string().uuid({ message: 'A valid plot identifier is required.' });

const nonEmpty = (value) => Object.keys(value).length > 0;
const nonEmptyOptions = { message: 'Provide at least one field to update.' };

export const createHarvestSchema = z.object({
  cropType,
  quantityKg,
  harvestDate: dateOnly,
  plotId: plotId.nullable().optional(),
  notes: notes.nullable().optional(),
  clientId: clientId.optional(),
});

export const updateHarvestSchema = z
  .object({
    cropType: cropType.optional(),
    quantityKg: quantityKg.optional(),
    harvestDate: dateOnly.optional(),
    plotId: plotId.nullable().optional(),
    notes: notes.nullable().optional(),
  })
  .refine(nonEmpty, nonEmptyOptions);

export const listHarvestQuerySchema = z.object({
  from: dateOnly.optional(),
  to: dateOnly.optional(),
  cropType: cropType.optional(),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
});

export const createLivestockSchema = z.object({
  animalType,
  headCount,
  recordDate: dateOnly,
  notes: notes.nullable().optional(),
  clientId: clientId.optional(),
});

export const updateLivestockSchema = z
  .object({
    animalType: animalType.optional(),
    headCount: headCount.optional(),
    recordDate: dateOnly.optional(),
    notes: notes.nullable().optional(),
  })
  .refine(nonEmpty, nonEmptyOptions);

export const listLivestockQuerySchema = z.object({
  from: dateOnly.optional(),
  to: dateOnly.optional(),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
});

/* ------------------------------ herd register ----------------------------- */

/**
 * The register entry kinds the field study named (CR1): births, deaths, weights,
 * sales and dipping/veterinary dates. `OTHER` is the safety valve so a keeper
 * is never forced to misfile an entry the list does not describe.
 * Mirrors the CHECK constraint on `herd_events.event_type`
 * (migrations/009_herd_register.sql).
 */
export const HERD_EVENT_TYPES = ['BIRTH', 'DEATH', 'SALE', 'WEIGHING', 'DIPPING', 'VETERINARY', 'OTHER'];

const eventType = z.enum(HERD_EVENT_TYPES);
const tagNumber = z.string().trim().min(1).max(60).nullable().optional();
const headCountPositive = z.number().int().min(1).max(1_000_000);
const weightKg = z.number().finite().nonnegative().nullable().optional();
const money = z.number().finite().nonnegative().nullable().optional();

export const createHerdEventSchema = z.object({
  animalType,
  eventType,
  eventDate: dateOnly,
  headCount: headCountPositive.default(1),
  weightKg,
  unitPrice: money,
  amount: money,
  tagNumber,
  notes: notes.nullable().optional(),
  clientId: clientId.optional(),
});

export const updateHerdEventSchema = z
  .object({
    animalType: animalType.optional(),
    eventType: eventType.optional(),
    eventDate: dateOnly.optional(),
    headCount: headCountPositive.optional(),
    weightKg,
    unitPrice: money,
    amount: money,
    tagNumber,
    notes: notes.nullable().optional(),
  })
  .refine(nonEmpty, nonEmptyOptions);

export const listHerdEventQuerySchema = z.object({
  from: dateOnly.optional(),
  to: dateOnly.optional(),
  animalType: animalType.optional(),
  eventType: eventType.optional(),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
});

export const herdSummaryQuerySchema = z.object({
  from: dateOnly.optional(),
  to: dateOnly.optional(),
});

export const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid identifier is required.' }),
});

export default {
  createHarvestSchema,
  updateHarvestSchema,
  listHarvestQuerySchema,
  createLivestockSchema,
  updateLivestockSchema,
  listLivestockQuerySchema,
  HERD_EVENT_TYPES,
  createHerdEventSchema,
  updateHerdEventSchema,
  listHerdEventQuerySchema,
  herdSummaryQuerySchema,
  idParamSchema,
};
