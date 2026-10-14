import { z } from 'zod';

export const OPERATIONS = ['CREATE', 'UPDATE', 'DELETE'];

const isoDateTime = (label) =>
  z
    .string()
    .min(1)
    .max(64)
    .refine((v) => !Number.isNaN(Date.parse(v)), `${label} must be an ISO8601 date-time`);

/**
 * One queued offline write. `entityType` is intentionally a free string (not an
 * enum): an unknown entity type must fail as a single `FAILED` item, never reject
 * the whole batch. `payload` is accepted as any JSON shape (object or JSON string)
 * and is decoded/validated per item, so a bad payload fails only that item.
 */
export const pushItemSchema = z
  .object({
    queueId: z.string().trim().min(1).max(128),
    entityType: z.string().trim().min(1).max(64),
    entityId: z.string().trim().max(128).nullish(),
    operation: z.enum(OPERATIONS),
    payload: z.any(),
    clientTimestamp: isoDateTime('clientTimestamp'),
    retryCount: z.coerce.number().int().min(0).max(10_000).optional(),
  })
  .passthrough();

export const pushSchema = z
  .object({
    items: z.array(pushItemSchema).min(1).max(500),
    deviceId: z.string().trim().max(128).nullish(),
  })
  .passthrough();

export const pullQuerySchema = z
  .object({
    since: isoDateTime('since').optional(),
  })
  .passthrough();

export default { OPERATIONS, pushItemSchema, pushSchema, pullQuerySchema };
