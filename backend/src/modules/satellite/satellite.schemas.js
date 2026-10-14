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

export default { createNdviSchema, historyQuerySchema, latestQuerySchema, idParamSchema };
