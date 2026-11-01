# VitalRix SDKs

One-call setup for sending traces, metrics and logs to [VitalRix](https://vitalrix.com). Every SDK configures the
official OpenTelemetry SDK of its language and exports over OTLP/HTTP, so anything OpenTelemetry instruments works.

| Language | Package | Folder |
|---|---|---|
| Node.js | [`@vitalrix/node`](https://www.npmjs.com/package/@vitalrix/node) | [platform repo](https://docs.vitalrix.com/apm/setup) |
| Python | `vitalrix` (PyPI) | [python](python) |
| Go | `github.com/avinashManiTripathi/vitalrix-sdks/go` | [go](go) |
| Java | `com.vitalrix:vitalrix` (Maven Central) | [java](java) |
| Ruby | `vitalrix` (RubyGems) | [ruby](ruby) |
| PHP | `vitalrix/vitalrix` (Packagist) | [php](php) |

Each reads the same variables: `VITALRIX_URL`, `VITALRIX_API_KEY`, `OTEL_SERVICE_NAME`, `DEPLOYMENT_ENVIRONMENT`,
`SERVICE_VERSION`, `TRACE_SAMPLE_RATE`. Keep the API key on the server; browsers use the real-user script instead.

Any other language: point its OpenTelemetry SDK at `https://api.vitalrix.com/api/v1/{traces/otlp/v1/traces,metrics/otlp/v1/metrics,logs/otlp/v1/logs}`
with the header `x-api-key: <key>` (protobuf or JSON).

MIT licensed.
