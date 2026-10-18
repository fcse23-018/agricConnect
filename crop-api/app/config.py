"""Environment-driven settings.

Deliberately dependency-free (no pydantic-settings): the surface is tiny and a
plain dataclass keeps the service importable in the narrowest environments.
Values are read once at import; tests override them with :func:`reload`.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(raw: str | None, default: bool) -> bool:
    if raw is None or raw == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(raw: str | None, default: int) -> int:
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _origins(raw: str | None) -> list[str]:
    if raw is None or raw.strip() == "" or raw.strip() == "*":
        return ["*"]
    return [part.strip() for part in raw.split(",") if part.strip()]


@dataclass(frozen=True)
class Settings:
    """Resolved service configuration."""

    # Earth Engine
    gee_enabled: bool
    gee_project: str
    cache_ttl_seconds: int

    # Access control. Empty string means "no key configured".
    api_key: str

    # HTTP
    cors_origins: list[str]

    @property
    def auth_enabled(self) -> bool:
        """True when callers must present ``X-API-Key``."""
        return bool(self.api_key)


def load() -> Settings:
    """Read settings from the process environment (and a local ``.env``)."""
    _load_dotenv()
    return Settings(
        gee_enabled=_bool(os.environ.get("GEE_ENABLED"), False),
        gee_project=os.environ.get("GEE_PROJECT", "agriconnect-crop-monitoring"),
        cache_ttl_seconds=_int(os.environ.get("CACHE_TTL_SECONDS"), 6 * 60 * 60),
        api_key=(os.environ.get("CROP_API_KEY") or "").strip(),
        cors_origins=_origins(os.environ.get("CORS_ORIGINS")),
    )


def reload() -> Settings:
    """Re-read settings. Used after tests mutate the environment."""
    global settings
    settings = load()
    return settings


def _load_dotenv() -> None:
    """Minimal ``.env`` loader — no python-dotenv dependency.

    Only ``KEY=VALUE`` lines are honoured, existing environment variables win,
    and anything malformed is ignored rather than crashing the service.
    """
    path = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")
    if not os.path.isfile(path):
        return
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                key = key.strip()
                value = value.strip().strip('"').strip("'")
                if key and key not in os.environ:
                    os.environ[key] = value
    except OSError:
        return


# Module-level singleton imported by the rest of the app.
settings: Settings = load()
