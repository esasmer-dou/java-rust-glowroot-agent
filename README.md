# Java-Rust Glowroot Agent

[English](README.md) | [Turkish](README.tr.md)

[![CI](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/esasmer-dou/java-rust-glowroot-agent/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/esasmer-dou/java-rust-glowroot-agent)](https://github.com/esasmer-dou/java-rust-glowroot-agent/releases)

Bounded, Rust-first telemetry for Rust-Java REST applications and Spring Boot services. It sends
exact HTTP aggregates, bounded slow/error traces, Spring controller and Dubbo consumer timing,
process/JVM gauges, and authorized live JVM diagnostics to an existing Glowroot Central collector.

Your controllers, handlers, services, validation, and database code do not change. The agent does
not weave bytecode and does not install Byte Buddy, ASM, Java gRPC, Netty, or a Java executor.

## Contents

- [Choose Your Runtime](#choose-your-runtime)
- [Sample Projects](#sample-projects)
- [What You Get](#what-you-get)
- [Where The Work Runs](#where-the-work-runs)
- [Rust-Java REST Setup](#rust-java-rest-setup)
- [Spring Boot Setup](#spring-boot-setup)
- [GitHub Packages](#github-packages)
- [Kubernetes](#kubernetes)
- [Linux Container Compatibility](#linux-container-compatibility)
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
| Rust-Java REST `4.6.2` | No starter is required | Uses the framework's `rust_hyper` library | `1` when enabled |
| Spring Boot `3.x`, MVC | `java-rust-glowroot-spring-boot-starter:0.5.4` | Loads the small standalone agent library and the matching optional server adapter | `1` |
| Spring Boot `3.x`, non-web | Minimum: `java-rust-glowroot-spring-runtime:0.5.4`; the umbrella starter also works | Loads only the web-independent standalone agent library in the minimum setup | `1` |
| Spring Boot `3.x`, WebFlux | `java-rust-glowroot-spring-webflux-adapter:0.5.4` | Uses the same standalone native runtime | `1` |
| Either runtime with `-javaagent` syntax | Add the one-class `java-rust-glowroot-agent:0.5.4` bootstrap | Same runtime as the row above | Same single exporter; bootstrap adds none |

The bootstrap JAR only maps `-javaagent:key=value` arguments to properties. It contains one class,
no native binary, no transformer, and no runtime dependency. The Spring starter is a separate JAR
so Spring classes never cross the executable-JAR classloader boundary.

The existing Glowroot collector, UI, and database stay unchanged.

> **Compatibility boundary:** runtime profile switching requires REST native ABI `29` and Glowroot
> ABI `6`. Use agent `0.5.4` with Rust-Java REST `4.6.2`. Do not copy DLL/SO files from an older
> package.

Version `0.5.4` adds the complete Spring diagnostic trace path. After one export interval, open
Glowroot **Transactions** and select transaction type **Web**. Route patterns such as
`/orders/{id}` appear as `/orders/*`. A selected slow or failed MVC trace also shows the HTTP
method, response code, controller timer, Dubbo consumer timer, thread counters, and bounded error.

## Sample Projects

The samples do not enable telemetry by default. This keeps the normal quick start independent of a
Glowroot Central deployment.

| Sample | Agent path | What to expect |
|---|---|---|
| [`rest-sample-cache-reader`](https://github.com/esasmer-dou/rest-sample-cache-reader) | Embedded Rust-Java REST runtime | Enable through properties; HTTP and native Redis read aggregates are available |
| [`rest-sample-dubbo-consumer`](https://github.com/esasmer-dou/rest-sample-dubbo-consumer) | Embedded Rust-Java REST runtime | Enable through properties; HTTP and native Dubbo aggregates are available |
| [`rest-sample-cache-writer`](https://github.com/esasmer-dou/rest-sample-cache-writer) | Plain Java scheduler | Release `0.5.4` does not provide a standalone plain-Java runtime; bootstrap alone is insufficient |
| [`rest-sample-dubbo-provider`](https://github.com/esasmer-dou/rest-sample-dubbo-provider) | Plain Java Dubbo/Netty provider | Release `0.5.4` does not instrument the official Java provider runtime |

Do not add Spring Boot or a REST server to a plain-Java sample only to obtain telemetry. If an
application already uses Spring Boot for a real application requirement, use the non-web starter.
Otherwise keep the smaller runtime and use platform-level metrics until a dedicated standalone
runtime is available.

The reader and consumer READMEs contain copy-paste local and Kubernetes examples. For a new
production deployment, align them to Rust-Java REST `4.6.2` before enabling agent `0.5.4`.

## What You Get

| Signal | Behavior |
| --- | --- |
| HTTP count, duration, percentile, and throughput | Counted exactly for every completed request and grouped by normalized route pattern |
| HTTP errors | Status and error totals are counted exactly; `5xx` is an error even when Spring handles the exception |
| Slow/error trace | Bounded request detail with method, response code, controller timer, thread counters, and error stack |
| Java Dubbo consumer inside Spring MVC | Diagnostic-only nested timer, operation, call count, and error count; arguments and results are never retained |
| Rust-native Dubbo | Aggregate count, duration, and errors |
| Rust-native Redis | Separate read/write count, duration, and errors |
| Process gauges | RSS and thread count once per export interval |
| JVM gauges | Optional heap, non-heap, memory-pool, GC count, and GC time gauges |
| SQL aggregates | Optional explicit, bounded operation/statement timing; no JDBC proxy or bytecode weaving |
| Error stacks | Optional bounded stack capture for failed HTTP requests and explicit SQL operations |
| Live diagnostics | Thread dump, OpenJ9 heap histogram/dump, Force GC, MBean tree, and masked system properties in the short-lived diagnostic profile |
| Export health | Connect, reconnect, failure, drop, and last-error counters |

Request/response bodies, query values, headers, Dubbo arguments/results, and personal data are not
copied into telemetry. This is a deliberate bounded-memory and data-protection rule, not a missing
trace field.

## Where The Work Runs

The heavy agent work belongs to Rust. Java is only the event boundary for information that exists
inside Spring or the JVM.

| Surface | Owner | What happens |
| --- | --- | --- |
| Aggregation and export | Rust | Bounded route/SQL state, sampling totals, queues, protobuf encoding, collector HTTP/2 transport, reconnect, timeout, and drop policy on one low-priority batch exporter |
| JVM gauges | Rust | The isolated exporter discovers and owns JNI global references, invokes the selected MXBeans, aggregates values, and builds the gauge message |
| Error detail capture | Rust | A failed Java request hands off only a bounded weak `Throwable` reference. The isolated exporter reads class, message, and stack frames later; the application request thread never walks a stack trace |
| Diagnostics | Rust | The command queue, JNI calls, bounded orchestration, file creation, atomic publication, failure cleanup, and counters stay in Rust |
| Profile lifecycle | Rust | Optional state is allocated, retired, dropped, and optionally trimmed outside Hyper and application workers |
| Optional Spring MVC edge | Java, constant-time only | The adapter selected by the application's existing server records completion: Tomcat Valve, Jetty RequestLog, or Undertow completion listener. The MVC enricher adds controller identity and request-owned timing only for diagnostic traces |
| Optional Java Dubbo edge | Java, diagnostic-only | One Dubbo consumer SPI filter stores a bounded timer in the current MVC request. It does not inspect or copy RPC arguments, results, or payload bytes |
| Optional Spring WebFlux edge | Java, bounded reactive callback | The separate WebFlux module keeps only the state required by the reactive lifecycle, reads the normalized route at commit, and passes the bounded event to Rust. It does not add or select Reactor Netty |
| JVM internals | JVM, invoked by Rust | MXBean and dump APIs still execute inside the JVM because that is where the data exists; no Java helper, polling thread, cache, or direct-buffer callback is used |

The native lifecycle is independent of Spring Web. A database worker, Kafka application, scheduler,
or command-line Spring Boot service can therefore export process and profile-specific JVM/SQL data
without adding MVC, a Servlet container, or a Java telemetry executor.

Rust-Java REST HTTP telemetry is already recorded directly in the Rust server. Spring Boot Servlet
applications use a direct completion hook for their existing server. The umbrella starter contains
only small internal adapter JARs; each server API is `provided` and `optional`, so the agent neither
adds nor selects Tomcat, Jetty, or Undertow. Every completed request crosses one constant-time event
boundary so Glowroot Average, Percentile, Throughput, and error views remain exact. Trace sampling
does not change these aggregates. Slow, failed, asynchronous, and not-found requests use the same
bounded event contract on all supported engines. On an error, Java does not call
`Throwable.getMessage()` or `Throwable.getStackTrace()`. Rust queues a weak JNI reference under the
same hard trace-capacity limit and resolves the detail on the isolated exporter. A collected weak
reference or full queue drops only that optional detail and increments the drop counter; business
request completion is never blocked and an arbitrary exception object graph is never retained.
This hand-off is absent in `micro` and `jvm`, and is also absent when error-trace capacity is zero.

This is intentionally not a replacement for every full Glowroot feature. It does not weave arbitrary
Java methods, wrap every JDBC object, run a profiler, capture logs, or accept remote instrumentation.
Use the full Glowroot agent when those features are required. WebFlux route telemetry is deliberately
packaged in a separate optional artifact. A Servlet application therefore does not receive
`spring-webflux` or Reactor Netty, and a WebFlux application does not receive MVC or a Servlet engine
from the agent.

## Rust-Java REST Setup

Use the coordinated `4.6.2` framework line. It contains Glowroot native ABI `6` and validates the
native provenance before the HTTP server starts.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>rust-java-rest</artifactId>
  <version>4.6.2</version>
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
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.5.4.jar=collector=http://glowroot-collector:8181,agent-id=catalog::pod-1,application=catalog-api \
  -jar catalog-api.jar
```

## Spring Boot Setup

### 1. Add the starter

`-javaagent` alone is not Spring HTTP instrumentation. The Spring application must contain the
starter below (or the matching MVC/WebFlux adapter). Without it, no Servlet route aggregate can be
created even when the bootstrap JAR and native library are present.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.5.4</version>
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

At startup, verify this one-time message. Its adapter value depends on the selected server:

```text
Java-Rust Glowroot HTTP telemetry active: adapter=tomcat-valve, transaction-type=Web
```

If this message is absent, do not evaluate the Glowroot transaction screens yet. First verify the
starter dependency, `reactor.glowroot.enabled=true`, and `reactor.glowroot.spring.enabled=true`.

### 2. Check the Glowroot screens

Wait for one export interval after sending traffic. With the default configuration this is up to
60 seconds. Open **Transactions** and select transaction type **Web**. A mapped route such as
`/orders/{id}` is shown as `/orders/*`; the HTTP method is not included in the transaction name.
The same exact request aggregate feeds **Average**, **Percentile**, **Throughput**, and **Errors**.

`reactor.glowroot.http.sample-rate` does not reduce these dashboard counts. It controls only which
requests may enter the optional bounded trace-detail queue. Keep `trace.capacity=0` when aggregate
telemetry is sufficient.

For controller, Dubbo, error, and live JVM investigation, use this bounded incident profile on one
pod:

```yaml
env:
  - name: REACTOR_GLOWROOT_ENABLED
    value: "true"
  - name: REACTOR_GLOWROOT_PROFILE
    value: "diagnostic"
  - name: REACTOR_GLOWROOT_SPRING_ENABLED
    value: "true"
  - name: REACTOR_GLOWROOT_HTTP_SAMPLE_RATE
    value: "256"
  - name: REACTOR_GLOWROOT_TRACE_CAPACITY
    value: "8"
  - name: REACTOR_GLOWROOT_TRACE_SLOW_THRESHOLD_MS
    value: "500"
```

`sample-rate=256` means roughly one normal successful request can keep trace detail. It does not
sample endpoint counts, Average, Percentile, Throughput, or Errors. Requests slower than `500 ms`
and failed requests are still selected by their own rules. `trace.capacity=8` is a hard queue bound;
it never grows with traffic.

A selected Spring MVC trace contains:

| Glowroot trace field | Source |
| --- | --- |
| Request HTTP method and response code | Direct server completion adapter |
| `http request` root timer | Full request duration |
| `spring controller` child timer and `package.Controller.method()` entry | MVC mapping and controller lifecycle |
| `dubbo consumer` child timer, operation, count, and error count | Optional Dubbo consumer SPI filter |
| CPU, blocked, and waited deltas | Current request thread while `diagnostic` is active |
| Error and bounded stack | Original Spring/Servlet failure; otherwise `HTTP status 5xx` |

The Dubbo timer is automatic when the application already contains Apache Dubbo and uses the
umbrella starter. It applies to consumer calls made within the current synchronous MVC request. No
SQL configuration is required. The adapter has no runtime Dubbo dependency of its own; it uses the
application's existing Dubbo `3.3.x` API and stays inactive when Dubbo is absent.

HTTP or Dubbo payloads are intentionally not shown. Capturing bodies would retain personal data,
tokens, and potentially large byte arrays. If a business field must be searchable, emit a redacted
application log or a bounded domain metric instead of enabling generic body capture.

The application still chooses its embedded server. Auto-configuration detects the server already
present in the application and activates exactly one direct adapter. Every server API remains
`provided` and `optional` inside its adapter module. The umbrella starter therefore adds no server
engine and does not change Spring Boot's server selection.

| Application runtime | Agent HTTP path | Server dependency added by agent | Feature set |
| --- | --- | --- | --- |
| Spring MVC + Tomcat | Direct context Valve (`tomcat-valve`) | None | Sync, async, mapped status, exception, 404, bounded route and slow/error telemetry |
| Spring MVC + Jetty | Direct completion RequestLog (`jetty-request-log`) | None | Same feature set as Tomcat |
| Spring MVC + Undertow | Direct exchange completion listener (`undertow-completion-listener`) | None | Same feature set as Tomcat |
| Spring WebFlux | Separate optional `WebFilter` | None | Same HTTP lifecycle contract; tested with Reactor Netty |

For Jetty, the application owns this dependency choice:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jetty</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.5.4</version>
</dependency>
```

For Undertow, keep the same exclusion and select Undertow instead:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-undertow</artifactId>
</dependency>
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-boot-starter</artifactId>
  <version>0.5.4</version>
</dependency>
```

All three Servlet engines read Spring's normalized route, such as `/orders/{id}`, only when the
request must be recorded. Their direct completion hooks add no Java worker pool, application class
scan, or per-request wrapper. A portable MVC interceptor remains only as a safety fallback for an
unknown Servlet engine; Tomcat, Jetty, and Undertow never use that fallback. Synchronous and
asynchronous responses, mapped statuses, unhandled failures, and not-found responses pass the same
lifecycle gate.

### 2. WebFlux applications

Keep the reactive surface separate. Add the optional adapter only to a WebFlux application:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-webflux-adapter</artifactId>
  <version>0.5.4</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

The adapter pulls in the common native Spring runtime, but it does not pull Reactor Netty. The
application's `spring-boot-starter-webflux` chooses the reactive server. Do not add
`spring-boot-starter-web` merely for telemetry. The `reactor.glowroot.*` properties are the same as
for MVC.

### 3. Non-web applications

For the smallest classpath, add only the web-independent runtime to a scheduler, Kafka worker, batch
process, command-line process, or database-only Spring Boot application:

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-spring-runtime</artifactId>
  <version>0.5.4</version>
</dependency>
```

The umbrella `java-rust-glowroot-spring-boot-starter` also works when one organization wants the same
dependency in every Spring Boot service. It contains the small adapter JARs, but their server APIs are
`provided` and `optional`; no adapter auto-configuration becomes active in a non-web application.
Choose the runtime-only artifact when the smallest production classpath is the priority.

Do not add `spring-webmvc`, WebFlux, Tomcat, Jetty, Undertow, Reactor Netty, or the Servlet API only for
telemetry. The runtime auto-configuration has no web condition and starts without a web surface.

```properties
spring.main.web-application-type=none
reactor.glowroot.enabled=true
reactor.glowroot.profile=jvm
reactor.glowroot.collector.address=http://glowroot-collector:8181
reactor.glowroot.agent.id=invoice-worker::pod-1
reactor.glowroot.application.name=invoice-worker
```

No application Java code is required for process or JVM gauges. When Spring creates the application
context, one process-scoped `NativeTelemetry` bean loads the packaged DLL/SO, verifies Glowroot ABI
`6`, and starts one isolated Rust exporter. Closing the Spring context stops that exporter. With
`reactor.glowroot.enabled=false`, the bean is not created, the native library is not loaded, and no
exporter thread, route table, SQL table, trace queue, or collector connection is allocated.

| Profile | Data available without a web server |
| --- | --- |
| `micro` | Process RSS, operating-system thread count, exporter/reconnect/drop health |
| `jvm` | `micro` plus heap, non-heap, memory pools, GC count, and GC time |
| `sql` | `micro` plus explicitly registered SQL duration/error/row aggregates |
| `full` | JVM gauges plus explicit SQL and bounded error stacks |
| `diagnostic` | `full` plus authorized thread dump, heap histogram, and heap dump commands |

The runtime does not guess Kafka topic names, scheduler job names, batch step names, or arbitrary
business-method boundaries. It does not weave methods. Kafka, scheduler, and batch operation timing
is therefore not automatic in `0.5.4`. Process and JVM evidence is automatic. Database timing is
explicit through the reusable `SqlStatement` API shown below. This keeps the hot path predictable
and avoids a framework-specific Java agent layer.

| Application shape | Recommended profile | Automatic data | Explicit application work |
| --- | --- | --- | --- |
| Kafka consumer or producer | `micro`; temporarily `jvm` during an incident | Process, exporter health; JVM/GC in `jvm` | Topic/message processing duration is not captured automatically |
| Scheduler or Spring Batch | `micro`; temporarily `jvm` or `full` | Process, exporter health; selected JVM/GC gauges | Job and step duration is not captured automatically |
| Database worker | `micro` normally; `sql` or `full` while investigating | Process and optional JVM/GC gauges | Define reusable `SqlStatement` descriptors for selected repository operations |
| Command-line or background service | `micro` | Process and exporter health | No HTTP transaction exists; add only bounded domain metrics required by the service |

`reactor.glowroot.spring.enabled=false` disables Spring HTTP telemetry while process/JVM/SQL
telemetry remains available. To stop the native runtime and remove its exporter thread, use
`reactor.glowroot.enabled=false`.

### 4. Optional early-start bootstrap

Use the bootstrap when your deployment standard expects `-javaagent`, or when you want process
start metadata captured before Spring starts.

The bootstrap is not the telemetry runtime. Keep either `java-rust-glowroot-spring-runtime` or the
Spring Boot starter in the application. The bootstrap only maps early JVM arguments and process
start metadata to the same runtime; by itself it does not load a native library or export data.

```xml
<dependency>
  <groupId>com.reactor</groupId>
  <artifactId>java-rust-glowroot-agent</artifactId>
  <version>0.5.4</version>
  <scope>runtime</scope>
</dependency>
```

Keep the bootstrap JAR outside the executable Spring Boot JAR and pass its file path to the JVM:

```bash
java \
  -javaagent:/opt/agent/java-rust-glowroot-agent-0.5.4.jar=collector=http://glowroot-collector:8181,agent-id=orders::pod-1,application=orders-api,http-sample-rate=256,trace-capacity=0 \
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

## Linux Container Compatibility

Release `0.5.4` supports Linux x64 images with `glibc 2.17` or newer. The release build has a fixed
GLIBC symbol ceiling and is loaded in both Debian Stretch (`glibc 2.24`) and Debian Bookworm
(`glibc 2.36`) before publication. This is a build-time compatibility change. It adds no runtime
thread, allocation, or JNI call.

Check the **final application image**, not only the JRE builder image:

```bash
docker run --rm --entrypoint sh YOUR_IMAGE -lc 'getconf GNU_LIBC_VERSION'
docker run --rm --entrypoint sh YOUR_IMAGE -lc \
  'ldd /u01/applications/nmc-store-common/glowroot/librust_glowroot_agent.so'
```

### Custom dependency layers and shared PVCs

Adding the starter to `pom.xml` is not enough when the image deletes `BOOT-INF/lib` and redirects
`BOOT-INF/classpath.idx` to a shared `NEW_BOOT-INF` directory. The final pod must contain both the
native library and the Java runtime JARs. Updating only `librust_glowroot_agent.so` leaves Spring
without the auto-configuration classes, so the service starts normally but exports nothing.

For release `0.5.4` with Jetty, verify the running pod before testing the UI:

```bash
APP_HOME=/u01/applications/nmc-store-common

grep 'java-rust-glowroot' "$APP_HOME/BOOT-INF/classpath.idx"
find "$APP_HOME" -type f -name 'java-rust-glowroot-*.jar' -print
grep -F 'librust_glowroot_agent.so' /proc/1/maps
```

The first two commands must show the `0.5.4` starter, runtime, and Jetty adapter. The `/proc/1/maps`
line appears only after Java has loaded the native library. A custom dependency image or shared PVC
must therefore be rebuilt or updated from the same Maven dependency layer as the application.

The repository also provides a fail-fast final-image check:

```bash
scripts/verify-container-runtime.sh \
  /u01/applications/nmc-store-common 0.5.4 jetty \
  /u01/applications/nmc-store-common/glowroot/librust_glowroot_agent.so
```

At application startup, look for both lines:

```text
Java-Rust Glowroot native telemetry active: abi=6, profile=diagnostic, application=nmc-store-common
Java-Rust Glowroot HTTP telemetry active: adapter=jetty-request-log, transaction-type=Web
```

If neither line exists, the starter/runtime JAR is not active. If only the first line exists, inspect
the selected web adapter. If both exist but Central remains empty, inspect `diagnosticsJson()` for
`connected`, `export_success`, `export_failure`, and `last_error_code` after one 60-second export
interval.

If an image declared as Bookworm reports `GLIBC_2.25 not found`, the running final image is not the
shown Bookworm filesystem. Bookworm provides GLIBC `2.36`. Check the deployed image digest, stale
custom-image tags, and the final `FROM` line.

Use one immutable custom-JRE image for both application stages:

```dockerfile
ARG JRE_IMAGE=zenia.azurecr.io/example/custom-jre21:1.1.1

FROM ${JRE_IMAGE} AS builder
# Extract the application layers here.

FROM ${JRE_IMAGE}
ARG APP_HOME=/u01/applications/nmc-store-common
WORKDIR ${APP_HOME}

COPY glowroot/librust_glowroot_agent.so ${APP_HOME}/glowroot/
RUN getconf GNU_LIBC_VERSION \
 && ldd ${APP_HOME}/glowroot/librust_glowroot_agent.so \
 && ! ldd ${APP_HOME}/glowroot/librust_glowroot_agent.so | grep -q 'not found'
```

Do not mix custom JRE tags such as `1.0.0` in the builder and `1.1.0` in the final stage. The builder
may pass while production runs against a different libc. Prefer an immutable digest when the image
registry supports it.

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
| `reactor.glowroot.application.name` | `reactor.application.name`, then `spring.application.name` | 1-128 bytes | Name shown in Glowroot |
| `reactor.glowroot.hostname` | `HOSTNAME` | up to 255 bytes | Host or pod label |
| `reactor.glowroot.export.interval-ms` | `60000` | 60000-3600000; 60000 multiple | Aggregate export interval |
| `reactor.glowroot.connect-timeout-ms` | `1000` | 100-30000 | TCP/h2 connection timeout |
| `reactor.glowroot.request-timeout-ms` | `2000` | 100-30000 | Complete collector request timeout |
| `reactor.glowroot.trace.slow-threshold-ms` | `500` | 1-3600000 | Slow trace threshold when traces are enabled |
| `reactor.glowroot.http.sample-rate` | `256` | power of two, 1-1024 | Samples optional HTTP trace detail; route count, duration, percentile, throughput, and errors stay exact |
| `reactor.glowroot.trace.capacity` | `0` | 0-32 | Bounded trace queue; `0` allocates no trace queue |
| `reactor.glowroot.sql.capacity` | `16` | 0-32 | Maximum SQL statement slots allocated only by `sql`, `full`, or `diagnostic` |
| `reactor.glowroot.error.trace.capacity` | `8` | 0-16 | Maximum retained detailed error stacks in enabled profiles |
| `reactor.glowroot.error.max-frames` | `24` | 0-32 | Maximum frames copied for one captured error |
| `reactor.glowroot.error.max-bytes` | `4096` | 256-8192 | Maximum UTF-8 error detail size |
| `reactor.glowroot.max-routes` | `64` | 1-64 | Total bounded route slots; one slot groups excess cardinality as `<route-limit-exceeded>` so requests are not silently lost |
| `reactor.glowroot.max-export-bytes` | `65536` | 16384-65536 | Maximum encoded collector request |
| `reactor.glowroot.spring.enabled` | `true` | boolean | Enables the optional Spring HTTP adapter; the native core is controlled by `reactor.glowroot.enabled` |
| `reactor.glowroot.spring.order` | `-2147483548` | integer | Portable MVC fallback or optional WebFlux filter order; direct Tomcat, Jetty, and Undertow completion adapters do not require ordering |
| `reactor.glowroot.native.extract-dir` | user home | directory | Standalone Spring native extraction directory |
| `reactor.glowroot.native.path` | empty | existing DLL/SO path | Development and staging override; production should use packaged binaries |

Invalid bounds stop startup. There is no property that enlarges the agent-owned memory ceiling.

## Runtime Profiles

Start with `micro`. Raise one pod only when you need more evidence. Return it to `micro` after the
investigation.

### Collector UI visibility

When the HTTP adapter is active, endpoint aggregates remain exact in every profile. Sampling affects
only detailed trace selection; it does not reduce counts, throughput, or percentiles.

| Profile | Transactions | Process | JVM/GC | SQL and error stack | Live JVM |
| --- | --- | --- | --- | --- | --- |
| `micro` | Average, Percentile, Throughput, Errors | RSS, threads, exporter health | None | None | None |
| `jvm` | Same as `micro` | Same as `micro` | Heap, non-heap, pools, GC | None | None |
| `sql` | Same as `micro` | Same as `micro` | None | Registered SQL and bounded stacks | None |
| `full` | Same as `micro` | Same as `micro` | Heap, non-heap, pools, GC | Registered SQL and bounded stacks | None |
| `diagnostic` | All aggregates; controller, Dubbo, and thread detail | Same as `micro` | Heap, non-heap, pools, GC | Registered SQL and bounded stacks | Dumps, MBeans, and JVM commands |

### Trace conditions

| Collector UI area | Required configuration |
| --- | --- |
| Endpoint aggregates and HTTP error counts | Every profile; independent of `reactor.glowroot.trace.capacity` |
| Basic HTTP trace | `reactor.glowroot.trace.capacity > 0`; selected by sampling, slowness, or failure |
| Detailed error stack | `sql`, `full`, or `diagnostic`; also requires `reactor.glowroot.error.trace.capacity > 0` |
| Spring controller, Java Dubbo, and request-thread timing | `diagnostic`; detailed traces require `reactor.glowroot.trace.capacity > 0` |
| Thread dump, heap histogram/dump, Force GC, MBeans, and system properties | `diagnostic` only |

For a non-web application, the **Web Transactions** view remains empty. Process gauges and selected
JVM/SQL evidence are still exported. Request/response bodies, headers, query values, and Dubbo
payloads are never collected by any profile.

`reactor.glowroot.trace.capacity` and `reactor.glowroot.http.sample-rate` are startup settings. A
profile switch does not resize either capacity; restart the pod to use different values.

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
| High-traffic production API | `micro` | `256` | `0` | Exact dashboard aggregates with no trace queue |
| Low-traffic API, aggregate only | `micro` | `256` | `0` | Counts and latency remain exact; no sampling change is needed |
| Short trace investigation | `micro` | `8` or `1` | `8` or `16` | Collect more optional request traces on one pod, then restore the lower-cost startup settings |
| JVM or GC investigation | `jvm` | unchanged | `0` | Raise one pod, observe several export intervals, then return to `micro` |
| SQL latency investigation | `sql` | unchanged | `0` | Instrument only selected repository statements |
| Short incident investigation | `full` | unchanged | `0` by default | JVM, SQL, and error state is dynamic; use one pod and revert after the incident |
| Authorized dump operation | `diagnostic` | unchanged | unchanged | Run one command, confirm completion, then return to `micro` |

Do not try to fix missing endpoint aggregates by setting the sample rate to `1`; aggregate telemetry
is already exact. Use explicit business metrics for orders, payments, or domain failures.

`http.sample-rate` and `trace.capacity` are startup settings. A profile switch does not resize them.
If you start with `trace.capacity=16`, that bounded HTTP trace queue remains allocated in `micro`.
Keep it at `0` when strict downgrade reclamation is the priority; profile-owned SQL, error, JVM, and
diagnostic state is still allocated and released dynamically.

## Failure Behavior

- Invalid local configuration fails startup.
- A collector outage does not block HTTP, Dubbo, Redis, or business logic.
- Connect and request timeouts are bounded.
- The retained collector connection is replaced after 15 minutes, before Glowroot Central's
  20-minute maximum connection age.
- A readiness check runs before aggregate counters are drained. A stale connection is replaced
  before the interval becomes an outbound payload.
- If Central closes during an aggregate request, the exporter reconnects, sends init, and retries
  the byte-identical protobuf payload with bounded backoff.
- Central must acknowledge an aggregate chunk before the exporter releases it. Retry exhaustion
  keeps one bounded pending batch; newer traffic remains in the preallocated counters instead of
  creating an unbounded interval queue.
- A non-retryable protocol or size rejection is reported and dropped explicitly. Process
  termination before an acknowledgement is not persisted because this micro agent has no disk WAL.
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

Watch `connected`, `downstream_connected`, `export_failure`, `collector_rotations`,
`collector_preflight_reconnects`, `collector_request_retries`, `deferred_intervals`,
`pending_aggregate_transactions`, `dropped_intervals`, `dropped_transactions`, `dropped_traces`,
`pending_error_captures`, `queued_error_traces`, `dropped_error_traces`, `dropped_routes`,
`reconnects`, `downstream_reconnects`, `downstream_failures`, and `last_error_code`.
During a normal connection rotation, `collector_rotations` increases while drop counters and
`last_error_code` stay unchanged. A close race may increase `collector_request_retries`.
`pending_aggregate_transactions` must return to zero after Central acknowledges the retry.
`pending_error_captures` is work waiting for the isolated Rust exporter; it should remain bounded
and return to zero after the burst. During a profile change watch
`active_profile`, `active_profile_memory_ceiling_bytes`, `retired_profile_memory_ceiling_bytes`,
`jvm_probe_registered`, `jvm_probe_owned_global_refs`, `profile_release_pending`, `profile_released_transition`,
`profile_release_timeouts`, `profile_last_release_micros`, `profile_max_release_micros`, and
`profile_trim_succeeded`.

After returning to a profile without JVM gauges or diagnostics, both `jvm_probe_registered=false`
and `jvm_probe_owned_global_refs=0` are required. Diagnostic output is written by Rust to a temporary
file in the target directory and published only after success. Heap histograms and heap dumps can
still create a large one-time JVM diagnostic allocation; use them on one pod, never as a periodic
job or during a latency-sensitive peak.

Glowroot Central's live JVM buttons use a separate bidirectional HTTP/2 downstream connection. It
is active only in `diagnostic`. Wait for this one-time line before opening the live pages:

```text
Java-Rust Glowroot live diagnostics connected: agent-id=nmc-store-common::pod-name
```

If Central says the agent is not connected, first check `downstream_connected=true`; historical
aggregate delivery through `connected=true` is not sufficient for live commands. The downstream
connection is rotated before Central's idle limit and reconnects independently. It does not run on
the application server, Hyper, or business worker pool.

| Glowroot JVM page/action | `diagnostic` behavior on Semeru OpenJ9 21 |
| --- | --- |
| Thread dump | Available; bounded to 512 threads and 128 frames per thread |
| Heap histogram | Available through OpenJ9's in-process class statistics; bounded to 4096 classes and a 1 MiB response |
| Heap dump | Available as an OpenJ9 PHD file in the selected server directory |
| Force GC | Available through the JVM memory MXBean; use only during an incident |
| MBean tree | Available through the platform MBean server; names, attributes, and response bytes are bounded |
| System properties | Available; password, secret, token, credential, authorization, and private-key values are masked |
| Environment | Startup host, CPU, physical memory, OS/version, PID/start time, Java/JVM, masked JVM arguments, dump directory, and agent version |

The Environment page does not export arbitrary operating-system environment variables. Kubernetes
Secrets often live there, and the upstream Environment protobuf has no safe arbitrary key/value
field. Put a non-secret deployment label in a JVM property such as
`-Ddeployment.environment=test` when it must be visible under **System properties**. Do not mirror
passwords or tokens into JVM properties.

## Performance Contract

The `micro` configuration enforces a deterministic `1 MiB` ceiling for agent-attributed state and
native feature pages. Both Rust-Java REST and Spring use one isolated current-thread Tokio exporter
with a `256 KiB` stack when telemetry is enabled. It does not share Hyper workers, application
executors, or Spring request threads. Embedded REST also packs request capture into one 32-bit state
value; route aggregates remain exact and the public sampling rate controls only optional traces.
The strict Spring gate enables the starter through properties or environment variables. The optional
`-javaagent` bootstrap is a deployment convenience and is validated separately because starting the
JVM instrumentation subsystem adds OpenJ9-owned memory even though no transformer is installed.

Release gates compare telemetry off/on in the same image with randomized paired runs. Every
endpoint/concurrency cell must keep:

- useful HTTP 200 RPS loss at or above `-2%`;
- p99 regression at or below `+10%`;
- non-2xx regression at `0` percentage points for every stable-release cell. Baseline and candidate
  aggregate and peak error rates must remain at or below `0.05%`;
- additional agent threads at most `1` for both embedded Rust-Java and standalone Spring.

Package publication is fail-closed. The release workflow accepts only a successful Production Gate
run whose `head_sha` is the exact release commit. It also requires the non-expired
`spring-boot-production-gate` and `rust-java-rest-production-gate` evidence artifacts. A normal CI
build, an older passing run, or evidence from a different runtime commit cannot authorize a release.

The stable release runs the same per-request telemetry matrix separately for Spring Boot and
Rust-Java REST. Both cover small JSON and precomputed raw JSON at c64/c256. These paths maximize
request rate and expose the agent's fixed cost more strongly than a serializer-bound route. Dynamic
heavy JSON is still called by the functional route smoke. The optional `extended` workflow adds
measured heavy JSON at c64/c128 and always runs all six pairs, but it is stress evidence rather than
the stable publish gate. The release starts with three independent pairs. It stops only when a
stricter early-pass envelope succeeds; otherwise it continues to six. RPS, p99, and startup use each
pair's delta before the median is calculated. Non-2xx uses the
paired median, request-weighted total, peak error envelope, and the absolute `0.05%` ceiling
together. The normal release matrix uses the zero-delta rule for every cell. The bounded `0.02`
percentage-point margin applies only when heavy JSON is tested manually at the saturated embedded
REST c256+ range. One saturated-run delta remains visible without replacing the overall error decision. After both
variants complete the same full workload, a controlled equal-process-age phase requires
paired-median process RSS and cgroup deltas at or below `+3 MiB` for `micro`. Both runtimes may add
one bounded exporter thread. `jvm`, `sql`, `full`, and `diagnostic` are separate temporary-profile
gates because OpenJ9 management/error classes can create one-time JVM warm state. REST wire
compatibility, collector-down fail-open, and
the optional bootstrap are separate mandatory checks.

See [Architecture And Production Boundary](docs/ARCHITECTURE.md). User-facing changes and
compatibility details are in the [0.5.4 release notes](docs/releases/0.5.4.md). Reproducible
release-gate source is version-controlled under `benchmark/`; generated results, host-specific
runner state, and raw evidence remain excluded from the repository and from every Maven artifact.

## Compatibility

| Component | Release | Contract |
| --- | ---: | --- |
| Java | `21` | Semeru OpenJ9 is the primary tested JVM |
| Rust-Java REST | `4.6.2` | REST ABI `29`, Glowroot ABI `6` |
| Agent bootstrap | `0.5.4` | One class; works with either supported runtime |
| Spring Boot starter | `0.5.4` | Spring Boot `3.x`; web-independent core plus direct full-lifecycle adapters for Tomcat, Jetty, and Undertow; no server engine dependency |
| Spring Dubbo adapter | `0.5.4` | Optional consumer SPI correlation compiled against Dubbo `3.3.0-beta.4`; no transitive Dubbo runtime |
| Spring WebFlux adapter | `0.5.4` | Separate optional `WebFilter`; no Reactor Netty or Servlet engine dependency |
| Standalone native source | `rust-spring v4.6.2` | Glowroot ABI `6`; clean CI DLL/SO |
| Glowroot Central wire contract | upstream `0.14.8-beta.5-SNAPSHOT` checkout | Unary h2/protobuf compatibility gate |
| Native platforms | Windows x64, Linux glibc x64 | Clean CI-built DLL/SO with SHA-256 provenance |

Runtime profile switching requires the coordinated REST ABI `29` and Glowroot ABI `6` pair shown
above. Startup provenance checks reject an older or locally copied native binary.

Do not copy DLL/SO files between versions. The framework, cache, Dubbo, and agent libraries validate
their coordinated native ABI at startup.

## Build

```powershell
$env:JAVA_HOME = "D:\Dropbox\java64\Semeru\jdk-21.0.2.13-openj9"
mvn -B -ntp clean verify
```

The Maven reactor builds:

- `agent-bootstrap/target/java-rust-glowroot-agent-0.5.4.jar`
- `spring-runtime-core/target/java-rust-glowroot-spring-runtime-0.5.4.jar`
- `spring-mvc-adapter/target/java-rust-glowroot-spring-mvc-adapter-0.5.4.jar`
- `spring-tomcat-adapter/target/java-rust-glowroot-spring-tomcat-adapter-0.5.4.jar`
- `spring-jetty-adapter/target/java-rust-glowroot-spring-jetty-adapter-0.5.4.jar`
- `spring-undertow-adapter/target/java-rust-glowroot-spring-undertow-adapter-0.5.4.jar`
- `spring-dubbo-adapter/target/java-rust-glowroot-spring-dubbo-adapter-0.5.4.jar`
- `spring-boot-starter/target/java-rust-glowroot-spring-boot-starter-0.5.4.jar`
- `spring-webflux-adapter/target/java-rust-glowroot-spring-webflux-adapter-0.5.4.jar`

The native DLL/SO are built only from the clean `rust-spring` commit recorded in
`native-provenance.properties`. `scripts/sync-native-artifacts.ps1` is retained because it is part of
the reproducible release build. Internal load generators, raw benchmark evidence, and local runner
configuration stay outside the public repository.
