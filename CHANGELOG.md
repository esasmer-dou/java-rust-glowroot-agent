# Changelog

All notable changes to this project are recorded here.

## [Unreleased]

## [0.3.0] - 2026-08-15

### Added

- Added bounded runtime profiles: `micro`, `jvm`, `sql`, `full`, and `diagnostic`.
- Added synchronous, process-scoped profile switching for Spring MVC and the coordinated Rust-Java
  REST runtime.
- Added opt-in JVM memory/GC gauges, explicit reusable SQL timing descriptors, bounded error stack
  capture, and authorized JVM diagnostic commands.
- Added profile lifecycle diagnostics for active/retired bytes, transition ids, release latency,
  timeouts, allocator trims, and JNI JVM-probe ownership.
- Added `configuredProfile()` and `restoreConfiguredProfile()` so temporary incident profiles can
  return to the configured baseline without hard-coding `micro`.
- Split Spring Boot auto-configuration into a web-independent native core and an optional Servlet
  MVC adapter. Non-web workers collect process/JVM gauges, explicit SQL timings, and authorized
  diagnostics without adding Spring Web, Tomcat, Servlet dependencies, or a Java telemetry worker.
- Added an executable `WebApplicationType.NONE` gate that rejects MVC beans and web dependencies in
  the non-web runtime graph.

### Changed

- Moved the exporter and profile-release loop onto one isolated `256 KiB` Rust thread in both Spring
  and Rust-Java REST. Telemetry no longer consumes Hyper workers or application executors.
- Moved Spring MVC synchronous request sampling state into one reusable per-thread primitive holder.
  Normal synchronous requests no longer touch the Servlet attribute table, and sampled synchronous
  requests no longer allocate an `Observation` object. Async redispatch keeps the bounded attribute
  hand-off required for cross-thread correctness.
- Moved JVM bean discovery, heap/non-heap/pool/GC sampling, diagnostic orchestration, and diagnostic
  file I/O out of Java helper classes and into the isolated Rust runtime. Java now supplies only
  Spring/JVM boundary events that cannot be observed outside the JVM.
- Made profile transitions generation-aware and serialized. Stale SQL slots become no-ops after a
  downgrade and are re-registered when the profile is enabled again.
- Made `full -> micro` release synchronous: profile-owned SQL slots, error/diagnostic queues,
  Rust-owned JNI MXBean global references, and in-flight profile-derived export data are dropped
  before the control call returns.
- Widened generation-aware SQL tokens to a positive 32-bit namespace so stale raw slots cannot
  alias after the former 10-bit generation range.
- Counted retained error-frame structures and per-string allocator metadata in the hard startup
  memory budget instead of accounting only for UTF-8 payload bytes.

### Fixed

- Aligned the production gate with the one-thread exporter isolation contract and extended fixed
  OpenJ9 warmup before measuring either variant.
- Replaced the outlier-sensitive final-three min/max warmup check with two robust controls: at most
  `3%` primary normalized Theil-Sen trend and at most `4%` median absolute deviation across the final
  six rounds. A `3-5%` boundary trend is accepted only when adjacent three-round medians remain within
  `3%`. Sustained JIT ramp-up still fails; a near-plateau outlier is not misclassified as a trend.
- Interleaved all sixteen fixed rounds across endpoint classes. The calibrated one-slot release gate
  reverses baseline/candidate process order in alternating pairs; an explicit dual-slot gate can also
  interleave variants. A process that is still improving can receive at most sixteen additional rounds.
  Both JVMs receive identical work at the same process age, and the final six-round decision keeps
  the same strict trend and dispersion thresholds.
- Replaced the impossible zero-error requirement for the deliberately saturated embedded REST heavy JSON c256
  cell with a bounded `0.02` percentage-point non-inferiority margin. Normal cells remain zero-delta,
  and every baseline/candidate aggregate and peak error rate is capped at `0.05%`.
- Made HTTP/SQL telemetry registration and error-stack extraction fail open. JNI inspection failure
  increments a drop counter and cannot replace or interrupt the application exception flow.
- Replaced per-exception-class REST labels with one fixed `Java Error` identity so temporary `full`
  mode cannot leave permanent route slots after returning to `micro`.
- Made profile reclamation inspect pending retired state before waiting for a notification, allowing
  an exporter restart to complete a release whose earlier notification was already consumed.
- Corrected the release fail-open gate to wait for the first real transport attempt under the
  60-second export interval contract. A disconnected pre-export state no longer causes a false
  failure, and release evidence must show a failed attempt within the bounded 75-second window.
- Moved the full performance matrix to the dedicated `reactor-performance-native-linux` runner
  class while keeping GitHub Actions as the exact-commit orchestrator and release gatekeeper.
  Release validation now rejects hosted, WSL, containerized, undersized, or multiply registered
  runner evidence. Local Docker retains a short development-only A/B gate.
- Added repeatable CPU-group calibration and deterministic application, load-runner, and collector
  roles on three separate physical groups. A partial configuration, shared physical group, or an
  application role that omits an SMT sibling now fails closed.

### Compatibility

- Agent `0.3.0` requires Glowroot native ABI `3`. The embedded path requires Rust-Java REST `4.5.0`
  and REST native ABI `29`.
- Windows x64 and Linux glibc x64 binaries are produced from the same clean `rust-spring v4.5.0`
  source revision and are protected by SHA-256 provenance checks.

## [0.2.1] - 2026-08-14

### Production Gate

- Added a full Rust-Java REST c64/c256 matrix with six paired runs for small, raw, and heavy JSON.
- Pinned the embedded path to `rust-java-rest:4.4.1` and native ABI `28`.
- Repacked the standalone Windows/Linux native exporter from clean `rust-spring v4.4.2` CI
  artifacts with full source-revision provenance.
- Made REST protocol, collector-down fail-open, and optional bootstrap checks release-enforced.
- Required both Spring Boot and Rust-Java REST evidence from the exact tag commit before publish.
- Added performance, warmup-stability, steady-memory, and protocol summaries to stable release assets.
- Replaced the noise-sensitive single-pair non-2xx veto with three guards: paired median and
  request-weighted aggregate must show no regression, and candidate peak error rate may not exceed
  baseline peak; worst-pair drift remains visible in release evidence.

### Runtime

- Replaced the Spring Servlet filter with a bounded MVC interceptor so normal unsampled successes
  allocate no agent request object and do not wrap the Servlet filter chain.
- Kept mapped and unhandled `5xx` accounting exact across synchronous and async MVC completion.
- Replaced sampled-request composite route keys with a fixed-capacity allocation-free lookup table.
- Retained the public 0.2.0 filter as a deprecated, non-auto-configured binary compatibility adapter.
- Reserved the complete application SMT group and added an RPS-stability warmup gate before every
  measured endpoint/process pair.

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
[0.3.0]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.2.1...v0.3.0
