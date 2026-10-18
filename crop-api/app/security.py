"""Optional API-key access control.

The prototype in the setup document had "no access control: anyone who can
reach the port can call the service". This module closes that gap: when
``CROP_API_KEY`` is set, every ``/v1/*`` route requires a matching
``X-API-Key`` header. When it is unset the service still runs (for local
development) but logs a warning at startup.
"""

from __future__ import annotations

import secrets

from fastapi import Header, HTTPException, status

from . import config


async def require_api_key(x_api_key: str | None = Header(default=None, alias="X-API-Key")) -> None:
    """FastAPI dependency that enforces the shared API key when configured."""
    expected = config.settings.api_key
    if not expected:
        return  # development mode: no key configured
    if not x_api_key or not secrets.compare_digest(x_api_key, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="A valid X-API-Key header is required.",
        )
