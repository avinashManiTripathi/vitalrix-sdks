"""init(): official OpenTelemetry SDK wired to VitalRix (traces, metrics, logs over OTLP/HTTP protobuf)."""
from __future__ import annotations

import atexit
import json
import logging
import urllib.request
from importlib.metadata import entry_points
from typing import List, Optional

from opentelemetry import metrics, trace
from opentelemetry._logs import set_logger_provider
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased

from . import config as _config

_log = logging.getLogger("vitalrix")
_state: dict = {}


def init(
    service: Optional[str] = None,
    environment: Optional[str] = None,
    version: Optional[str] = None,
    instrument: bool = True,
    logs: bool = True,
    metrics_enabled: bool = True,
    log_level: int = logging.INFO,
) -> bool:
    """Starts sending to VitalRix. Returns False (and sends nothing) without VITALRIX_API_KEY. Safe to call twice."""
    if _state:
        return True
    c = _config.load(service=service, environment=environment, version=version)
    if not c.api_key:
        _log.warning("VITALRIX_API_KEY is not set: nothing is sent")
        return False

    resource = Resource.create({"service.name": c.service, "service.version": c.version, "deployment.environment.name": c.environment})

    # Parent-based: a request arriving with a sampled traceparent (browser, another service) stays sampled.
    tracer_provider = TracerProvider(resource=resource, sampler=ParentBased(TraceIdRatioBased(c.sample_rate)))
    tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=c.endpoint("traces/otlp/v1/traces"), headers=c.headers)))
    trace.set_tracer_provider(tracer_provider)
    _state["traces"] = tracer_provider

    if metrics_enabled:
        reader = PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=c.endpoint("metrics/otlp/v1/metrics"), headers=c.headers), export_interval_millis=15_000)
        meter_provider = MeterProvider(resource=resource, metric_readers=[reader])
        metrics.set_meter_provider(meter_provider)
        _state["metrics"] = meter_provider

    if logs:
        # Standard `logging` records go to VitalRix with the active trace id.
        logger_provider = LoggerProvider(resource=resource)
        logger_provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter(endpoint=c.endpoint("logs/otlp/v1/logs"), headers=c.headers)))
        set_logger_provider(logger_provider)
        root = logging.getLogger()
        root.addHandler(LoggingHandler(level=log_level, logger_provider=logger_provider))
        if root.level == logging.NOTSET or root.level > log_level:
            root.setLevel(log_level)
        _state["logs"] = logger_provider

    if instrument:
        _state["instrumented"] = instrument_installed()

    atexit.register(shutdown)
    return True


def instrument_installed() -> List[str]:
    """Turns on every installed OpenTelemetry instrumentation package (Flask, Django, FastAPI, requests, psycopg, ...)."""
    done: List[str] = []
    for ep in entry_points(group="opentelemetry_instrumentor"):
        try:
            instrumentor = ep.load()()
            if not getattr(instrumentor, "is_instrumented_by_opentelemetry", False):
                instrumentor.instrument()
            done.append(ep.name)
        except Exception as err:  # a missing optional dependency must not stop the application
            _log.debug("instrumentation %s skipped: %s", ep.name, err)
    return done


def shutdown() -> None:
    """Flushes what is buffered (also runs at exit)."""
    for key in ("traces", "metrics", "logs"):
        provider = _state.get(key)
        if provider is not None:
            try:
                provider.shutdown()
            except Exception:
                pass


def check(timeout: float = 10.0) -> str:
    """Sends one test log line; raises with the server's answer when the key or URL does not work."""
    c = _config.load()
    if not c.api_key:
        raise RuntimeError("VITALRIX_API_KEY is not set")
    body = json.dumps({"logs": [{"level": "info", "message": "vitalrix check: connection works", "service": c.service, "environment": c.environment}]}).encode()
    req = urllib.request.Request(c.endpoint("logs"), data=body, method="POST", headers={"content-type": "application/json", **c.headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout):
            pass
    except urllib.error.HTTPError as err:
        raise RuntimeError(f"{err.code} {err.read().decode(errors='replace')}") from err
    return f'ok: a test log from "{c.service}" ({c.environment}) reached {c.url}'
