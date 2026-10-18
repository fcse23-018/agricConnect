"""HTTP surface of the AgriConnect crop-monitoring service.

Run it with::

    uvicorn app.main:app --host 0.0.0.0 --port 8000

Three useful endpoints (plus a liveness probe and the auto-generated docs at
``/docs``):

* ``GET  /health``            — is the service up, and is it in live-GEE mode?
* ``POST /v1/area``           — the measured area and minimum pixel count only.
* ``POST /v1/indices``        — the full NDVI/EVI series.
* ``POST /v1/health-summary`` — latest reading + a plain-language status.

All ``POST`` bodies share the same :class:`~app.schemas.FieldArea` shape.
"""

from __future__ import annotations

import datetime as _dt
import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware

from . import __version__, config, gee
from .geometry import inset_ring, ring_to_geojson_polygon
from .schemas import (
    FieldArea,
    HealthResponse,
    HealthSummaryResponse,
    IndicesResponse,
    Point,
)
from .security import require_api_key

logger = logging.getLogger("agriconnect.crop")

SERVICE_NAME = "agriconnect-crop-monitoring"


@asynccontextmanager
async def lifespan(_app: FastAPI):  # pragma: no cover - exercised implicitly
    logger.info(
        "%s v%s starting — GEE_ENABLED=%s, cache TTL=%ss",
        SERVICE_NAME,
        __version__,
        config.settings.gee_enabled,
        config.settings.cache_ttl_seconds,
    )
    if not config.settings.auth_enabled:
        logger.warning(
            "CROP_API_KEY is not set: the service is unauthenticated. Set it before "
            "exposing this port beyond localhost."
        )
    yield


app = FastAPI(
    title="AgriConnect Crop Monitoring",
    version=__version__,
    summary="Sentinel-2 NDVI/EVI for a marked field, computed by Google Earth Engine.",
    description=__doc__,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=config.settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def _now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat()


def _run_series(area: FieldArea) -> dict:
    """Call Earth Engine, translating failures into meaningful HTTP statuses."""
    try:
        return gee.fetch_series(
            area.corners_latlon(),
            inset_m=area.inset_m,
            days_back=area.days_back,
        )
    except ValueError as exc:
        # Bad geometry (area too small after the inset) is a client problem.
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc))
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001 - Earth Engine / network boundary
        logger.exception("Earth Engine request failed")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Earth Engine request failed: {exc}",
        )


# --------------------------------------------------------------------------- #
# Routes
# --------------------------------------------------------------------------- #
@app.get("/health", response_model=HealthResponse, tags=["meta"])
def health() -> HealthResponse:
    """Liveness probe — safe to call without an API key."""
    return HealthResponse(
        ok=True,
        service=SERVICE_NAME,
        version=__version__,
        gee_enabled=config.settings.gee_enabled,
    )


@app.post("/v1/area", tags=["geometry"], dependencies=[Depends(require_api_key)])
def area(area: FieldArea) -> dict:
    """The measured area, minimum pixel count and GeoJSON ring — no satellite data.

    Useful for the field-marker screen: it confirms a shape is big enough and
    shows exactly what will be measured before the user spends a satellite call.
    """
    corners = area.corners_latlon()
    try:
        measured = gee.measured_area_m2(corners, area.inset_m)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc))
    measured_ring = (
        inset_ring(corners, area.inset_m) if area.inset_m > 0 else corners
    ) or corners
    return {
        "area_m2": round(measured, 1),
        "min_pixels": gee.min_clear_pixels(measured),
        "outer": ring_to_geojson_polygon(corners),
        "measured": ring_to_geojson_polygon(measured_ring),
        "centre": Point(
            lat=sum(p[0] for p in corners) / len(corners),
            lon=sum(p[1] for p in corners) / len(corners),
        ).model_dump(),
    }


@app.post(
    "/v1/indices",
    response_model=IndicesResponse,
    tags=["crop"],
    dependencies=[Depends(require_api_key)],
)
def indices(area: FieldArea) -> IndicesResponse:
    """Full NDVI/EVI series for the marked area."""
    data = _run_series(area)
    return IndicesResponse(**data, generated_at=_now())


@app.post(
    "/v1/health-summary",
    response_model=HealthSummaryResponse,
    tags=["crop"],
    dependencies=[Depends(require_api_key)],
)
def health_summary(area: FieldArea) -> HealthSummaryResponse:
    """Latest reading, the change since roughly two weeks earlier, and a status."""
    data = _run_series(area)
    return HealthSummaryResponse(
        area_m2=data["area_m2"],
        summary=gee.summarise(data["readings"]),
        readings_used=len(data["readings"]),
        source=data["source"],
        generated_at=_now(),
    )
