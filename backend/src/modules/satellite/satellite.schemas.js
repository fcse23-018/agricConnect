import { z } from 'zod';

import { geoJsonBoundarySchema } from '../farms/farms.schemas.js';

export const createNdviSchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
  // Optional: when omitted the plot's stored boundary is used.
  geoJsonBoundary: geoJsonBoundarySchema.optional(),
});

export const historyQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }).optional(),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
});

export const latestQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
});

export const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid identifier is required.' }),
});

/* --------------------------- monitoring: geometry -------------------------- */

/** A single vertex. Latitude/longitude are validated for range, not stringly. */
const vertexSchema = z.object({
  lat: z.coerce.number().min(-90).max(90),
  lon: z.coerce.number().min(-180).max(180),
});

/**
 * Method A — a rotated rectangle. Bearing is allowed outside 0-360 so a client
 * that accumulates the rotation (e.g. 405°) is not rejected for a cosmetic
 * reason; the geometry service normalises it.
 */
export const rectangleSchema = z.object({
  method: z.literal('RECTANGLE'),
  centreLat: z.coerce.number().min(-90).max(90),
  centreLon: z.coerce.number().min(-180).max(180),
  widthM: z.coerce.number().positive().max(100_000),
  lengthM: z.coerce.number().positive().max(100_000),
  bearingDeg: z.coerce.number().min(-1_080).max(1_080),
});

/** Method B — a farmer-drawn polygon. Deeper checks live in the geometry service. */
export const polygonSchema = z.object({
  method: z.literal('POLYGON'),
  vertices: z.array(vertexSchema).min(3).max(500, { message: 'A boundary cannot have more than 500 vertices.' }),
});

export const plotBoundarySchema = z.discriminatedUnion('method', [rectangleSchema, polygonSchema]);

/** A short, non-identifying label for the preview and the notification body. */
export const plotNameSchema = z.string().trim().min(1).max(120);

/* ------------------------- monitoring: observations ------------------------ */

/**
 * Observation window. `days` is the documented parameter; `daysBack` is accepted
 * as an alias so a client that names it after the provider's `days_back` is not
 * silently given the default window instead of the one it asked for.
 */
const windowDays = z.coerce.number().int().min(15).max(730).optional();

export const overviewQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
  days: windowDays,
  daysBack: windowDays,
  /** `true` forces a fresh provider call instead of the stored series. */
  refresh: z.union([z.literal('true'), z.literal('false'), z.literal('1'), z.literal('0')]).optional(),
});

export const compareQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
  days: windowDays,
  daysBack: windowDays,
});

/**
 * The colour map ("satellite sight"). `size` is clamped by the service, so this
 * only has to keep obviously bad input out; the grid resolution is a display
 * preference, not something that can corrupt a stored reading.
 */
export const colourMapQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
  index: z.enum(['ndvi', 'evi']).optional(),
  size: z.coerce.number().int().min(8).max(48).optional(),
  days: windowDays,
});

/* ---------------------------- monitoring: events --------------------------- */

export const EVENT_KINDS = Object.freeze([
  'HARVEST',
  'CLEARING',
  'HEAT_STRESS',
  'WATER_STRESS',
  'OBSERVATION',
  'OTHER',
]);

export const createEventSchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
  eventDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, { message: 'Use the date format YYYY-MM-DD.' }),
  kind: z.enum(EVENT_KINDS),
  notes: z.string().trim().max(2_000).optional(),
  /** Links a farmer's entry to a detected drop, so the two become one record. */
  detectedEventId: z.string().uuid().optional(),
  /** Idempotency for an offline queue replaying the same event. */
  clientId: z.string().trim().max(120).optional(),
});

export const updateEventSchema = z.object({
  /** The farmer confirming or correcting the system's suggested classification. */
  kind: z.enum(EVENT_KINDS).optional(),
  confirmed: z.boolean().optional(),
  notes: z.string().trim().max(2_000).optional(),
  eventDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, { message: 'Use the date format YYYY-MM-DD.' }).optional(),
});

export const eventListQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
  kind: z.enum(EVENT_KINDS).optional(),
  source: z.enum(['FARMER', 'SYSTEM']).optional(),
});

/* ---------------------------- monitoring: alerts --------------------------- */

export const alertListQuerySchema = z.object({
  plotId: z.string().uuid({ message: 'A valid plot identifier is required.' }).optional(),
  status: z.enum(['OPEN', 'ACKNOWLEDGED', 'DISMISSED', 'ALL']).optional(),
  page: z.coerce.number().int().min(1).optional(),
  pageSize: z.coerce.number().int().min(1).max(100).optional(),
});

export const alertActionSchema = z.object({
  action: z.enum(['ACKNOWLEDGE', 'DISMISS', 'REOPEN']),
});

export default {
  createNdviSchema,
  historyQuerySchema,
  latestQuerySchema,
  idParamSchema,
  rectangleSchema,
  polygonSchema,
  plotBoundarySchema,
  plotNameSchema,
  overviewQuerySchema,
  compareQuerySchema,
  colourMapQuerySchema,
  createEventSchema,
  updateEventSchema,
  eventListQuerySchema,
  alertListQuerySchema,
  alertActionSchema,
  EVENT_KINDS,
};
