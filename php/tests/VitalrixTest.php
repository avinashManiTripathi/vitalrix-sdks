<?php

declare(strict_types=1);

namespace Vitalrix\Tests;

use OpenTelemetry\API\Globals;
use PHPUnit\Framework\TestCase;
use Vitalrix\Vitalrix;

final class VitalrixTest extends TestCase
{
    public function testConfigDefaultsAndBounds(): void
    {
        $c = Vitalrix::config([]);
        self::assertSame(['https://api.vitalrix.com', 'unknown', 'production', 1.0], [$c['url'], $c['service'], $c['environment'], $c['sampleRate']]);
        self::assertSame('http://x', Vitalrix::config(['VITALRIX_URL' => 'http://x/'])['url']);
        self::assertSame(1.0, Vitalrix::config(['TRACE_SAMPLE_RATE' => '9'])['sampleRate']);
    }

    public function testExportsProtobufAndChecksWithJson(): void
    {
        $file = tempnam(sys_get_temp_dir(), 'vr');
        $port = random_int(20000, 40000);
        $proc = proc_open([PHP_BINARY, '-S', "127.0.0.1:$port", __DIR__ . '/recorder.php'], [1 => ['file', '/dev/null', 'w'], 2 => ['file', '/dev/null', 'w']], $pipes, null, ['RECORDER_FILE' => $file]);
        usleep(400_000);
        try {
            $c = Vitalrix::config(['VITALRIX_URL' => "http://127.0.0.1:$port", 'VITALRIX_API_KEY' => 'k_test', 'OTEL_SERVICE_NAME' => 'php-test']);
            self::assertTrue(Vitalrix::init($c));
            $span = Globals::tracerProvider()->getTracer('t')->spanBuilder('work')->startSpan();
            $scope = $span->activate();
            Vitalrix::log('warn', 'inside a span', ['orderId' => 'o_1']);
            Globals::meterProvider()->getMeter('t')->createCounter('orders')->add(1);
            $scope->detach();
            $span->end();
            Vitalrix::shutdown();
            self::assertStringStartsWith('ok:', Vitalrix::check($c));
            $got = [];
            foreach (file($file, FILE_IGNORE_NEW_LINES) as $line) {
                [$path, $type, $key] = json_decode($line, true);
                $got[$path] = "$type|$key";
            }
            foreach (['/api/v1/traces/otlp/v1/traces', '/api/v1/metrics/otlp/v1/metrics', '/api/v1/logs/otlp/v1/logs'] as $p) {
                self::assertSame('application/x-protobuf|k_test', $got[$p] ?? null, $p);
            }
            self::assertSame('application/json|k_test', $got['/api/v1/logs'] ?? null);
        } finally {
            proc_terminate($proc);
            @unlink($file);
        }
    }
}
