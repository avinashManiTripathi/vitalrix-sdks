<?php

declare(strict_types=1);

namespace Vitalrix;

use OpenTelemetry\API\Common\Time\Clock;
use OpenTelemetry\API\Globals;
use OpenTelemetry\API\Logs\LogRecord;
use OpenTelemetry\API\Trace\Propagation\TraceContextPropagator;
use OpenTelemetry\Contrib\Otlp\LogsExporter;
use OpenTelemetry\Contrib\Otlp\MetricExporter;
use OpenTelemetry\Contrib\Otlp\OtlpHttpTransportFactory;
use OpenTelemetry\Contrib\Otlp\SpanExporter;
use OpenTelemetry\SDK\Common\Attribute\Attributes;
use OpenTelemetry\SDK\Logs\LoggerProvider;
use OpenTelemetry\SDK\Logs\Processor\BatchLogRecordProcessor;
use OpenTelemetry\SDK\Metrics\MeterProvider;
use OpenTelemetry\SDK\Metrics\MetricReader\ExportingReader;
use OpenTelemetry\SDK\Resource\ResourceInfo;
use OpenTelemetry\SDK\Resource\ResourceInfoFactory;
use OpenTelemetry\SDK\Sdk;
use OpenTelemetry\SDK\Trace\Sampler\ParentBased;
use OpenTelemetry\SDK\Trace\Sampler\TraceIdRatioBasedSampler;
use OpenTelemetry\SDK\Trace\SpanProcessor\BatchSpanProcessor;
use OpenTelemetry\SDK\Trace\TracerProvider;

/**
 * VitalRix for PHP: the official OpenTelemetry SDK wired to VitalRix (traces, metrics and logs over OTLP/HTTP protobuf).
 *
 *     \Vitalrix\Vitalrix::init();      // reads VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, ...
 *     \Vitalrix\Vitalrix::log('info', 'order placed', ['orderId' => $id]);
 *
 * Data is flushed when the request or script ends.
 */
final class Vitalrix
{
    private const SEVERITY = ['debug' => 5, 'info' => 9, 'warn' => 13, 'warning' => 13, 'error' => 17, 'fatal' => 21];

    private static ?LoggerProvider $logs = null;
    private static ?TracerProvider $traces = null;
    private static ?MeterProvider $metrics = null;

    /**
     * Settings from VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, DEPLOYMENT_ENVIRONMENT, SERVICE_VERSION and TRACE_SAMPLE_RATE.
     *
     * @param array<string,string>|null $env
     * @return array{url:string,apiKey:string,service:string,environment:string,version:string,sampleRate:float}
     */
    public static function config(?array $env = null): array
    {
        $get = static function (string $k, string $d) use ($env): string {
            $v = $env === null ? (getenv($k) ?: ($_SERVER[$k] ?? '')) : ($env[$k] ?? '');
            return $v === '' || $v === false ? $d : (string) $v;
        };
        $rate = is_numeric($get('TRACE_SAMPLE_RATE', '1')) ? (float) $get('TRACE_SAMPLE_RATE', '1') : 1.0;
        return [
            'url' => rtrim($get('VITALRIX_URL', 'https://api.vitalrix.com'), '/'),
            'apiKey' => $get('VITALRIX_API_KEY', ''),
            'service' => $get('OTEL_SERVICE_NAME', 'unknown'),
            'environment' => $get('DEPLOYMENT_ENVIRONMENT', 'production'),
            'version' => $get('SERVICE_VERSION', '0.0.0'),
            'sampleRate' => max(0.0, min(1.0, $rate)),
        ];
    }

    /** Starts sending and registers the global OpenTelemetry. Returns false (and sends nothing) without VITALRIX_API_KEY. */
    public static function init(?array $config = null): bool
    {
        if (self::$traces !== null) {
            return true;
        }
        $c = $config ?? self::config();
        if ($c['apiKey'] === '') {
            error_log('[vitalrix] VITALRIX_API_KEY is not set: nothing is sent');
            return false;
        }
        $headers = ['x-api-key' => $c['apiKey']];
        $transport = static fn (string $path) => (new OtlpHttpTransportFactory())->create("{$c['url']}/api/v1/{$path}", 'application/x-protobuf', $headers);
        $resource = ResourceInfoFactory::defaultResource()->merge(ResourceInfo::create(Attributes::create([
            'service.name' => $c['service'],
            'service.version' => $c['version'],
            'deployment.environment.name' => $c['environment'],
        ])));

        self::$traces = TracerProvider::builder()
            ->setResource($resource)
            // Parent-based: a request arriving with a sampled traceparent (browser, another service) stays sampled.
            ->setSampler(new ParentBased(new TraceIdRatioBasedSampler($c['sampleRate'])))
            ->addSpanProcessor(BatchSpanProcessor::builder(new SpanExporter($transport('traces/otlp/v1/traces')))->build())
            ->build();
        self::$metrics = MeterProvider::builder()
            ->setResource($resource)
            ->addReader(new ExportingReader(new MetricExporter($transport('metrics/otlp/v1/metrics'))))
            ->build();
        self::$logs = LoggerProvider::builder()
            ->setResource($resource)
            ->addLogRecordProcessor(new BatchLogRecordProcessor(new LogsExporter($transport('logs/otlp/v1/logs')), Clock::getDefault()))
            ->build();

        Sdk::builder()
            ->setTracerProvider(self::$traces)
            ->setMeterProvider(self::$metrics)
            ->setLoggerProvider(self::$logs)
            ->setPropagator(TraceContextPropagator::getInstance())
            ->setAutoShutdown(true)
            ->buildAndRegisterGlobal();
        return true;
    }

    /** A log line with the current trace id. No-op before init(). */
    public static function log(string $level, string $message, array $attributes = []): void
    {
        if (self::$logs === null) {
            return;
        }
        $level = strtolower($level);
        $record = (new LogRecord($message))
            ->setSeverityNumber(self::SEVERITY[$level] ?? 9)
            ->setSeverityText(strtoupper($level))
            ->setAttributes($attributes);
        Globals::loggerProvider()->getLogger('vitalrix')->emit($record);
    }

    /** Flushes and stops (also runs at the end of the request or script). */
    public static function shutdown(): void
    {
        self::$traces?->shutdown();
        self::$metrics?->shutdown();
        self::$logs?->shutdown();
        self::$traces = self::$metrics = self::$logs = null;
    }

    /** Sends one test log line; throws with the server's answer when the key or URL does not work. */
    public static function check(?array $config = null): string
    {
        $c = $config ?? self::config();
        if ($c['apiKey'] === '') {
            throw new \RuntimeException('VITALRIX_API_KEY is not set');
        }
        $body = json_encode(['logs' => [['level' => 'info', 'message' => 'vitalrix check: connection works', 'service' => $c['service'], 'environment' => $c['environment']]]]);
        $res = (new \GuzzleHttp\Client(['timeout' => 10, 'http_errors' => false]))->post("{$c['url']}/api/v1/logs", [
            'headers' => ['content-type' => 'application/json', 'x-api-key' => $c['apiKey']],
            'body' => $body,
        ]);
        if ($res->getStatusCode() >= 300) {
            throw new \RuntimeException($res->getStatusCode() . ' ' . $res->getBody());
        }
        return sprintf('ok: a test log from "%s" (%s) reached %s', $c['service'], $c['environment'], $c['url']);
    }
}
