"""A tiny in-process TTL cache.

Sentinel-2 imagery refreshes roughly every five days, so a six-hour cache costs
nothing in freshness and saves a full Earth Engine round-trip on repeat taps
from the app. The cache is deliberately process-local and unbounded-by-design
at prototype scale; swap it for Redis when the service is horizontally scaled.
"""

from __future__ import annotations

import threading
import time
from typing import Any, Hashable


class TtlCache:
    """Thread-safe ``key -> value`` store with a per-entry time-to-live."""

    def __init__(self, ttl_seconds: int) -> None:
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._store: dict[Hashable, tuple[float, Any]] = {}

    def get(self, key: Hashable) -> Any | None:
        """Return the live value for ``key``, or ``None`` on a miss/expiry."""
        with self._lock:
            hit = self._store.get(key)
            if hit is None:
                return None
            stored_at, value = hit
            if time.time() - stored_at >= self._ttl:
                self._store.pop(key, None)
                return None
            return value

    def set(self, key: Hashable, value: Any) -> None:
        with self._lock:
            self._store[key] = (time.time(), value)

    def clear(self) -> None:
        with self._lock:
            self._store.clear()

    def __len__(self) -> int:  # pragma: no cover - trivial
        with self._lock:
            return len(self._store)
