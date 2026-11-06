# frozen_string_literal: true

require "json"
require "socket"
require "net/http"
require "uri"
require_relative "vitalrix/version"

# VitalRix for Ruby: the official OpenTelemetry SDK wired to VitalRix (traces, metrics and logs over OTLP/HTTP).
#
#   require "vitalrix"
#   Vitalrix.init          # reads VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, ...
module Vitalrix
  Config = Struct.new(:url, :api_key, :service, :environment, :version, :sample_rate) do
    def endpoint(path) = "#{url}/api/v1/#{path}"
  end

  # Settings from VITALRIX_URL, VITALRIX_API_KEY, OTEL_SERVICE_NAME, DEPLOYMENT_ENVIRONMENT, SERVICE_VERSION, TRACE_SAMPLE_RATE.
  def self.config(env = ENV)
    pick = ->(k, d) { (v = env[k]).nil? || v.empty? ? d : v }
    rate = Float(pick.("TRACE_SAMPLE_RATE", "1"), exception: false) || 1.0
    Config.new(pick.("VITALRIX_URL", "https://api.vitalrix.com").sub(%r{/+\z}, ""), pick.("VITALRIX_API_KEY", ""),
               pick.("OTEL_SERVICE_NAME", "unknown"), pick.("DEPLOYMENT_ENVIRONMENT", "production"),
               pick.("SERVICE_VERSION", "0.0.0"), rate.clamp(0.0, 1.0))
  end

  # The standard OpenTelemetry variables that point the SDK at VitalRix. Values the application already set win.
  def self.otel_environment(c = config)
    {
      "OTEL_SERVICE_NAME" => c.service,
      # host.name: VitalRix counts APM hosts by it, the same way for every SDK.
      "OTEL_RESOURCE_ATTRIBUTES" => "deployment.environment.name=#{c.environment},service.version=#{c.version},host.name=#{Socket.gethostname}",
      "OTEL_EXPORTER_OTLP_PROTOCOL" => "http/protobuf",
      "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT" => c.endpoint("traces/otlp/v1/traces"),
      "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT" => c.endpoint("metrics/otlp/v1/metrics"),
      "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT" => c.endpoint("logs/otlp/v1/logs"),
      "OTEL_EXPORTER_OTLP_HEADERS" => "x-api-key=#{c.api_key}",
      "OTEL_TRACES_EXPORTER" => "otlp",
      "OTEL_METRICS_EXPORTER" => "otlp",
      "OTEL_LOGS_EXPORTER" => "otlp",
      # Parent-based: a request arriving with a sampled traceparent (browser, another service) stays sampled.
      "OTEL_TRACES_SAMPLER" => "parentbased_traceidratio",
      "OTEL_TRACES_SAMPLER_ARG" => c.sample_rate.to_s,
    }
  end

  # Starts sending. Returns false (and sends nothing) without VITALRIX_API_KEY. Turns on every installed
  # opentelemetry-instrumentation-* gem (Rails, Rack, Sinatra, Net::HTTP, pg, Redis, Sidekiq, Logger, ...).
  def self.init(instrument: true)
    return true if @started
    c = config
    if c.api_key.empty?
      warn "[vitalrix] VITALRIX_API_KEY is not set: nothing is sent"
      return false
    end
    otel_environment(c).each { |k, v| ENV[k] = v if ENV[k].nil? || ENV[k].empty? }
    require "opentelemetry/sdk"
    require "opentelemetry/exporter/otlp"
    require "opentelemetry-metrics-sdk"
    require "opentelemetry/exporter/otlp_metrics"
    require "opentelemetry-logs-sdk"
    require "opentelemetry/exporter/otlp_logs"
    OpenTelemetry::SDK.configure { |cfg| cfg.use_all if instrument }
    @started = true
    at_exit { shutdown }
    true
  end

  # A log line with the current trace id. No-op before init.
  def self.log(severity, message, attributes = {})
    return unless @started
    logger = OpenTelemetry.logger_provider.logger(name: "vitalrix", version: VERSION)
    logger.on_emit(severity_text: severity.to_s.upcase, severity_number: SEVERITY.fetch(severity.to_sym, 9), body: message.to_s,
                   attributes: attributes.transform_keys(&:to_s), context: OpenTelemetry::Context.current)
  end
  SEVERITY = { debug: 5, info: 9, warn: 13, error: 17, fatal: 21 }.freeze

  # Flushes and stops (also runs at exit).
  def self.shutdown
    return unless @started
    OpenTelemetry.tracer_provider.shutdown if OpenTelemetry.tracer_provider.respond_to?(:shutdown)
    OpenTelemetry.meter_provider.shutdown if OpenTelemetry.meter_provider.respond_to?(:shutdown)
    OpenTelemetry.logger_provider.shutdown if OpenTelemetry.logger_provider.respond_to?(:shutdown)
    @started = false
  end

  # Sends one test log line; raises with the server's answer when the key or URL does not work.
  def self.check(c = config)
    raise "VITALRIX_API_KEY is not set" if c.api_key.empty?
    uri = URI(c.endpoint("logs"))
    body = { logs: [{ level: "info", message: "vitalrix check: connection works", service: c.service, environment: c.environment }] }.to_json
    res = Net::HTTP.post(uri, body, "content-type" => "application/json", "x-api-key" => c.api_key)
    raise "#{res.code} #{res.body}" unless res.is_a?(Net::HTTPSuccess)
    %(ok: a test log from "#{c.service}" (#{c.environment}) reached #{c.url})
  end
end
