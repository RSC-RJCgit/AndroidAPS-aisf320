"""Connection configuration for a Nightscout site.

The access token is a secret. It is held only for the lifetime of the pull, never
written to disk by this module, and redacted from every ``repr``/log. The CLI reads it
from the environment, not from an argument, so it does not land in shell history.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class NightscoutConfig:
    base_url: str
    token: str | None = None          # Nightscout access token (read-only recommended)
    api_secret_sha1: str | None = None  # alternative: pre-hashed API-SECRET
    timeout_s: float = 30.0

    def __post_init__(self) -> None:
        self.base_url = self.base_url.rstrip("/")
        if not self.base_url.startswith(("http://", "https://")):
            self.base_url = "https://" + self.base_url

    # --- secret hygiene -------------------------------------------------------
    def __repr__(self) -> str:  # never expose the token
        return (
            f"NightscoutConfig(base_url={self.base_url!r}, "
            f"token={'***' if self.token else None}, "
            f"api_secret={'***' if self.api_secret_sha1 else None})"
        )

    def auth_params(self) -> dict[str, str]:
        return {"token": self.token} if self.token else {}

    def auth_headers(self) -> dict[str, str]:
        return {"api-secret": self.api_secret_sha1} if self.api_secret_sha1 else {}

    @classmethod
    def from_env(cls) -> "NightscoutConfig":
        url = os.environ.get("NS_URL")
        if not url:
            raise ValueError("NS_URL is not set")
        return cls(
            base_url=url,
            token=os.environ.get("NS_TOKEN"),
            api_secret_sha1=os.environ.get("NS_API_SECRET_SHA1"),
        )
