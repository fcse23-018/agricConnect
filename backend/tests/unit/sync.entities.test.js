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

/**
 * The plot boundary is the geometry NDVI is reduced over, so it must reach the jsonb
 * column as an object — a stringified polygon left in the column would break the Earth
 * Engine geometry. The device may send either form, so both are verified here.
 */
describe('FarmPlot sync descriptor — NDVI boundary', () => {
  const polygon = {
    type: 'Polygon',
    coordinates: [[[25.91, -24.65], [25.911, -24.65], [25.911, -24.649], [25.91, -24.65]]],
  };

  it('binds a GeoJSON object decoded into the jsonb column', () => {
    const payload = ENTITIES.FarmPlot.validate({
      clientId: 'plot-1',
      plotName: 'North field',
      geoJsonBoundary: polygon,
    });

    const { sql, params } = ENTITIES.FarmPlot.buildInsert({
      payload,
      ownerFarmerId: 'farmer-uuid',
      clientId: 'plot-1',
    });

    expect(sql).toContain('geojson_boundary');
    // farmer_id, client_id, plot_name, area, boundary, lat, lng, notes, image_url
    expect(params[4]).toEqual(polygon);
  });

  it('accepts a stringified polygon and stores it as an object', () => {
    const payload = ENTITIES.FarmPlot.validate({
      clientId: 'plot-1',
      plotName: 'North field',
      geoJsonBoundary: JSON.stringify(polygon),
    });

    const { params } = ENTITIES.FarmPlot.buildInsert({
      payload,
      ownerFarmerId: 'farmer-uuid',
      clientId: 'plot-1',
    });

    expect(typeof params[4]).toBe('object');
    expect(params[4]).toEqual(polygon);
  });

  it('clears the stored boundary when the payload carries none', () => {
    const payload = ENTITIES.FarmPlot.validate({ clientId: 'plot-1', plotName: 'North field' });

    const { params } = ENTITIES.FarmPlot.buildUpdate({ payload, existingId: 'existing-uuid' });

    expect(params[2]).toBeNull();
  });

  it('rejects a stringified boundary that is not valid JSON', () => {
    expect(() =>
      ENTITIES.FarmPlot.validate({
        clientId: 'plot-1',
        plotName: 'North field',
        geoJsonBoundary: '{not json',
      }),
    ).toThrow();
  });
});

describe('FarmPlot sync descriptor — reshaping a plot invalidates its observations', () => {
  /** A fake transaction client that records the statements it is handed. */
  function fakeClient(count = 0) {
    const calls = [];
    return {
      calls,
      query: async (sql, params) => {
        calls.push({ sql, params });
        return { rows: [{ count }] };
      },
    };
  }

  const square = (east) => ({
    type: 'Polygon',
    coordinates: [[[25.90, -24.63], [east, -24.63], [east, -24.62], [25.90, -24.62], [25.90, -24.63]]],
  });

  it('clears the stored series when the boundary actually moves', async () => {
    const client = fakeClient(7);

    const result = await ENTITIES.FarmPlot.afterUpdate({
      client,
      previous: { id: 'plot-uuid', geojson_boundary: square(25.92) },
      updated: { id: 'plot-uuid', geojson_boundary: square(25.93) },
    });

    expect(result).toEqual({ observationsInvalidated: 7 });
    expect(client.calls).toHaveLength(1);
    expect(client.calls[0].sql).toContain('DELETE FROM plot_observations');
    expect(client.calls[0].params).toEqual(['plot-uuid']);
  });

  it('leaves the series alone when the boundary is unchanged, so a re-save is not destructive', async () => {
    const client = fakeClient();

    const result = await ENTITIES.FarmPlot.afterUpdate({
      client,
      previous: { id: 'plot-uuid', geojson_boundary: square(25.92) },
      updated: { id: 'plot-uuid', geojson_boundary: square(25.92) },
    });

    expect(result).toBeNull();
    expect(client.calls).toHaveLength(0);
  });

  it('clears the series when a boundary is added for the first time', async () => {
    const client = fakeClient(3);

    const result = await ENTITIES.FarmPlot.afterUpdate({
      client,
      previous: { id: 'plot-uuid', geojson_boundary: null },
      updated: { id: 'plot-uuid', geojson_boundary: square(25.92) },
    });

    expect(result).toEqual({ observationsInvalidated: 3 });
  });

  it('clears the series when a boundary is removed', async () => {
    const client = fakeClient(4);

    const result = await ENTITIES.FarmPlot.afterUpdate({
      client,
      previous: { id: 'plot-uuid', geojson_boundary: square(25.92) },
      updated: { id: 'plot-uuid', geojson_boundary: null },
    });

    expect(result).toEqual({ observationsInvalidated: 4 });
  });

  it('runs inside the caller’s transaction, never opening its own connection', async () => {
    // The hook receives the sync transaction's client; if it ever reached for the
    // pool, a rolled-back sync would still have deleted the observations.
    const client = fakeClient(1);

    await ENTITIES.FarmPlot.afterUpdate({
      client,
      previous: { id: 'plot-uuid', geojson_boundary: null },
      updated: { id: 'plot-uuid', geojson_boundary: square(25.95) },
    });

    expect(client.calls).toHaveLength(1);
  });
});
