"""The Earth Engine brain of the crop-monitoring service.

Two modes, selected by ``GEE_ENABLED``:

* **gee**  — real Sentinel-2 (``COPERNICUS/S2_SR_HARMONIZED``) with the
  Cloud Score+ layer, exactly as validated in the setup document. Requires
  ``earthengine-api`` and credentials.
* **mock** — a deterministic synthetic series derived from the field's own
  coordinates. It needs no Google account and no network, so the service, its
  tests and the Android integration can all run offline. Results are tagged
  ``source: "mock"`` and are **never** presented as real observations.

``summarise`` is mode-independent and pure: it turns a reading list into the
status label the app shows.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import math
import random
import threading
from typing import Any, Optional

from . import config
from .cache import TtlCache
from .geometry import LatLon, inset_ring, polygon_area_m2

#: A measured area smaller than this is refused — too few pixels to be honest.
MIN_AREA_M2 = 200.0

#: One Sentinel-2 pixel covers 10 m x 10 m.
PIXEL_AREA_M2 = 100.0

#: Cleaning thresholds (see the setup document, section 7.3).
CLOUD_SCORE_MIN = 0.6
MIN_CLEAR_FRACTION = 0.5
MAX_NDVI_EVI_GAP = 0.45
SPIKE_GAP = 0.3

_cache = TtlCache(config.settings.cache_ttl_seconds)
_init_lock = threading.Lock()
_initialised = False


# --------------------------------------------------------------------------- #
# Earth Engine bootstrap
# --------------------------------------------------------------------------- #
def init_ee() -> Any:
    """Import and initialise the Earth Engine client once per process."""
    global _initialised
    with _init_lock:
        if _initialised:
            import ee  # type: ignore

            return ee
        try:
            import ee  # type: ignore
        except ImportError as exc:  # pragma: no cover - depends on environment
            raise RuntimeError(
                "GEE_ENABLED=true but the 'earthengine-api' package is not installed. "
                "Run: pip install -r requirements.txt  (or set GEE_ENABLED=false)."
            ) from exc
        ee.Initialize(project=config.settings.gee_project)
        _initialised = True
        return ee


# --------------------------------------------------------------------------- #
# Public entry point: the series
# --------------------------------------------------------------------------- #
def fetch_series(
    corners: list[LatLon],
    inset_m: float = 5.0,
    days_back: int = 365,
) -> dict[str, Any]:
    """Return the cleaned NDVI/EVI series for the area inside ``corners``.

    The result is cached for ``CACHE_TTL_SECONDS`` keyed on the shape, the inset,
    the window and the day — imagery only changes every few days.
    """
    if len(corners) < 3:
        raise ValueError("At least three corners are required.")

    source = "gee" if config.settings.gee_enabled else "mock"
    key = (
        tuple((round(lat, 6), round(lon, 6)) for lat, lon in corners),
        round(inset_m, 3),
        days_back,
        source,
        _dt.date.today().isoformat(),
    )
    cached = _cache.get(key)
    if cached is not None:
        return cached

    if config.settings.gee_enabled:
        result = _fetch_series_gee(corners, inset_m, days_back)
    else:
        result = _fetch_series_mock(corners, inset_m, days_back)

    _cache.set(key, result)
    return result


def reset_cache() -> None:
    """Drop every cached series (used by tests)."""
    _cache.clear()


# --------------------------------------------------------------------------- #
# Measured-area arithmetic (shared by both modes)
# --------------------------------------------------------------------------- #
def measured_area_m2(corners: list[LatLon], inset_m: float) -> float:
    """Area after the inward inset, in square metres (pure geometry)."""
    ring = inset_ring(corners, inset_m) if inset_m > 0 else corners
    if ring is None:
        raise ValueError(
            "Area is under 200 m2 after the inset; mark a bigger area or lower inset_m."
        )
    return polygon_area_m2(ring)


def min_clear_pixels(area_m2: float, min_clear_fraction: float = MIN_CLEAR_FRACTION) -> int:
    """Clear pixels a reading needs to count: half the area's expected pixels."""
    return max(3, round(min_clear_fraction * area_m2 / PIXEL_AREA_M2))


# --------------------------------------------------------------------------- #
# Real Earth Engine path
# --------------------------------------------------------------------------- #
def _fetch_series_gee(
    corners: list[LatLon],
    inset_m: float,
    days_back: int,
) -> dict[str, Any]:  # pragma: no cover - requires live Earth Engine credentials
    ee = init_ee()
    ring = [[lon, lat] for lat, lon in corners]
    outer = ee.Geometry.Polygon([ring], None, False)
    region = outer.buffer(-inset_m) if inset_m > 0 else outer

    area_m2 = float(region.area(1).getInfo())
    if area_m2 < MIN_AREA_M2:
        raise ValueError(
            "Area is under 200 m2 after the inset; mark a bigger area or lower inset_m."
        )
    min_pixels = min_clear_pixels(area_m2)

    end = _dt.date.today()
    start = end - _dt.timedelta(days=days_back)
    s2 = ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
    csp = ee.ImageCollection("GOOGLE/CLOUD_SCORE_PLUS/V1/S2_HARMONIZED")

    def add_indices(img: Any) -> Any:
        scl = img.select("SCL")
        clear = img.select("cs_cdf").gte(CLOUD_SCORE_MIN).And(scl.eq(4).Or(scl.eq(5)))
        blue = img.select("B2").divide(10000)
        red = img.select("B4").divide(10000)
        nir = img.select("B8").divide(10000)
        ndvi = nir.subtract(red).divide(nir.add(red)).rename("ndvi")
        evi = (
            nir.subtract(red)
            .multiply(2.5)
            .divide(nir.add(red.multiply(6)).subtract(blue.multiply(7.5)).add(1))
            .rename("evi")
        )
        return ndvi.addBands(evi).updateMask(clear).copyProperties(img, ["system:time_start"])

    collection = (
        s2.filterBounds(region)
        .filterDate(start.isoformat(), (end + _dt.timedelta(days=1)).isoformat())
        .linkCollection(csp, ["cs_cdf"])
        .map(add_indices)
    )
    reducer = ee.Reducer.median().combine(ee.Reducer.count(), "", True)

    def to_feature(img: Any) -> Any:
        stats = img.reduceRegion(reducer, region, 10, maxPixels=1e8)
        return ee.Feature(
            None,
            {
                "date": img.date().format("YYYY-MM-dd"),
                "ndvi": stats.get("ndvi_median"),
                "evi": stats.get("evi_median"),
                "pixels": stats.get("ndvi_count"),
            },
        )

    fc = ee.FeatureCollection(collection.map(to_feature)).filter(
        ee.Filter.notNull(["ndvi", "evi", "pixels"])
    )
    raw = [f["properties"] for f in fc.getInfo()["features"]]
    raw.sort(key=lambda row: row["date"])

    readings, skipped = _clean(raw, min_pixels)
    return {
        "area_m2": round(area_m2, 1),
        "min_pixels": min_pixels,
        "readings": readings,
        "skipped": skipped,
        "source": "gee",
    }


# --------------------------------------------------------------------------- #
# Deterministic synthetic path
# --------------------------------------------------------------------------- #
def _fetch_series_mock(
    corners: list[LatLon],
    inset_m: float,
    days_back: int,
) -> dict[str, Any]:
    """A reproducible, plausible series — no Google account required.

    Values are seeded from the field's coordinates, so the same plot always
    produces the same curve while two different plots differ. Every response is
    tagged ``source: "mock"``.
    """
    area_m2 = measured_area_m2(corners, inset_m)
    if area_m2 < MIN_AREA_M2:
        raise ValueError(
            "Area is under 200 m2 after the inset; mark a bigger area or lower inset_m."
        )
    min_pixels = min_clear_pixels(area_m2)

    seed = _seed_for(corners)
    rng = random.Random(seed)
    end = _dt.date.today()
    start = end - _dt.timedelta(days=days_back)
    total_days = max(1, (end - start).days)

    expected_pixels = max(min_pixels, round(area_m2 / PIXEL_AREA_M2))
    phase = rng.uniform(0, 2 * math.pi)
    period = rng.choice((110.0, 130.0, 150.0))
    offset = rng.uniform(-0.08, 0.08)

    raw: list[dict[str, Any]] = []
    day = 0
    while day <= total_days:
        date = start + _dt.timedelta(days=day)
        progress = (day + phase * period / (2 * math.pi)) / period
        seasonal = 0.52 + 0.24 * math.sin(2 * math.pi * progress)
        ndvi = _clamp(seasonal + offset + rng.gauss(0, 0.03), 0.15, 0.82)
        evi = _clamp(ndvi * 0.62 - 0.01 + rng.gauss(0, 0.015), 0.05, 0.62)
        pixels = min(expected_pixels, max(min_pixels, round(expected_pixels * rng.uniform(0.6, 1.0))))
        raw.append(
            {
                "date": date.isoformat(),
                "ndvi": round(ndvi, 4),
                "evi": round(evi, 4),
                "pixels": int(pixels),
            }
        )
        day += 5  # Sentinel-2 revisit is about five days

    readings, skipped = _clean(raw, min_pixels)
    return {
        "area_m2": round(area_m2, 1),
        "min_pixels": min_pixels,
        "readings": readings,
        "skipped": skipped,
        "source": "mock",
    }


def _seed_for(corners: list[LatLon]) -> int:
    """A stable 32-bit seed from the ring's rounded coordinates."""
    payload = "|".join(f"{lat:.5f},{lon:.5f}" for lat, lon in corners)
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    return int(digest[:8], 16)


# --------------------------------------------------------------------------- #
# Cleaning rules
# --------------------------------------------------------------------------- #
def _clean(raw: list[dict[str, Any]], min_pixels: int) -> tuple[list[dict], list[dict]]:
    """Apply the pixel, haze and spike rules from the setup document (7.3).

    ``raw`` is sorted oldest-first. Returns ``(readings, skipped)``.
    """
    raw = sorted(raw, key=lambda row: row["date"])
    kept: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []

    for index, row in enumerate(raw):
        if row["pixels"] < min_pixels:
            skipped.append({"date": row["date"], "reason": "too_few_pixels"})
            continue
        if row["ndvi"] - row["evi"] > MAX_NDVI_EVI_GAP:
            skipped.append({"date": row["date"], "reason": "ndvi_evi_disagree"})
            continue
        if 0 < index < len(raw) - 1:
            d_prev = row["ndvi"] - raw[index - 1]["ndvi"]
            d_next = row["ndvi"] - raw[index + 1]["ndvi"]
            if (d_prev > SPIKE_GAP and d_next > SPIKE_GAP) or (
                d_prev < -SPIKE_GAP and d_next < -SPIKE_GAP
            ):
                skipped.append({"date": row["date"], "reason": "isolated_spike"})
                continue
        kept.append(
            {
                "date": row["date"],
                "ndvi": round(float(row["ndvi"]), 4),
                "evi": round(float(row["evi"]), 4),
                "pixels": int(row["pixels"]),
            }
        )
    return kept, skipped


# --------------------------------------------------------------------------- #
# Status summary
# --------------------------------------------------------------------------- #
def summarise(readings: list[dict[str, Any]]) -> dict[str, Any]:
    """Turn a reading list into a status label and a plain-language message.

    NDVI alone cannot tell harvest from stress, so the wording deliberately says
    "check the plot" rather than diagnosing a problem. Rules are applied in
    order; the first match wins (see the setup document, section 5).
    """
    if not readings:
        return {
            "state": "no_data",
            "message": "No clear satellite readings in this period.",
            "latest": None,
            "compared_with": None,
            "ndvi_change": None,
            "days_between": None,
            "data_age_days": None,
        }

    latest = readings[-1]
    d_latest = _dt.date.fromisoformat(latest["date"])

    reference: Optional[dict[str, Any]] = None
    for row in reversed(readings[:-1]):
        if (d_latest - _dt.date.fromisoformat(row["date"])).days >= 10:
            reference = row
            break

    change = round(latest["ndvi"] - reference["ndvi"], 4) if reference else None
    days = (d_latest - _dt.date.fromisoformat(reference["date"])).days if reference else None
    ndvi = latest["ndvi"]

    if change is not None and change <= -0.15:
        state = "watch"
        message = (
            "Sharp drop in greenness. This can be harvest or clearing, but also "
            "water stress, pests or disease. Please check the plot."
        )
    elif ndvi < 0.35:
        state = "bare_or_fallow"
        message = "Very little green cover: bare soil, fallow, or just harvested."
    elif change is not None and change >= 0.08:
        state = "rising"
        message = "Cover is increasing: crop establishing or growing."
    elif change is not None and change <= -0.08:
        state = "declining"
        message = "Greenness is falling: maturing or ageing crop, or early stress."
    else:
        state = "stable"
        message = "Greenness is steady."

    return {
        "state": state,
        "message": message,
        "latest": latest,
        "compared_with": reference,
        "ndvi_change": change,
        "days_between": days,
        "data_age_days": (_dt.date.today() - d_latest).days,
    }


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))
