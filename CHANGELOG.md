# Changelog

All notable changes to this project are recorded here.

## [0.1.0-rc1] - 2026-08-13

### Added

- Thin optional Java bootstrap with no runtime dependencies or class transformer.
- Property and environment bridge for the bounded Rust telemetry engine.
- HTTP route aggregates, exact HTTP 5xx counts, native Dubbo and Redis aggregates.
- Bounded slow/error trace queue, process RSS and thread gauges.
- Glowroot-compatible h2/protobuf export using the existing Rust runtime.
- Collector-down fail-open behavior, bounded reconnect, diagnostics, and Prometheus counters.
- Reproducible protocol, footprint, startup, and focused c256 performance gates.

### Release boundary

- This release candidate packages only the optional bootstrap JAR.
- It requires the coordinated Rust-Java REST native runtime with REST ABI 28.
- The published Rust-Java REST 4.3.0 ABI 26 runtime is not compatible.
- The embedded properties/environment path is the recommended strict-memory production mode.

[0.1.0-rc1]: https://github.com/esasmer-dou/java-rust-glowroot-agent/releases/tag/v0.1.0-rc1
