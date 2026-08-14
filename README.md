# Java-Rust Glowroot Agent

[English](README.md) | [Turkish](README.tr.md)

[![CI](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/esasmer-dou/java-rust-glowroot-agent)](https://github.com/esasmer-dou/java-rust-glowroot-agent/releases)

Bounded, Rust-first telemetry for Rust-Java REST applications and Spring Boot MVC services. It sends
HTTP aggregates, errors, optional bounded traces, process gauges, and native Dubbo/Redis timings to
an existing Glowroot Central collector.

Your controllers, handlers, services, validation, and database code do not change. The agent does
not weave bytecode and does not install Byte Buddy, ASM, Java gRPC, Netty, or a Java executor.

## Contents

- [Choose Your Runtime](#choose-your-runtime)
- [What You Get](#what-you-get)
- [Where The Work Runs](#where-the-work-runs)
- [Rust-Java REST Setup](#rust-java-rest-setup)
- [Spring Boot MVC Setup](#spring-boot-mvc-setup)
- [Kubernetes](#kubernetes)
- [Configuration](#configuration)
- [Runtime Profiles](#runtime-profiles)
- [Switch Profiles Without Restarting](#switch-profiles-without-restarting)
- [Tuning Recipes](#tuning-recipes)
- [Failure Behavior](#failure-behavior)
- [Diagnostics](#diagnostics)
- [Performance Contract](#performance-contract)
- [Compatibility](#compatibility)
- [Build](#build)

## Choose Your Runtime

| Application | Add to the application | Native runtime | Extra telemetry thread |
| --- | --- | --- | ---: |
| Rust-Java REST `4.5.0` | No starter is required | Uses the framework's `rust_hyper` library | `1` when enabled |
| Spring Boot MVC `3.x` | `java-rust-glowroot-spring-boot-starter:0.3.0` | Loads the small standalone agent library | `1` |
| Either runtime with `-javaagent` syntax | Add the one-class `java-rust-glowroot-agent:0.3.0` bootstrap | Same runtime as the row above | Same single exporter; bootstrap adds none |

The bootstrap JAR only maps `-javaagent:key=value` arguments to properties. It contains one class,
no native binary, no transformer, and no runtime dependency. The Spring starter is a separate JAR
so Spring classes never cross the executable-JAR classloader boundary.

The existing Glowroot collector, UI, and database stay unchanged.

> **Compatibility boundary:** runtime profile switching requires REST native ABI `29` and Glowroot
> ABI `3`. Use agent `0.3.0` with Rust-Java REST `4.5.0`. Do not copy DLL/SO files from an older
> package.

## What You Get

| Signal | Behavior |
| --- | --- |
| HTTP count and duration | Bounded weighted sampling by normalized route pattern |
| HTTP `5xx` | Counted exactly, even when successful requests are sampled |
| Slow/error trace | Optional bounded queue; disabled by default |
| Rust-native Dubbo | Aggregate count, duration, and errors |
| Rust-native Redis | Separate read/write count, duration, and errors |
| Process gauges | RSS and thread count once per export interval |
| JVM gauges | Optional heap, non-heap, memory-pool, GC count, and GC time gauges |
| SQL aggregates | Optional explicit, bounded operation/statement timing; no JDBC proxy or bytecode weaving |
| Error stacks | Optional bounded stack capture for failed Spring MVC and Rust-Java REST requests |
| On-demand diagnostics | Optional thread dump, heap histogram, or heap dump in the short-lived diagnostic profile |
| Export health | Connect, reconnect, failure, drop, and last-error counters |

Request bodies, query values, headers, SQL text, and personal data are not copied into telemetry.

## Where The Work Runs

The heavy agent work belongs to Rust. Java is only the event boundary for information that exists
inside Spring or the JVM.

| Surface | Owner | What happens |
| --- | --- | --- |
| Aggregation and export | Rust | Bounded route/SQL state, sampling totals, queues, protobuf encoding, h2, reconnect, timeout, and drop policy |
| JVM gauges | Rust | The isolated exporter discovers and owns JNI global references, invokes the selected MXBeans, aggregates values, and builds the gauge message |
| Diagnostics | Rust | The command queue, JNI calls, bounded orchestration, file creation, atomic publication, failure cleanup, and counters stay in Rust |
| Profile lifecycle | Rust | Optional state is allocated, retired, dropped, and optionally trimmed outside Hyper and application workers |
| Spring MVC edge | Java, constant-time only | Supplies the matched route, status, async completion, timestamp, and optional `Throwable` reference to Rust |
| JVM internals | JVM, invoked by Rust | MXBean and dump APIs still execute inside the JVM because that is where the data exists; no Java helper, polling thread, cache, or direct-buffer callback is used |

Rust-Java REST HTTP telemetry is already recorded directly in the Rust server. Spring MVC cannot
derive the final matched controller route from the socket layer. Moving its small edge adapter to
Rust would require another JNI call at request start or general JVMTI/bytecode weaving. Both options
increase request cost and violate the performance contract, so they are deliberately rejected.

This is intentionally not a replacement for every full Glowroot feature. It does not weave arbitrary
Java methods, wrap every JDBC object, run a profiler, capture logs, or accept remote instrumentation.
Use the full Glowroot agent when those features are required. Spring WebFlux is not supported by
`0.3.0`; the adapter targets Servlet MVC.

## Rust-Java REST Setup

Use the coordinated `4.5.0` framework line. It contains Glowroot native ABI `3` and validates the
native provenance before the HTTP server starts.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.5.0</version>
</dependency>
```

Add these values to `rust-spring.properties`:

```properties
reactor.application.name=catalog-api
reactor.glowroot.enabled=true
reactor.glowroot.profile=micro
reactor.glowroot.collector.address=http://glowroot-collector:8181
reactor.glowroot.agent.id=catalog::local
reactor.glowroot.application.name=catalog-api
reactor.glowroot.http.sample-rate=256
reactor.glowroot.trace.capacity=0
```

Start the application normally. No agent JAR is required:

```bash
java -jar catalog-api.jar
```

If your platform requires `-javaagent` syntax, use the optional bootstrap JAR. It changes only how
configuration reaches the same embedded Rust engine:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.3.0.jar=collector=http://glowroot-collector:8181,agent-id=catalog::pod-1,application=catalog-api \
  -jar catalog-api.jar
```

## Spring Boot MVC Setup

### 1. Add the starter

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.3.0</version>
</dependency>
```

The starter is opt-in. Add these values to `application.properties`:

```properties
reactor.glowroot.enabled=true
reactor.glowroot.collector.address=http://127.0.0.1:8181
reactor.glowroot.agent.id=orders::local
reactor.glowroot.application.name=orders-api
reactor.glowroot.http.sample-rate=256
reactor.glowroot.trace.capacity=0
```

Then run the existing Spring Boot application:

```bash
java -jar orders-api.jar
```

Spring auto-configuration registers one MVC interceptor. It reads the normalized route pattern
selected by Spring, such as `/orders/{id}`, after the handler completes. It does not add a Servlet
filter, scan application classes, or create a Java worker pool. A normal unsampled success creates no
agent request object. Sampled, slow, failed, and async requests reuse Spring MVC's completion
lifecycle. Mapped status codes and unhandled failures remain exact.

### 2. Optional early-start bootstrap

Use the bootstrap when your deployment standard expects `-javaagent`, or when you want process
start metadata captured before Spring starts.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-agent</artifactId>
  <version>0.3.0</version>
  <scope>runtime</scope>
</dependency>
```

Keep the bootstrap JAR outside the executable Spring Boot JAR and pass its file path to the JVM:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.3.0.jar=collector=http://glowroot-collector:8181,agent-id=orders::pod-1,application=orders-api,http-sample-rate=256,trace-capacity=0 \
  -jar orders-api.jar
```

Do not place Spring classes in the bootstrap JAR. Spring Boot loads nested dependencies with a child
classloader; the split artifact design is required for executable-JAR compatibility.

## GitHub Packages

GitHub Packages requires authentication for Maven downloads, including packages from public
repositories. Create a token with `read:packages`, then add this server to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-glowroot</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_PACKAGES_TOKEN</password>
    </server>
  </servers>
</settings>
```

Add the package repository to the application POM:

```xml
<repositories>
  <repository>
    <id>github-glowroot</id>
    <url>https://maven.pkg.github.com/esasmer-dou/java-rust-glowroot-agent</url>
  </repository>
</repositories>
```

## Kubernetes

Use the pod name as the leaf agent id. A prefix ending with `::` creates a Glowroot rollup group.

```yaml
env:
  - name: REACTOR_GLOWROOT_ENABLED
    value: "true"
  - name: REACTOR_GLOWROOT_COLLECTOR_ADDRESS
    value: "http://glowroot-collector.observability.svc.cluster.local:8181"
  - name: REACTOR_GLOWROOT_AGENT_ID
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
  - name: REACTOR_GLOWROOT_APPLICATION_NAME
    value: "catalog-api"
  - name: REACTOR_GLOWROOT_HTTP_SAMPLE_RATE
    value: "256"
  - name: REACTOR_GLOWROOT_TRACE_CAPACITY
    value: "0"
```

For a hierarchy such as `catalog::pod-name`, pass the prefix in the container command or create the
complete value in your deployment tooling. Agent ids must be unique per live pod.

Use a stable `ClusterIP` Service or a localhost sidecar for the collector. DNS is resolved at
startup and at most four addresses are retained. Restart the pod when the collector DNS target
changes. Do not expose the plain collector port to the internet. Use a service mesh or localhost
TLS sidecar when encryption or mTLS is required.

## Configuration

Priority is: JVM `-D` property, `-javaagent` argument, environment variable, application property,
then default. An environment key is the uppercase property with dots and dashes replaced by
underscores. Example: `reactor.glowroot.max-export-bytes` becomes
`REACTOR_GLOWROOT_MAX_EXPORT_BYTES`.

| Property | Default | Allowed value | Purpose |
| --- | ---: | --- | --- |
| `reactor.glowroot.enabled` | `false` | boolean | Enables the bounded telemetry runtime |
| `reactor.glowroot.profile` | `micro` | `micro`, `jvm`, `sql`, `full`, `diagnostic` | Selects the bounded startup profile; it can change at runtime |
| `reactor.glowroot.profile.release-timeout-ms` | `5000` | 100-60000 | Maximum wait for retired profile state to be dropped |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | plaintext HTTP URL | Glowroot Central gRPC over HTTP/2 endpoint |
| `reactor.glowroot.agent.id` | empty | 1-256 bytes | Required unique agent/rollup id |
| `reactor.glowroot.application.name` | application name | 1-128 bytes | Name shown in Glowroot |
| `reactor.glowroot.hostname` | `HOSTNAME` | up to 255 bytes | Host or pod label |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000; 60000 multiple | Aggregate export interval |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 connection timeout |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Complete collector request timeout |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Slow trace threshold when traces are enabled |
| `reactor.glowroot.http.sample-rate` | `256` | power of two, 1-1024 | Samples successful HTTP requests; `5xx` stays exact |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Bounded trace queue; `0` allocates no trace queue |
| `reactor.glowroot.sql.capacity` | `16` | 0-32 | Maximum SQL statement slots allocated only by `sql`, `full`, or `diagnostic` |
| `reactor.glowroot.error.trace.capacity` | `8` | 0-16 | Maximum retained detailed error stacks in enabled profiles |
| `reactor.glowroot.error.max-frames` | `24` | 0-32 | Maximum frames copied for one captured error |
| `reactor.glowroot.error.max-bytes` | `4096` | 256-8192 | Maximum UTF-8 error detail size |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Maximum retained HTTP route slots |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Maximum encoded collector request |
| `reactor.glowroot.spring.enabled` | `true` | boolean | Enables the Spring MVC interceptor when the starter is present |
| `reactor.glowroot.spring.order` | `-2147483548` | integer | MVC interceptor order; former `interceptor-order` and `filter-order` names remain aliases |
| `reactor.glowroot.native.extract-dir` | user home | directory | Standalone Spring native extraction directory |
| `reactor.glowroot.native.path` | empty | existing DLL/SO path | Development and staging override; production should use packaged binaries |

Invalid bounds stop startup. There is no property that enlarges the agent-owned memory ceiling.

## Runtime Profiles

Start with `micro`. Raise one pod only when you need more evidence. Return it to `micro` after the
investigation.

| Profile | Adds to the always-on HTTP/Dubbo/Redis aggregates | Good fit |
| --- | --- | --- |
| `micro` | Nothing | Normal production traffic and the lowest steady memory |
| `jvm` | Heap, non-heap, memory-pool, GC count, and GC time gauges | Short JVM memory or GC investigation |
| `sql` | Explicit bounded SQL aggregates and detailed error stacks | Database latency investigation without JVM gauges |
| `full` | `jvm` plus `sql` and error stacks | Short incident window on one pod |
| `diagnostic` | `full` plus a two-command queue for dump operations | One authorized thread dump, heap histogram, or heap dump |

`sql` is explicit by design. The agent does not proxy `DataSource`, wrap JDBC objects, or weave
drivers. Create a statement descriptor once and reuse it. The timed call does not allocate an
observation wrapper:

```java
private final NativeTelemetry.SqlStatement findCustomer;

CustomerRepository(NativeTelemetry telemetry) {
    this.findCustomer = telemetry.sqlStatement(
            "customer.find",
            "select id, name from customer where id = ?"
    );
}

Customer find(long id) {
    long started = findCustomer.start();
    try {
        Customer customer = queryCustomer(id);
        findCustomer.recordSuccess(started, customer == null ? 0 : 1);
        return customer;
    } catch (RuntimeException error) {
        findCustomer.recordFailure(started, error);
        throw error;
    }
}
```

The statement text is normalized and bounded when its slot is first registered. Bind values are not
sent. Keep the descriptor in a singleton service or repository. Do not create it per request.

Rust-Java REST uses the same lifecycle without the Spring starter:

```java
private static final GlowrootTelemetry.SqlStatement FIND_CUSTOMER =
        GlowrootTelemetry.sql("customer.find", "select id, name from customer where id = ?");

long started = FIND_CUSTOMER.start();
try {
    Customer customer = repository.find(id);
    FIND_CUSTOMER.recordSuccess(started, customer == null ? 0 : 1);
    return customer;
} catch (RuntimeException error) {
    FIND_CUSTOMER.recordFailure(started, error);
    throw error;
}
```

## Switch Profiles Without Restarting

Rust-Java REST uses the built-in control API:

```java
import com.reactor.rust.telemetry.GlowrootTelemetry;
import com.reactor.rust.telemetry.TelemetryProfile;
import java.time.Duration;

GlowrootTelemetry.switchTo(TelemetryProfile.FULL, Duration.ofSeconds(5));
// Collect incident data for a bounded period.
GlowrootTelemetry.restoreConfiguredProfile();
```

Spring Boot injects the existing process-scoped bean:

```java
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryProfile;
import java.time.Duration;

telemetry.updateProfile(TelemetryProfile.JVM, Duration.ofSeconds(5));
// Collect a short JVM window.
telemetry.restoreConfiguredProfile(Duration.ofSeconds(5));
```

`configuredProfile()` returns the startup value from `reactor.glowroot.profile`. Therefore an
operations command does not need to hard-code `micro`. If the service later starts with another
bounded baseline, the same restore call still returns to the correct profile.

Do not expose profile changes on a public endpoint. Call the API from an authenticated operations
endpoint or an internal control command. The starter and the REST framework intentionally do not
open a profile-management endpoint. A profile switch is a control-plane operation, not a request
feature. Do not switch profiles per request or on every health-check sample.

SQL slot tokens use a separate positive 32-bit namespace with a 25-bit generation. A stale raw slot
cannot alias a new statement during normal process life; exhaustion is fail-fast after more than
`33 million` state-shape transitions instead of silently wrapping. Profiles are still control-plane
incident tools, not per-request or periodic-sampling switches.

The switch is synchronous and serialized. Returning from `switchTo` or `updateProfile` means:

1. the old feature mask no longer accepts new SQL, error, or diagnostic work;
2. old native queues, statement slots, and profile-derived export payloads have no remaining references;
3. their Rust allocations were dropped only after an in-flight bounded collector request finished or timed out;
4. Rust dropped every profile-owned JNI MXBean global reference when it was no longer needed;
5. Linux glibc received `malloc_trim(0)` from the isolated agent thread.

The trim call does not run on a Hyper or application worker, but glibc trimming is process-wide.
Use profile switching only as a rare control-plane action. Do not infer agent-only RSS savings from
a lower post-switch process RSS; validate feature footprint with fresh telemetry-off/on processes.

If release does not finish before `reactor.glowroot.profile.release-timeout-ms`, the call fails. The
transition id remains visible in diagnostics and the next control call waits for that exact release.
The exporter is not stopped while retired state is pending. A downgrade from `diagnostic` is rejected
while a dump is running.

`micro` keeps the base exporter, route aggregates, and collector connection because telemetry is
still enabled. Disable telemetry only at process startup when you want zero telemetry state.

OpenJ9 cannot unload system-classloader metadata or its lazily created `Finalizer thread` after JVM
management APIs are used once. The agent releases its references, native queues, and buffers, but a
small one-time JVM/JIT warm-state residue can remain. It must not grow with repeated profile cycles.
Linux can return allocator pages immediately; Windows releases ownership but lets the OS allocator
reclaim resident pages later instead of forcing a process-wide working-set eviction.

## Tuning Recipes

| Scenario | Profile | `sample-rate` | `trace.capacity` | Recommendation |
| --- | --- | ---: | ---: | --- |
| High-traffic production API | `micro` | `256` | `0` | Lowest steady overhead; keep exact `5xx` |
| Low-traffic API, exact aggregate trend | `micro` | `1` or `8` | `0` | More samples are needed because traffic is sparse |
| JVM or GC investigation | `jvm` | unchanged | `0` | Raise one pod, observe several export intervals, then return to `micro` |
| SQL latency investigation | `sql` | unchanged | `0` | Instrument only selected repository statements |
| Short incident investigation | `full` | unchanged | `0` by default | JVM, SQL, and error state is dynamic; use one pod and revert after the incident |
| Authorized dump operation | `diagnostic` | unchanged | unchanged | Run one command, confirm completion, then return to `micro` |

Do not solve missing business metrics by setting the sample rate to `1` on every high-traffic pod.
Use explicit business metrics for orders, payments, or domain failures.

`http.sample-rate` and `trace.capacity` are startup settings. A profile switch does not resize them.
If you start with `trace.capacity=16`, that bounded HTTP trace queue remains allocated in `micro`.
Keep it at `0` when strict downgrade reclamation is the priority; profile-owned SQL, error, JVM, and
diagnostic state is still allocated and released dynamically.

## Failure Behavior

- Invalid local configuration fails startup.
- A collector outage does not block HTTP, Dubbo, Redis, or business logic.
- Connect and request timeouts are bounded.
- Reconnect uses bounded exponential backoff.
- Failed intervals are dropped at the rollup boundary; they are not queued forever.
- Route, trace, message, DNS-address, and export sizes have hard limits.
- An old or mismatched native ABI fails early with an actionable error.

## Diagnostics

Rust-Java REST exposes built-in diagnostics:

```bash
curl -s http://localhost:8080/diagnostics/glowroot
curl -s http://localhost:8080/metrics | grep reactor_glowroot
```

For Spring Boot, inject `NativeTelemetry` into an existing secured diagnostics controller only when
you need it, then return `diagnosticsJson()`. The starter does not open a management endpoint by
itself.

Watch `connected`, `export_failure`, `dropped_intervals`, `dropped_transactions`, `dropped_traces`,
`dropped_routes`, `reconnects`, and `last_error_code`. During a profile change also watch
`active_profile`, `active_profile_memory_ceiling_bytes`, `retired_profile_memory_ceiling_bytes`,
`jvm_probe_registered`, `jvm_probe_owned_global_refs`, `profile_release_pending`, `profile_released_transition`,
`profile_release_timeouts`, `profile_last_release_micros`, `profile_max_release_micros`, and
`profile_trim_succeeded`.

After returning to a profile without JVM gauges or diagnostics, both `jvm_probe_registered=false`
and `jvm_probe_owned_global_refs=0` are required. Diagnostic output is written by Rust to a temporary
file in the target directory and published only after success. Heap histograms and heap dumps can
still create a large one-time JVM diagnostic allocation; use them on one pod, never as a periodic
job or during a latency-sensitive peak.

## Performance Contract

The `micro` configuration enforces a deterministic `1 MiB` ceiling for agent-attributed state and
native feature pages. Both Rust-Java REST and Spring use one isolated current-thread Tokio exporter
with a `256 KiB` stack when telemetry is enabled. It does not share Hyper workers, application
executors, or Spring request threads.
The strict Spring gate enables the starter through properties or environment variables. The optional
`-javaagent` bootstrap is a deployment convenience and is validated separately because starting the
JVM instrumentation subsystem adds OpenJ9-owned memory even though no transformer is installed.

Release gates compare telemetry off/on in the same image with randomized paired runs. Every
endpoint/concurrency cell must keep:

- useful HTTP 200 RPS loss at or above `-2%`;
- p99 regression at or below `+10%`;
- non-2xx regression at `0` percentage points for normal cells; the explicit saturated embedded REST
  heavy JSON c256 cell has a `0.02` percentage-point non-inferiority margin. Baseline and candidate aggregate
  and peak error rates must still remain at or below `0.05%`;
- additional agent threads at most `1` for both embedded Rust-Java and standalone Spring.

The stable release runs this full matrix separately for Spring Boot and Rust-Java REST. Both cover
small JSON, precomputed raw JSON, and dynamic heavy JSON at c64 and c256 with six balanced paired
runs. RPS, p99, and startup use each pair's delta before the median is calculated. Non-2xx uses the
paired median, request-weighted total, peak error envelope, and the absolute `0.05%` ceiling
together. The bounded `0.02` percentage-point margin applies only to the saturated embedded REST heavy JSON
c256 cell. One saturated-run delta remains visible without replacing the overall error decision. After both
variants complete the same full workload, a controlled equal-process-age phase requires
paired-median process RSS and cgroup deltas at or below `+3 MiB` for `micro`. Both runtimes may add
one bounded exporter thread. `jvm`, `sql`, `full`, and `diagnostic` are separate temporary-profile
gates because OpenJ9 management/error classes can create one-time JVM warm state. REST wire
compatibility, collector-down fail-open, and
the optional bootstrap are separate mandatory checks.

See [Validation Evidence](docs/VALIDATION.md),
[Architecture And Production Boundary](docs/ARCHITECTURE.md), and
[Benchmark Guide](benchmark/README.md). User-facing changes and compatibility details are in the
[0.3.0 release notes](docs/releases/0.3.0.md).

## Compatibility

| Component | Release | Contract |
| --- | ---: | --- |
| Java | `21` | Semeru OpenJ9 is the primary tested JVM |
| Rust-Java REST | `4.5.0` | REST ABI `29`, Glowroot ABI `3` |
| Agent bootstrap | `0.3.0` | One class; works with either supported runtime |
| Spring Boot starter | `0.3.0` | Spring Boot `3.x`, Servlet MVC |
| Standalone native source | `rust-spring v4.5.0` | Glowroot ABI `3`; clean CI DLL/SO |
| Glowroot Central wire contract | upstream `0.14.8-beta.5-SNAPSHOT` checkout | Unary h2/protobuf compatibility gate |
| Native platforms | Windows x64, Linux glibc x64 | Clean CI-built DLL/SO with SHA-256 provenance |

Runtime profile switching requires the coordinated REST ABI `29` and Glowroot ABI `3` pair shown
above. Startup provenance checks reject an older or locally copied native binary.

Do not copy DLL/SO files between versions. The framework, cache, Dubbo, and agent libraries validate
their coordinated native ABI at startup.

## Build

```powershell
$env:JAVA_HOME = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
mvn -B -ntp clean verify
```

The Maven reactor builds:

- `agent-bootstrap/target/java-rust-glowroot-agent-0.3.0.jar`
- `spring-boot-starter/target/java-rust-glowroot-spring-boot-starter-0.3.0.jar`

The native DLL/SO are built only from the clean `rust-spring` commit recorded in
`native-provenance.properties`. Run `scripts/sync-native-artifacts.ps1` with verified CI artifacts;
do not publish a local dirty native build.

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 16 `
  -MaxWarmupRobustTrendPercent 3 `
  -MaxWarmupMedianAbsoluteDeviationPercent 4 `
  -MaxNon2xxDeltaPercentagePoints 0 `
  -MaxSaturatedNon2xxDeltaPercentagePoints 0.02 `
  -MaxAbsoluteNon2xxPercent 0.05 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Use the following additional parameters to reproduce the embedded REST matrix:

```powershell
-ApplicationKind rust-java-rest `
-RequiredRestVersion "4.5.0" `
-RequiredRestNativeAbi 29 `
-MemoryLimit "128m" `
-AllowedThreadDelta 1
```

See [Validation Evidence](docs/VALIDATION.md) for the complete copy-paste commands.

The mock collector is test-only. Never deploy it in place of Glowroot Central.
