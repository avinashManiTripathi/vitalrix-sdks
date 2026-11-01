package com.vitalrix;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * VitalRix for Java: the official OpenTelemetry SDK wired to VitalRix (traces, metrics and logs over OTLP/HTTP protobuf).
 *
 * <pre>
 * OpenTelemetry otel = VitalRix.init();          // reads VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, ...
 * VitalRix.log(Severity.INFO, "order placed", Map.of("orderId", id));
 * </pre>
 *
 * For automatic instrumentation of Spring, JDBC, HTTP clients and more, run the OpenTelemetry Java agent with the
 * environment from {@link #agentEnvironment()} instead.
 */
public final class VitalRix {
  private VitalRix() {}

  /** Settings from the environment (VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, DEPLOYMENT_ENVIRONMENT, SERVICE_VERSION, TRACE_SAMPLE_RATE). */
  public static final class Config {
    public final String url, apiKey, service, environment, version;
    public final double sampleRate;

    Config(Function<String, String> env) {
      this.url = or(env.apply("VITALRIX_URL"), "https://api.vitalrix.com").replaceAll("/+$", "");
      this.apiKey = or(env.apply("VITALRIX_API_KEY"), "");
      this.service = or(env.apply("OTEL_SERVICE_NAME"), "unknown");
      this.environment = or(env.apply("DEPLOYMENT_ENVIRONMENT"), "production");
      this.version = or(env.apply("SERVICE_VERSION"), "0.0.0");
      double rate;
      try { rate = Double.parseDouble(or(env.apply("TRACE_SAMPLE_RATE"), "1")); } catch (NumberFormatException e) { rate = 1; }
      this.sampleRate = Math.max(0, Math.min(1, rate));
    }

    String endpoint(String path) { return url + "/api/v1/" + path; }

    private static String or(String v, String d) { return v == null || v.isEmpty() ? d : v; }
  }

  public static Config config() { return new Config(System::getenv); }

  static Config config(Map<String, String> env) { return new Config(env::get); }

  private static volatile OpenTelemetrySdk sdk;
  private static volatile Logger logger;

  /** Sets up and registers the global OpenTelemetry. Without VITALRIX_API_KEY it returns a no-op and sends nothing. */
  public static synchronized OpenTelemetry init() { return init(config()); }

  static synchronized OpenTelemetry init(Config c) {
    if (sdk != null) return sdk;
    if (c.apiKey.isEmpty()) {
      System.err.println("[vitalrix] VITALRIX_API_KEY is not set: nothing is sent");
      return OpenTelemetry.noop();
    }
    Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
        AttributeKey.stringKey("service.name"), c.service,
        AttributeKey.stringKey("service.version"), c.version,
        AttributeKey.stringKey("deployment.environment.name"), c.environment)));
    SdkTracerProvider tracer = SdkTracerProvider.builder().setResource(resource)
        // Parent-based: a request arriving with a sampled traceparent (browser, another service) stays sampled.
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(c.sampleRate)))
        .addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
            .setEndpoint(c.endpoint("traces/otlp/v1/traces")).addHeader("x-api-key", c.apiKey).build()).build())
        .build();
    SdkMeterProvider meter = SdkMeterProvider.builder().setResource(resource)
        .registerMetricReader(PeriodicMetricReader.builder(OtlpHttpMetricExporter.builder()
            .setEndpoint(c.endpoint("metrics/otlp/v1/metrics")).addHeader("x-api-key", c.apiKey).build()).setInterval(Duration.ofSeconds(15)).build())
        .build();
    SdkLoggerProvider logs = SdkLoggerProvider.builder().setResource(resource)
        .addLogRecordProcessor(BatchLogRecordProcessor.builder(OtlpHttpLogRecordExporter.builder()
            .setEndpoint(c.endpoint("logs/otlp/v1/logs")).addHeader("x-api-key", c.apiKey).build()).build())
        .build();
    sdk = OpenTelemetrySdk.builder().setTracerProvider(tracer).setMeterProvider(meter).setLoggerProvider(logs)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .buildAndRegisterGlobal();
    logger = sdk.getLogsBridge().get(c.service);
    Runtime.getRuntime().addShutdownHook(new Thread(VitalRix::shutdown, "vitalrix-shutdown"));
    return sdk;
  }

  /** A log line with the current trace id (call inside a span to link it). No-op before {@link #init()}. */
  public static void log(Severity severity, String message, Map<String, String> attributes) {
    Logger l = logger;
    if (l == null) return;
    var b = l.logRecordBuilder().setSeverity(severity).setSeverityText(severity.name()).setBody(message).setContext(Context.current());
    if (attributes != null) attributes.forEach((k, v) -> b.setAttribute(AttributeKey.stringKey(k), v));
    b.emit();
  }

  /** Flushes and stops (also runs at JVM exit). */
  public static synchronized void shutdown() {
    if (sdk != null) {
      sdk.shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
      sdk = null;
      logger = null;
    }
  }

  /** Environment variables for the OpenTelemetry Java agent (automatic instrumentation) that send to VitalRix. */
  public static Map<String, String> agentEnvironment() {
    Config c = config();
    return Map.of(
        "OTEL_SERVICE_NAME", c.service,
        "OTEL_RESOURCE_ATTRIBUTES", "deployment.environment.name=" + c.environment + ",service.version=" + c.version,
        "OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf",
        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", c.endpoint("traces/otlp/v1/traces"),
        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", c.endpoint("metrics/otlp/v1/metrics"),
        "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", c.endpoint("logs/otlp/v1/logs"),
        "OTEL_EXPORTER_OTLP_HEADERS", "x-api-key=" + c.apiKey);
  }

  /** Sends one test log line; throws with the server's answer when the key or URL does not work. */
  public static String check() throws Exception { return check(config()); }

  static String check(Config c) throws Exception {
    if (c.apiKey.isEmpty()) throw new IllegalStateException("VITALRIX_API_KEY is not set");
    String body = "{\"logs\":[{\"level\":\"info\",\"message\":\"vitalrix check: connection works\",\"service\":\"" + json(c.service) + "\",\"environment\":\"" + json(c.environment) + "\"}]}";
    HttpResponse<String> res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(c.endpoint("logs"))).timeout(Duration.ofSeconds(10))
            .header("content-type", "application/json").header("x-api-key", c.apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() >= 300) throw new IllegalStateException(res.statusCode() + " " + res.body());
    return "ok: a test log from \"" + c.service + "\" (" + c.environment + ") reached " + c.url;
  }

  private static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

  /** {@code java -cp vitalrix.jar com.vitalrix.VitalRix check} */
  public static void main(String[] args) throws Exception {
    if (args.length == 0 || !args[0].equals("check")) {
      System.err.println("usage: vitalrix check");
      System.exit(2);
    }
    try {
      System.out.println(check());
    } catch (Exception e) {
      System.err.println("vitalrix: " + e.getMessage());
      System.exit(1);
    }
  }
}
