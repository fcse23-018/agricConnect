import { z } from 'zod';

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const dateOnly = z.string().regex(DATE_ONLY, 'Date must be in YYYY-MM-DD format.');
const clientId = z.string().trim().min(1).max(120);
const notes = z.string().trim().max(4_000);
const amount = z.number().finite().nonnegative();
const quantity = z.number().finite().nonnegative();
const unitPrice = z.number().finite().nonnegative();

const nonEmpty = (value) => Object.keys(value).length > 0;
const nonEmptyOptions = { message: 'Provide at least one field to update.' };

export const createSaleSchema = z.object({
  item: z.string().trim().min(1).max(120),
  quantity,
  unitPrice: unitPrice.nullable().optional(),
  amount,
  buyerName: z.string().trim().max(120).nullable().optional(),
  date: dateOnly,
  notes: notes.nullable().optional(),
  clientId: clientId.optional(),
});

export const updateSaleSchema = z
  .object({
    item: z.string().trim().min(1).max(120).optional(),
    quantity: quantity.optional(),
    unitPrice: unitPrice.nullable().optional(),
    amount: amount.optional(),
    buyerName: z.string().trim().max(120).nullable().optional(),
    date: dateOnly.optional(),
    notes: notes.nullable().optional(),
  })
  .refine(nonEmpty, nonEmptyOptions);

export const createExpenseSchema = z.object({
  category: z.string().trim().min(1).max(80),
  description: z.string().trim().max(2_000).nullable().optional(),
  amount,
  expenseDate: dateOnly,
  clientId: clientId.optional(),
});

export const updateExpenseSchema = z
  .object({
    category: z.string().trim().min(1).max(80).optional(),
    description: z.string().trim().max(2_000).nullable().optional(),
    amount: amount.optional(),
    expenseDate: dateOnly.optional(),
  })
  .refine(nonEmpty, nonEmptyOptions);

export const listQuerySchema = z.object({
  from: dateOnly.optional(),
  to: dateOnly.optional(),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
});

export const summaryQuerySchema = z.object({
  from: dateOnly.optional(),
  to: dateOnly.optional(),
});

export const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid identifier is required.' }),
});

export default {
  createSaleSchema,
  updateSaleSchema,
  createExpenseSchema,
  updateExpenseSchema,
  listQuerySchema,
  summaryQuerySchema,
  idParamSchema,
};
