import { describe, it, expect } from '@jest/globals';

import { boundaryChanged } from '../../src/modules/satellite/satellite.service.js';
import { ringFromGeoJson } from '../../src/services/geometry.js';

/**
 * A boundary edit is a geometry change, and a geometry change invalidates the
 * satellite readings taken over the old shape. Both screens can move a boundary —
 * farm management via `PATCH /farms/plots/:id`, satellite via
 * `PUT /satellite/plots/:id/boundary` — and they share the `boundaryChanged`
 * predicate so the same edit has the same effect whichever screen made it.
 *
 * The predicate also has to say "no" convincingly, because the app resends the
 * boundary on every save and the offline replay can resend a whole payload.
 * Treating one of those as an edit would wipe the plot's observation history for
 * nothing, which is the failure mode these tests exist to prevent.
 */

/** A square drawn with GeoJSON's closing vertex, as the API stores it. */
function squareGeoJson({ west = 25.90, south = -24.63, east = 25.92, north = -24.62 } = {}) {
  return {
    type: 'Polygon',
    coordinates: [[
      [west, south],
      [east, south],
      [east, north],
      [west, north],
      [west, south],
    ]],
  };
}

describe('boundaryChanged', () => {
  it('reports no change for an identical boundary', () => {
    expect(boundaryChanged(squareGeoJson(), squareGeoJson())).toBe(false);
  });

  it('reports no change when only the closing vertex is missing', () => {
    // GeoJSON repeats the first point to close the ring; the stored column does
    // not always carry it. The two must not look like different shapes.
    const closed = squareGeoJson();
    const open = {
      type: 'Polygon',
      coordinates: [closed.coordinates[0].slice(0, -1)],
    };

    expect(boundaryChanged(closed, open)).toBe(false);
  });

  it('reports no change for float noise below the comparison precision', () => {
    // Re-encoding the same ring can shift the last decimal place. That is not a
    // edit a farmer made, and must not be treated as one.
    const original = squareGeoJson();
    const reEncoded = {
      type: 'Polygon',
      coordinates: [original.coordinates[0].map(([lng, lat]) => [lng + 1e-9, lat - 1e-9])],
    };

    expect(boundaryChanged(original, reEncoded)).toBe(false);
  });

  it('reports a change when a vertex actually moves', () => {
    const moved = squareGeoJson({ east: 25.93 });

    expect(boundaryChanged(squareGeoJson(), moved)).toBe(true);
  });

  it('reports a change when the shape is redrawn entirely', () => {
    const redrawn = squareGeoJson({ west: 25.80, south: -24.70, east: 25.85, north: -24.66 });

    expect(boundaryChanged(squareGeoJson(), redrawn)).toBe(true);
  });

  it('reports a change from nothing to something and back', () => {
    expect(boundaryChanged(null, squareGeoJson())).toBe(true);
    expect(boundaryChanged(squareGeoJson(), null)).toBe(true);
    expect(boundaryChanged(null, null)).toBe(false);
  });

  it('reads a Feature-wrapped boundary the same as a bare Polygon', () => {
    const bare = squareGeoJson();
    const feature = { type: 'Feature', properties: {}, geometry: bare };

    expect(boundaryChanged(bare, feature)).toBe(false);
  });

  it('reads a bare ring array the same as a Polygon', () => {
    // Some rows store the raw ring rather than a wrapped Polygon object.
    const wrapped = squareGeoJson();
    const ring = wrapped.coordinates[0];

    expect(boundaryChanged(wrapped, ring)).toBe(false);
  });

  it('is not fooled by vertex order — a different ring is a different shape', () => {
    // Reversing the winding order still covers the same ground, but it is a
    // different coordinate list; treating it as changed is the safe direction
    // (it clears observations rather than risking stale ones being reused).
    const forward = squareGeoJson();
    const reversed = {
      type: 'Polygon',
      coordinates: [[...forward.coordinates[0]].reverse()],
    };

    expect(boundaryChanged(forward, reversed)).toBe(true);
  });

  it('agrees with the geometry service about what a boundary is', () => {
    // The preview, the save and this comparison must all read the same ring, or
    // a boundary the server measured one way would be compared another way.
    // `ringFromGeoJson` is the canonical reader; the comparison must see the same
    // vertices it does.
    const boundary = squareGeoJson();
    const ring = ringFromGeoJson(boundary);

    expect(ring).toHaveLength(4); // the closing vertex is dropped
    expect(boundaryChanged(boundary, boundary)).toBe(false);
    // A shape built from exactly the vertices ringFromGeoJson reports is unchanged.
    const rebuilt = {
      type: 'Polygon',
      coordinates: [[...ring.map(({ lon, lat }) => [lon, lat]), [ring[0].lon, ring[0].lat]]],
    };
    expect(boundaryChanged(boundary, rebuilt)).toBe(false);
  });
});
