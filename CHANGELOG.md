# Changelog

All notable changes to this project are recorded here.

## [0.2.1] - 2026-08-13

### Production Gate

- Added a full Rust-Java REST c64/c256 matrix with six paired runs for small, raw, and heavy JSON.
- Pinned the embedded path to `rust-java-rest:4.4.1` and native ABI `28`.
- Repacked the standalone Windows/Linux native exporter from clean `rust-spring v4.4.2` CI
  artifacts with full source-revision provenance.
- Made REST protocol, collector-down fail-open, and optional bootstrap checks release-enforced.
- Required both Spring Boot and Rust-Java REST evidence from the exact tag commit before publish.
- Added REST performance, steady-memory, and protocol summaries to stable release assets.

### Release Hygiene

- Moved active documentation and Maven examples to stable `0.2.1`.
- Marked the superseded `0.1.0-rc1` documentation as historical evidence.
- Removed the pre-release version label from the internal, unpublished mock collector.

## [0.2.0] - 2026-08-13

### Added

- Opt-in Spring Boot Servlet MVC starter with one bounded, low-allocation Servlet filter.
- Standalone Windows x64 and Linux glibc x64 Rust exporter binaries with clean SHA-256 provenance.
- Exact mapped and unhandled HTTP `5xx` accounting, normalized route aggregation, and async completion support.
- Linux post-build CPU role selection, quiet-host preflight, and per-cell SMT sibling/steal validation.
- Release workflow enforcement requiring the exact tag commit to pass the production matrix.

### Changed

- Standalone exporter now reuses one bounded h2 collector connection instead of reconnecting for each interval.
- Unsampled Spring requests use a specialized path without general observation/finally state.
- `reactor.glowroot.spring.order` is the canonical filter-order property; former order names remain aliases.
- The production matrix uses six paired runs for small JSON, raw/precomputed JSON, and dynamic heavy JSON at c64 and c256.

### Compatibility

- Java business code and Rust-Java REST handler APIs are unchanged.
- Spring WebFlux and full Java-method/JDBC instrumentation remain outside this bounded agent.
- Rust-Java REST uses the embedded native runtime; Spring Boot uses the standalone native runtime.

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
[0.2.0]: https://github.com/esasmer-dou/java-rust-glowroot-agent/releases/tag/v0.2.0
[0.2.1]: https://github.com/esasmer-dou/java-rust-glowroot-agent/releases/tag/v0.2.1
