# vitalrix/vitalrix (PHP)

Send traces, metrics and logs from PHP to [VitalRix](https://vitalrix.com) with the official OpenTelemetry SDK (OTLP/HTTP).

```bash
composer require vitalrix/vitalrix
```

```bash
VITALRIX_URL=https://api.vitalrix.com
VITALRIX_API_KEY=pk_...              # log.write, metric.write, trace.write (the Connect tab in VitalRix creates it)
OTEL_SERVICE_NAME=shop-api           # the application's name in VitalRix
DEPLOYMENT_ENVIRONMENT=production
SERVICE_VERSION=1.4.2                # optional: marks deployments
```

```php
\Vitalrix\Vitalrix::init();               // early, e.g. in public/index.php or a service provider
\Vitalrix\Vitalrix::log('info', 'order placed', ['orderId' => $id]);
```

For automatic instrumentation (Laravel, Symfony, PDO, Guzzle, ...) add the matching `open-telemetry/opentelemetry-auto-*`
package and the `opentelemetry` PHP extension. Data is flushed when the request ends. Check the key: `vendor/bin/vitalrix check`. PHP 8.1+.
