"""Pure geometry tests — the maths the whole service stands on."""

from __future__ import annotations

import math

import pytest

from app.geometry import (
    centroid,
    inset_ring,
    polygon_area_m2,
    rectangle_corners,
    ring_to_geojson_polygon,
)

# The test plot from the setup document: a 40 x 70 m block beside the Shashe River.
PLOT = dict(lat=-21.382573, lon=27.446793, width_m=40.0, length_m=70.0, bearing_deg=140.0)


def test_rectangle_corners_returns_four_ordered_points():
    corners = rectangle_corners(**PLOT)
    assert len(corners) == 4
    # Consecutive corners are either a width or a length apart, never more.
    for i in range(4):
        a = corners[i]
        b = corners[(i + 1) % 4]
        m_per_deg_lon = 111_320.0 * math.cos(math.radians(a[0]))
        dx = (b[1] - a[1]) * m_per_deg_lon
        dy = (b[0] - a[0]) * 111_320.0
        side = math.hypot(dx, dy)
        assert side == pytest.approx(40.0, abs=1.0) or side == pytest.approx(70.0, abs=1.0)


def test_rectangle_area_is_width_times_length():
    corners = rectangle_corners(**PLOT)
    assert polygon_area_m2(corners) == pytest.approx(40 * 70, rel=0.01)


def test_inset_shrinks_each_side_by_the_inset():
    corners = rectangle_corners(**PLOT)
    measured = inset_ring(corners, 5.0)
    assert measured is not None
    # 30 x 60 = 1800 m2; the document reported ~1794.4 m2 (geodesic vs planar).
    assert polygon_area_m2(measured) == pytest.approx(1800.0, rel=0.02)


def test_inset_of_zero_returns_the_original_shape():
    corners = rectangle_corners(**PLOT)
    assert inset_ring(corners, 0) == corners


def test_inset_too_large_consumes_the_shape():
    corners = rectangle_corners(**PLOT)
    assert inset_ring(corners, 45.0) is None


def test_centroid_of_a_square():
    ring = [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)]
    lat, lon = centroid(ring)
    assert (lat, lon) == pytest.approx((0.5, 0.5))


def test_geojson_polygon_is_closed():
    ring = [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0)]
    polygon = ring_to_geojson_polygon(ring)
    assert polygon["type"] == "Polygon"
    coords = polygon["coordinates"][0]
    assert coords[0] == coords[-1]
    assert coords[0] == [0.0, 0.0]
