package vitalrix

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"go.opentelemetry.io/otel"
)

func TestLoad(t *testing.T) {
	c := Load(func(string) string { return "" })
	if c.URL != "https://api.vitalrix.com" || c.Service != "unknown" || c.Environment != "production" || c.SampleRate != 1 {
		t.Fatalf("defaults: %+v", c)
	}
	env := map[string]string{"VITALRIX_URL": "http://x/", "TRACE_SAMPLE_RATE": "9"}
	if c := Load(func(k string) string { return env[k] }, WithService("svc")); c.URL != "http://x" || c.SampleRate != 1 || c.Service != "svc" {
		t.Fatalf("overrides: %+v", c)
	}
}

func TestInitWithoutKey(t *testing.T) {
	t.Setenv("VITALRIX_API_KEY", "")
	if _, err := Init(context.Background()); err != ErrNoAPIKey {
		t.Fatalf("want ErrNoAPIKey, got %v", err)
	}
}

func TestExportsAndCheck(t *testing.T) {
	var mu sync.Mutex
	got := map[string]string{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		mu.Lock()
		got[r.URL.Path] = r.Header.Get("Content-Type") + "|" + r.Header.Get("x-api-key")
		mu.Unlock()
		w.Header().Set("content-type", "application/json")
		w.Write([]byte(`{"success":true,"data":{}}`))
	}))
	defer srv.Close()
	t.Setenv("VITALRIX_URL", srv.URL)
	t.Setenv("VITALRIX_API_KEY", "k_test")
	t.Setenv("OTEL_SERVICE_NAME", "go-test")

	ctx := context.Background()
	shutdown, err := Init(ctx)
	if err != nil {
		t.Fatal(err)
	}
	ctx, span := otel.Tracer("t").Start(ctx, "work")
	Logger().InfoContext(ctx, "inside a span")
	counter, _ := otel.Meter("t").Int64Counter("orders")
	counter.Add(ctx, 1)
	span.End()
	if err := shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"/api/v1/traces/otlp/v1/traces", "/api/v1/metrics/otlp/v1/metrics", "/api/v1/logs/otlp/v1/logs"} {
		if got[p] != "application/x-protobuf|k_test" {
			t.Errorf("%s: got %q", p, got[p])
		}
	}
	msg, err := Check(context.Background())
	if err != nil || !strings.HasPrefix(msg, "ok:") || got["/api/v1/logs"] != "application/json|k_test" {
		t.Fatalf("check: %q %v %q", msg, err, got["/api/v1/logs"])
	}
}
