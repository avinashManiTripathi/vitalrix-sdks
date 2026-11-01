# vitalrix (Python)

Send traces, metrics and logs from Python to [VitalRix](https://vitalrix.com) in one call. Uses the official
OpenTelemetry SDK and exports over OTLP/HTTP.

```bash
pip install vitalrix
# plus the instrumentations for what you use, for example:
pip install opentelemetry-instrumentation-flask opentelemetry-instrumentation-requests
```

`.env` (server side; the application's Connect tab in VitalRix creates the key):

```bash
VITALRIX_URL=https://api.vitalrix.com
VITALRIX_API_KEY=pk_...              # log.write, metric.write, trace.write
OTEL_SERVICE_NAME=shop-api           # the application's name in VitalRix
DEPLOYMENT_ENVIRONMENT=production
SERVICE_VERSION=1.4.2                # optional: marks deployments
```

```python
import logging
import vitalrix

vitalrix.init()                       # call once, before your app starts serving

logging.getLogger(__name__).info("order placed")   # goes to VitalRix with the request's trace id
```

`init()` turns on every installed `opentelemetry-instrumentation-*` package (Flask, Django, FastAPI, requests,
httpx, psycopg, SQLAlchemy, Celery, ...). Pass `instrument=False` to do it yourself, `logs=False` to keep logging
local, `metrics_enabled=False` to skip metrics. Without `VITALRIX_API_KEY` it sends nothing and logs a warning.

Check the key and URL:

```bash
vitalrix check
```

Requires Python 3.9+. Tests: `pip install -e ".[test]" && pytest`.
