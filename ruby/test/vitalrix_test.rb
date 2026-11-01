# frozen_string_literal: true

require "minitest/autorun"
require "socket"
require "vitalrix"

# A tiny HTTP server that records path, content type and key of each request.
class Recorder
  attr_reader :got, :url

  def initialize
    @got = {}
    @server = TCPServer.new("127.0.0.1", 0)
    @url = "http://127.0.0.1:#{@server.addr[1]}"
    @thread = Thread.new { loop { handle(@server.accept) } }
  end

  def handle(sock)
    head = +""
    head << sock.gets until head.end_with?("\r\n\r\n")
    path = head[/\A\w+ (\S+)/, 1]
    h = head.lines.drop(1).to_h { |l| k, v = l.split(":", 2); [k.to_s.downcase.strip, v.to_s.strip] }
    sock.read(h["content-length"].to_i)
    @got[path] = "#{h['content-type']}|#{h['x-api-key']}"
    body = '{"success":true,"data":{}}'
    sock.write("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: #{body.bytesize}\r\nconnection: close\r\n\r\n#{body}")
    sock.close
  rescue StandardError => e
    warn "recorder: #{e.class}: #{e.message}" if ENV['DEBUG_RECORDER']
    sock&.close
  end

  def stop = @thread.kill
end

class VitalrixTest < Minitest::Test
  def test_config_defaults
    c = Vitalrix.config({})
    assert_equal ["https://api.vitalrix.com", "unknown", "production", 1.0], [c.url, c.service, c.environment, c.sample_rate]
    assert_equal "http://x", Vitalrix.config({ "VITALRIX_URL" => "http://x/" }).url
    assert_equal 1.0, Vitalrix.config({ "TRACE_SAMPLE_RATE" => "9" }).sample_rate
  end

  def test_exports_protobuf_and_checks_with_json
    rec = Recorder.new
    ENV.update("VITALRIX_URL" => rec.url, "VITALRIX_API_KEY" => "k_test", "OTEL_SERVICE_NAME" => "ruby-test")
    assert Vitalrix.init(instrument: false)
    OpenTelemetry.tracer_provider.tracer("t").in_span("work") do
      Vitalrix.log(:warn, "inside a span", order_id: "o_1")
      OpenTelemetry.meter_provider.meter("t").create_counter("orders").add(1)
    end
    Vitalrix.shutdown
    %w[/api/v1/traces/otlp/v1/traces /api/v1/logs/otlp/v1/logs].each do |p|
      assert_equal "application/x-protobuf|k_test", rec.got[p], p
    end
    assert Vitalrix.check.start_with?("ok:")
    assert_equal "application/json|k_test", rec.got["/api/v1/logs"]
  ensure
    rec&.stop
  end
end
