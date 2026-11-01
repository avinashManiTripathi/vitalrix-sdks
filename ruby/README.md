# vitalrix (Ruby)

Send traces, metrics and logs from Ruby to [VitalRix](https://vitalrix.com) with the official OpenTelemetry SDK (OTLP/HTTP).

```ruby
# Gemfile
gem "vitalrix"
gem "opentelemetry-instrumentation-all"   # optional: Rails, Rack, Sinatra, Net::HTTP, pg, Redis, Sidekiq, Logger, ...
```

```bash
VITALRIX_URL=https://api.vitalrix.com
VITALRIX_API_KEY=pk_...              # log.write, metric.write, trace.write (the Connect tab in VitalRix creates it)
OTEL_SERVICE_NAME=shop-api           # the application's name in VitalRix
DEPLOYMENT_ENVIRONMENT=production
SERVICE_VERSION=1.4.2                # optional: marks deployments
```

```ruby
require "vitalrix"
Vitalrix.init                              # Rails: in config/initializers/vitalrix.rb
Vitalrix.log(:info, "order placed", order_id: id)
```

`init` sets the standard OpenTelemetry variables (values you set yourself win) and turns on every installed
instrumentation. Check the key: `bundle exec vitalrix check`. Ruby 3.1+.
