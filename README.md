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
- [Rust-Java REST Setup](#rust-java-rest-setup)
- [Spring Boot MVC Setup](#spring-boot-mvc-setup)
- [Kubernetes](#kubernetes)
- [Configuration](#configuration)
- [Tuning Recipes](#tuning-recipes)
- [Failure Behavior](#failure-behavior)
- [Diagnostics](#diagnostics)
- [Performance Contract](#performance-contract)
- [Compatibility](#compatibility)
- [Build](#build)

## Choose Your Runtime

| Application | Add to the application | Native runtime | Extra telemetry thread |
| --- | --- | --- | ---: |
| Rust-Java REST `4.4.0` | No starter is required | Reuses the framework's `rust_hyper` library | `0` |
| Spring Boot MVC `3.x` | `java-rust-glowroot-spring-boot-starter:0.2.0` | Loads the small standalone agent library | `1` |
| Either runtime with `-javaagent` syntax | Add the one-class `java-rust-glowroot-agent:0.2.0` bootstrap | Same runtime as the row above | No additional thread |

The bootstrap JAR only maps `-javaagent:key=value` arguments to properties. It contains one class,
no native binary, no transformer, and no runtime dependency. The Spring starter is a separate JAR
so Spring classes never cross the executable-JAR classloader boundary.

The existing Glowroot collector, UI, and database stay unchanged.

## What You Get

| Signal | Behavior |
| --- | --- |
| HTTP count and duration | Bounded weighted sampling by normalized route pattern |
| HTTP `5xx` | Counted exactly, even when successful requests are sampled |
| Slow/error trace | Optional bounded queue; disabled by default |
| Rust-native Dubbo | Aggregate count, duration, and errors |
| Rust-native Redis | Separate read/write count, duration, and errors |
| Process gauges | RSS and thread count once per export interval |
| Export health | Connect, reconnect, failure, drop, and last-error counters |

Request bodies, query values, headers, SQL text, and personal data are not copied into telemetry.

This is intentionally not a replacement for every full Glowroot feature. Use the full Glowroot
agent when you need arbitrary Java method tracing, JDBC SQL capture, JMX, profiling, heap dumps, or
remote configuration. Spring WebFlux is not supported by `0.2.0`; the adapter targets Servlet MVC.

## Rust-Java REST Setup

Use the coordinated `4.4.0` framework line. It contains Glowroot native ABI `1` and validates the
native provenance before the HTTP server starts.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.4.0</version>
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
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.2.0.jar=collector=http://glowroot-collector:8181,agent-id=catalog::pod-1,application=catalog-api \
  -jar catalog-api.jar
```

## Spring Boot MVC Setup

### 1. Add the starter

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.2.0</version>
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

Spring auto-configuration registers one MVC interceptor. It uses the normalized route pattern
selected by Spring, such as `/orders/{id}`. It does not scan application classes and does not create
a Java worker pool. Errors from mapped MVC handlers remain exact; failures before handler mapping
belong to the servlet container or ingress telemetry boundary.

### 2. Optional early-start bootstrap

Use the bootstrap when your deployment standard expects `-javaagent`, or when you want process
start metadata captured before Spring starts.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-agent</artifactId>
  <version>0.2.0</version>
  <scope>runtime</scope>
</dependency>
```

Keep the bootstrap JAR outside the executable Spring Boot JAR and pass its file path to the JVM:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.2.0.jar=collector=http://glowroot-collector:8181,agent-id=orders::pod-1,application=orders-api,http-sample-rate=256,trace-capacity=0 \
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
| `reactor.glowroot.profile` | `micro` | `micro` | Selects the hard-bounded feature set |
| `reactor.glowroot.collector.address` | `http://127.0.0.1:8181` | h2 HTTP URL | Glowroot Central endpoint |
| `reactor.glowroot.agent.id` | empty | 1-256 bytes | Required unique agent/rollup id |
| `reactor.glowroot.application.name` | application name | 1-128 bytes | Name shown in Glowroot |
| `reactor.glowroot.hostname` | `HOSTNAME` | up to 255 bytes | Host or pod label |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000; 60000 multiple | Aggregate export interval |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 connection timeout |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Complete collector request timeout |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Slow trace threshold when traces are enabled |
| `reactor.glowroot.http.sample-rate` | `256` | power of two, 1-1024 | Samples successful HTTP requests; `5xx` stays exact |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Bounded trace queue; `0` allocates no trace queue |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Maximum retained HTTP route slots |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Maximum encoded collector request |
| `reactor.glowroot.spring.enabled` | `true` | boolean | Enables the Spring MVC interceptor when the starter is present |
| `reactor.glowroot.spring.interceptor-order` | `-2147483548` | integer | Spring MVC interceptor order |
| `reactor.glowroot.native.extract-dir` | user home | directory | Standalone Spring native extraction directory |

Invalid bounds stop startup. There is no property that enlarges the agent-owned memory ceiling.

## Tuning Recipes

| Scenario | `sample-rate` | `trace.capacity` | Recommendation |
| --- | ---: | ---: | --- |
| High-traffic production API | `256` | `0` | Lowest steady overhead; keep exact `5xx` |
| Low-traffic API, exact aggregate trend | `1` or `8` | `0` | More samples are needed because traffic is sparse |
| Staging latency investigation | `64` | `0` | More histogram updates; run p99 A/B first |
| Short incident investigation on one pod | `8` | `16` | Bounded traces; revert after the incident |

Do not solve missing business metrics by setting the sample rate to `1` on every high-traffic pod.
Use explicit business metrics for orders, payments, or domain failures.

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
`dropped_routes`, `reconnects`, and `last_error_code`.

## Performance Contract

The embedded Rust-Java path enforces a deterministic `1 MiB` ceiling for agent-attributed state and
native feature pages and adds no thread. The standalone Spring path is gated separately because it
loads a small native library and one current-thread Tokio exporter with a `256 KiB` stack.

Release gates compare telemetry off/on in the same image with randomized paired runs. Every
endpoint/concurrency cell must keep:

- useful HTTP 200 RPS loss at or above `-2%`;
- p99 regression at or below `+10%`;
- every paired process RSS and cgroup delta at or below `+3 MiB`;
- non-2xx regression at `0` percentage points;
- additional threads at `0` for embedded Rust-Java and at most `1` for standalone Spring.

The full Spring matrix covers small JSON, precomputed raw JSON, and dynamic heavy JSON at c64 and
c256 with at least three paired runs. A favourable total median cannot hide a failed cell or a
resident-memory maximum.

See [Validation Evidence](docs/VALIDATION.md),
[Architecture And Production Boundary](docs/ARCHITECTURE.md), and
[Benchmark Guide](benchmark/README.md).

## Compatibility

| Component | Release | Contract |
| --- | ---: | --- |
| Java | `21` | Semeru OpenJ9 is the primary tested JVM |
| Rust-Java REST | `4.4.0` | REST ABI `28`, Glowroot ABI `1` |
| Agent bootstrap | `0.2.0` | One class; works with either supported runtime |
| Spring Boot starter | `0.2.0` | Spring Boot `3.x`, Servlet MVC |
| Glowroot Central wire contract | upstream `0.14.8-beta.5-SNAPSHOT` checkout | Unary h2/protobuf compatibility gate |
| Native platforms | Windows x64, Linux glibc x64 | Clean CI-built DLL/SO with SHA-256 provenance |

Do not copy DLL/SO files between versions. The framework, cache, Dubbo, and agent libraries validate
their coordinated native ABI at startup.

## Build

```powershell
$env:JAVA_HOME = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
mvn -B -ntp clean verify
```

The Maven reactor builds:

- `agent-bootstrap/target/java-rust-glowroot-agent-0.2.0.jar`
- `spring-boot-starter/target/java-rust-glowroot-spring-boot-starter-0.2.0.jar`

The native DLL/SO are built only from the clean `rust-spring` commit recorded in
`native-provenance.properties`. Run `scripts/sync-native-artifacts.ps1` with verified CI artifacts;
do not publish a local dirty native build.

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 3 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -FailOnGate
```

The mock collector is test-only. Never deploy it in place of Glowroot Central.
