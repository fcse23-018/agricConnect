import { describe, it, expect } from '@jest/globals';

import { ENTITIES, entityFor } from '../../src/modules/sync/sync.entities.js';

/**
 * These tests exercise the sync descriptors without a database: validation and
 * the insert/update builders are pure functions, so we can assert the exact SQL
 * text and bind parameters that would be sent to PostgreSQL.
 *
 * The plot photo column (`farm_plots.image_url`) is the reason this file exists:
 * it was added after the initial sync engine and is easy to drop from the
 * INSERT/UPDATE parameter list by accident.
 */
describe('sync entity registry', () => {
  it('knows the plot entity and rejects an unknown one', () => {
    expect(entityFor('FarmPlot')).toBe(ENTITIES.FarmPlot);
    expect(entityFor('NotAnEntity')).toBeNull();
  });
});

describe('FarmPlot sync descriptor — plot photo', () => {
  const basePayload = {
    clientId: 'plot-1',
    plotName: 'North field',
    areaHectares: 2.5,
    geoJsonBoundary: null,
    centroidLat: -24.6,
    centroidLng: 25.9,
    notes: 'maize',
  };

  it('accepts a relative upload path for imageUrl', () => {
    const parsed = ENTITIES.FarmPlot.validate({
      ...basePayload,
      imageUrl: '/uploads/9f8e7d6c-1234-4abc-9def-000000000000.jpg',
    });

    expect(parsed.imageUrl).toBe('/uploads/9f8e7d6c-1234-4abc-9def-000000000000.jpg');
  });

  it('allows imageUrl to be omitted (nullable)', () => {
    const parsed = ENTITIES.FarmPlot.validate(basePayload);
    expect(parsed.imageUrl ?? null).toBeNull();
  });

  it('rejects an imageUrl longer than the column allows', () => {
    expect(() =>
      ENTITIES.FarmPlot.validate({ ...basePayload, imageUrl: 'x'.repeat(501) }),
    ).toThrow();
  });

  it('binds image_url into the INSERT and always as the last parameter', () => {
    const payload = ENTITIES.FarmPlot.validate({
      ...basePayload,
      imageUrl: '/uploads/photo.png',
    });

    const { sql, params } = ENTITIES.FarmPlot.buildInsert({
      payload,
      ownerFarmerId: 'farmer-uuid',
      clientId: 'plot-1',
    });

    expect(sql).toContain('image_url');
    expect(sql).toContain('RETURNING *');
    // farmer_id, client_id, plot_name, area, boundary, lat, lng, notes, image_url
    expect(params).toHaveLength(9);
    expect(params[8]).toBe('/uploads/photo.png');
  });

  it('writes NULL for image_url when the plot has no photo', () => {
    const payload = ENTITIES.FarmPlot.validate(basePayload);
    const { params } = ENTITIES.FarmPlot.buildInsert({
      payload,
      ownerFarmerId: 'farmer-uuid',
      clientId: 'plot-1',
    });

    expect(params[8]).toBeNull();
  });

  it('updates image_url and keeps the WHERE id as the last parameter', () => {
    const payload = ENTITIES.FarmPlot.validate({
      ...basePayload,
      imageUrl: '/uploads/updated.webp',
    });

    const { sql, params } = ENTITIES.FarmPlot.buildUpdate({
      payload,
      existingId: 'existing-uuid',
    });

    expect(sql).toContain('image_url = $7');
    expect(params).toHaveLength(8);
    expect(params[6]).toBe('/uploads/updated.webp');
    expect(params[7]).toBe('existing-uuid');
  });

  it('serializes the stored image_url back to the client as imageUrl', () => {
    const row = {
      id: 'plot-uuid',
      client_id: 'plot-1',
      farmer_id: 'farmer-uuid',
      plot_name: 'North field',
      area_hectares: '2.5000',
      geojson_boundary: null,
      centroid_lat: -24.6,
      centroid_lng: 25.9,
      notes: null,
      image_url: '/uploads/photo.png',
      created_at: '2026-01-01T00:00:00.000Z',
      updated_at: '2026-01-02T00:00:00.000Z',
    };

    expect(ENTITIES.FarmPlot.serialize(row)).toEqual(
      expect.objectContaining({ plotId: 'plot-uuid', imageUrl: '/uploads/photo.png' }),
    );

    expect(ENTITIES.FarmPlot.serialize({ ...row, image_url: null })).toEqual(
      expect.objectContaining({ imageUrl: null }),
    );
  });
});
