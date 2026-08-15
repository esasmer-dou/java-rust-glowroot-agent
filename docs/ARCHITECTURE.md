# Architecture And Production Boundary

[English](ARCHITECTURE.md) | [Turkish](ARCHITECTURE.tr.md)

## Decision

This project does not repackage or partially exclude dependencies from the full Glowroot Java
agent. It implements a separate framework-specific telemetry plane that speaks the Glowroot
collector wire contract.

The telemetry plane is application-side only. The existing Glowroot Central/collector deployment,
storage, and UI remain unchanged. The benchmark mock collector is validation infrastructure, not a
runtime component or a replacement collector.

The decision is deliberate. The upstream agent core uses Java instrumentation and retransformation,
ASM weaving, plugin discovery, Guava, Jackson, Logback, protobuf, gRPC, and Netty. Removing those
dependencies while preserving arbitrary Java method, JDBC, JMX, and profiler behavior would break
the full agent contract. Keeping them would make the `1 MiB` agent-owned budget impossible.

## Data Plane

```mermaid
flowchart LR
    A["Rust Hyper request"] --> B["Preassigned route slot"]
    D["Native Dubbo call"] --> M["Bounded atomic aggregates"]
    R["Native Redis call"] --> M
    J["Constant-time Spring/SQL event edge"] --> M
    V["Rust-owned JNI JVM probe"] --> M
    B --> M
    M --> S["One-minute snapshot"]
    S --> P["Hand-encoded protobuf"]
    P --> H["Rust h2 client"]
    H --> C["Existing Glowroot Central (unchanged)"]
```

- Java `premain` only establishes configuration. It installs no transformer.
- Route names are assigned once during startup. Request paths do not allocate metric names.
- Successful HTTP requests use weighted bounded sampling. HTTP `5xx` remains exact.
- HTTP trace samples contain route name, status, and timing only. Request bodies, query values,
  headers, bind values, and business payloads are not copied. Explicit SQL slots retain only a
  normalized bounded statement label.
- Route slots, trace capacity, encoded request size, h2 windows, timeouts, and reconnect backoff all
  have hard limits.
- Export and profile release run on one dedicated current-thread Tokio runtime with a `256 KiB`
  stack. They do not share Hyper workers or application executors.
- The same Rust thread owns JVM bean discovery, JNI global references, periodic heap/non-heap/pool/GC
  sampling, diagnostic commands, and diagnostic file I/O. Java has no JMX polling worker, bean cache,
  snapshot buffer, or diagnostic helper.
- Minute snapshots remain lock-free. A request racing the exact rollup boundary can be accounted in
  the adjacent interval, so individual minute boundaries are approximate. This avoids a request-path
  lock; dashboards and alerts should use rolling windows instead of requiring exact boundary totals.

## Failure Contract

Collector failure must never fail an application request. The exporter uses connect and whole-call
timeouts plus bounded exponential reconnect backoff. When a rollup expires while disconnected, its
aggregates and traces are dropped and exposed through diagnostics. The agent does not build an
unbounded retry backlog.

Invalid local configuration is different: it fails startup before traffic. Examples are a missing
agent id, an unsupported TLS collector URL, a non-power-of-two sample rate, or limits above the
micro profile bounds.

## Memory Budget

`+1 MiB` is a hard agent-attributed product boundary, not a tuning suggestion. Startup admits at
most `448 KiB` for calculated state and transport reserves. It leaves `576 KiB` for native feature
pages and allocator residency. Startup fails if the calculated ceiling is too large. A property
cannot raise the limit. This contract does not cap unrelated JVM, application, or kernel memory.
The error budget includes frame structures, the boxed frame array, UTF-8 payload bytes, and
conservative allocator metadata for every retained string; it does not count only message bytes.

| Surface | Design control |
| --- | --- |
| Java surface | Embedded-native production mode needs no agent; optional JAR has one class and no transformer |
| Route state | Fixed `max-routes`; atomics allocated once |
| Traces | Optional fixed `trace-capacity`; `0` removes the queue and slow-trace check |
| Network | Startup-only DNS capped at 4 IPs; one batch-scoped h2 connection with 16 KiB flow-control and socket-buffer requests |
| Encoding | At most `max-export-bytes`; no protobuf-java object graph |
| Threads | One isolated current-thread Tokio exporter; no Hyper or Java executor sharing |
| Dynamic profile state | SQL slots, error queue, diagnostic queue, and Rust-owned JNI MXBean global references exist only while their profile needs them |
| Disconnected collector | Bounded backoff and interval drop, no retained backlog |

The public `micro` profile is deliberately capped at `64` route slots, `32` trace samples, and a
`64 KiB` encoded request. The default keeps trace capacity at `0`. Export snapshots and protobuf
buffers allocate their calculated final capacity once instead of growing geometrically.

Linux release evidence separates two boundaries. Agent-owned state plus measured feature pages must
remain below the deterministic `1 MiB` source budget. Every observed feature-enabled versus
feature-disabled paired delta for process `VmRSS`, smaps RSS, and cgroup current must remain at or
below the conservative `3 MiB` resident boundary for `micro`, with at most one exporter thread. Independent OpenJ9
processes can differ because JIT, GC, allocator, and page residency are not deterministic. The
resident gate intentionally includes this noise; it does not replace the source allocation contract.
Each important endpoint and concurrency cell must also stay within `-2%` RPS and `+10%` p99 gates.
An unstable run is `INCONCLUSIVE`.

Release evidence also compares the previous published framework with the new framework plus the
enabled agent. This second comparison includes native code-page growth that a same-image on/off
comparison cannot expose.

The published `0.2.1` exact-source embedded-native artifact had an attributed ceiling of `0.694 MiB`
and `0` additional threads before exporter isolation. Its three-phase resident gate passed: maximum deltas were
`VmRSS +1.742 MiB`, smaps RSS `+1.817 MiB`, and cgroup current `+1.754 MiB`. The gate fingerprints all
native source inputs before accepting a cached feature-disabled SO, so stale baseline binaries are
rejected. The optional convenience JAR is reported separately and is not certified for the strict
resident path; its observed process/smaps maximum reached about `3.055 MiB`.

The exporter resolves collector DNS synchronously during startup and stores at most four unique IP
addresses. Reconnects use only that immutable set, so no runtime DNS worker or growing resolver state
is introduced. The hard-memory profile therefore requires a normal Kubernetes `ClusterIP` Service
or localhost sidecar. Headless or dynamically changing DNS requires a pod restart and is outside this
profile's contract.

## Spring Boot Boundary

Spring Boot support has two separate auto-configuration layers. The web-independent core owns the
process-scoped `NativeTelemetry` lifecycle and standalone native binary. It has no Servlet or Spring
MVC condition and therefore starts in database workers, Kafka applications, schedulers, batch jobs,
and command-line services. The optional MVC layer is the only layer guarded by Servlet web-application
and MVC class conditions. It registers one interceptor when those conditions are present.

Packaging still uses two deliberately separate artifacts. The bootstrap JAR contains one premain
class and only maps bounded arguments to properties. The starter stays in Spring Boot's application
classloader and owns the core plus optional adapter. This split avoids the parent/child classloader
failure caused by putting Spring classes in a `-javaagent` JAR used with an executable Spring Boot
archive. MVC, Tomcat, and Servlet dependencies are not transitively added to a non-web application.

Successful requests are sampled in Java before JNI. Mapped MVC handler `5xx` responses remain exact.
The interceptor resolves Spring's normalized route pattern after dispatch only for a sampled, slow,
or failed request. An unsampled successful request allocates no agent request object. Sampled or
trace-enabled requests retain one small observation on the request; async redispatch reuses Spring
MVC's existing completion lifecycle. The adapter creates no Java executor, Servlet filter, or
classpath scan. The standalone Rust library runs one current-thread
Tokio exporter on a `256 KiB` stack. Every queue, route table, trace buffer, message, and DNS address
set is bounded by the same native engine contract.

The Spring boundary still reads the final mapped route, response status, async completion, and an
optional `Throwable`, because those values exist only after Spring MVC dispatch. It performs no
aggregation, encoding, networking, JMX polling, or file I/O. Replacing this constant-time edge with
a Rust start call would add JNI to every request; replacing it with JVMTI weaving would recreate the
full-agent footprint. Both are rejected by the hot-path contract.

The adapter does not use bytecode weaving, Byte Buddy/ASM, Java gRPC/Netty, or a general-purpose
event bus. Version `0.3.0` supports Servlet MVC HTTP interception, not WebFlux HTTP interception. Use
the full Glowroot agent when Spring-specific arbitrary method weaving, automatic JDBC proxying,
arbitrary JMX discovery, or profiling is required. The bounded runtime profiles add only explicit
SQL slots, selected JVM memory/GC gauges, bounded error stacks, and authorized on-demand diagnostics.

Without MVC, the same native exporter still records process RSS/thread gauges and exporter health.
The `jvm` profile adds JVM memory/GC gauges; `sql` and `full` accept explicit reusable SQL statement
events; `diagnostic` accepts bounded authorized dump commands. Kafka record and scheduler job names
are not inferred or woven in `0.3.0`. This is deliberate: automatic arbitrary-method instrumentation
would reintroduce transformer, class metadata, allocation, and Java-agent costs.

## Dynamic Profile Lifecycle

Profile changes are serialized in Rust. New work is disabled before the active state pointer is
swapped. The old state moves to one retired slot. A second state-changing transition is rejected
until the isolated control future observes the final reference, drops the state, and acknowledges
the exact transition id. There is never an unbounded retired-state list.

The exporter holds the same generation guard while a SQL aggregate or detailed error payload is in
flight. Therefore a release acknowledgement cannot race ahead of a collector request that still
owns profile-derived labels or detail bytes. The request remains bounded by the configured collector
timeout; a control call reports a timeout instead of claiming that memory was released early.

On downgrade, queued error and diagnostic entries are discarded with counters. Stale SQL generation
ids become no-ops. Rust drops its profile-owned MXBean `GlobalRef` values; no Java bean list or
profile-owned bridge callback exists.
Linux glibc runs `malloc_trim(0)` only on the isolated agent thread after the final reference is gone.
Windows does not force a process-wide working-set eviction; allocator ownership is released and OS
reclaim remains deferred.

Uncaught REST handler errors use one fixed native `Java Error` identity. Exception class, message,
and frames live only in the bounded profile queue. New exception classes therefore cannot consume
permanent route slots or leave label strings behind after returning to `micro`.

`malloc_trim(0)` runs outside Hyper workers, but glibc trimming is process-wide. It is a rare
control-plane action and must never be triggered per request. A lower post-switch RSS can include
unrelated free allocator pages and is not, by itself, agent-attributed memory evidence.

OpenJ9 system-classloader metadata, JIT code, and a lazily initialized JVM `Finalizer thread` cannot
be unloaded by an application library. They are treated as one-time JVM warm state and must pass a
repeated-cycle no-growth gate. They are not reported as retained native profile state.

The certified low-memory Spring path activates the starter with properties or environment
variables. The optional one-class `-javaagent` bootstrap remains supported for early startup
metadata and operational conventions, but its OpenJ9 instrumentation bootstrap is measured and
reported separately even though it installs no transformer.

## Compatibility

The test collector compiles the protobuf schema directly from the read-only upstream Glowroot
checkout and parses the Rust messages with generated protobuf classes. The reference revision is
`622dc6f800228cccc6fa37b0ed9e779446d7c41e`. Re-run this protocol gate for every Glowroot Central
upgrade. The current implementation uses collector-compatible legacy unary aggregate and trace
methods; it does not assume that deprecated methods will remain forever.

## Deliberately Unsupported

- arbitrary method or library instrumentation;
- automatic JDBC proxying, bind capture, and arbitrary query tracing;
- arbitrary JMX attribute discovery beyond the selected JVM memory/GC gauges;
- log-event capture;
- thread or allocation profiling;
- scheduled or remotely triggered dumps without an authorized local diagnostic command;
- live bytecode weaving or remote agent configuration;
- direct TLS/mTLS transport in v1.

Use the full Glowroot Java agent for those requirements. For TLS/mTLS with the micro agent, use a
localhost sidecar or service mesh and keep the native h2 hop inside the pod.
