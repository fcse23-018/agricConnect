"""Request-contract tests for the shared FieldArea body."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas import FieldArea, Point


def test_corners_form_is_accepted():
    area = FieldArea(
        corners=[
            Point(lat=-21.38250, lon=27.44660),
            Point(lat=-21.38250, lon=27.44700),
            Point(lat=-21.38285, lon=27.44700),
        ]
    )
    ring = area.corners_latlon()
    assert len(ring) == 3
    assert ring[0] == (-21.38250, 27.44660)


def test_centre_form_is_accepted_and_expanded():
    area = FieldArea(
        centre=Point(lat=-21.382573, lon=27.446793),
        width_m=40,
        length_m=70,
        bearing_deg=140,
    )
    assert len(area.corners_latlon()) == 4


def test_both_forms_is_rejected():
    with pytest.raises(ValidationError):
        FieldArea(
            corners=[Point(lat=0, lon=0), Point(lat=0, lon=1), Point(lat=1, lon=1)],
            centre=Point(lat=0, lon=0),
            width_m=40,
            length_m=70,
        )


def test_neither_form_is_rejected():
    with pytest.raises(ValidationError):
        FieldArea(bearing_deg=10)


def test_too_few_corners_is_rejected():
    with pytest.raises(ValidationError):
        FieldArea(corners=[Point(lat=0, lon=0), Point(lat=0, lon=1)])


def test_width_bounds_are_enforced():
    with pytest.raises(ValidationError):
        FieldArea(centre=Point(lat=0, lon=0), width_m=3, length_m=70)


def test_bearing_must_be_under_360():
    with pytest.raises(ValidationError):
        FieldArea(centre=Point(lat=0, lon=0), width_m=40, length_m=70, bearing_deg=360)


def test_unknown_fields_are_rejected():
    with pytest.raises(ValidationError):
        FieldArea(centre=Point(lat=0, lon=0), width_m=40, length_m=70, mystery=1)
