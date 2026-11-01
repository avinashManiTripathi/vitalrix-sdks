# vitalrix (Java)

Send traces, metrics and logs from Java to [VitalRix](https://vitalrix.com) with the official OpenTelemetry SDK (OTLP/HTTP).

```xml
<dependency>
  <groupId>com.vitalrix</groupId>
  <artifactId>vitalrix</artifactId>
  <version>0.1.0</version>
</dependency>
```

```bash
VITALRIX_URL=https://api.vitalrix.com
VITALRIX_API_KEY=pk_...              # log.write, metric.write, trace.write (the Connect tab in VitalRix creates it)
OTEL_SERVICE_NAME=shop-api           # the application's name in VitalRix
DEPLOYMENT_ENVIRONMENT=production
SERVICE_VERSION=1.4.2                # optional: marks deployments
```

```java
OpenTelemetry otel = VitalRix.init();      // without VITALRIX_API_KEY: a no-op, nothing is sent
VitalRix.log(Severity.INFO, "order placed", Map.of("orderId", id));
```

For automatic instrumentation (Spring, JDBC, HTTP clients, Kafka, ...) run the
[OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/) with the variables from
`VitalRix.agentEnvironment()`. Check the key: `java -cp vitalrix.jar com.vitalrix.VitalRix check`. Java 11+.
