"""Test configuration.

Keeps the suite hermetic: no Earth Engine, no API key, no network. The service's
synthetic series makes the HTTP tests fully deterministic in shape (exact
values are seed-derived and covered by the geometry/summary unit tests).
"""

from __future__ import annotations

import os

os.environ["GEE_ENABLED"] = "false"
os.environ.pop("CROP_API_KEY", None)
os.environ.setdefault("CORS_ORIGINS", "*")

from app import config  # noqa: E402  (must follow the env setup)

config.reload()
