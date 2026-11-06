// Package vitalrix sends traces, metrics and logs from Go to VitalRix in one call, using the official OpenTelemetry
// SDK over OTLP/HTTP.
//
//	shutdown, err := vitalrix.Init(ctx)        // reads VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, ...
//	defer shutdown(context.Background())
//	http.ListenAndServe(":8080", vitalrix.Handler(mux, "api"))
//	vitalrix.Logger().InfoContext(ctx, "order placed", "orderId", id)
package vitalrix

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"go.opentelemetry.io/contrib/bridges/otelslog"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/log/global"
	"go.opentelemetry.io/otel/propagation"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// Config comes from the environment; Option overrides it.
type Config struct {
	URL, APIKey, Service, Environment, Version string
	SampleRate                                 float64
	Metrics, Logs                              bool
}

// Option changes the configuration read from the environment.
type Option func(*Config)

func WithService(name string) Option      { return func(c *Config) { c.Service = name } }
func WithEnvironment(env string) Option   { return func(c *Config) { c.Environment = env } }
func WithVersion(v string) Option         { return func(c *Config) { c.Version = v } }
func WithoutMetrics() Option              { return func(c *Config) { c.Metrics = false } }
func WithoutLogs() Option                 { return func(c *Config) { c.Logs = false } }

// Load reads VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, DEPLOYMENT_ENVIRONMENT, SERVICE_VERSION and TRACE_SAMPLE_RATE.
func Load(getenv func(string) string, opts ...Option) Config {
	or := func(v, d string) string {
		if v == "" {
			return d
		}
		return v
	}
	rate, err := strconv.ParseFloat(getenv("TRACE_SAMPLE_RATE"), 64)
	if err != nil || rate > 1 {
		rate = 1
	}
	if rate < 0 {
		rate = 0
	}
	c := Config{
		URL:         strings.TrimRight(or(getenv("VITALRIX_URL"), "https://api.vitalrix.com"), "/"),
		APIKey:      getenv("VITALRIX_API_KEY"),
		Service:     or(getenv("OTEL_SERVICE_NAME"), "unknown"),
		Environment: or(getenv("DEPLOYMENT_ENVIRONMENT"), "production"),
		Version:     or(getenv("SERVICE_VERSION"), "0.0.0"),
		SampleRate:  rate,
		Metrics:     true,
		Logs:        true,
	}
	for _, o := range opts {
		o(&c)
	}
	return c
}

func (c Config) endpoint(path string) string { return c.URL + "/api/v1/" + path }

// ErrNoAPIKey is returned when VITALRIX_API_KEY is empty; nothing is set up then.
var ErrNoAPIKey = errors.New("vitalrix: VITALRIX_API_KEY is not set")

var logger = slog.Default()

// Init sets the global tracer, meter and logger providers. Call the returned function on shutdown to flush.
func Init(ctx context.Context, opts ...Option) (func(context.Context) error, error) {
	c := Load(os.Getenv, opts...)
	if c.APIKey == "" {
		return func(context.Context) error { return nil }, ErrNoAPIKey
	}
	headers := map[string]string{"x-api-key": c.APIKey}
	// host.name: VitalRix counts APM hosts by it, the same way for every SDK.
	host, _ := os.Hostname()
	res, err := resource.Merge(resource.Default(), resource.NewSchemaless(
		attribute.String("host.name", host),
		attribute.String("service.name", c.Service),
		attribute.String("service.version", c.Version),
		attribute.String("deployment.environment.name", c.Environment),
	))
	if err != nil {
		return nil, err
	}

	var shutdowns []func(context.Context) error
	traceExp, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(c.endpoint("traces/otlp/v1/traces")), otlptracehttp.WithHeaders(headers))
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(traceExp),
		sdktrace.WithResource(res),
		// Parent-based: a request arriving with a sampled traceparent (browser, another service) stays sampled.
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(c.SampleRate))),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	shutdowns = append(shutdowns, tp.Shutdown)

	if c.Metrics {
		metricExp, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithEndpointURL(c.endpoint("metrics/otlp/v1/metrics")), otlpmetrichttp.WithHeaders(headers))
		if err != nil {
			return nil, err
		}
		mp := sdkmetric.NewMeterProvider(sdkmetric.WithResource(res), sdkmetric.WithReader(sdkmetric.NewPeriodicReader(metricExp, sdkmetric.WithInterval(15*time.Second))))
		otel.SetMeterProvider(mp)
		shutdowns = append(shutdowns, mp.Shutdown)
	}
	if c.Logs {
		logExp, err := otlploghttp.New(ctx, otlploghttp.WithEndpointURL(c.endpoint("logs/otlp/v1/logs")), otlploghttp.WithHeaders(headers))
		if err != nil {
			return nil, err
		}
		lp := sdklog.NewLoggerProvider(sdklog.WithResource(res), sdklog.WithProcessor(sdklog.NewBatchProcessor(logExp)))
		global.SetLoggerProvider(lp)
		logger = otelslog.NewLogger(c.Service, otelslog.WithLoggerProvider(lp))
		shutdowns = append(shutdowns, lp.Shutdown)
	}

	return func(ctx context.Context) error {
		var errs []error
		for _, s := range shutdowns {
			errs = append(errs, s(ctx))
		}
		return errors.Join(errs...)
	}, nil
}

// Logger is an slog.Logger whose records go to VitalRix with the trace id of the context passed to it
// (use the *Context methods). Before Init, or without logs, it is slog.Default().
func Logger() *slog.Logger { return logger }

// Handler wraps an http.Handler so each request is a span (continuing an incoming traceparent).
func Handler(h http.Handler, operation string) http.Handler { return otelhttp.NewHandler(h, operation) }

// Transport wraps an http.RoundTripper so outgoing requests are spans and carry the trace context.
func Transport(base http.RoundTripper) http.RoundTripper { return otelhttp.NewTransport(base) }

// Check sends one test log line and reports whether the key and URL work.
func Check(ctx context.Context) (string, error) {
	c := Load(os.Getenv)
	if c.APIKey == "" {
		return "", ErrNoAPIKey
	}
	body, _ := json.Marshal(map[string]any{"logs": []map[string]string{{"level": "info", "message": "vitalrix check: connection works", "service": c.Service, "environment": c.Environment}}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint("logs"), bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("content-type", "application/json")
	req.Header.Set("x-api-key", c.APIKey)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 2048))
		return "", fmt.Errorf("vitalrix: %d %s", res.StatusCode, msg)
	}
	return fmt.Sprintf("ok: a test log from %q (%s) reached %s", c.Service, c.Environment, c.URL), nil
}
