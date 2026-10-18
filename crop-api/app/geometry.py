"""Pure geometry helpers — no Earth Engine, no web framework, fully unit-tested.

A *ring* is an ordered list of ``(lat, lon)`` tuples, listed once around the
shape (the closing vertex is implied). Everything here works on a local
east/north metre plane, which is accurate to well under a metre for the field
sizes the service accepts (up to 2 km a side).
"""

from __future__ import annotations

import math

LatLon = tuple[float, float]
XY = tuple[float, float]

#: Metres per degree of latitude (mean value; the variation with latitude is
#: irrelevant at field scale).
M_PER_DEG_LAT = 111_320.0


def rectangle_corners(
    lat: float,
    lon: float,
    width_m: float,
    length_m: float,
    bearing_deg: float,
) -> list[LatLon]:
    """Four ``(lat, lon)`` corners of a rotated rectangle.

    ``bearing_deg`` is the compass direction of the **long** side, measured
    clockwise from north (0 = north, 90 = east). Corners are returned in order
    around the shape, starting at the south-west-most corner.
    """
    m_per_deg_lon = M_PER_DEG_LAT * math.cos(math.radians(lat))
    b = math.radians(bearing_deg)
    ux, uy = math.sin(b), math.cos(b)  # along the long side (east, north)
    vx, vy = math.cos(b), -math.sin(b)  # across the short side
    half_l, half_w = length_m / 2.0, width_m / 2.0

    corners: list[LatLon] = []
    for sl, sw in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        east = sl * half_l * ux + sw * half_w * vx
        north = sl * half_l * uy + sw * half_w * vy
        corners.append((lat + north / M_PER_DEG_LAT, lon + east / m_per_deg_lon))
    return corners


def _project(ring: list[LatLon]) -> tuple[list[XY], float, float]:
    """Project a ring onto a local east/north metre plane."""
    ref_lat = sum(p[0] for p in ring) / len(ring)
    ref_lon = sum(p[1] for p in ring) / len(ring)
    m_per_deg_lon = M_PER_DEG_LAT * math.cos(math.radians(ref_lat))
    pts = [((lon - ref_lon) * m_per_deg_lon, (lat - ref_lat) * M_PER_DEG_LAT) for lat, lon in ring]
    return pts, ref_lat, ref_lon


def _unproject(pts: list[XY], ref_lat: float, ref_lon: float) -> list[LatLon]:
    m_per_deg_lon = M_PER_DEG_LAT * math.cos(math.radians(ref_lat))
    return [(ref_lat + y / M_PER_DEG_LAT, ref_lon + x / m_per_deg_lon) for x, y in pts]


def signed_area_xy(pts: list[XY]) -> float:
    """Twice-signed planar area of a closed ring (positive when counter-clockwise)."""
    total = 0.0
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        total += x1 * y2 - x2 * y1
    return total / 2.0


def polygon_area_m2(ring: list[LatLon]) -> float:
    """Area of a ``(lat, lon)`` ring in square metres."""
    pts, _, _ = _project(ring)
    return abs(signed_area_xy(pts))


def centroid(ring: list[LatLon]) -> LatLon:
    """Arithmetic mean of the ring's vertices, as ``(lat, lon)``."""
    n = len(ring)
    return (sum(p[0] for p in ring) / n, sum(p[1] for p in ring) / n)


def inset_ring(ring: list[LatLon], inset_m: float) -> list[LatLon] | None:
    """Shrink a convex ring inwards by ``inset_m`` on every edge.

    Each edge is moved towards the interior by ``inset_m`` and adjacent offset
    lines are intersected to get the new vertices — exact for rectangles (the
    shape the field-marker produces) and a good approximation for any convex
    polygon. Returns ``None`` when the inset consumes the shape.

    The inset exists to drop "mixed" edge pixels that blend the plot with the
    path or the neighbouring field: half a Sentinel-2 pixel (5 m) is the
    sensible default.
    """
    if inset_m <= 0:
        return list(ring)

    pts, ref_lat, ref_lon = _project(ring)
    n = len(pts)
    if n < 3:
        return None

    # Work counter-clockwise so the interior is always to the left of an edge.
    if signed_area_xy(pts) < 0:
        pts = list(reversed(pts))
        reversed_input = True
    else:
        reversed_input = False

    offset_lines: list[tuple[XY, XY]] = []  # (point on line, unit direction)
    for i in range(n):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        if length == 0:
            return None
        ux, uy = dx / length, dy / length
        nx, ny = -uy, ux  # left-hand (inward) normal for a CCW ring
        offset_lines.append(((ax + nx * inset_m, ay + ny * inset_m), (ux, uy)))

    new_pts: list[XY] = []
    for i in range(n):
        p1, d1 = offset_lines[(i - 1) % n]
        p2, d2 = offset_lines[i]
        denom = d1[0] * d2[1] - d1[1] * d2[0]
        if abs(denom) < 1e-9:
            # Collinear edges (a straight-through vertex): the offset point works.
            new_pts.append(p2)
            continue
        dx, dy = p2[0] - p1[0], p2[1] - p1[1]
        t = (dx * d2[1] - dy * d2[0]) / denom
        new_pts.append((p1[0] + d1[0] * t, p1[1] + d1[1] * t))

    # Guard against an inset so large the shape turns inside out: the offset
    # lines still intersect, but every edge would point the opposite way.
    for i in range(n):
        ax, ay = pts[i]
        bx, by = pts[(i + 1) % n]
        cx, cy = new_pts[i]
        dx, dy = new_pts[(i + 1) % n]
        if (bx - ax) * (dx - cx) + (by - ay) * (dy - cy) <= 0:
            return None

    if abs(signed_area_xy(new_pts)) < 1e-6:
        return None
    if reversed_input:
        new_pts = list(reversed(new_pts))
    return _unproject(new_pts, ref_lat, ref_lon)


def ring_to_lonlat(ring: list[LatLon]) -> list[list[float]]:
    """Convert a ``(lat, lon)`` ring to GeoJSON/Earth-Engine ``[lon, lat]``."""
    return [[lon, lat] for lat, lon in ring]


def ring_to_geojson_polygon(ring: list[LatLon]) -> dict:
    """A closed GeoJSON Polygon geometry for the ring (handy for the client)."""
    coords = ring_to_lonlat(ring)
    coords.append(coords[0])
    return {"type": "Polygon", "coordinates": [coords]}
