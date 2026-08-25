"""Nightscout REST client: chunked fetch with backoff.

Design points (see DESIGN.md §5.1):
  * pulls are chunked into windows (default 7 days) because long windows 502 on many
    self-hosted sites;
  * transient failures (network errors, 5xx) retry with exponential backoff; auth/4xx
    failures raise immediately (retrying a bad token is pointless);
  * the HTTP layer is injectable (``transport``) so the client is testable offline.

The transport contract is a callable::

    transport(method, url, params, headers, timeout) -> (status_code: int, body: list|dict)

The default transport uses ``requests`` and is imported lazily so this module can be
imported (and unit-tested) without the dependency present.
"""

from __future__ import annotations

import time
from typing import Any, Callable, Iterable
from urllib.parse import quote

from .config import NightscoutConfig
from .normalise import parse_iso_ms

Transport = Callable[[str, str, dict, dict, float], "tuple[int, Any]"]

# entries key on numeric `date` (ms); treatments/devicestatus key on ISO `created_at`.
DAY_MS = 86_400_000
DEFAULT_WINDOW_DAYS = 7
DEFAULT_MAX_RETRIES = 4
DEFAULT_BASE_DELAY_S = 2.0
PER_WINDOW_COUNT = 50_000  # generous; a 7-day 5-min window is ~2000 docs


class NightscoutError(RuntimeError):
    pass


class NightscoutAuthError(NightscoutError):
    pass


def _requests_transport(method: str, url: str, params: dict, headers: dict, timeout: float):
    import requests  # lazy: keep the module importable without the dep

    resp = requests.request(method, url, params=params, headers=headers, timeout=timeout)
    try:
        body = resp.json()
    except ValueError:
        body = None
    return resp.status_code, body


class NightscoutClient:
    def __init__(
        self,
        config: NightscoutConfig,
        transport: Transport | None = None,
        *,
        max_retries: int = DEFAULT_MAX_RETRIES,
        base_delay_s: float = DEFAULT_BASE_DELAY_S,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.config = config
        self._transport = transport or _requests_transport
        self.max_retries = max_retries
        self.base_delay_s = base_delay_s
        self._sleep = sleep

    # --- low-level ------------------------------------------------------------
    def _get(self, path: str, params: dict[str, Any]) -> Any:
        url = f"{self.config.base_url}/api/v1/{path}"
        params = {**params, **self.config.auth_params()}
        headers = self.config.auth_headers()
        attempt = 0
        while True:
            attempt += 1
            try:
                status, body = self._transport("GET", url, params, headers, self.config.timeout_s)
            except Exception as exc:  # network-level failure
                if attempt > self.max_retries:
                    raise NightscoutError(f"GET {path} failed after {attempt} attempts: {exc}") from exc
                self._sleep(self.base_delay_s * (2 ** (attempt - 1)))
                continue

            if status in (401, 403):
                raise NightscoutAuthError(f"GET {path}: {status} (check token/permissions)")
            if status == 200:
                return body if body is not None else []
            if status in (429, 500, 502, 503, 504) and attempt <= self.max_retries:
                self._sleep(self.base_delay_s * (2 ** (attempt - 1)))
                continue
            raise NightscoutError(f"GET {path}: unexpected status {status}")

    # --- windowing ------------------------------------------------------------
    @staticmethod
    def _windows(start_ms: int, end_ms: int, window_days: int) -> Iterable[tuple[int, int]]:
        step = window_days * DAY_MS
        lo = start_ms
        while lo < end_ms:
            hi = min(lo + step, end_ms)
            yield lo, hi
            lo = hi

    def _paged(self, path: str, start_ms: int, end_ms: int, time_field: str, iso: bool,
               window_days: int) -> list[dict]:
        seen: set = set()
        out: list[dict] = []
        for lo, hi in self._windows(start_ms, end_ms, window_days):
            if iso:
                lo_v = _iso(lo)
                hi_v = _iso(hi)
            else:
                lo_v, hi_v = lo, hi
            params = {
                f"find[{time_field}][$gte]": lo_v,
                f"find[{time_field}][$lte]": hi_v,
                "count": PER_WINDOW_COUNT,
            }
            docs = self._get(path, params)
            if not isinstance(docs, list):
                continue
            for d in docs:
                if not isinstance(d, dict):
                    continue
                key = d.get("_id") or (d.get(time_field), d.get("device"), d.get("sgv"))
                if key in seen:
                    continue
                seen.add(key)
                out.append(d)
        return out

    # --- public API -----------------------------------------------------------
    def fetch_entries(self, start_ms: int, end_ms: int, window_days: int = DEFAULT_WINDOW_DAYS) -> list[dict]:
        return self._paged("entries.json", start_ms, end_ms, "date", iso=False, window_days=window_days)

    def fetch_treatments(self, start_ms: int, end_ms: int, window_days: int = DEFAULT_WINDOW_DAYS) -> list[dict]:
        return self._paged("treatments.json", start_ms, end_ms, "created_at", iso=True, window_days=window_days)

    def fetch_devicestatus(self, start_ms: int, end_ms: int, window_days: int = DEFAULT_WINDOW_DAYS) -> list[dict]:
        return self._paged("devicestatus.json", start_ms, end_ms, "created_at", iso=True, window_days=window_days)

    def fetch_profiles(self) -> list[dict]:
        docs = self._get("profile.json", {})
        return [d for d in docs if isinstance(d, dict)] if isinstance(docs, list) else []


def _iso(ms: int) -> str:
    from datetime import datetime, timezone

    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


# Re-exported for callers that want to parse ISO independently.
__all__ = ["NightscoutClient", "NightscoutError", "NightscoutAuthError", "parse_iso_ms"]
