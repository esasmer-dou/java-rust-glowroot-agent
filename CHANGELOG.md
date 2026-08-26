# Changelog

All notable changes to this project are recorded here.

## [Unreleased]

## [0.5.4] - 2026-08-27

### Production Gate

- Pin the Rust-Java REST performance matrix to release `4.6.2` and its exact source commit.
- Require a successful Production Gate run for the exact release commit before Maven packages or
  GitHub release assets can be published.
- Require both non-expired Spring Boot and Rust-Java REST performance evidence artifacts. Missing,
  stale, mismatched, or expired evidence now fails the release before publication begins.
- Add deterministic pass/fail tests for the release-gate verifier and execute them in CI and the
  Production Gate workflow.
- Resolve every Spring MVC, WebFlux, and non-web benchmark fixture against the root release version
  and fail CI when a fixture drifts to an older agent artifact.
- Run each baseline/candidate pair on the same automatically selected CPU group, reverse execution
  order between pairs, and reject unstable warmup windows without relaxing RSS, p99, RPS, error, or
  thread limits.
- Move release performance evidence to pinned GitHub-hosted Linux images. Spring Boot and Rust-Java
  REST matrices run in parallel after shared correctness checks, reducing wall-clock gate time while
  keeping each A/B comparison inside one isolated VM.
- Reserve one complete physical CPU group for the measured application. The load runner, collector,
  and orchestration process are bounded to the other physical group on standard hosted runners, and
  this topology is recorded in machine-readable evidence. A 30-second quiet-host window rejects
  noisy runners before evidence collection.
- Use a bounded, deterministic curl request burst for the embedded REST protocol and collector-down
  fail-open gates. The release gate no longer depends on a non-existent executable in the curl image,
  and CI now rejects that invalid runner contract.

### Compatibility

- Runtime behavior, Java APIs, REST ABI `29`, and Glowroot ABI `6` are unchanged from `0.5.3`.
- Agent `0.5.4` remains coordinated with Rust-Java REST `4.6.2`.

## [0.5.3] - 2026-08-26

### Fixed

- Rotate the retained collector HTTP/2 connection after 15 minutes, before Glowroot Central's
  20-minute maximum connection age.
- Run a connection readiness preflight before draining aggregate counters.
- Keep one bounded aggregate batch until Central acknowledges it. A connection close now performs
  reconnect, init, and a byte-identical retry instead of losing the current transaction interval.
- Preserve a pending aggregate after retry exhaustion while newer traffic remains in preallocated
  counters. This avoids both silent data loss and an unbounded retry queue.

### Added

- Export proactive reconnect, recovered retry, deferred interval, and pending aggregate transaction
  counters through diagnostics, Prometheus, and Glowroot gauges.
- Add a deterministic HTTP/2 test that closes the first aggregate stream before acknowledgement and
  verifies the exact payload is delivered on the replacement connection without increasing the
  dropped transaction counter.

### Compatibility

- Glowroot native ABI remains `6`. Use agent `0.5.3` with its packaged DLL/SO or coordinated
  Rust-Java REST `4.6.2`.
- Linux x64 remains compatible with `glibc 2.17` and newer.

## [0.5.2] - 2026-08-26

### Added

- Add bounded Spring MVC slow/error trace details: HTTP method, response code, controller timer and
  entry, request-thread counters, and original exception class/message/stack.
- Add a diagnostic-only Apache Dubbo consumer SPI adapter. It reports the operation, total call
  duration, call count, and error count without retaining RPC arguments or results.
- Add Glowroot Central's bidirectional downstream service for live Thread dump, OpenJ9 heap
  histogram/dump, Force GC, MBean tree, masked System properties, capabilities, and current time.
- Populate the Glowroot Environment view with bounded host, process, Java/OpenJ9, masked JVM
  argument, dump-directory, and agent-version information.

### Changed

- Keep all protobuf encoding, collector/downstream transport, reconnect, bounded queues, JVM
  diagnostic dispatch, and exception stack materialization on the isolated Rust exporter runtime.
- Enable request CPU, contention, and allocated-memory counters only while `diagnostic` owns them,
  then restore the JVM's prior setting on profile downgrade.
- Preserve unsent aggregate atomics across collector connection failures and rotate long-lived HTTP/2
  connections before Central's idle boundary.

### Fixed

- Failed Spring requests now reach Glowroot Errors and detailed traces even when the server adapter
  only observes a final `5xx`; an available Spring/Servlet exception remains the preferred error.
- Slow traces now use the upstream `http request` root timer with nested `spring controller` and
  `dubbo consumer` timers instead of a header-only trace.
- Live JVM pages no longer report the agent as disconnected when the diagnostic downstream stream is
  established.

### Compatibility

- Glowroot native ABI is `6`. Use agent `0.5.2` only with its packaged DLL/SO or coordinated
  Rust-Java REST `4.6.1` native runtime.
- Linux x64 remains compatible with `glibc 2.17` and newer.
- Request/response bodies, query/header values, Dubbo arguments/results, arbitrary operating-system
  environment variables, and application log weaving remain outside the bounded-agent contract.

## [0.5.1] - 2026-08-21

### Fixed

- Print a process-scoped native runtime startup line after the Java package, native library, ABI,
  and configured profile have all initialized successfully.
- Use `spring.application.name` when neither `reactor.glowroot.application.name` nor
  `reactor.application.name` is configured.
- Add a fail-fast final-container gate for custom dependency layers and shared `NEW_BOOT-INF` PVCs.
  It rejects images whose classpath index references agent JARs that are not physically present.

### Validated

- Reproduced the NMC runtime combination with Spring Boot `3.2.4`, Jetty, Semeru OpenJ9 21, the
  Kubernetes environment-variable names, and an explicit native path. The collector accepted one
  init message, one aggregate message, 102 exact `Web` transactions, 20 gauges, and no validation
  errors.
- Confirmed that deleting `BOOT-INF/lib` without updating the redirected dependency layer leaves
  zero agent JARs in the final application filesystem while the service can still start.

### Compatibility

- Glowroot native ABI remains `4`. The Windows DLL and Linux SO are unchanged from `0.5.0`.

## [0.5.0] - 2026-08-21

### Fixed

- Feed Glowroot **Average**, **Percentile**, **Throughput**, and **Errors** with one exact aggregate
  contribution for every completed HTTP request. `reactor.glowroot.http.sample-rate` now limits only
  optional trace detail and never removes successful requests from endpoint dashboards.
- Emit upstream-compatible `Web` transaction types, route-only names such as `/orders/*`, and the
  `http request` root timer expected by the existing Glowroot Central and Cassandra read model.
- Aggregate route-cardinality overflow under one bounded `<route-limit-exceeded>` transaction
  instead of silently dropping completed requests. Transient native registration failures retry on
  the next request while telemetry remains fail-open.
- Print one startup readiness line naming the active Spring HTTP adapter. This makes a missing
  starter or disabled HTTP adapter visible before operators inspect the transaction screens.

### Compatibility

- The next coordinated package requires Glowroot native ABI `4`. REST native ABI remains `29`.
- The optional `-javaagent` bootstrap is still configuration-only. Spring HTTP telemetry requires
  the Spring Boot starter or the matching MVC/WebFlux adapter on the application classpath.

## [0.4.1] - 2026-08-18

### Fixed

- Linked Linux x64 native artifacts with a fixed `glibc 2.17` symbol ceiling instead of inheriting
  the hosted runner's newer GLIBC surface.
- Added fail-closed release checks for GLIBC symbol versions, native provenance, and loadability on
  Debian Stretch and Debian Bookworm.
- Documented final-image verification and the requirement to use the same immutable custom-JRE
  image in builder and runtime stages.

### Compatibility

- REST ABI remains `29` and Glowroot ABI remains `3`.
- Telemetry behavior, thread count, allocation limits, and Java integration are unchanged.

## [0.4.0] - 2026-08-18

### Added

- Added a separate optional Spring WebFlux `WebFilter` artifact. It preserves bounded route sampling,
  exact `5xx`, asynchronous completion, fail-open behavior, and does not add Reactor Netty.
- Added executable Tomcat, Jetty, Undertow, and Reactor Netty lifecycle/load gates covering
  synchronous `200`, asynchronous `200`, `500`, `404`, native route registration, and zero non-2xx.
- Added a fail-closed dependency-isolation gate that rejects any Tomcat, Jetty, or Undertow engine
  dependency in the main starter and rejects unselected engines in executable applications.

### Changed

- Added equal direct full-lifecycle adapters for all supported Spring web runtimes: Tomcat context
  Valve, Jetty completion RequestLog, Undertow exchange completion listener, and WebFlux WebFilter.
  The dominant unsampled success returns before route resolution, request attributes, or JNI.
- Split native runtime, portable MVC fallback, and every server adapter into small artifacts. The
  one-dependency MVC starter includes only these adapter JARs; all server APIs remain `provided` and
  `optional`, so the agent does not add or select a server engine.
- Documented `java-rust-glowroot-spring-runtime` as the minimum dependency for non-web Spring Boot
  workers, including profile behavior, disabled-runtime allocation boundaries, and explicit SQL and
  business-operation instrumentation limits.
- Made unstable warmup block quantitative benchmark claims in development mode as well as release
  mode; noisy OpenJ9/container ramp-up can no longer be reported as a passing performance result.
- Moved Spring exception class, message, and stack traversal off application request threads. Java
  now hands Rust a bounded weak JNI reference; the isolated exporter materializes optional error
  detail under one shared hard capacity, so telemetry cannot retain arbitrary exception graphs.
- Moved WebFlux telemetry completion after the downstream terminal signal and replaced the
  callback chain with one bounded subscriber wrapper. Final status, async completion, exact errors,
  and normalized routes remain intact without extending the response critical path.
- Bypassed DNS resolution for literal collector IP addresses and corrected the Windows process
  thread gauge in the coordinated native runtime.

### Validated

- Passed randomized Semeru OpenJ9 telemetry-off/on A/B gates within the stable limits of at most
  `2%` useful-RPS loss, `10%` p99 regression, and `3 MiB` process-RSS growth.
- Passed Tomcat, Jetty, Undertow, Reactor Netty, non-web Spring, dependency isolation, native
  provenance, and all-module Maven release gates.

## [0.3.0] - 2026-08-16

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

- Embedded Tomcat now uses one bounded context valve instead of entering the Spring MVC interceptor
  lifecycle on every request. Jetty, Undertow, and other Servlet containers retain the portable MVC
  interceptor fallback. Exact `5xx`, async completion, route bounds, and the one-thread native
  exporter contract are unchanged.
- Added `reactor.glowroot.spring.tomcat-native.enabled=false` as an explicit escape hatch for forcing
  the portable MVC fallback without disabling process/JVM/SQL telemetry.
- Moved the exporter and profile-release loop onto one isolated `256 KiB` Rust thread in both Spring
  and Rust-Java REST. Telemetry no longer consumes Hyper workers or application executors.
- Packed the embedded REST request capture state into one 32-bit value and simplified the exact
  rotating sample window. Sampled aggregates, periodic-route coverage, and exact 5xx accounting
  remain unchanged.
- Validated collector reachability when the isolated exporter starts and lowered its operating-
  system scheduling priority. Linux uses low-priority batch scheduling and Windows uses its lowest
  normal thread priority. Embedded REST releases that startup probe and reconnects only for a
  bounded export window; standalone Spring reuses one bounded connection.
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
- Separated the stable agent-cost matrix from serializer/JIT stress. Stable publication measures
  small/raw JSON at c64/c256 and still smoke-tests dynamic heavy routes; `extended` adds measured
  heavy JSON c64/c128 but cannot authorize a stable package.

### Fixed

- Removed the embedded REST idle h2 connection from the Hyper process CPU budget. The published
  `4.4.1` gate had already proven the export-window connection lifecycle; `4.5.2` restores that
  behavior while retaining the isolated lower-priority exporter thread.
- Aligned the production gate with the one-thread exporter isolation contract and extended fixed
  OpenJ9 warmup before measuring either variant.
- Replaced the outlier-sensitive final-three min/max warmup check with two robust controls: at most
  `3%` primary normalized Theil-Sen trend and at most `4%` median absolute deviation across the final
  six rounds. A `3-5%` boundary trend is accepted only when adjacent three-round medians remain within
  `3%`. Sustained JIT ramp-up still fails; a near-plateau outlier is not misclassified as a trend.
- Interleaved all six fixed rounds across measured endpoint classes. The calibrated one-slot release gate
  reverses baseline/candidate process order in alternating pairs; an explicit dual-slot gate can also
  interleave variants. A process that is still improving can receive at most fourteen additional rounds.
  Both JVMs receive identical work at the same process age, and the final six-round decision keeps
  the same strict trend and dispersion thresholds.
- Kept a bounded `0.02` percentage-point non-inferiority margin for explicit extended/manual
  saturated embedded REST heavy JSON c256+ tests. Stable release cells remain zero-delta, every
  baseline/candidate aggregate and peak error rate is capped at `0.05%`, and extended evidence
  cannot authorize publication.
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
- Reserved the load runner's complete SMT sibling group for its two `wrk` event loops. The release
  gate now fails closed when a two-thread load generator is pinned to one logical CPU and could hide
  application throughput behind load-generator saturation.
- Recorded the application's reserved and execution CPU sets separately. Single-logical-CPU pinning
  remains diagnostic; stable evidence keeps the complete reserved group behind the one-CPU quota.
- Rejected forced single OpenJ9 compiler/GC workers after measured Spring warmup showed a prolonged
  JIT ramp. Release images keep the same production-representative JVM policy in both variants.
- Added symmetric, bounded invalid-process-pair replacement. A JVM that cannot satisfy the unchanged
  warmup stability gate is discarded in full; neither variant contributes startup, workload, memory,
  or warmup evidence, and at most two such pair attempts are allowed.
- Bound release evidence to runtime Git objects instead of a repository-wide commit id. A
  benchmark-only follow-up can reuse a passing REST matrix only when the POM, bootstrap, starter,
  and packaged native artifact trees are byte-identical; protocol and Spring gates still run on the
  release commit.
- Removed the monitor lock from registered Spring MVC route lookups and shortened the dominant
  unsampled successful completion path without changing exact `5xx` or async accounting.

### Compatibility

- Agent `0.3.0` requires Glowroot native ABI `3`. The embedded path requires Rust-Java REST `4.5.4`
  and REST native ABI `29`.
- Windows x64 and Linux glibc x64 binaries are produced from the same clean `rust-spring v4.5.4`
  source revision and are protected by SHA-256 provenance checks.

## [0.2.1] - 2026-08-14

### Production Gate

- Added a full Rust-Java REST c64/c256 matrix with six paired runs for small, raw, and heavy JSON.
- Pinned the embedded path to `rust-java-rest:4.4.1` and native ABI `28`.
- Repacked the standalone Windows/Linux native exporter from clean `rust-spring v4.4.2` CI
  artifacts with full source-revision provenance.
- Made REST protocol, collector-down fail-open, and optional bootstrap checks release-enforced.
- Required both Spring Boot and Rust-Java REST evidence from the exact tag commit before publish.
- Added performance, warmup-stability, rejected-pair, steady-memory, and protocol evidence to stable
  release assets.
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

[Unreleased]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.5.4...HEAD
[0.5.4]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.5.3...v0.5.4
[0.5.3]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.3.0...v0.4.0
[0.1.0-rc1]: https://github.com/esasmer-dou/java-rust-glowroot-agent/releases/tag/v0.1.0-rc1
[0.2.0]: https://github.com/esasmer-dou/java-rust-glowroot-agent/releases/tag/v0.2.0
[0.2.1]: https://github.com/esasmer-dou/java-rust-glowroot-agent/releases/tag/v0.2.1
[0.3.0]: https://github.com/esasmer-dou/java-rust-glowroot-agent/compare/v0.2.1...v0.3.0
