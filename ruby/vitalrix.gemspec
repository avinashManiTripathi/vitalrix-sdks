# frozen_string_literal: true

require_relative "lib/vitalrix/version"

Gem::Specification.new do |s|
  s.name = "vitalrix"
  s.version = Vitalrix::VERSION
  s.summary = "Send traces, metrics and logs from Ruby to VitalRix in one call (OpenTelemetry under the hood)."
  s.description = "Configures the official OpenTelemetry Ruby SDK to export traces, metrics and logs to VitalRix over OTLP/HTTP, turns on installed instrumentations, and adds a `vitalrix check` command."
  s.authors = ["VitalRix"]
  s.homepage = "https://vitalrix.com"
  s.license = "MIT"
  s.required_ruby_version = ">= 3.1"
  s.metadata = { "source_code_uri" => "https://github.com/avinashManiTripathi/vitalrix-sdks", "documentation_uri" => "https://docs.vitalrix.com/apm/setup" }
  s.files = Dir["lib/**/*.rb", "exe/*", "README.md"]
  s.bindir = "exe"
  s.executables = ["vitalrix"]
  s.add_dependency "opentelemetry-sdk", "~> 1.5"
  s.add_dependency "opentelemetry-exporter-otlp", "~> 0.29"
  s.add_dependency "opentelemetry-logs-sdk", ">= 0.1"
  s.add_dependency "opentelemetry-exporter-otlp-logs", ">= 0.1"
  s.add_dependency "opentelemetry-metrics-sdk", ">= 0.2"
  s.add_dependency "opentelemetry-exporter-otlp-metrics", ">= 0.2"
end
