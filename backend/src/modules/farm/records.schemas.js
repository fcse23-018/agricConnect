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
  idParamSchema,
};
