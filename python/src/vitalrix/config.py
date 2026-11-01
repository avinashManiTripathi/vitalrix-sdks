"""Settings from environment variables, shared by init(), check() and the CLI.

VITALRIX_URL            gateway base URL (default https://api.vitalrix.com)
VITALRIX_API_KEY        key with log.write, metric.write and trace.write (server side only)
OTEL_SERVICE_NAME       the application's name in VitalRix, or one of its service names
DEPLOYMENT_ENVIRONMENT  production | staging | development (default production)
SERVICE_VERSION         release / git sha; the first time a version is seen marks a deployment
TRACE_SAMPLE_RATE       0..1 for new traces (default 1)
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Mapping, Optional


@dataclass(frozen=True)
class Config:
    url: str
    api_key: str
    service: str
    environment: str
    version: str
    sample_rate: float

    def endpoint(self, path: str) -> str:
        return f"{self.url}/api/v1/{path.lstrip('/')}"

    @property
    def headers(self) -> dict:
        return {"x-api-key": self.api_key}


def load(
    env: Optional[Mapping[str, str]] = None,
    service: Optional[str] = None,
    environment: Optional[str] = None,
    version: Optional[str] = None,
) -> Config:
    e = os.environ if env is None else env
    try:
        rate = float(e.get("TRACE_SAMPLE_RATE", "1"))
    except ValueError:
        rate = 1.0
    return Config(
        url=(e.get("VITALRIX_URL") or "https://api.vitalrix.com").rstrip("/"),
        api_key=e.get("VITALRIX_API_KEY", ""),
        service=service or e.get("OTEL_SERVICE_NAME") or e.get("VITALRIX_SERVICE") or "unknown",
        environment=environment or e.get("DEPLOYMENT_ENVIRONMENT") or "production",
        version=version or e.get("SERVICE_VERSION") or "0.0.0",
        sample_rate=min(1.0, max(0.0, rate)),
    )
