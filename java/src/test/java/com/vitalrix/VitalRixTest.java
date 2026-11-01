package com.vitalrix;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class VitalRixTest {
  @Test
  void configDefaultsAndBounds() {
    VitalRix.Config c = VitalRix.config(Map.of());
    assertEquals("https://api.vitalrix.com", c.url);
    assertEquals("production", c.environment);
    assertEquals(1.0, c.sampleRate);
    assertEquals("http://x", VitalRix.config(Map.of("VITALRIX_URL", "http://x/", "TRACE_SAMPLE_RATE", "9")).url);
    assertEquals(1.0, VitalRix.config(Map.of("TRACE_SAMPLE_RATE", "9")).sampleRate);
  }

  @Test
  void exportsProtobufToTheOtlpPathsAndChecksWithJson() throws Exception {
    Map<String, String> got = new ConcurrentHashMap<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      ex.getRequestBody().readAllBytes();
      got.put(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("content-type") + "|" + ex.getRequestHeaders().getFirst("x-api-key"));
      byte[] ok = "{\"success\":true,\"data\":{}}".getBytes();
      ex.getResponseHeaders().add("content-type", "application/json");
      ex.sendResponseHeaders(200, ok.length);
      ex.getResponseBody().write(ok);
      ex.close();
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      VitalRix.Config c = VitalRix.config(Map.of("VITALRIX_URL", url, "VITALRIX_API_KEY", "k_test", "OTEL_SERVICE_NAME", "java-test"));
      var otel = VitalRix.init(c);
      Span span = otel.getTracer("t").spanBuilder("work").startSpan();
      try (Scope ignored = span.makeCurrent()) {
        VitalRix.log(Severity.WARN, "inside a span", Map.of("orderId", "o_1"));
        otel.getMeter("t").counterBuilder("orders").build().add(1);
      } finally {
        span.end();
      }
      VitalRix.shutdown();
      for (String p : new String[] {"/api/v1/traces/otlp/v1/traces", "/api/v1/metrics/otlp/v1/metrics", "/api/v1/logs/otlp/v1/logs"}) {
        assertEquals("application/x-protobuf|k_test", got.get(p), p);
      }
      assertTrue(VitalRix.check(c).startsWith("ok:"));
      assertEquals("application/json|k_test", got.get("/api/v1/logs"));
    } finally {
      server.stop(0);
    }
  }
}
