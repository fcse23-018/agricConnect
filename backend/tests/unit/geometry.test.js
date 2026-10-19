import { describe, it, expect } from '@jest/globals';

import {
  ringFromGeoJson,
  geoJsonFromRing,
  ringCentroid,
  rectangleVertices,
  measureBoundary,
  validatePolygon,
  signedAreaM2,
  perimeterM,
  distanceM,
  sphericalAreaHectares,
} from '../../src/services/geometry.js';

/** A 1 km × 2 km rectangle drawn as a polygon, centred near Gaborone. */
const RECTANGLE_POLYGON = [
  { lat: -24.6300, lon: 25.9000 },
  { lat: -24.6300, lon: 25.9200 },
  { lat: -24.6200, lon: 25.9200 },
  { lat: -24.6200, lon: 25.9000 },
];

describe('ringFromGeoJson', () => {
  it('accepts Polygon, Feature and MultiPolygon and drops the closing vertex', () => {
    const closed = { type: 'Polygon', coordinates: [[
      [25.9, -24.63], [25.92, -24.63], [25.92, -24.62], [25.9, -24.62], [25.9, -24.63],
    ]] };
    expect(ringFromGeoJson(closed)).toHaveLength(4);
    expect(ringFromGeoJson({ type: 'Feature', geometry: closed })).toHaveLength(4);
    expect(ringFromGeoJson({ type: 'MultiPolygon', coordinates: [closed.coordinates] })).toHaveLength(4);
  });

  it('rejects unusable input instead of guessing', () => {
    expect(ringFromGeoJson(null)).toBeNull();
    expect(ringFromGeoJson({})).toBeNull();
    expect(ringFromGeoJson({ type: 'Polygon', coordinates: [[[1, 2], [3, 4]]] })).toBeNull();
  });
});

describe('geoJsonFromRing', () => {
  it('round-trips a ring through a closed GeoJSON polygon', () => {
    const geoJson = geoJsonFromRing(RECTANGLE_POLYGON);
    expect(geoJson.type).toBe('Polygon');
    expect(geoJson.coordinates[0]).toHaveLength(5);
    expect(geoJson.coordinates[0][0]).toEqual(geoJson.coordinates[0][4]);
    expect(ringFromGeoJson(geoJson)).toHaveLength(4);
  });
});

describe('ringCentroid', () => {
  it('averages the vertices', () => {
    expect(ringCentroid(RECTANGLE_POLYGON)).toEqual({ latitude: -24.625, longitude: 25.91 });
  });
});

describe('measureBoundary', () => {
  it('measures a real polygon area, not a bounding box', () => {
    // A triangle covering half of the 1 km × 2 km rectangle above. The bounding
    // box would report the full rectangle, so this catches box-based shortcuts.
    const triangle = [
      { lat: -24.6300, lon: 25.9000 },
      { lat: -24.6300, lon: 25.9200 },
      { lat: -24.6200, lon: 25.9000 },
    ];
    const measurement = measureBoundary(triangle);
    const rectangle = measureBoundary(RECTANGLE_POLYGON);

    expect(measurement.valid).toBe(true);
    expect(measurement.areaHectares).toBeCloseTo(rectangle.areaHectares / 2, 1);
    expect(measurement.areaHectares).toBeLessThan(rectangle.areaHectares);
  });

  it('reports m², hectares, perimeter, centroid and edge lengths', () => {
    const measurement = measureBoundary(RECTANGLE_POLYGON);
    expect(measurement).toBeDefined();
    expect(measurement.valid).toBe(true);
    expect(measurement.areaM2).toBeGreaterThan(1_800_000);
    expect(measurement.areaM2).toBeLessThan(2_300_000);
    expect(measurement.areaHectares).toBeCloseTo(measurement.areaM2 / 10_000, 3);
    // Two ~2 km edges and two ~1.1 km edges.
    expect(measurement.perimeterM).toBeGreaterThan(6_000);
    expect(measurement.perimeterM).toBeLessThan(6_500);
    expect(measurement.sideLengthsM).toHaveLength(4);
    expect(measurement.centroid).toEqual({ latitude: -24.625, longitude: 25.91 });
    expect(measurement.method).toMatch(/shoelace/);
  });

  it('agrees with a great-circle area cross-check', () => {
    const measurement = measureBoundary(RECTANGLE_POLYGON);
    const spherical = sphericalAreaHectares(RECTANGLE_POLYGON);
    // Within 1%: the local-plane projection is good at plot scale.
    expect(Math.abs(measurement.areaHectares - spherical) / spherical).toBeLessThan(0.01);
  });

  it('refuses to invent an area for an unusable boundary', () => {
    const measurement = measureBoundary(null);
    expect(measurement.valid).toBe(false);
    expect(measurement.areaM2).toBeNull();
    expect(measurement.areaHectares).toBeNull();
    expect(measurement.perimeterM).toBeNull();
    expect(measurement.reason).toMatch(/three vertices/);
  });

  it('accepts a stored GeoJSON boundary directly', () => {
    expect(measureBoundary(geoJsonFromRing(RECTANGLE_POLYGON)).areaHectares).toBeGreaterThan(180);
  });
});

describe('rectangleVertices', () => {
  it('produces a rectangle with the requested width, length and bearing', () => {
    const corners = rectangleVertices({
      centreLat: -24.625, centreLon: 25.91, widthM: 400, lengthM: 800, bearingDeg: 0,
    });
    expect(corners).toHaveLength(4);

    const measurement = measureBoundary(corners);
    expect(measurement.areaM2).toBeCloseTo(320_000, -3); // 0.2 tolerance is too tight; see below
    expect(measurement.perimeterM).toBeCloseTo(2_400, -2);
  });

  it('measures back to 32 ha for a 400 m × 800 m rectangle', () => {
    const corners = rectangleVertices({
      centreLat: -24.625, centreLon: 25.91, widthM: 400, lengthM: 800, bearingDeg: 0,
    });
    const measurement = measureBoundary(corners);
    expect(measurement.areaHectares).toBeCloseTo(32, 1);
  });

  it('rotates the rectangle with the bearing while the area is unchanged', () => {
    const north = rectangleVertices({
      centreLat: -24.625, centreLon: 25.91, widthM: 300, lengthM: 600, bearingDeg: 0,
    });
    const east = rectangleVertices({
      centreLat: -24.625, centreLon: 25.91, widthM: 300, lengthM: 600, bearingDeg: 90,
    });

    expect(measureBoundary(north).areaHectares).toBeCloseTo(18, 1);
    expect(measureBoundary(east).areaHectares).toBeCloseTo(18, 1);

    // Bearing 90° means the "length" axis runs east, so the north-south extent
    // becomes the width instead of the length.
    const northExtent = Math.abs(north[0].lat - north[2].lat);
    const eastExtent = Math.abs(east[0].lat - east[2].lat);
    expect(northExtent).toBeGreaterThan(eastExtent);
  });

  it('survives a bearing beyond 360 and a negative bearing', () => {
    const base = { centreLat: -24.625, centreLon: 25.91, widthM: 200, lengthM: 400 };
    const a = rectangleVertices({ ...base, bearingDeg: 45 });
    const wrapped = rectangleVertices({ ...base, bearingDeg: 405 });
    const negative = rectangleVertices({ ...base, bearingDeg: -315 });
    expect(wrapped).toEqual(a);
    expect(negative).toEqual(a);
  });

  it('rejects a rectangle with no size', () => {
    expect(rectangleVertices({ centreLat: -24.6, centreLon: 25.9, widthM: 0, lengthM: 400, bearingDeg: 0 })).toBeNull();
    expect(rectangleVertices({ centreLat: -24.6, centreLon: 25.9, widthM: 400, lengthM: -1, bearingDeg: 0 })).toBeNull();
    expect(rectangleVertices({ centreLat: null, centreLon: 25.9, widthM: 400, lengthM: 400, bearingDeg: 0 })).toBeNull();
  });
});

describe('validatePolygon', () => {
  it('accepts a properly ordered polygon and measures it', () => {
    const result = validatePolygon(RECTANGLE_POLYGON);
    expect(result.valid).toBe(true);
    expect(result.areaHectares).toBeGreaterThan(180);
    expect(result.orientation).toBe('COUNTER_CLOCKWISE');
  });

  it('rejects fewer than three vertices', () => {
    const result = validatePolygon([{ lat: -24.63, lon: 25.9 }, { lat: -24.62, lon: 25.92 }]);
    expect(result.valid).toBe(false);
    expect(result.reason).toMatch(/at least three vertices/);
  });

  it('rejects duplicate vertices', () => {
    const result = validatePolygon([
      { lat: -24.63, lon: 25.9 },
      { lat: -24.63, lon: 25.9 },
      { lat: -24.62, lon: 25.92 },
    ]);
    expect(result.valid).toBe(false);
    expect(result.reason).toMatch(/same place/);
  });

  it('rejects a self-intersecting bow-tie outline', () => {
    const result = validatePolygon([
      { lat: -24.630, lon: 25.900 },
      { lat: -24.620, lon: 25.920 },
      { lat: -24.620, lon: 25.900 },
      { lat: -24.630, lon: 25.920 },
    ]);
    expect(result.valid).toBe(false);
    expect(result.reason).toMatch(/crosses itself/);
  });

  it('rejects a degenerate zero-area sliver', () => {
    const result = validatePolygon([
      { lat: -24.630, lon: 25.900 },
      { lat: -24.630, lon: 25.910 },
      { lat: -24.630, lon: 25.920 },
    ]);
    expect(result.valid).toBe(false);
    expect(result.reason).toMatch(/almost no area/);
  });

  it('rejects an implausibly large "plot"', () => {
    const result = validatePolygon([
      { lat: -30, lon: 20 },
      { lat: -30, lon: 32 },
      { lat: -20, lon: 32 },
      { lat: -20, lon: 20 },
    ]);
    expect(result.valid).toBe(false);
    expect(result.reason).toMatch(/larger than/);
  });

  it('rejects out-of-range and non-numeric coordinates', () => {
    expect(validatePolygon([{ lat: 95, lon: 25 }, { lat: -24, lon: 25 }, { lat: -24, lon: 26 }]).reason)
      .toMatch(/latitude\/longitude range/);
    expect(validatePolygon([{ lat: 'abc', lon: 25 }, { lat: -24, lon: 25 }, { lat: -24, lon: 26 }]).reason)
      .toMatch(/valid latitude and longitude/);
  });

  it('accepts a triangle without closing it', () => {
    const result = validatePolygon([
      { lat: -24.6300, lon: 25.9000 },
      { lat: -24.6300, lon: 25.9200 },
      { lat: -24.6200, lon: 25.9000 },
    ]);
    expect(result.valid).toBe(true);
  });
});

describe('primitives', () => {
  it('computes a signed area whose magnitude is independent of winding', () => {
    const reversed = [...RECTANGLE_POLYGON].reverse();
    expect(Math.abs(signedAreaM2(RECTANGLE_POLYGON))).toBeCloseTo(Math.abs(signedAreaM2(reversed)), 3);
  });

  it('measures a straight perimeter', () => {
    const square = [
      { lat: -24.6300, lon: 25.9000 },
      { lat: -24.6300, lon: 25.9100 },
      { lat: -24.6200, lon: 25.9100 },
      { lat: -24.6200, lon: 25.9000 },
    ];
    expect(perimeterM(square)).toBeCloseTo(4_200, -2);
  });

  it('measures the distance between two vertices', () => {
    const d = distanceM({ lat: -24.63, lon: 25.90 }, { lat: -24.63, lon: 25.91 });
    expect(d).toBeGreaterThan(900);
    expect(d).toBeLessThan(1_100);
    expect(distanceM(null, { lat: 0, lon: 0 })).toBeNull();
  });
});
