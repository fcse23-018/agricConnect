"""Status-rule tests — the wording the farmer actually reads."""

from __future__ import annotations

from app.gee import summarise


def _reading(date: str, ndvi: float, evi: float | None = None) -> dict:
    return {"date": date, "ndvi": ndvi, "evi": ndvi * 0.6 if evi is None else evi, "pixels": 32}


def test_no_readings_is_no_data():
    result = summarise([])
    assert result["state"] == "no_data"
    assert result["latest"] is None


def test_rising_when_cover_increases():
    readings = [_reading("2026-01-01", 0.50), _reading("2026-01-19", 0.62)]
    result = summarise(readings)
    assert result["state"] == "rising"
    assert result["ndvi_change"] == 0.12
    assert result["days_between"] == 18


def test_declining_when_cover_falls_moderately():
    readings = [_reading("2026-01-01", 0.70), _reading("2026-01-19", 0.60)]
    result = summarise(readings)
    assert result["state"] == "declining"
    assert result["ndvi_change"] == -0.10


def test_watch_takes_priority_over_bare_soil():
    # A crash to near-bare soil must read as 'watch', not 'bare_or_fallow'.
    readings = [_reading("2026-04-01", 0.60), _reading("2026-04-19", 0.29)]
    result = summarise(readings)
    assert result["state"] == "watch"


def test_bare_or_fallow_when_no_reference_and_low_ndvi():
    # Two readings inside the 10-day window -> no reference -> judged on level.
    readings = [_reading("2026-08-16", 0.28), _reading("2026-08-18", 0.27)]
    result = summarise(readings)
    assert result["state"] == "bare_or_fallow"
    assert result["compared_with"] is None
    assert result["ndvi_change"] is None


def test_stable_when_change_is_small():
    readings = [_reading("2026-06-01", 0.60), _reading("2026-06-19", 0.63)]
    result = summarise(readings)
    assert result["state"] == "stable"


def test_reference_is_the_newest_reading_at_least_ten_days_older():
    readings = [
        _reading("2026-01-01", 0.40),
        _reading("2026-01-21", 0.45),  # only 4 days before the latest -> not the reference
        _reading("2026-01-25", 0.60),
    ]
    result = summarise(readings)
    assert result["compared_with"]["date"] == "2026-01-01"
    assert result["days_between"] == 24


def test_data_age_is_reported():
    readings = [_reading("2026-01-01", 0.50), _reading("2026-01-19", 0.60)]
    assert summarise(readings)["data_age_days"] is not None
