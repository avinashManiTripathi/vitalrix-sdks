# vitalrix (Go)

Send traces, metrics and logs from Go to [VitalRix](https://vitalrix.com) with the official OpenTelemetry SDK (OTLP/HTTP).

```bash
go get github.com/avinashManiTripathi/vitalrix-sdks/go
```

```bash
VITALRIX_URL=https://api.vitalrix.com
VITALRIX_API_KEY=pk_...              # log.write, metric.write, trace.write (the Connect tab in VitalRix creates it)
OTEL_SERVICE_NAME=shop-api           # the application's name in VitalRix
DEPLOYMENT_ENVIRONMENT=production
SERVICE_VERSION=1.4.2                # optional: marks deployments
```

```go
shutdown, err := vitalrix.Init(ctx)          // without VITALRIX_API_KEY: ErrNoAPIKey, nothing is sent
if err != nil { log.Println(err) }
defer shutdown(context.Background())

http.ListenAndServe(":8080", vitalrix.Handler(mux, "api"))              // a span per request
client := &http.Client{Transport: vitalrix.Transport(http.DefaultTransport)} // outgoing calls carry the trace
vitalrix.Logger().InfoContext(r.Context(), "order placed", "orderId", id)   // slog, linked to the trace
```

Options: `WithService`, `WithEnvironment`, `WithVersion`, `WithoutMetrics`, `WithoutLogs`.
Check the key: `go run github.com/avinashManiTripathi/vitalrix-sdks/go/cmd/vitalrix check`. Go 1.22+.
