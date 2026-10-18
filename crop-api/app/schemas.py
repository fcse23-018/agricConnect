"""Request and response contracts (Pydantic v2).

Bad input becomes a FastAPI **422** automatically; Earth Engine/network
failures are translated to **502** in :mod:`app.main`.
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .geometry import LatLon, rectangle_corners

Source = Literal["gee", "mock"]


class Point(BaseModel):
    """A WGS84 coordinate."""

    lat: float = Field(ge=-90, le=90, description="Latitude in degrees.")
    lon: float = Field(ge=-180, le=180, description="Longitude in degrees.")


class FieldArea(BaseModel):
    """The area to measure, given **either** as corners **or** as centre+size.

    Sending both forms — or neither — is a 422. This is the same contract the
    Android field-marker and the standalone ``ndvi_plot.py`` tool use.
    """

    model_config = ConfigDict(extra="forbid")

    corners: Optional[list[Point]] = Field(
        default=None,
        description="Three or more points listed in order around the shape.",
    )
    centre: Optional[Point] = Field(
        default=None,
        description="Centre point; use together with width_m and length_m.",
    )
    width_m: Optional[float] = Field(default=None, gt=5, le=2000, description="Short side (m).")
    length_m: Optional[float] = Field(default=None, gt=5, le=2000, description="Long side (m).")
    bearing_deg: float = Field(
        default=0,
        ge=0,
        lt=360,
        description="Direction of the long side, clockwise from north.",
    )
    inset_m: float = Field(
        default=5,
        ge=0,
        le=50,
        description="Shrink the area inwards by this much to avoid mixed edge pixels.",
    )
    days_back: int = Field(
        default=365,
        ge=15,
        le=730,
        description="How far back to read the Sentinel-2 series.",
    )

    @model_validator(mode="after")
    def _check_shape(self) -> "FieldArea":
        has_corners = bool(self.corners) and len(self.corners) >= 3
        has_rect = (
            self.centre is not None
            and self.width_m is not None
            and self.length_m is not None
        )
        if has_corners == has_rect:
            raise ValueError(
                "Give either 'corners' (3 or more) or 'centre' + 'width_m' + 'length_m' "
                "— not both and not neither."
            )
        return self

    def corners_latlon(self) -> list[LatLon]:
        """The outer ring as ``(lat, lon)`` tuples, whichever form was sent."""
        if self.corners:
            return [(p.lat, p.lon) for p in self.corners]
        assert self.centre and self.width_m and self.length_m  # guaranteed by the validator
        return rectangle_corners(
            self.centre.lat,
            self.centre.lon,
            self.width_m,
            self.length_m,
            self.bearing_deg,
        )


class Reading(BaseModel):
    """One clean satellite observation."""

    date: str = Field(description="ISO date (YYYY-MM-DD) of the Sentinel-2 acquisition.")
    ndvi: float = Field(description="Median NDVI over the measured area.")
    evi: float = Field(description="Median EVI over the measured area.")
    pixels: int = Field(description="Number of clear 10 m pixels used.")


class SkippedReading(BaseModel):
    """A raw observation that failed a cleaning rule, and why."""

    date: str
    reason: Literal["ndvi_evi_disagree", "isolated_spike", "too_few_pixels"]


class IndicesResponse(BaseModel):
    """The full NDVI/EVI series for the measured area."""

    area_m2: float = Field(description="Area actually measured, after the inset.")
    min_pixels: int = Field(description="Clear pixels a reading needed to count.")
    readings: list[Reading]
    skipped: list[SkippedReading]
    source: Source = Field(description="'gee' for live Sentinel-2, 'mock' for the synthetic series.")
    generated_at: str = Field(description="ISO timestamp this result was produced.")


class SummaryInfo(BaseModel):
    """A plain-language read of the latest observation."""

    state: Literal["rising", "stable", "declining", "watch", "bare_or_fallow", "no_data"]
    message: str
    latest: Optional[Reading] = None
    compared_with: Optional[Reading] = Field(
        default=None,
        description="Newest reading at least 10 days older than 'latest'.",
    )
    ndvi_change: Optional[float] = None
    days_between: Optional[int] = None
    data_age_days: Optional[int] = Field(
        default=None,
        description="Days since the latest satellite reading.",
    )


class HealthSummaryResponse(BaseModel):
    """The trimmed-down response the mobile app's card shows."""

    area_m2: float
    summary: SummaryInfo
    readings_used: int
    source: Source
    generated_at: str


class HealthResponse(BaseModel):
    """Liveness probe."""

    ok: bool
    service: str
    version: str
    gee_enabled: bool
