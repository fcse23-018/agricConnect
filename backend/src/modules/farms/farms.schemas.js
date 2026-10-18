import { z } from 'zod';

/**
 * GeoJSON boundaries arrive from the Android client as objects (a bare Polygon /
 * MultiPolygon or a Feature wrapper) and are stored verbatim in the
 * `farm_plots.geojson_boundary` jsonb column. We therefore validate the envelope
 * only — checking every vertex here would reject legitimate device output.
 */
export const geoJsonBoundarySchema = z
  .object({ type: z.enum(['Polygon', 'MultiPolygon', 'Feature']) })
  .passthrough()
  .superRefine((value, ctx) => {
    const coordinates = value.type === 'Feature' ? value.geometry?.coordinates : value.coordinates;
    if (!Array.isArray(coordinates) || coordinates.length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['coordinates'],
        message: 'Boundary must include coordinates.',
      });
    }
  });

const plotName = z.string().trim().min(1, 'Plot name is required.').max(120);
const areaHectares = z.number().finite().nonnegative().max(1_000_000);
const notes = z.string().trim().max(4_000);
const clientId = z.string().trim().min(1).max(120);
// A stored upload path ("/uploads/<uuid>.<ext>"), not an arbitrary external URL.
// See backend/src/services/uploads.js — the client uploads via POST /uploads
// (or /users/me/avatar) and then persists the returned path here.
const imageUrl = z.string().trim().max(500);

export const createPlotSchema = z.object({
  plotName,
  areaHectares: areaHectares.optional(),
  geoJsonBoundary: geoJsonBoundarySchema.optional(),
  clientId: clientId.optional(),
  notes: notes.optional(),
  imageUrl: imageUrl.optional(),
});

export const updatePlotSchema = z
  .object({
    plotName: plotName.optional(),
    areaHectares: areaHectares.nullable().optional(),
    geoJsonBoundary: geoJsonBoundarySchema.nullable().optional(),
    notes: notes.nullable().optional(),
    imageUrl: imageUrl.nullable().optional(),
  })
  .refine((value) => Object.keys(value).length > 0, {
    message: 'Provide at least one field to update.',
  });

export const idParamSchema = z.object({
  id: z.string().uuid({ message: 'A valid identifier is required.' }),
});

export default { geoJsonBoundarySchema, createPlotSchema, updatePlotSchema, idParamSchema };
