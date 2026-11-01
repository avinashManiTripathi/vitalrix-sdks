import json
import logging
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from opentelemetry import metrics, trace

import vitalrix
from vitalrix import config, core

received = []


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("content-length", 0)))
        received.append((self.path, self.headers.get("content-type"), self.headers.get("x-api-key"), body))
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"success":true,"data":{}}')

    def log_message(self, *a):
        pass


@pytest.fixture(scope="module")
def server():
    srv = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_port}"
    srv.shutdown()


def test_config_defaults():
    c = config.load({})
    assert (c.url, c.service, c.environment, c.sample_rate) == ("https://api.vitalrix.com", "unknown", "production", 1.0)
    assert config.load({"VITALRIX_URL": "http://x/", "TRACE_SAMPLE_RATE": "9"}).url == "http://x"
    assert config.load({"TRACE_SAMPLE_RATE": "9"}).sample_rate == 1.0


def test_init_without_key_sends_nothing(monkeypatch):
    monkeypatch.delenv("VITALRIX_API_KEY", raising=False)
    assert vitalrix.init() is False


def test_traces_and_logs_reach_the_otlp_endpoints_as_protobuf(server, monkeypatch):
    received.clear()
    monkeypatch.setenv("VITALRIX_URL", server)
    monkeypatch.setenv("VITALRIX_API_KEY", "k_test")
    monkeypatch.setenv("OTEL_SERVICE_NAME", "py-test")
    assert vitalrix.init(instrument=False) is True
    with trace.get_tracer("t").start_as_current_span("work"):
        logging.getLogger("app").warning("inside a span")
    metrics.get_meter("t").create_counter("orders").add(1)
    vitalrix.shutdown()
    paths = {p: (ct, key) for p, ct, key, _ in received}
    assert paths["/api/v1/traces/otlp/v1/traces"] == ("application/x-protobuf", "k_test")
    assert paths["/api/v1/logs/otlp/v1/logs"] == ("application/x-protobuf", "k_test")
    assert "/api/v1/metrics/otlp/v1/metrics" in paths


def test_check_posts_a_json_log(server, monkeypatch):
    received.clear()
    monkeypatch.setenv("VITALRIX_URL", server)
    monkeypatch.setenv("VITALRIX_API_KEY", "k_test")
    assert core.check().startswith("ok:")
    path, ctype, key, body = received[-1]
    assert (path, ctype, key) == ("/api/v1/logs", "application/json", "k_test")
    assert json.loads(body)["logs"][0]["message"] == "vitalrix check: connection works"
