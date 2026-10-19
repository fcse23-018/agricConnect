/**
 * Plot geometry: real area and perimeter from the stored boundary.
 *
 * The brief is explicit that area must come from the actual geometry and not
 * from a bounding box, and it must be reported in both m² and hectares. This
 * module is the single place that does that, for both the rectangle method
 * (centre + width + length + bearing) and the polygon method (3+ vertices).
 *
 * Method: the ring is first projected onto a local tangent plane with a
 * spherical (WGS-84 mean radius) mercator-ish scaling around the ring's own
 * centroid, then measured with the shoelace formula and a plain Pythagorean
 * perimeter. For a farm plot, which is at most a few kilometres across, the
 * projection error is far below the uncertainty of the underlying imagery —
 * but it is *not* a geodesic computation, and this module says so rather than
 * pretending otherwise. There is no dependency on an external geospatial
 * library because the whole backend currently has no such dependency and the
 * accuracy here does not justify adding one.
 */

const METRES_PER_DEGREE_LAT = 111_132.0; // WGS-84 mean, at any latitude for a small plot
const MEAN_EARTH_RADIUS_M = 6_371_008.8;

/** Degrees → radians. */
const rad = (deg) => (deg * Math.PI) / 180;

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function round(value, places) {
  if (value === null) return null;
  const factor = 10 ** places;
  return Math.round(value * factor) / factor;
}

export const GEOMETRY_METHOD =
  'Local tangent-plane projection (WGS-84 mean radius) measured with the shoelace formula. ' +
  'Accurate for farm-plot scale; not a full geodesic computation.';

/**
 * Extract the outer ring as `[{lat, lon}]` from any accepted boundary shape.
 * Returns null when there is no ring of at least three usable vertices.
 */
export function ringFromGeoJson(geoJson) {
  if (!geoJson || typeof geoJson !== 'object') return null;

  let ring;
  if (geoJson.type === 'Feature') ring = geoJson.geometry?.coordinates?.[0];
  else if (geoJson.type === 'MultiPolygon') ring = geoJson.coordinates?.[0]?.[0];
  else ring = geoJson.coordinates?.[0];

  if (!Array.isArray(ring) || ring.length < 3) return null;

  const points = ring
    .filter((pair) => Array.isArray(pair) && finite(pair[0]) !== null && finite(pair[1]) !== null)
    .map(([lon, lat]) => ({ lat: Number(lat), lon: Number(lon) }));
  if (points.length < 3) return null;

  // Drop the duplicated closing vertex if the ring is explicitly closed.
  const first = points[0];
  const last = points[points.length - 1];
  if (points.length > 3 && first.lat === last.lat && first.lon === last.lon) points.pop();

  return points.length >= 3 ? points : null;
}

/** A boundary shape other code can store. Always a closed Polygon ring. */
export function geoJsonFromRing(points) {
  const ring = points.map(({ lat, lon }) => [Number(lon), Number(lat)]);
  ring.push([Number(points[0].lon), Number(points[0].lat)]);
  return { type: 'Polygon', coordinates: [ring] };
}

/** Arithmetic mean of the vertices — adequate as a weather/centroid point. */
export function ringCentroid(points) {
  if (!Array.isArray(points) || points.length === 0) return null;
  const lat = points.reduce((sum, p) => sum + p.lat, 0) / points.length;
  const lon = points.reduce((sum, p) => sum + p.lon, 0) / points.length;
  return { latitude: round(lat, 6), longitude: round(lon, 6) };
}

/**
 * The four corners of a rotated rectangle.
 *
 * `bearingDeg` is compass degrees, clockwise from north (0 = the `length`
 * axis runs north); `width` runs perpendicular to it. The preview the farmer
 * sees in the app is generated from exactly these corners, so what is drawn
 * is what gets measured.
 */
export function rectangleVertices({ centreLat, centreLon, widthM, lengthM, bearingDeg }) {
  const lat = finite(centreLat);
  const lon = finite(centreLon);
  const width = finite(widthM);
  const length = finite(lengthM);
  const bearing = finite(bearingDeg);
  if (lat === null || lon === null || width === null || length === null || bearing === null) return null;
  if (width <= 0 || length <= 0) return null;

  const halfLength = length / 2;
  const halfWidth = width / 2;
  const cosLat = Math.cos(rad(lat));
  const mPerDegLon = METRES_PER_DEGREE_LAT * Math.max(cosLat, 1e-6);

  // Local axes: `forward` along the bearing, `right` perpendicular to it.
  const bearingRad = rad(bearing);
  const forwardNorth = Math.cos(bearingRad);
  const forwardEast = Math.sin(bearingRad);
  const rightNorth = -forwardEast;
  const rightEast = forwardNorth;

  const local = [
    { north: halfLength, east: halfWidth },
    { north: halfLength, east: -halfWidth },
    { north: -halfLength, east: -halfWidth },
    { north: -halfLength, east: halfWidth },
  ];

  return local.map(({ north, east }) => ({
    lat: lat + (north * forwardNorth + east * rightNorth) / METRES_PER_DEGREE_LAT,
    lon: lon + (north * forwardEast + east * rightEast) / mPerDegLon,
  })).map(({ lat: outLat, lon: outLon }) => ({ lat: round(outLat, 7), lon: round(outLon, 7) }));
}

/** Project lat/lon vertices into local metres around their own centroid. */
function projectToMetres(points) {
  const centre = points.reduce(
    (acc, p) => ({ lat: acc.lat + p.lat / points.length, lon: acc.lon + p.lon / points.length }),
    { lat: 0, lon: 0 },
  );
  const mPerDegLon = METRES_PER_DEGREE_LAT * Math.max(Math.cos(rad(centre.lat)), 1e-6);
  return points.map((p) => ({
    x: (p.lon - centre.lon) * mPerDegLon,
    y: (p.lat - centre.lat) * METRES_PER_DEGREE_LAT,
  }));
}

/**
 * Signed area of a ring, in m². Positive is counter-clockwise on the plane.
 * Exported because the polygon validation needs the sign to reject a
 * self-intersecting "bow-tie" ring, whose lobes cancel to near zero.
 */
export function signedAreaM2(points) {
  const flat = projectToMetres(points);
  let twiceArea = 0;
  for (let i = 0; i < flat.length; i += 1) {
    const a = flat[i];
    const b = flat[(i + 1) % flat.length];
    twiceArea += a.x * b.y - b.x * a.y;
  }
  return twiceArea / 2;
}

/** Perimeter of a ring in metres, summed edge by edge. */
export function perimeterM(points) {
  const flat = projectToMetres(points);
  let total = 0;
  for (let i = 0; i < flat.length; i += 1) {
    const a = flat[i];
    const b = flat[(i + 1) % flat.length];
    total += Math.hypot(b.x - a.x, b.y - a.y);
  }
  return total;
}

/** Distance between two vertices in metres. */
export function distanceM(a, b) {
  if (!a || !b) return null;
  const mPerDegLon = METRES_PER_DEGREE_LAT * Math.max(Math.cos(rad((a.lat + b.lat) / 2)), 1e-6);
  return Math.hypot((b.lon - a.lon) * mPerDegLon, (b.lat - a.lat) * METRES_PER_DEGREE_LAT);
}

/**
 * Full measurement of a boundary.
 *
 * Returns `null` area rather than a guess when the ring is unusable, so a
 * caller can never present a made-up hectare figure.
 */
export function measureBoundary(geoJsonOrPoints) {
  const points = Array.isArray(geoJsonOrPoints) ? geoJsonOrPoints : ringFromGeoJson(geoJsonOrPoints);
  if (!points || points.length < 3) {
    return {
      valid: false,
      method: GEOMETRY_METHOD,
      reason: 'At least three vertices are required to measure a plot.',
      vertexCount: points?.length ?? 0,
      areaM2: null,
      areaHectares: null,
      perimeterM: null,
    };
  }

  const areaM2 = Math.abs(signedAreaM2(points));
  const perimeter = perimeterM(points);
  const centre = ringCentroid(points);
  const sideLengths = points.map((point, index) =>
    round(distanceM(point, points[(index + 1) % points.length]), 1));

  return {
    valid: areaM2 > 1,
    method: GEOMETRY_METHOD,
    reason: areaM2 > 1 ? null : 'The boundary encloses too little area to measure.',
    vertexCount: points.length,
    areaM2: round(areaM2, 1),
    areaHectares: round(areaM2 / 10_000, 4),
    perimeterM: round(perimeter, 1),
    centroid: centre,
    sideLengthsM: sideLengths,
    vertices: points.map((p) => ({ lat: round(p.lat, 7), lon: round(p.lon, 7) })),
  };
}

/**
 * Validate a farmer-drawn polygon.
 *
 * Rejects, with a reason the app can show, anything that is not a real plot:
 * too few vertices, repeated points, a self-intersecting ("bow-tie") outline,
 * a degenerate near-zero area, and an implausibly large area (a mis-tap that
 * spans continents). It never silently "fixes" bad input into a valid plot.
 */
export function validatePolygon(points, { maxHectares = 10_000, minAreaM2 = 10 } = {}) {
  if (!Array.isArray(points) || points.length < 3) {
    return { valid: false, reason: 'A plot boundary needs at least three vertices.' };
  }

  const normalised = points.map((p) => ({ lat: Number(p?.lat), lon: Number(p?.lon) }));
  if (normalised.some((p) => !Number.isFinite(p.lat) || !Number.isFinite(p.lon))) {
    return { valid: false, reason: 'Every vertex needs a valid latitude and longitude.' };
  }
  if (normalised.some((p) => Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180)) {
    return { valid: false, reason: 'A vertex is outside the valid latitude/longitude range.' };
  }

  // Duplicate or near-duplicate consecutive vertices carry no information and
  // make the ring ambiguous; treat them as a mistake rather than trimming them.
  for (let i = 0; i < normalised.length; i += 1) {
    const a = normalised[i];
    const b = normalised[(i + 1) % normalised.length];
    if (Math.abs(a.lat - b.lat) < 1e-7 && Math.abs(a.lon - b.lon) < 1e-7) {
      return { valid: false, reason: 'Two vertices are in the same place. Move or remove one of them.' };
    }
  }

  if (segmentsIntersect(normalised)) {
    return { valid: false, reason: 'The boundary crosses itself. Reorder the vertices so the outline does not overlap.' };
  }

  const signed = signedAreaM2(normalised);
  const areaM2 = Math.abs(signed);
  if (areaM2 < minAreaM2) {
    return { valid: false, reason: 'The boundary encloses almost no area. Check the vertex positions.' };
  }
  if (areaM2 / 10_000 > maxHectares) {
    return { valid: false, reason: `The boundary is larger than ${maxHectares} hectares, which is not a single plot.` };
  }

  return {
    valid: true,
    reason: null,
    orientation: signed > 0 ? 'COUNTER_CLOCKWISE' : 'CLOCKWISE',
    areaM2: round(areaM2, 1),
    areaHectares: round(areaM2 / 10_000, 4),
    measurement: measureBoundary(normalised),
  };
}

/** Segment-intersection test for the ring, ignoring adjacent (sharing) edges. */
function segmentsIntersect(points) {
  const n = points.length;
  for (let i = 0; i < n; i += 1) {
    for (let j = i + 1; j < n; j += 1) {
      // Skip edges that share a vertex, including the first and last edge.
      if (j === i || (j + 1) % n === i || (i + 1) % n === j) continue;
      if (edgesCross(points[i], points[(i + 1) % n], points[j], points[(j + 1) % n])) return true;
    }
  }
  return false;
}

function orientation(a, b, c) {
  const value = (b.lat - a.lat) * (c.lon - b.lon) - (b.lon - a.lon) * (c.lat - b.lat);
  if (Math.abs(value) < 1e-12) return 0;
  return value > 0 ? 1 : 2;
}

function onSegment(a, b, c) {
  return b.lat <= Math.max(a.lat, c.lat) + 1e-12 && b.lat >= Math.min(a.lat, c.lat) - 1e-12 &&
    b.lon <= Math.max(a.lon, c.lon) + 1e-12 && b.lon >= Math.min(a.lon, c.lon) - 1e-12;
}

function edgesCross(p1, q1, p2, q2) {
  const o1 = orientation(p1, q1, p2);
  const o2 = orientation(p1, q1, q2);
  const o3 = orientation(p2, q2, p1);
  const o4 = orientation(p2, q2, q1);

  if (o1 !== o2 && o3 !== o4) return true;
  if (o1 === 0 && onSegment(p1, p2, q1)) return true;
  if (o2 === 0 && onSegment(p1, q2, q1)) return true;
  if (o3 === 0 && onSegment(p2, p1, q2)) return true;
  if (o4 === 0 && onSegment(p2, q1, q2)) return true;
  return false;
}

/** Area of a great-circle polygon in hectares — used only to cross-check. */
export function sphericalAreaHectares(points) {
  if (!Array.isArray(points) || points.length < 3) return null;
  let total = 0;
  for (let i = 0; i < points.length; i += 1) {
    const a = points[i];
    const b = points[(i + 1) % points.length];
    total += rad(b.lon - a.lon) * (2 + Math.sin(rad(a.lat)) + Math.sin(rad(b.lat)));
  }
  return Math.abs((total * MEAN_EARTH_RADIUS_M * MEAN_EARTH_RADIUS_M) / 2) / 10_000;
}

export default {
  GEOMETRY_METHOD,
  ringFromGeoJson,
  geoJsonFromRing,
  ringCentroid,
  rectangleVertices,
  signedAreaM2,
  perimeterM,
  distanceM,
  measureBoundary,
  validatePolygon,
  sphericalAreaHectares,
};
